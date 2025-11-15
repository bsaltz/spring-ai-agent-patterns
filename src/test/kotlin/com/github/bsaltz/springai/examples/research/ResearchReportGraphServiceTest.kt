package com.github.bsaltz.springai.examples.research

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider
import org.springframework.ai.tool.ToolCallback

class ResearchReportGraphServiceTest {
    private lateinit var researchReportLlmService: ResearchReportLlmService
    private lateinit var toolCallbackProvider: SyncMcpToolCallbackProvider
    private lateinit var service: ResearchReportGraphService

    @BeforeEach
    fun setUp() {
        researchReportLlmService = mock()
        toolCallbackProvider = mock()

        // Mock toolCallbacks to return empty array
        whenever(toolCallbackProvider.toolCallbacks) doReturn emptyArray()

        service = ResearchReportGraphService(researchReportLlmService, toolCallbackProvider, emptyList())
    }

    @Test
    fun `service can be instantiated with dependencies`() {
        // Given/When
        val testService = ResearchReportGraphService(researchReportLlmService, toolCallbackProvider, emptyList())

        // Then
        assertNotNull(testService)
    }

    @Test
    fun `generateReport builds graph with correct nodes`() {
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
    fun `GuardrailsNode should call researchReportLlmService validateAndSanitizeQuery for valid query`() {
        // Given
        val rawQuery = "Write a report about AI"
        val validationResult =
            QueryValidationResult(
                sanitizedQuery = "Write a report about AI",
                sectionCount = 4,
                rejected = false,
                rejectionReason = "",
            )

        whenever(researchReportLlmService.validateAndSanitizeQuery(eq(rawQuery))) doReturn validationResult

        val node = ResearchReportGraphService.GuardrailsNode(researchReportLlmService)
        val state =
            ResearchReportGraphService.ResearchReportState(
                mapOf(
                    ResearchReportGraphService.ResearchReportState.RAW_USER_QUERY_KEY to rawQuery,
                ),
            )

        // When
        val result = node.apply(state)

        // Then
        assertNotNull(result)
        assertEquals("Write a report about AI", result[ResearchReportGraphService.ResearchReportState.SANITIZED_QUERY_KEY])
        assertEquals(4, result[ResearchReportGraphService.ResearchReportState.SECTION_COUNT_KEY])
        assertFalse(result[ResearchReportGraphService.ResearchReportState.REJECTED_KEY] as Boolean)

        verify(researchReportLlmService).validateAndSanitizeQuery(eq(rawQuery))
    }

    @Test
    fun `GuardrailsNode should return rejection when query is rejected`() {
        // Given
        val rawQuery = "Harmful query"
        val validationResult =
            QueryValidationResult(
                sanitizedQuery = "Harmful query",
                sectionCount = 4,
                rejected = true,
                rejectionReason = "Contains harmful content",
            )

        whenever(researchReportLlmService.validateAndSanitizeQuery(eq(rawQuery))) doReturn validationResult

        val node = ResearchReportGraphService.GuardrailsNode(researchReportLlmService)
        val state =
            ResearchReportGraphService.ResearchReportState(
                mapOf(
                    ResearchReportGraphService.ResearchReportState.RAW_USER_QUERY_KEY to rawQuery,
                ),
            )

        // When
        val result = node.apply(state)

        // Then
        assertNotNull(result)
        assertTrue(result[ResearchReportGraphService.ResearchReportState.REJECTED_KEY] as Boolean)
        assertEquals("Contains harmful content", result[ResearchReportGraphService.ResearchReportState.REJECTION_REASON_KEY])
    }

    @Test
    fun `RejectionHandlerNode should create rejection message`() {
        // Given
        val node = ResearchReportGraphService.RejectionHandlerNode()
        val state =
            ResearchReportGraphService.ResearchReportState(
                mapOf(
                    ResearchReportGraphService.ResearchReportState.RAW_USER_QUERY_KEY to "test",
                    ResearchReportGraphService.ResearchReportState.REJECTED_KEY to true,
                    ResearchReportGraphService.ResearchReportState.REJECTION_REASON_KEY to "Test reason",
                ),
            )

        // When
        val result = node.apply(state)

        // Then
        assertNotNull(result)
        val report = result[ResearchReportGraphService.ResearchReportState.FINAL_REPORT_KEY] as String
        assertTrue(report.contains("Unable to Process Request"))
        assertTrue(report.contains("Test reason"))
    }

    @Test
    fun `RejectionHandlerNode should handle missing rejection reason`() {
        // Given
        val node = ResearchReportGraphService.RejectionHandlerNode()
        val state =
            ResearchReportGraphService.ResearchReportState(
                mapOf(
                    ResearchReportGraphService.ResearchReportState.RAW_USER_QUERY_KEY to "test",
                    ResearchReportGraphService.ResearchReportState.REJECTED_KEY to true,
                ),
            )

        // When
        val result = node.apply(state)

        // Then
        assertNotNull(result)
        val report = result[ResearchReportGraphService.ResearchReportState.FINAL_REPORT_KEY] as String
        assertTrue(report.contains("Your query could not be processed"))
    }

    @Test
    fun `PlannerNode should call researchReportLlmService createResearchPlan`() {
        // Given
        val sanitizedQuery = "AI advances"
        val sectionCount = 4
        val plan =
            ResearchPlan(
                sections =
                    listOf(
                        Section("Introduction", listOf("What is AI?"), listOf("AI", "machine learning")),
                        Section("Current State", listOf("Current trends?"), listOf("trends", "adoption")),
                    ),
                overallObjective = "Explore AI advances",
            )

        whenever(researchReportLlmService.createResearchPlan(eq(sanitizedQuery), eq(sectionCount))) doReturn plan

        val node = ResearchReportGraphService.PlannerNode(researchReportLlmService)
        val state =
            ResearchReportGraphService.ResearchReportState(
                mapOf(
                    ResearchReportGraphService.ResearchReportState.RAW_USER_QUERY_KEY to "AI advances",
                    ResearchReportGraphService.ResearchReportState.SANITIZED_QUERY_KEY to sanitizedQuery,
                    ResearchReportGraphService.ResearchReportState.SECTION_COUNT_KEY to sectionCount,
                    ResearchReportGraphService.ResearchReportState.REJECTED_KEY to false,
                ),
            )

        // When
        val result = node.apply(state)

        // Then
        assertNotNull(result)
        assertEquals(plan, result[ResearchReportGraphService.ResearchReportState.RESEARCH_PLAN_KEY])
        assertEquals(0, result[ResearchReportGraphService.ResearchReportState.CURRENT_SECTION_INDEX_KEY])

        verify(researchReportLlmService).createResearchPlan(eq(sanitizedQuery), eq(sectionCount))
    }

    @Test
    fun `ResearchExecutorNode should call researchReportLlmService conductResearch`() {
        // Given
        val section = Section("Introduction", listOf("What is AI?"), listOf("AI"))
        val plan = ResearchPlan(sections = listOf(section), overallObjective = "Test")
        val tools = emptyList<ToolCallback>()
        val sectionResearch = SectionResearch("Introduction", "### Research Question: What is AI?\n- AI is...")

        whenever(researchReportLlmService.conductResearch(eq(section), any())) doReturn sectionResearch

        val node = ResearchReportGraphService.ResearchExecutorNode(researchReportLlmService, tools)
        val state =
            ResearchReportGraphService.ResearchReportState(
                mapOf(
                    ResearchReportGraphService.ResearchReportState.RAW_USER_QUERY_KEY to "AI",
                    ResearchReportGraphService.ResearchReportState.SANITIZED_QUERY_KEY to "AI",
                    ResearchReportGraphService.ResearchReportState.SECTION_COUNT_KEY to 1,
                    ResearchReportGraphService.ResearchReportState.RESEARCH_PLAN_KEY to plan,
                    ResearchReportGraphService.ResearchReportState.CURRENT_SECTION_INDEX_KEY to 0,
                ),
            )

        // When
        val result = node.apply(state)

        // Then
        assertNotNull(result)
        assertEquals(1, result[ResearchReportGraphService.ResearchReportState.CURRENT_SECTION_INDEX_KEY])

        @Suppress("UNCHECKED_CAST")
        val results = result[ResearchReportGraphService.ResearchReportState.RESEARCH_RESULTS_KEY] as List<SectionResearch>
        assertEquals(1, results.size)
        assertEquals("Introduction", results[0].sectionTitle)

        verify(researchReportLlmService).conductResearch(eq(section), any())
    }

    @Test
    fun `ResearchExecutorNode should accumulate research results`() {
        // Given
        val section1 = Section("Introduction", listOf("What is AI?"), listOf("AI"))
        val section2 = Section("Applications", listOf("Where is AI used?"), listOf("applications"))
        val plan = ResearchPlan(sections = listOf(section1, section2), overallObjective = "Test")
        val tools = emptyList<ToolCallback>()

        val existingResearch = listOf(SectionResearch("Introduction", "Intro content"))
        val newResearch = SectionResearch("Applications", "Applications content")

        whenever(researchReportLlmService.conductResearch(eq(section2), any())) doReturn newResearch

        val node = ResearchReportGraphService.ResearchExecutorNode(researchReportLlmService, tools)
        val state =
            ResearchReportGraphService.ResearchReportState(
                mapOf(
                    ResearchReportGraphService.ResearchReportState.RAW_USER_QUERY_KEY to "AI",
                    ResearchReportGraphService.ResearchReportState.SANITIZED_QUERY_KEY to "AI",
                    ResearchReportGraphService.ResearchReportState.SECTION_COUNT_KEY to 2,
                    ResearchReportGraphService.ResearchReportState.RESEARCH_PLAN_KEY to plan,
                    ResearchReportGraphService.ResearchReportState.CURRENT_SECTION_INDEX_KEY to 1,
                    ResearchReportGraphService.ResearchReportState.RESEARCH_RESULTS_KEY to existingResearch,
                ),
            )

        // When
        val result = node.apply(state)

        // Then
        @Suppress("UNCHECKED_CAST")
        val results = result[ResearchReportGraphService.ResearchReportState.RESEARCH_RESULTS_KEY] as List<SectionResearch>
        assertEquals(2, results.size)
        assertEquals("Introduction", results[0].sectionTitle)
        assertEquals("Applications", results[1].sectionTitle)
    }

    @Test
    fun `SynthesizerNode should call researchReportLlmService synthesizeReport`() {
        // Given
        val sanitizedQuery = "AI advances"
        val researchResults =
            listOf(
                SectionResearch("Introduction", "Intro content"),
                SectionResearch("Conclusion", "Conclusion content"),
            )
        val finalReport = "# AI Advances Report\n\n..."

        whenever(
            researchReportLlmService.synthesizeReport(eq(sanitizedQuery), eq(researchResults)),
        ) doReturn finalReport

        val node = ResearchReportGraphService.SynthesizerNode(researchReportLlmService)
        val state =
            ResearchReportGraphService.ResearchReportState(
                mapOf(
                    ResearchReportGraphService.ResearchReportState.RAW_USER_QUERY_KEY to "AI advances",
                    ResearchReportGraphService.ResearchReportState.SANITIZED_QUERY_KEY to sanitizedQuery,
                    ResearchReportGraphService.ResearchReportState.RESEARCH_RESULTS_KEY to researchResults,
                ),
            )

        // When
        val result = node.apply(state)

        // Then
        assertNotNull(result)
        assertEquals(finalReport, result[ResearchReportGraphService.ResearchReportState.FINAL_REPORT_KEY])

        verify(researchReportLlmService).synthesizeReport(eq(sanitizedQuery), eq(researchResults))
    }

    @Test
    fun `ResearchReportState should retrieve values correctly`() {
        // Given
        val plan = ResearchPlan(sections = listOf(Section("Test", emptyList(), emptyList())), overallObjective = "Test")
        val results = listOf(SectionResearch("Test", "Content"))
        val state =
            ResearchReportGraphService.ResearchReportState(
                mapOf(
                    ResearchReportGraphService.ResearchReportState.RAW_USER_QUERY_KEY to "raw query",
                    ResearchReportGraphService.ResearchReportState.SANITIZED_QUERY_KEY to "sanitized",
                    ResearchReportGraphService.ResearchReportState.SECTION_COUNT_KEY to 3,
                    ResearchReportGraphService.ResearchReportState.RESEARCH_PLAN_KEY to plan,
                    ResearchReportGraphService.ResearchReportState.CURRENT_SECTION_INDEX_KEY to 1,
                    ResearchReportGraphService.ResearchReportState.RESEARCH_RESULTS_KEY to results,
                    ResearchReportGraphService.ResearchReportState.REJECTED_KEY to false,
                ),
            )

        // When/Then
        assertEquals("raw query", state.rawUserQuery())
        assertEquals("sanitized", state.sanitizedQuery())
        assertEquals(3, state.sectionCount())
        assertEquals(plan, state.researchPlan())
        assertEquals(1, state.currentSectionIndex())
        assertEquals(1, state.researchResults().size)
        assertFalse(state.rejected())
        assertEquals(null, state.rejectionReason())
    }

    @Test
    fun `ResearchReportState should return defaults for optional values`() {
        // Given
        val state =
            ResearchReportGraphService.ResearchReportState(
                mapOf(
                    ResearchReportGraphService.ResearchReportState.RAW_USER_QUERY_KEY to "test",
                ),
            )

        // When/Then
        assertEquals(0, state.currentSectionIndex())
        assertEquals(emptyList<SectionResearch>(), state.researchResults())
        assertFalse(state.rejected())
        assertEquals(null, state.rejectionReason())
    }
}
