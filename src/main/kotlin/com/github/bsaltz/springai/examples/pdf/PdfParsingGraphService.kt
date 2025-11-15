package com.github.bsaltz.springai.examples.pdf

import org.bsc.langgraph4j.NodeOutput
import org.bsc.langgraph4j.StateGraph
import org.bsc.langgraph4j.action.AsyncNodeAction.node_async
import org.bsc.langgraph4j.action.NodeAction
import org.bsc.langgraph4j.state.AgentState
import org.bsc.langgraph4j.state.Channel
import org.bsc.langgraph4j.state.Channels
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path

@Service
class PdfParsingGraphService(
    private val pdfParsingLlmService: PdfParsingLlmService,
) {
    companion object {
        private val log = LoggerFactory.getLogger(PdfParsingGraphService::class.java)
    }

    fun <T : Refineable> parsePdf(
        path: Path,
        parsingInstructions: String,
        clazz: Class<T>,
    ): T {
        // Build the graph
        val graph = buildPdfParsingGraph(clazz)
        val compiledGraph = graph.compile()

        // Execute the graph with the initial state
        val initialState =
            mapOf(
                PdfParsingState.PDF_PATH_KEY to path.toString(),
                PdfParsingState.PARSING_INSTRUCTIONS_KEY to parsingInstructions,
            )

        log.debug("Starting PDF parsing graph execution for path: {}", path)

        // Stream through the graph and get the final state
        val finalState: NodeOutput<PdfParsingState> =
            compiledGraph
                .stream(initialState)
                .onEach { nodeOutput ->
                    log.debug(
                        "Graph node '{}' completed. State keys: {}",
                        nodeOutput.node(),
                        nodeOutput.state().data().keys,
                    )
                }.lastOrNull()
                ?: error("Graph execution produced no output")

        log.debug("PDF parsing graph execution completed")

        // Extract the final result from the state
        @Suppress("UNCHECKED_CAST")
        return finalState
            .state()
            .value<T>(PdfParsingState.FINAL_RESULT_KEY)
            .orElseThrow { IllegalStateException("No final result in state") }
    }

    private fun <T : Refineable> buildPdfParsingGraph(clazz: Class<T>): StateGraph<PdfParsingState> {
        val ocrPdfNode = OcrPdfNode(pdfParsingLlmService)
        val extractTextNode = ExtractTextNode(pdfParsingLlmService)
        val initialParseNode = InitialParseNode(pdfParsingLlmService, clazz)
        val refinementNode = RefinementNode(pdfParsingLlmService, clazz)

        return StateGraph(PdfParsingState.SCHEMA) { initData -> PdfParsingState(initData) }
            .addNode("ocr_pdf", node_async(ocrPdfNode))
            .addNode("extract_text", node_async(extractTextNode))
            .addNode("initial_parse", node_async(initialParseNode))
            .addNode("refine", node_async(refinementNode))
            .addEdge(StateGraph.START, "ocr_pdf")
            .addEdge("ocr_pdf", "extract_text")
            .addEdge("extract_text", "initial_parse")
            .addEdge("initial_parse", "refine")
            .addEdge("refine", StateGraph.END)
    }

    // State class for the PDF parsing graph
    class PdfParsingState(
        initData: Map<String, Any>,
    ) : AgentState(initData) {
        companion object {
            const val PDF_PATH_KEY = "pdf_path"
            const val OCR_PDF_PATH_KEY = "ocr_pdf_path"
            const val OCR_TEXT_KEY = "ocr_text"
            const val PARSING_INSTRUCTIONS_KEY = "parsing_instructions"
            const val INTERMEDIATE_RESULT_KEY = "intermediate_result"
            const val FINAL_RESULT_KEY = "final_result"

            // Schema only includes input keys that are provided in initial state
            // Node output keys (OCR_PDF_PATH_KEY, OCR_TEXT_KEY, INTERMEDIATE_RESULT_KEY, FINAL_RESULT_KEY)
            // don't need to be in schema as they use the default "last write wins" behavior
            val SCHEMA: Map<String, Channel<*>> =
                mapOf(
                    PDF_PATH_KEY to Channels.base<String>({ _, o2 -> o2 }, { "" }),
                    PARSING_INSTRUCTIONS_KEY to Channels.base<String>({ _, o2 -> o2 }, { "" }),
                )
        }

        fun pdfPath(): String = value<String>(PDF_PATH_KEY).orElseThrow()

        fun ocrPdfPath(): String = value<String>(OCR_PDF_PATH_KEY).orElseThrow()

        fun ocrText(): String = value<String>(OCR_TEXT_KEY).orElseThrow()

        fun parsingInstructions(): String = value<String>(PARSING_INSTRUCTIONS_KEY).orElseThrow()

        fun intermediateResult(): Any? = value<Any>(INTERMEDIATE_RESULT_KEY).orElse(null)
    }

    // Node for running OCRmyPDF - thin wrapper around service
    class OcrPdfNode(
        private val pdfParsingLlmService: PdfParsingLlmService,
    ) : NodeAction<PdfParsingState> {
        override fun apply(state: PdfParsingState): Map<String, Any> {
            val pdfPath = Path.of(state.pdfPath())
            val ocrPdfPath = pdfParsingLlmService.runOcr(pdfPath)

            return mapOf(PdfParsingState.OCR_PDF_PATH_KEY to ocrPdfPath.toString())
        }
    }

    // Node for extracting text from OCR'd PDF - thin wrapper around service
    class ExtractTextNode(
        private val pdfParsingLlmService: PdfParsingLlmService,
    ) : NodeAction<PdfParsingState> {
        override fun apply(state: PdfParsingState): Map<String, Any> {
            val ocrPdfPath = Path.of(state.ocrPdfPath())
            val ocrText = pdfParsingLlmService.extractText(ocrPdfPath)

            return mapOf(PdfParsingState.OCR_TEXT_KEY to ocrText)
        }
    }

    // Node for initial parsing - thin wrapper around service
    class InitialParseNode<T : Refineable>(
        private val pdfParsingLlmService: PdfParsingLlmService,
        private val clazz: Class<T>,
    ) : NodeAction<PdfParsingState> {
        override fun apply(state: PdfParsingState): Map<String, Any> {
            val ocrText = state.ocrText()
            val parsingInstructions = state.parsingInstructions()

            val parsedResult = pdfParsingLlmService.initialParse(ocrText, parsingInstructions, clazz)

            return mapOf(PdfParsingState.INTERMEDIATE_RESULT_KEY to parsedResult)
        }
    }

    // Node for refinement - thin wrapper around service
    class RefinementNode<T : Refineable>(
        private val pdfParsingLlmService: PdfParsingLlmService,
        private val clazz: Class<T>,
    ) : NodeAction<PdfParsingState> {
        override fun apply(state: PdfParsingState): Map<String, Any> {
            val ocrText = state.ocrText()
            val parsingInstructions = state.parsingInstructions()
            val intermediateResult =
                state.intermediateResult()
                    ?: error("No intermediate result available")

            @Suppress("UNCHECKED_CAST")
            val refinedResult =
                pdfParsingLlmService.refineResult(
                    ocrText,
                    parsingInstructions,
                    intermediateResult as T,
                    clazz,
                )

            return mapOf(PdfParsingState.FINAL_RESULT_KEY to refinedResult)
        }
    }
}
