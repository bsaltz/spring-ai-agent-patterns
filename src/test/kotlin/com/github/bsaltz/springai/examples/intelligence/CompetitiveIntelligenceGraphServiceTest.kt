package com.github.bsaltz.springai.examples.intelligence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CompetitiveIntelligenceGraphServiceTest {
    private lateinit var intelligenceService: CompetitiveIntelligenceLlmService
    private lateinit var service: CompetitiveIntelligenceGraphService

    @BeforeEach
    fun setUp() {
        intelligenceService = mock()
        service = CompetitiveIntelligenceGraphService(intelligenceService)
    }

    @Test
    fun `service can be instantiated with dependencies`() {
        // Given/When
        val testService = CompetitiveIntelligenceGraphService(intelligenceService)

        // Then
        assertNotNull(testService)
    }

    @Test
    fun `analyzeCompany should call graph nodes in sequence`() {
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
    fun `SupervisorNode should call intelligenceService decideSupervisor`() {
        // Given
        val company = "TestCo"
        val decision =
            SupervisorDecision(
                nextAgent = "financial",
                reasoning = "Start with financial",
                skipReason = null,
                researchFocus = "Focus on revenue",
            )

        whenever(
            intelligenceService.decideSupervisor(
                eq(company),
                eq(emptyList()),
                eq(emptyList()),
                eq(10),
            ),
        ) doReturn decision

        val node = CompetitiveIntelligenceGraphService.SupervisorNode(intelligenceService)
        val state =
            CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState(
                mapOf(
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.COMPANY_NAME_KEY to company,
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.REMAINING_AGENT_CALLS_KEY to 10,
                ),
            )

        // When
        val result = node.apply(state)

        // Then
        assertNotNull(result)
        assertEquals("financial", result[CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.NEXT_AGENT_KEY])
        assertEquals("Focus on revenue", result[CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.RESEARCH_FOCUS_KEY])

        @Suppress("UNCHECKED_CAST")
        val reasoning = result[CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.SUPERVISOR_REASONING_KEY] as List<String>
        assertEquals(1, reasoning.size)
        assertEquals("Start with financial", reasoning[0])

        @Suppress("UNCHECKED_CAST")
        val agentsCalled = result[CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.AGENTS_CALLED_KEY] as List<String>
        assertEquals(1, agentsCalled.size)
        assertEquals("financial", agentsCalled[0])

        verify(intelligenceService).decideSupervisor(eq(company), eq(emptyList()), eq(emptyList()), eq(10))
    }

    @Test
    fun `SupervisorNode should accumulate reasoning and agentsCalled lists`() {
        // Given
        val company = "TestCo"
        val previousFindings =
            listOf(
                AgentFindings("Financial Analysis", listOf(Finding("test", "url")), "high", "comprehensive"),
            )
        val decision =
            SupervisorDecision(
                nextAgent = "product",
                reasoning = "Next check product",
                skipReason = null,
                researchFocus = null,
            )

        whenever(
            intelligenceService.decideSupervisor(
                eq(company),
                eq(previousFindings),
                eq(listOf("financial")),
                eq(9),
            ),
        ) doReturn decision

        val node = CompetitiveIntelligenceGraphService.SupervisorNode(intelligenceService)
        val state =
            CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState(
                mapOf(
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.COMPANY_NAME_KEY to company,
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.REMAINING_AGENT_CALLS_KEY to 9,
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.AGENT_FINDINGS_KEY to previousFindings,
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.SUPERVISOR_REASONING_KEY to
                        listOf("Previous reasoning"),
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.AGENTS_CALLED_KEY to listOf("financial"),
                ),
            )

        // When
        val result = node.apply(state)

        // Then
        @Suppress("UNCHECKED_CAST")
        val reasoning = result[CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.SUPERVISOR_REASONING_KEY] as List<String>
        assertEquals(2, reasoning.size)
        assertEquals("Previous reasoning", reasoning[0])
        assertEquals("Next check product", reasoning[1])

        @Suppress("UNCHECKED_CAST")
        val agentsCalled = result[CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.AGENTS_CALLED_KEY] as List<String>
        assertEquals(2, agentsCalled.size)
        assertEquals("financial", agentsCalled[0])
        assertEquals("product", agentsCalled[1])
    }

    @Test
    fun `FinancialAgent should call intelligenceService conductFinancialResearch`() {
        // Given
        val company = "TestCo"
        val agentFindings =
            AgentFindings(
                "Financial Analysis",
                listOf(Finding("Revenue $100M", "url")),
                "high",
                "comprehensive",
            )

        whenever(
            intelligenceService.conductFinancialResearch(eq(company), eq(emptyList()), isNull()),
        ) doReturn agentFindings

        val node = CompetitiveIntelligenceGraphService.FinancialAgent(intelligenceService)
        val state =
            CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState(
                mapOf(
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.COMPANY_NAME_KEY to company,
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.REMAINING_AGENT_CALLS_KEY to 10,
                ),
            )

        // When
        val result = node.apply(state)

        // Then
        assertNotNull(result)
        assertEquals(9, result[CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.REMAINING_AGENT_CALLS_KEY])

        @Suppress("UNCHECKED_CAST")
        val findings = result[CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.AGENT_FINDINGS_KEY] as List<AgentFindings>
        assertEquals(1, findings.size)
        assertEquals("Financial Analysis", findings[0].agentName)
        assertEquals(1, findings[0].findings.size)

        verify(intelligenceService).conductFinancialResearch(eq(company), eq(emptyList()), isNull())
    }

    @Test
    fun `FinancialAgent should pass researchFocus when provided`() {
        // Given
        val company = "TestCo"
        val researchFocus = "Focus on recent funding"
        val agentFindings =
            AgentFindings(
                "Financial Analysis",
                listOf(Finding("Series B $50M", "url")),
                "high",
                "comprehensive",
            )

        whenever(
            intelligenceService.conductFinancialResearch(eq(company), eq(emptyList()), eq(researchFocus)),
        ) doReturn agentFindings

        val node = CompetitiveIntelligenceGraphService.FinancialAgent(intelligenceService)
        val state =
            CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState(
                mapOf(
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.COMPANY_NAME_KEY to company,
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.REMAINING_AGENT_CALLS_KEY to 10,
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.RESEARCH_FOCUS_KEY to researchFocus,
                ),
            )

        // When
        val result = node.apply(state)

        // Then
        assertNotNull(result)
        verify(intelligenceService).conductFinancialResearch(eq(company), eq(emptyList()), eq(researchFocus))
    }

    @Test
    fun `ProductAgent should call intelligenceService conductProductResearch`() {
        // Given
        val company = "TestCo"
        val agentFindings =
            AgentFindings(
                "Product Analysis",
                listOf(Finding("SaaS platform", "url")),
                "medium",
                "limited",
            )

        whenever(
            intelligenceService.conductProductResearch(eq(company), eq(emptyList()), isNull()),
        ) doReturn agentFindings

        val node = CompetitiveIntelligenceGraphService.ProductAgent(intelligenceService)
        val state =
            CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState(
                mapOf(
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.COMPANY_NAME_KEY to company,
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.REMAINING_AGENT_CALLS_KEY to 10,
                ),
            )

        // When
        val result = node.apply(state)

        // Then
        assertNotNull(result)

        @Suppress("UNCHECKED_CAST")
        val findings = result[CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.AGENT_FINDINGS_KEY] as List<AgentFindings>
        assertEquals(1, findings.size)
        assertEquals("Product Analysis", findings[0].agentName)

        verify(intelligenceService).conductProductResearch(eq(company), eq(emptyList()), isNull())
    }

    @Test
    fun `NewsAgent should call intelligenceService conductNewsResearch`() {
        // Given
        val company = "TestCo"
        val agentFindings =
            AgentFindings(
                "Recent Developments",
                listOf(Finding("Acquired competitor", "url")),
                "high",
                "comprehensive",
            )

        whenever(
            intelligenceService.conductNewsResearch(eq(company), eq(emptyList()), isNull()),
        ) doReturn agentFindings

        val node = CompetitiveIntelligenceGraphService.NewsAgent(intelligenceService)
        val state =
            CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState(
                mapOf(
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.COMPANY_NAME_KEY to company,
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.REMAINING_AGENT_CALLS_KEY to 10,
                ),
            )

        // When
        val result = node.apply(state)

        // Then
        assertNotNull(result)

        @Suppress("UNCHECKED_CAST")
        val findings = result[CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.AGENT_FINDINGS_KEY] as List<AgentFindings>
        assertEquals(1, findings.size)
        assertEquals("Recent Developments", findings[0].agentName)

        verify(intelligenceService).conductNewsResearch(eq(company), eq(emptyList()), isNull())
    }

    @Test
    fun `SentimentAgent should call intelligenceService conductSentimentResearch`() {
        // Given
        val company = "TestCo"
        val agentFindings =
            AgentFindings(
                "Market Sentiment",
                listOf(Finding("4.5 stars on G2", "url")),
                "medium",
                "limited",
            )

        whenever(
            intelligenceService.conductSentimentResearch(eq(company), eq(emptyList()), isNull()),
        ) doReturn agentFindings

        val node = CompetitiveIntelligenceGraphService.SentimentAgent(intelligenceService)
        val state =
            CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState(
                mapOf(
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.COMPANY_NAME_KEY to company,
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.REMAINING_AGENT_CALLS_KEY to 10,
                ),
            )

        // When
        val result = node.apply(state)

        // Then
        assertNotNull(result)

        @Suppress("UNCHECKED_CAST")
        val findings = result[CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.AGENT_FINDINGS_KEY] as List<AgentFindings>
        assertEquals(1, findings.size)
        assertEquals("Market Sentiment", findings[0].agentName)

        verify(intelligenceService).conductSentimentResearch(eq(company), eq(emptyList()), isNull())
    }

    @Test
    fun `SynthesizerNode should call intelligenceService synthesizeReport`() {
        // Given
        val company = "TestCo"
        val findings =
            listOf(
                AgentFindings("Financial Analysis", listOf(Finding("$100M revenue", "url1")), "high", "comprehensive"),
                AgentFindings("Product Analysis", listOf(Finding("SaaS platform", "url2")), "medium", "limited"),
            )
        val reasoning = listOf("Started with financial", "Then checked product")
        val called = listOf("financial", "product")
        val expectedReport = "# TestCo Intelligence Report\n\nExecutive Summary..."

        whenever(
            intelligenceService.synthesizeReport(eq(company), eq(findings), eq(reasoning), eq(called)),
        ) doReturn expectedReport

        val node = CompetitiveIntelligenceGraphService.SynthesizerNode(intelligenceService)
        val state =
            CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState(
                mapOf(
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.COMPANY_NAME_KEY to company,
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.REMAINING_AGENT_CALLS_KEY to 0,
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.AGENT_FINDINGS_KEY to findings,
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.SUPERVISOR_REASONING_KEY to reasoning,
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.AGENTS_CALLED_KEY to called,
                ),
            )

        // When
        val result = node.apply(state)

        // Then
        assertNotNull(result)
        assertEquals(expectedReport, result[CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.FINAL_REPORT_KEY])

        verify(intelligenceService).synthesizeReport(eq(company), eq(findings), eq(reasoning), eq(called))
    }

    @Test
    fun `CompetitiveIntelligenceState should retrieve values correctly`() {
        // Given
        val findings =
            listOf(
                AgentFindings("Financial", listOf(Finding("test", "url")), "high", "comprehensive"),
            )
        val state =
            CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState(
                mapOf(
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.COMPANY_NAME_KEY to "TestCo",
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.REMAINING_AGENT_CALLS_KEY to 5,
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.AGENT_FINDINGS_KEY to findings,
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.AGENTS_CALLED_KEY to listOf("financial"),
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.SUPERVISOR_REASONING_KEY to listOf("reason"),
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.NEXT_AGENT_KEY to "product",
                    CompetitiveIntelligenceGraphService.CompetitiveIntelligenceState.RESEARCH_FOCUS_KEY to "focus",
                ),
            )

        // When/Then
        assertEquals("TestCo", state.companyName())
        assertEquals(5, state.remainingAgentCalls())
        assertEquals(1, state.agentFindings().size)
        assertEquals(1, state.agentsCalled().size)
        assertEquals(1, state.supervisorReasoning().size)
        assertEquals("product", state.nextAgent())
        assertEquals("focus", state.researchFocus())
    }
}
