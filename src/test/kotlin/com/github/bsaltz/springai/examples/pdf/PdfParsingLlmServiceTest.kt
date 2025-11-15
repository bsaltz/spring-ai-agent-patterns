package com.github.bsaltz.springai.examples.pdf

import com.github.bsaltz.springai.util.BeanOutputConverterCache
import com.github.bsaltz.springai.util.ocrmypdf.PdfOcrService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.converter.BeanOutputConverter
import java.nio.file.Paths

class PdfParsingLlmServiceTest {
    private lateinit var chatModel: ChatModel
    private lateinit var pdfOcrService: PdfOcrService
    private lateinit var beanOutputConverterCache: BeanOutputConverterCache
    private lateinit var service: PdfParsingLlmService

    @BeforeEach
    fun setUp() {
        chatModel = mock()
        pdfOcrService = mock()
        beanOutputConverterCache = mock()
        service = PdfParsingLlmService(chatModel, pdfOcrService, beanOutputConverterCache)
    }

    @Test
    fun `runOcr should call pdfOcrService and return path`() {
        // Given
        val inputPath = Paths.get("/path/to/input.pdf")
        val outputPath = Paths.get("/path/to/output-ocr.pdf")
        whenever(pdfOcrService.runOcrmypdf(inputPath)) doReturn outputPath

        // When
        val result = service.runOcr(inputPath)

        // Then
        assertEquals(outputPath, result)
        verify(pdfOcrService).runOcrmypdf(inputPath)
    }

    @Test
    fun `runOcr should propagate exception when pdfOcrService fails`() {
        // Given
        val inputPath = Paths.get("/path/to/input.pdf")
        whenever(pdfOcrService.runOcrmypdf(inputPath)) doThrow RuntimeException("OCR failed")

        // When/Then
        assertThrows(RuntimeException::class.java) {
            service.runOcr(inputPath)
        }
    }

    @Test
    fun `extractText should call pdfOcrService and return text`() {
        // Given
        val pdfPath = Paths.get("/path/to/ocr.pdf")
        val expectedText = "This is OCR extracted text from the PDF"
        whenever(pdfOcrService.runPdftotext(pdfPath)) doReturn expectedText

        // When
        val result = service.extractText(pdfPath)

        // Then
        assertEquals(expectedText, result)
        verify(pdfOcrService).runPdftotext(pdfPath)
    }

    @Test
    fun `extractText should propagate exception when pdfOcrService fails`() {
        // Given
        val pdfPath = Paths.get("/path/to/ocr.pdf")
        whenever(pdfOcrService.runPdftotext(pdfPath)) doThrow RuntimeException("Text extraction failed")

        // When/Then
        assertThrows(RuntimeException::class.java) {
            service.extractText(pdfPath)
        }
    }

    @Test
    fun `initialParse should parse OCR text successfully`() {
        // Given
        val ocrText = "Sample OCR text"
        val instructions = "Parse this text"
        val clazz = TestRefineable::class.java
        val expectedResult = TestRefineable(data = "parsed data", changes = emptyList())

        val converter = mock<BeanOutputConverter<TestRefineable>>()
        whenever(beanOutputConverterCache.getConverter(clazz)) doReturn converter
        whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")
        whenever(converter.convert(any())) doReturn expectedResult

        val response = createChatResponse("""{"data": "parsed data", "changes": []}""")
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When
        val result = service.initialParse(ocrText, instructions, clazz)

        // Then
        assertNotNull(result)
        assertEquals("parsed data", result.data)
        verify(chatModel).call(any<Prompt>())
        verify(converter).convert(any())
    }

    @Test
    fun `initialParse should throw error when ChatModel returns null output`() {
        // Given
        val ocrText = "Sample OCR text"
        val instructions = "Parse this text"
        val clazz = TestRefineable::class.java

        val converter = mock<BeanOutputConverter<TestRefineable>>()
        whenever(beanOutputConverterCache.getConverter(clazz)) doReturn converter
        whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")

        val message =
            mock<AssistantMessage> {
                on { text } doReturn null
            }
        val generation = Generation(message)
        val response = ChatResponse(listOf(generation))
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When/Then
        assertThrows(IllegalStateException::class.java) {
            service.initialParse(ocrText, instructions, clazz)
        }
    }

    @Test
    fun `initialParse should throw error when conversion fails`() {
        // Given
        val ocrText = "Sample OCR text"
        val instructions = "Parse this text"
        val clazz = TestRefineable::class.java

        val converter = mock<BeanOutputConverter<TestRefineable>>()
        whenever(beanOutputConverterCache.getConverter(clazz)) doReturn converter
        whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")
        whenever(converter.convert(any())) doReturn null

        val response = createChatResponse("""{"data": "parsed data"}""")
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When/Then
        assertThrows(IllegalStateException::class.java) {
            service.initialParse(ocrText, instructions, clazz)
        }
    }

    @Test
    fun `initialParse should include parsing instructions in prompt`() {
        // Given
        val ocrText = "Sample OCR text"
        val instructions = "Extract all dates and amounts"
        val clazz = TestRefineable::class.java
        val expectedResult = TestRefineable(data = "parsed", changes = emptyList())

        val converter = mock<BeanOutputConverter<TestRefineable>>()
        whenever(beanOutputConverterCache.getConverter(clazz)) doReturn converter
        whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")
        whenever(converter.convert(any())) doReturn expectedResult

        val response = createChatResponse("""{"data": "parsed", "changes": []}""")
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When
        val result = service.initialParse(ocrText, instructions, clazz)

        // Then
        assertNotNull(result)
        verify(chatModel).call(any<Prompt>())
    }

    @Test
    fun `refineResult should refine intermediate result successfully`() {
        // Given
        val ocrText = "Sample OCR text"
        val parsingInstructions = "Instructions for parsing"
        val intermediateResult = TestRefineable(data = "initial parse", changes = emptyList())
        val clazz = TestRefineable::class.java
        val refinedResult =
            TestRefineable(
                data = "refined parse",
                changes =
                    listOf(
                        Change("data", "initial parse", "Corrected after review"),
                    ),
            )

        val converter = mock<BeanOutputConverter<TestRefineable>>()
        whenever(beanOutputConverterCache.getConverter(clazz)) doReturn converter
        whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")
        whenever(converter.convert(any())) doReturn refinedResult

        val response =
            createChatResponse(
                """{"data": "refined parse", "changes": [{"field": "data", "oldValue": "initial parse", "newValue": "refined parse", "rationale": "Corrected after review"}]}""",
            )
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When
        val result = service.refineResult(ocrText, parsingInstructions, intermediateResult, clazz)

        // Then
        assertNotNull(result)
        assertEquals("refined parse", result.data)
        assertEquals(1, result.changes.size)
        assertEquals("data", result.changes[0].field)
        verify(chatModel).call(any<Prompt>())
        verify(converter).convert(any())
    }

    @Test
    fun `refineResult should throw error when ChatModel returns null output`() {
        // Given
        val ocrText = "Sample OCR text"
        val parsingInstructions = "Instructions for parsing"
        val intermediateResult = TestRefineable(data = "initial", changes = emptyList())
        val clazz = TestRefineable::class.java

        val converter = mock<BeanOutputConverter<TestRefineable>>()
        whenever(beanOutputConverterCache.getConverter(clazz)) doReturn converter
        whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")

        val message =
            mock<AssistantMessage> {
                on { text } doReturn null
            }
        val generation = Generation(message)
        val response = ChatResponse(listOf(generation))
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When/Then
        assertThrows(IllegalStateException::class.java) {
            service.refineResult(ocrText, parsingInstructions, intermediateResult, clazz)
        }
    }

    @Test
    fun `refineResult should throw error when conversion fails`() {
        // Given
        val ocrText = "Sample OCR text"
        val parsingInstructions = "Instructions for parsing"
        val intermediateResult = TestRefineable(data = "initial", changes = emptyList())
        val clazz = TestRefineable::class.java

        val converter = mock<BeanOutputConverter<TestRefineable>>()
        whenever(beanOutputConverterCache.getConverter(clazz)) doReturn converter
        whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")
        whenever(converter.convert(any())) doReturn null

        val response = createChatResponse("""{"data": "refined"}""")
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When/Then
        assertThrows(IllegalStateException::class.java) {
            service.refineResult(ocrText, parsingInstructions, intermediateResult, clazz)
        }
    }

    @Test
    fun `refineResult should include intermediate result in prompt`() {
        // Given
        val ocrText = "Sample OCR text"
        val parsingInstructions = "Instructions for parsing"
        val intermediateResult = TestRefineable(data = "initial value", changes = emptyList())
        val clazz = TestRefineable::class.java
        val refinedResult = TestRefineable(data = "final value", changes = emptyList())

        val converter = mock<BeanOutputConverter<TestRefineable>>()
        whenever(beanOutputConverterCache.getConverter(clazz)) doReturn converter
        whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")
        whenever(converter.convert(any())) doReturn refinedResult

        val response = createChatResponse("""{"data": "final value", "changes": []}""")
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When
        val result = service.refineResult(ocrText, parsingInstructions, intermediateResult, clazz)

        // Then
        assertNotNull(result)
        verify(chatModel).call(any<Prompt>())
    }

    // Helper methods

    private fun createChatResponse(text: String): ChatResponse {
        val message = AssistantMessage(text)
        val generation = Generation(message)
        return ChatResponse(listOf(generation))
    }

    // Test data class implementing Refineable
    data class TestRefineable(
        val data: String,
        override val changes: List<Change>,
    ) : Refineable
}
