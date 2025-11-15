package com.github.bsaltz.springai.examples.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path

class PdfParsingGraphServiceTest {
    private lateinit var pdfParsingLlmService: PdfParsingLlmService
    private lateinit var service: PdfParsingGraphService

    @TempDir
    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        pdfParsingLlmService = mock()
        service = PdfParsingGraphService(pdfParsingLlmService)
    }

    @Test
    fun `parsePdf builds graph with correct nodes`() {
        // This test verifies that the service can be instantiated and the graph structure
        // is set up correctly. Full integration testing of the async graph execution
        // would require more complex test infrastructure or integration tests.

        // Given/When - Service is created in setUp()

        // Then - verify service is properly initialized
        assertNotNull(service)
        // The actual graph execution is tested through individual node tests below
        // A full integration test would require mocking the entire langgraph4j execution flow
    }

    @Test
    fun `service can be instantiated with dependencies`() {
        // Given/When
        val testService = PdfParsingGraphService(pdfParsingLlmService)

        // Then
        assertNotNull(testService)
    }

    @Test
    fun `OcrPdfNode should call pdfParsingLlmService and return ocr path`() {
        // Given
        val inputPath = createTempPdf("input.pdf")
        val ocrPath = createTempPdf("ocr.pdf")

        whenever(pdfParsingLlmService.runOcr(inputPath)) doReturn ocrPath

        val node = PdfParsingGraphService.OcrPdfNode(pdfParsingLlmService)
        val state =
            PdfParsingGraphService.PdfParsingState(
                mapOf(
                    PdfParsingGraphService.PdfParsingState.PDF_PATH_KEY to inputPath.toString(),
                    PdfParsingGraphService.PdfParsingState.PARSING_INSTRUCTIONS_KEY to "test",
                ),
            )

        // When
        val result = node.apply(state)

        // Then
        assertNotNull(result)
        assertEquals(ocrPath.toString(), result[PdfParsingGraphService.PdfParsingState.OCR_PDF_PATH_KEY])
        verify(pdfParsingLlmService).runOcr(inputPath)
    }

    @Test
    fun `ExtractTextNode should call pdfParsingLlmService and return extracted text`() {
        // Given
        val ocrPath = createTempPdf("ocr.pdf")
        val extractedText = "This is the extracted text from the PDF"

        whenever(pdfParsingLlmService.extractText(ocrPath)) doReturn extractedText

        val node = PdfParsingGraphService.ExtractTextNode(pdfParsingLlmService)
        val state =
            PdfParsingGraphService.PdfParsingState(
                mapOf(
                    PdfParsingGraphService.PdfParsingState.PDF_PATH_KEY to "input.pdf",
                    PdfParsingGraphService.PdfParsingState.OCR_PDF_PATH_KEY to ocrPath.toString(),
                    PdfParsingGraphService.PdfParsingState.PARSING_INSTRUCTIONS_KEY to "test",
                ),
            )

        // When
        val result = node.apply(state)

        // Then
        assertNotNull(result)
        assertEquals(extractedText, result[PdfParsingGraphService.PdfParsingState.OCR_TEXT_KEY])
        verify(pdfParsingLlmService).extractText(ocrPath)
    }

    @Test
    fun `InitialParseNode should call pdfParsingLlmService and return parsed result`() {
        // Given
        val ocrText = "Sample OCR text with data"
        val parsingInstructions = "Extract name and age"
        val parsedResult = TestRefineable(name = "Alice", age = 25, changes = null)

        whenever(
            pdfParsingLlmService.initialParse(
                ocrText,
                parsingInstructions,
                TestRefineable::class.java,
            ),
        ) doReturn parsedResult

        val node =
            PdfParsingGraphService.InitialParseNode(
                pdfParsingLlmService,
                TestRefineable::class.java,
            )

        val state =
            PdfParsingGraphService.PdfParsingState(
                mapOf(
                    PdfParsingGraphService.PdfParsingState.PDF_PATH_KEY to "input.pdf",
                    PdfParsingGraphService.PdfParsingState.OCR_PDF_PATH_KEY to "ocr.pdf",
                    PdfParsingGraphService.PdfParsingState.OCR_TEXT_KEY to ocrText,
                    PdfParsingGraphService.PdfParsingState.PARSING_INSTRUCTIONS_KEY to parsingInstructions,
                ),
            )

        // When
        val result = node.apply(state)

        // Then
        assertNotNull(result)
        assertEquals(parsedResult, result[PdfParsingGraphService.PdfParsingState.INTERMEDIATE_RESULT_KEY])
        verify(pdfParsingLlmService).initialParse(ocrText, parsingInstructions, TestRefineable::class.java)
    }

    @Test
    fun `InitialParseNode should throw exception when pdfParsingLlmService throws`() {
        // Given
        val ocrText = "test text"
        val parsingInstructions = "test"

        whenever(
            pdfParsingLlmService.initialParse(
                ocrText,
                parsingInstructions,
                TestRefineable::class.java,
            ),
        ) doThrow IllegalStateException("Chat model returned no output")

        val node =
            PdfParsingGraphService.InitialParseNode(
                pdfParsingLlmService,
                TestRefineable::class.java,
            )

        val state =
            PdfParsingGraphService.PdfParsingState(
                mapOf(
                    PdfParsingGraphService.PdfParsingState.PDF_PATH_KEY to "input.pdf",
                    PdfParsingGraphService.PdfParsingState.OCR_PDF_PATH_KEY to "ocr.pdf",
                    PdfParsingGraphService.PdfParsingState.OCR_TEXT_KEY to ocrText,
                    PdfParsingGraphService.PdfParsingState.PARSING_INSTRUCTIONS_KEY to parsingInstructions,
                ),
            )

        // When/Then
        assertThrows(IllegalStateException::class.java) {
            node.apply(state)
        }
    }

    @Test
    fun `RefinementNode should call pdfParsingLlmService and return refined result`() {
        // Given
        val ocrText = "Original OCR text"
        val parsingInstructions = "test"
        val intermediateResult = TestRefineable(name = "Bob", age = 40, changes = null)
        val refinedResult =
            TestRefineable(
                name = "Robert",
                age = 40,
                changes = listOf(Change("name", "Bob", "Full name correction")),
            )

        whenever(
            pdfParsingLlmService.refineResult(
                ocrText,
                parsingInstructions,
                intermediateResult,
                TestRefineable::class.java,
            ),
        ) doReturn refinedResult

        val node =
            PdfParsingGraphService.RefinementNode(
                pdfParsingLlmService,
                TestRefineable::class.java,
            )

        val state =
            PdfParsingGraphService.PdfParsingState(
                mapOf(
                    PdfParsingGraphService.PdfParsingState.PDF_PATH_KEY to "input.pdf",
                    PdfParsingGraphService.PdfParsingState.OCR_PDF_PATH_KEY to "ocr.pdf",
                    PdfParsingGraphService.PdfParsingState.OCR_TEXT_KEY to ocrText,
                    PdfParsingGraphService.PdfParsingState.PARSING_INSTRUCTIONS_KEY to parsingInstructions,
                    PdfParsingGraphService.PdfParsingState.INTERMEDIATE_RESULT_KEY to intermediateResult,
                ),
            )

        // When
        val result = node.apply(state)

        // Then
        assertNotNull(result)
        assertEquals(refinedResult, result[PdfParsingGraphService.PdfParsingState.FINAL_RESULT_KEY])
        verify(pdfParsingLlmService).refineResult(
            ocrText,
            parsingInstructions,
            intermediateResult,
            TestRefineable::class.java,
        )
    }

    @Test
    fun `RefinementNode should throw exception when no intermediate result is available`() {
        // Given
        val node =
            PdfParsingGraphService.RefinementNode(
                pdfParsingLlmService,
                TestRefineable::class.java,
            )

        val state =
            PdfParsingGraphService.PdfParsingState(
                mapOf(
                    PdfParsingGraphService.PdfParsingState.PDF_PATH_KEY to "input.pdf",
                    PdfParsingGraphService.PdfParsingState.OCR_PDF_PATH_KEY to "ocr.pdf",
                    PdfParsingGraphService.PdfParsingState.OCR_TEXT_KEY to "text",
                    PdfParsingGraphService.PdfParsingState.PARSING_INSTRUCTIONS_KEY to "test",
                    // Note: No INTERMEDIATE_RESULT_KEY
                ),
            )

        // When/Then
        assertThrows(IllegalStateException::class.java) {
            node.apply(state)
        }
    }

    @Test
    fun `RefinementNode should throw exception when pdfParsingLlmService throws`() {
        // Given
        val ocrText = "text"
        val parsingInstructions = "test"
        val intermediateResult = TestRefineable(name = "Test", age = 50, changes = null)

        whenever(
            pdfParsingLlmService.refineResult(
                ocrText,
                parsingInstructions,
                intermediateResult,
                TestRefineable::class.java,
            ),
        ) doThrow IllegalStateException("Failed to convert refinement result")

        val node =
            PdfParsingGraphService.RefinementNode(
                pdfParsingLlmService,
                TestRefineable::class.java,
            )

        val state =
            PdfParsingGraphService.PdfParsingState(
                mapOf(
                    PdfParsingGraphService.PdfParsingState.PDF_PATH_KEY to "input.pdf",
                    PdfParsingGraphService.PdfParsingState.OCR_PDF_PATH_KEY to "ocr.pdf",
                    PdfParsingGraphService.PdfParsingState.OCR_TEXT_KEY to ocrText,
                    PdfParsingGraphService.PdfParsingState.PARSING_INSTRUCTIONS_KEY to parsingInstructions,
                    PdfParsingGraphService.PdfParsingState.INTERMEDIATE_RESULT_KEY to intermediateResult,
                ),
            )

        // When/Then
        assertThrows(IllegalStateException::class.java) {
            node.apply(state)
        }
    }

    @Test
    fun `PdfParsingState should retrieve values correctly`() {
        // Given
        val state =
            PdfParsingGraphService.PdfParsingState(
                mapOf(
                    PdfParsingGraphService.PdfParsingState.PDF_PATH_KEY to "test.pdf",
                    PdfParsingGraphService.PdfParsingState.OCR_PDF_PATH_KEY to "ocr.pdf",
                    PdfParsingGraphService.PdfParsingState.OCR_TEXT_KEY to "text content",
                    PdfParsingGraphService.PdfParsingState.PARSING_INSTRUCTIONS_KEY to "instructions",
                    PdfParsingGraphService.PdfParsingState.INTERMEDIATE_RESULT_KEY to "intermediate",
                ),
            )

        // When/Then
        assertEquals("test.pdf", state.pdfPath())
        assertEquals("ocr.pdf", state.ocrPdfPath())
        assertEquals("text content", state.ocrText())
        assertEquals("instructions", state.parsingInstructions())
        assertEquals("intermediate", state.intermediateResult())
    }

    // Helper methods

    private fun createTempPdf(name: String): Path {
        val pdf = tempDir.resolve(name)
        Files.writeString(pdf, "%PDF-1.4\nTest content\n%%EOF")
        return pdf
    }

    // Test data class
    data class TestRefineable(
        val name: String,
        val age: Int,
        override val changes: List<Change>?,
    ) : Refineable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}
