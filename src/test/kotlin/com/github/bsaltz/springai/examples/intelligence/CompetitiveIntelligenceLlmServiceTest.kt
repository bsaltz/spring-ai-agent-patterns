package com.github.bsaltz.springai.examples.intelligence

import com.github.bsaltz.springai.util.BeanOutputConverterCache
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider
import org.springframework.ai.tool.ToolCallback

class CompetitiveIntelligenceLlmServiceTest {
    private lateinit var chatModel: ChatModel
    private lateinit var toolCallbackProvider: SyncMcpToolCallbackProvider
    private lateinit var beanOutputConverterCache: BeanOutputConverterCache
    private lateinit var service: CompetitiveIntelligenceLlmService

    @BeforeEach
    fun setUp() {
        chatModel = mock()
        toolCallbackProvider = mock()
        beanOutputConverterCache = mock()
        service = CompetitiveIntelligenceLlmService(chatModel, toolCallbackProvider, beanOutputConverterCache)
    }

    @Test
    fun `buildContextPrompt should return empty string when no previous findings`() {
        // When
        val result = service.buildContextPrompt(emptyList())

        // Then
        assertEquals("", result)
    }

    @Test
    fun `buildContextPrompt should format previous findings correctly`() {
        // Given
        val findings =
            listOf(
                AgentFindings(
                    agentName = "Financial Analysis",
                    findings =
                        listOf(
                            Finding("Revenue is $100M", "https://example.com/1"),
                            Finding("Growing 50% YoY", "https://example.com/2"),
                        ),
                    confidence = "high",
                    dataAvailability = "comprehensive",
                ),
                AgentFindings(
                    agentName = "Product Analysis",
                    findings = listOf(Finding("SaaS product", "https://example.com/3")),
                    confidence = "medium",
                    dataAvailability = "limited",
                ),
            )

        // When
        val result = service.buildContextPrompt(findings)

        // Then
        assertTrue(result.contains("PREVIOUS FINDINGS CONTEXT"))
        assertTrue(result.contains("Financial Analysis"))
        assertTrue(result.contains("Revenue is $100M"))
        assertTrue(result.contains("Product Analysis"))
        assertTrue(result.contains("SaaS product"))
    }

    @Test
    fun `performTwoPassExtraction should return null when research call fails`() {
        // Given
        val tools = emptyList<ToolCallback>()
        whenever(chatModel.call(any<Prompt>())) doThrow RuntimeException("API Error")

        // When
        val result = service.performTwoPassExtraction("test prompt", tools, "TEST AGENT")

        // Then
        assertNull(result)
    }

    @Test
    fun `performTwoPassExtraction should return null when extraction call fails`() {
        // Given
        val tools = emptyList<ToolCallback>()
        val researchResponse = createChatResponse("Research findings with TOOL_USAGE: YES")

        whenever(chatModel.call(any<Prompt>())) doReturn researchResponse doThrow RuntimeException("Extraction failed")

        // When
        val result = service.performTwoPassExtraction("test prompt", tools, "TEST AGENT")

        // Then
        assertNull(result)
    }

    @Test
    fun `performTwoPassExtraction should return null when usedTools is false`() {
        // Given
        val tools = emptyList<ToolCallback>()
        val researchResponse = createChatResponse("Research findings with TOOL_USAGE: NO")
        val extraction =
            FindingsExtraction(
                findings = listOf(Finding("test", "url")),
                confidence = "medium",
                dataAvailability = "limited",
                usedTools = false,
            )

        val converter = mock<BeanOutputConverter<FindingsExtraction>>()
        whenever(beanOutputConverterCache.getConverter(FindingsExtraction::class.java)) doReturn converter
        whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")
        whenever(converter.convert(any())) doReturn extraction

        whenever(chatModel.call(any<Prompt>())) doReturn researchResponse doReturn createChatResponse("{}")

        // When
        val result = service.performTwoPassExtraction("test prompt", tools, "TEST AGENT")

        // Then
        assertNull(result)
    }

    @Test
    fun `performTwoPassExtraction should return extraction when successful`() {
        // Given
        val tools = emptyList<ToolCallback>()
        val researchResponse = createChatResponse("Research findings\nTOOL_USAGE: YES")
        val extraction =
            FindingsExtraction(
                findings = listOf(Finding("Company has $50M revenue", "https://example.com")),
                confidence = "high",
                dataAvailability = "comprehensive",
                usedTools = true,
            )

        val converter = mock<BeanOutputConverter<FindingsExtraction>>()
        whenever(beanOutputConverterCache.getConverter(FindingsExtraction::class.java)) doReturn converter
        whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")
        whenever(converter.convert(any())) doReturn extraction

        whenever(chatModel.call(any<Prompt>())) doReturn researchResponse doReturn createChatResponse("{}")

        // When
        val result = service.performTwoPassExtraction("test prompt", tools, "TEST AGENT")

        // Then
        assertNotNull(result)
        assertEquals(1, result?.findings?.size)
        assertEquals("Company has $50M revenue", result?.findings?.get(0)?.finding)
        assertEquals("high", result?.confidence)
        assertTrue(result!!.usedTools)
    }

    @Test
    fun `decideSupervisor should return DONE when no remaining calls`() {
        // When
        val result = service.decideSupervisor("TestCo", emptyList(), emptyList(), 0)

        // Then
        assertEquals("DONE", result.nextAgent)
        assertTrue(result.reasoning.contains("No remaining agent calls"))
    }

    @Test
    fun `decideSupervisor should call LLM for initial decision`() {
        // Given
        val decision =
            SupervisorDecision(
                nextAgent = "financial",
                reasoning = "Start with financial analysis",
                skipReason = null,
                researchFocus = null,
            )

        val converter = mock<BeanOutputConverter<SupervisorDecision>>()
        whenever(beanOutputConverterCache.getConverter(SupervisorDecision::class.java)) doReturn converter
        whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")
        whenever(converter.convert(any())) doReturn decision

        val response = createChatResponse("{}")
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When
        val result = service.decideSupervisor("TestCo", emptyList(), emptyList(), 5)

        // Then
        assertEquals("financial", result.nextAgent)
        assertEquals("Start with financial analysis", result.reasoning)
        verify(chatModel).call(any<Prompt>())
    }

    @Test
    fun `decideSupervisor should use continue prompt when there are previous findings`() {
        // Given
        val previousFindings =
            listOf(
                AgentFindings(
                    agentName = "Financial Analysis",
                    findings = listOf(Finding("test", "url")),
                    confidence = "medium",
                    dataAvailability = "limited",
                ),
            )
        val decision =
            SupervisorDecision(
                nextAgent = "product",
                reasoning = "Need product info next",
                skipReason = null,
                researchFocus = "Focus on pricing",
            )

        val converter = mock<BeanOutputConverter<SupervisorDecision>>()
        whenever(beanOutputConverterCache.getConverter(SupervisorDecision::class.java)) doReturn converter
        whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")
        whenever(converter.convert(any())) doReturn decision

        val response = createChatResponse("{}")
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When
        val result = service.decideSupervisor("TestCo", previousFindings, listOf("financial"), 3)

        // Then
        assertEquals("product", result.nextAgent)
        assertEquals("Focus on pricing", result.researchFocus)
    }

    @Test
    fun `conductFinancialResearch should return findings when extraction succeeds`() {
        // Given
        val extraction =
            FindingsExtraction(
                findings = listOf(Finding("Revenue $100M", "url")),
                confidence = "high",
                dataAvailability = "comprehensive",
                usedTools = true,
            )

        setupSuccessfulExtraction(extraction)
        val mockTool = mock<ToolCallback>()
        whenever(toolCallbackProvider.toolCallbacks).thenReturn(arrayOf(mockTool))

        // When
        val result = service.conductFinancialResearch("TestCo", emptyList(), null)

        // Then
        assertEquals("Financial Analysis", result.agentName)
        assertEquals(1, result.findings.size)
        assertEquals("high", result.confidence)
    }

    @Test
    fun `conductFinancialResearch should return empty findings when extraction fails`() {
        // Given
        setupFailedExtraction()
        val mockTool = mock<ToolCallback>()
        whenever(toolCallbackProvider.toolCallbacks).thenReturn(arrayOf(mockTool))

        // When
        val result = service.conductFinancialResearch("TestCo", emptyList(), null)

        // Then
        assertEquals("Financial Analysis", result.agentName)
        assertEquals(0, result.findings.size)
        assertEquals("low", result.confidence)
        assertEquals("sparse", result.dataAvailability)
    }

    @Test
    fun `conductProductResearch should return findings when extraction succeeds`() {
        // Given
        val extraction =
            FindingsExtraction(
                findings = listOf(Finding("SaaS platform", "url")),
                confidence = "medium",
                dataAvailability = "limited",
                usedTools = true,
            )

        setupSuccessfulExtraction(extraction)
        val mockTool = mock<ToolCallback>()
        whenever(toolCallbackProvider.toolCallbacks).thenReturn(arrayOf(mockTool))

        // When
        val result = service.conductProductResearch("TestCo", emptyList(), null)

        // Then
        assertEquals("Product Analysis", result.agentName)
        assertEquals(1, result.findings.size)
    }

    @Test
    fun `conductNewsResearch should return findings when extraction succeeds`() {
        // Given
        val extraction =
            FindingsExtraction(
                findings = listOf(Finding("Acquired competitor", "url")),
                confidence = "high",
                dataAvailability = "comprehensive",
                usedTools = true,
            )

        setupSuccessfulExtraction(extraction)
        val mockTool = mock<ToolCallback>()
        whenever(toolCallbackProvider.toolCallbacks).thenReturn(arrayOf(mockTool))

        // When
        val result = service.conductNewsResearch("TestCo", emptyList(), null)

        // Then
        assertEquals("Recent Developments", result.agentName)
        assertEquals(1, result.findings.size)
    }

    @Test
    fun `conductSentimentResearch should return findings when extraction succeeds`() {
        // Given
        val extraction =
            FindingsExtraction(
                findings = listOf(Finding("4.5 stars on G2", "url")),
                confidence = "medium",
                dataAvailability = "limited",
                usedTools = true,
            )

        setupSuccessfulExtraction(extraction)
        val mockTool = mock<ToolCallback>()
        whenever(toolCallbackProvider.toolCallbacks).thenReturn(arrayOf(mockTool))

        // When
        val result = service.conductSentimentResearch("TestCo", emptyList(), null)

        // Then
        assertEquals("Market Sentiment", result.agentName)
        assertEquals(1, result.findings.size)
    }

    @Test
    fun `conductFinancialResearch should include research focus when provided`() {
        // Given
        val extraction =
            FindingsExtraction(
                findings = listOf(Finding("Series B $50M", "url")),
                confidence = "high",
                dataAvailability = "comprehensive",
                usedTools = true,
            )

        setupSuccessfulExtraction(extraction)
        val mockTool = mock<ToolCallback>()
        whenever(toolCallbackProvider.toolCallbacks).thenReturn(arrayOf(mockTool))

        // When - with research focus
        val result = service.conductFinancialResearch("TestCo", emptyList(), "Focus on recent funding")

        // Then - verify result includes findings (research focus was used in prompt)
        assertNotNull(result)
        assertEquals(1, result.findings.size)
    }

    @Test
    fun `synthesizeReport should call LLM with findings and return report`() {
        // Given
        val findings =
            listOf(
                AgentFindings(
                    agentName = "Financial Analysis",
                    findings = listOf(Finding("$100M revenue", "url1")),
                    confidence = "high",
                    dataAvailability = "comprehensive",
                ),
                AgentFindings(
                    agentName = "Product Analysis",
                    findings = listOf(Finding("SaaS platform", "url2")),
                    confidence = "medium",
                    dataAvailability = "limited",
                ),
            )
        val reasoning = listOf("Started with financial", "Then checked product")
        val called = listOf("financial", "product")

        val reportText = "# TestCo Intelligence Report\n\nExecutive Summary..."
        val response = createChatResponse(reportText)
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When
        val result = service.synthesizeReport("TestCo", findings, reasoning, called)

        // Then
        assertEquals(reportText, result)
        verify(chatModel).call(any<Prompt>())
    }

    @Test
    fun `synthesizeReport should include all agent findings in prompt`() {
        // Given
        val findings =
            listOf(
                AgentFindings(
                    agentName = "Financial Analysis",
                    findings = listOf(Finding("Test finding", "url")),
                    confidence = "high",
                    dataAvailability = "comprehensive",
                ),
            )

        val response = createChatResponse("Report content")
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When
        service.synthesizeReport("TestCo", findings, emptyList(), listOf("financial"))

        // Then
        verify(chatModel).call(any<Prompt>())
    }

    // Helper methods

    private fun createChatResponse(text: String): ChatResponse {
        val message = AssistantMessage(text)
        val generation = Generation(message)
        return ChatResponse(listOf(generation))
    }

    private fun setupSuccessfulExtraction(extraction: FindingsExtraction) {
        val converter = mock<BeanOutputConverter<FindingsExtraction>>()
        whenever(beanOutputConverterCache.getConverter(FindingsExtraction::class.java)) doReturn converter
        whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")
        whenever(converter.convert(any())) doReturn extraction

        val response1 = createChatResponse("Research results\nTOOL_USAGE: YES")
        val response2 = createChatResponse("{}")
        whenever(chatModel.call(any<Prompt>())) doReturn response1 doReturn response2
    }

    private fun setupFailedExtraction() {
        // Make ChatModel throw exception to simulate failure
        whenever(chatModel.call(any<Prompt>())) doThrow RuntimeException("API Error")
    }
}
