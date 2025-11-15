package com.github.bsaltz.springai.examples.pdf

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.bsaltz.springai.util.BeanOutputConverterCache
import com.github.bsaltz.springai.util.ocrmypdf.PdfOcrService
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.ollama.api.OllamaOptions
import org.springframework.stereotype.Service
import java.nio.file.Path

/**
 * Service containing the core business logic for PDF parsing operations.
 * This service handles all OCR, LLM interactions, and parsing logic for the PDF
 * parsing workflow, separated from the graph orchestration logic.
 */
@Service
class PdfParsingLlmService(
    private val chatModel: ChatModel,
    private val pdfOcrService: PdfOcrService,
    private val beanOutputConverterCache: BeanOutputConverterCache,
) {
    companion object {
        private val log = LoggerFactory.getLogger(PdfParsingLlmService::class.java)
    }

    /**
     * Runs OCRmyPDF on the input PDF and returns the path to the OCR'd PDF
     */
    fun runOcr(input: Path): Path {
        val ocrPdfPath = pdfOcrService.runOcrmypdf(input)
        return ocrPdfPath
    }

    /**
     * Extracts text from an OCR'd PDF
     */
    fun extractText(ocrPdfPath: Path): String {
        val ocrText = pdfOcrService.runPdftotext(ocrPdfPath)
        return ocrText
    }

    /**
     * Performs initial parsing of OCR text into a structured format
     */
    fun <T : Refineable> initialParse(
        ocrText: String,
        parsingInstructions: String,
        clazz: Class<T>,
    ): T {
        val promptText =
            """
            |You are parsing the OCR text output of a PDF file. The OCR text was created by chaining OCRmyPDF with
            |pdftotext. The user has provided the following instructions:
            |
            |```text
            |$parsingInstructions
            |```
            |
            |For this step, you can leave the `changes` list empty or null. Embed the results of the parsing into the
            |remaining output fields.
            |
            |The OCR text to parse is:
            |
            |```text
            |$ocrText
            |```
            """.trimMargin()

        val prompt =
            Prompt
                .builder()
                .messages(UserMessage(promptText))
                .chatOptions(
                    OllamaOptions
                        .builder()
                        .format(beanOutputConverterCache.getConverter(clazz).jsonSchemaMap)
                        .build(),
                ).build()

        log.debug("InitialParse calling ChatModel with prompt: {}", promptText)
        val result =
            chatModel
                .call(prompt)
                .results[0]
                .output.text
                ?: error("Chat model returned no output")
        log.debug("InitialParse ChatModel response: {}", result)

        val parsedResult =
            beanOutputConverterCache.getConverter(clazz).convert(result)
                ?: error("Failed to convert result")

        return parsedResult
    }

    /**
     * Refines the initial parsing result by having the LLM review and correct it
     */
    fun <T : Refineable> refineResult(
        ocrText: String,
        parsingInstructions: String,
        intermediateResult: T,
        clazz: Class<T>,
    ): T {
        // Convert intermediate result to JSON string for the prompt
        val objectMapper = jacksonObjectMapper()
        val intermediateJson = objectMapper.writeValueAsString(intermediateResult)

        val refinementPromptText =
            """
            |Now that you've parsed the OCR text, you can refine the results. Use the original text and your output JSON to
            |identify any changes you want to make after a second glance. For every change you make, add exactly one record
            |to the changes list. Follow these rules when defining the changes records in the list:
            |
            |- Use dotted field names to represent nested fields in the changes.field values
            |- If the field is part of an array, use the array index as if it were an array accessor, e.g. changes[0].field
            |- Fill changes.oldValue with the previous data
            |- Specify a rationale for the change in the changes.rationale field
            |
            |Here are the original parsing instructions:
            |```text
            |$parsingInstructions
            |```
            |
            |Here is the original OCR text:
            |```text
            |$ocrText
            |```
            |
            |Here is your initial parsing result:
            |```json
            |$intermediateJson
            |```
            |
            |Output only the corrected JSON and fill in the changes list
            |
            """.trimMargin()

        val prompt =
            Prompt
                .builder()
                .messages(UserMessage(refinementPromptText))
                .chatOptions(
                    OllamaOptions
                        .builder()
                        .format(beanOutputConverterCache.getConverter(clazz).jsonSchemaMap)
                        .build(),
                ).build()

        log.debug("Refinement calling ChatModel with prompt: {}", refinementPromptText)
        val result =
            chatModel
                .call(prompt)
                .results[0]
                .output.text
                ?: error("Chat model returned no output during refinement")
        log.debug("Refinement ChatModel response: {}", result)

        val refinedResult =
            beanOutputConverterCache.getConverter(clazz).convert(result)
                ?: error("Failed to convert refinement result")

        return refinedResult
    }
}
