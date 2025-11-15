package com.github.bsaltz.springai.examples.research

import com.github.bsaltz.springai.util.BeanOutputConverterCache
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.springframework.ai.tool.ToolCallback

class ResearchReportLlmServiceTest {
    private lateinit var chatModel: ChatModel
    private lateinit var beanOutputConverterCache: BeanOutputConverterCache
    private lateinit var service: ResearchReportLlmService

    @BeforeEach
    fun setUp() {
        chatModel = mock()
        beanOutputConverterCache = mock()
        service = ResearchReportLlmService(chatModel, beanOutputConverterCache)
    }

    @Test
    fun `validateAndSanitizeQuery should extract section count and sanitize query`() {
        // Given
        val rawQuery = "Write a report about AI with 5 sections"
        val validation = GuardrailsValidation(rejected = false, rejectionReason = "")

        val converter = mock<BeanOutputConverter<GuardrailsValidation>>()
        whenever(beanOutputConverterCache.getConverter(GuardrailsValidation::class.java)) doReturn converter
        whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")
        whenever(converter.convert(any())) doReturn validation

        val response = createChatResponse("""{"rejected": false, "rejectionReason": ""}""")
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When
        val result = service.validateAndSanitizeQuery(rawQuery)

        // Then
        assertNotNull(result)
        // The regex removes "5 sections" but leaves "with"
        assertEquals("Write a report about AI with", result.sanitizedQuery)
        assertEquals(5, result.sectionCount)
        assertFalse(result.rejected)
        assertEquals("", result.rejectionReason)
        verify(chatModel).call(any<Prompt>())
    }

    @Test
    fun `validateAndSanitizeQuery should use default section count when not specified`() {
        // Given
        val rawQuery = "Write a report about quantum computing"
        val validation = GuardrailsValidation(rejected = false, rejectionReason = "")

        val converter = mock<BeanOutputConverter<GuardrailsValidation>>()
        whenever(beanOutputConverterCache.getConverter(GuardrailsValidation::class.java)) doReturn converter
        whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")
        whenever(converter.convert(any())) doReturn validation

        val response = createChatResponse("""{"rejected": false, "rejectionReason": ""}""")
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When
        val result = service.validateAndSanitizeQuery(rawQuery)

        // Then
        assertEquals("Write a report about quantum computing", result.sanitizedQuery)
        assertEquals(4, result.sectionCount) // Default
        assertFalse(result.rejected)
    }

    @Test
    fun `validateAndSanitizeQuery should clamp section count to valid range`() {
        // Given
        val rawQuery = "Write a report with 10 sections" // Should be clamped to 5
        val validation = GuardrailsValidation(rejected = false, rejectionReason = "")

        val converter = mock<BeanOutputConverter<GuardrailsValidation>>()
        whenever(beanOutputConverterCache.getConverter(GuardrailsValidation::class.java)) doReturn converter
        whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")
        whenever(converter.convert(any())) doReturn validation

        val response = createChatResponse("""{"rejected": false, "rejectionReason": ""}""")
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When
        val result = service.validateAndSanitizeQuery(rawQuery)

        // Then
        assertEquals(5, result.sectionCount) // Clamped to max
    }

    @Test
    fun `validateAndSanitizeQuery should return rejection when query is rejected`() {
        // Given
        val rawQuery = "Write something harmful"
        val validation = GuardrailsValidation(rejected = true, rejectionReason = "Contains harmful content")

        val converter = mock<BeanOutputConverter<GuardrailsValidation>>()
        whenever(beanOutputConverterCache.getConverter(GuardrailsValidation::class.java)) doReturn converter
        whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")
        whenever(converter.convert(any())) doReturn validation

        val response = createChatResponse("""{"rejected": true, "rejectionReason": "Contains harmful content"}""")
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When
        val result = service.validateAndSanitizeQuery(rawQuery)

        // Then
        assertTrue(result.rejected)
        assertEquals("Contains harmful content", result.rejectionReason)
    }

    @Test
    fun `validateAndSanitizeQuery should throw when ChatModel returns null`() {
        // Given
        val rawQuery = "Test query"

        val converter = mock<BeanOutputConverter<GuardrailsValidation>>()
        whenever(beanOutputConverterCache.getConverter(GuardrailsValidation::class.java)) doReturn converter
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
            service.validateAndSanitizeQuery(rawQuery)
        }
    }

    @Test
    fun `validateAndSanitizeQuery should throw when conversion fails`() {
        // Given
        val rawQuery = "Test query"

        val converter = mock<BeanOutputConverter<GuardrailsValidation>>()
        whenever(beanOutputConverterCache.getConverter(GuardrailsValidation::class.java)) doReturn converter
        whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")
        whenever(converter.convert(any())) doReturn null

        val response = createChatResponse("""{"rejected": false}""")
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When/Then
        assertThrows(IllegalStateException::class.java) {
            service.validateAndSanitizeQuery(rawQuery)
        }
    }

    @Test
    fun `createResearchPlan should return a research plan`() {
        // Given
        val sanitizedQuery = "Quantum computing advances"
        val sectionCount = 4
        val plan =
            ResearchPlan(
                sections =
                    listOf(
                        Section("Introduction", listOf("What is quantum computing?"), listOf("quantum", "computing")),
                    ),
                overallObjective = "Explore quantum computing",
            )

        val converter = mock<BeanOutputConverter<ResearchPlan>>()
        whenever(beanOutputConverterCache.getConverter(ResearchPlan::class.java)) doReturn converter
        whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")
        whenever(converter.convert(any())) doReturn plan

        val response = createChatResponse("""{"sections": [...], "overallObjective": "..."}""")
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When
        val result = service.createResearchPlan(sanitizedQuery, sectionCount)

        // Then
        assertNotNull(result)
        assertEquals(1, result.sections.size)
        assertEquals("Introduction", result.sections[0].title)
        assertEquals("Explore quantum computing", result.overallObjective)
        verify(chatModel).call(any<Prompt>())
    }

    @Test
    fun `createResearchPlan should throw when ChatModel returns null`() {
        // Given
        val sanitizedQuery = "Test query"
        val sectionCount = 3

        val converter = mock<BeanOutputConverter<ResearchPlan>>()
        whenever(beanOutputConverterCache.getConverter(ResearchPlan::class.java)) doReturn converter
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
            service.createResearchPlan(sanitizedQuery, sectionCount)
        }
    }

    @Test
    fun `createResearchPlan should throw when conversion fails`() {
        // Given
        val sanitizedQuery = "Test query"
        val sectionCount = 3

        val converter = mock<BeanOutputConverter<ResearchPlan>>()
        whenever(beanOutputConverterCache.getConverter(ResearchPlan::class.java)) doReturn converter
        whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")
        whenever(converter.convert(any())) doReturn null

        val response = createChatResponse("""{"sections": []}""")
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When/Then
        assertThrows(IllegalStateException::class.java) {
            service.createResearchPlan(sanitizedQuery, sectionCount)
        }
    }

    @Test
    fun `conductResearch should return section research with markdown content`() {
        // Given
        val section =
            Section(
                title = "Introduction",
                researchQuestions = listOf("What is AI?", "How does it work?"),
                keyTopics = listOf("artificial intelligence", "machine learning"),
            )
        val tools = emptyList<ToolCallback>()
        val markdownContent = "### Research Question: What is AI?\n- AI is..."

        val response = createChatResponse(markdownContent)
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When
        val result = service.conductResearch(section, tools)

        // Then
        assertNotNull(result)
        assertEquals("Introduction", result.sectionTitle)
        assertEquals(markdownContent, result.markdownContent)
        verify(chatModel).call(any<Prompt>())
    }

    @Test
    fun `conductResearch should handle errors gracefully`() {
        // Given
        val section =
            Section(
                title = "Test Section",
                researchQuestions = listOf("Question?"),
                keyTopics = listOf("topic"),
            )
        val tools = emptyList<ToolCallback>()

        whenever(chatModel.call(any<Prompt>())) doThrow RuntimeException("API Error")

        // When
        val result = service.conductResearch(section, tools)

        // Then
        assertNotNull(result)
        assertEquals("Test Section", result.sectionTitle)
        assertTrue(result.markdownContent.contains("technical issues"))
        assertTrue(result.markdownContent.contains("API Error"))
    }

    @Test
    fun `conductResearch should handle null ChatModel output gracefully`() {
        // Given
        val section =
            Section(
                title = "Test Section",
                researchQuestions = listOf("Question?"),
                keyTopics = listOf("topic"),
            )
        val tools = emptyList<ToolCallback>()

        val message =
            mock<AssistantMessage> {
                on { text } doReturn null
            }
        val generation = Generation(message)
        val response = ChatResponse(listOf(generation))
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When
        val result = service.conductResearch(section, tools)

        // Then
        assertNotNull(result)
        assertEquals("Test Section", result.sectionTitle)
        assertTrue(result.markdownContent.contains("technical issues"))
    }

    @Test
    fun `synthesizeReport should return final report`() {
        // Given
        val sanitizedQuery = "AI Advances"
        val researchResults =
            listOf(
                SectionResearch("Introduction", "Content for introduction"),
                SectionResearch("Conclusion", "Content for conclusion"),
            )

        val finalReport = "# AI Advances\n\nExecutive Summary...\n\n## Introduction\n..."
        val response = createChatResponse(finalReport)
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When
        val result = service.synthesizeReport(sanitizedQuery, researchResults)

        // Then
        assertNotNull(result)
        assertEquals(finalReport, result)
        verify(chatModel).call(any<Prompt>())
    }

    @Test
    fun `synthesizeReport should throw when ChatModel returns null`() {
        // Given
        val sanitizedQuery = "Test"
        val researchResults = listOf(SectionResearch("Section", "Content"))

        val message =
            mock<AssistantMessage> {
                on { text } doReturn null
            }
        val generation = Generation(message)
        val response = ChatResponse(listOf(generation))
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When/Then
        assertThrows(IllegalStateException::class.java) {
            service.synthesizeReport(sanitizedQuery, researchResults)
        }
    }

    @Test
    fun `synthesizeReport should include all sections in prompt`() {
        // Given
        val sanitizedQuery = "Test Query"
        val researchResults =
            listOf(
                SectionResearch("Section 1", "Content 1"),
                SectionResearch("Section 2", "Content 2"),
                SectionResearch("Section 3", "Content 3"),
            )

        val response = createChatResponse("Final report")
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When
        service.synthesizeReport(sanitizedQuery, researchResults)

        // Then
        verify(chatModel).call(any<Prompt>())
    }

    // Helper methods

    private fun createChatResponse(text: String): ChatResponse {
        val message = AssistantMessage(text)
        val generation = Generation(message)
        return ChatResponse(listOf(generation))
    }
}
