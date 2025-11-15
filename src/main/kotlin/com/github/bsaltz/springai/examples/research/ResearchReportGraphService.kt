package com.github.bsaltz.springai.examples.research

import org.bsc.langgraph4j.NodeOutput
import org.bsc.langgraph4j.StateGraph
import org.bsc.langgraph4j.action.AsyncEdgeAction
import org.bsc.langgraph4j.action.AsyncNodeAction.node_async
import org.bsc.langgraph4j.action.NodeAction
import org.bsc.langgraph4j.state.AgentState
import org.bsc.langgraph4j.state.Channel
import org.bsc.langgraph4j.state.Channels
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider
import org.springframework.ai.tool.ToolCallback
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture

@Service
class ResearchReportGraphService(
    private val researchReportLlmService: ResearchReportLlmService,
    private val toolCallbackProvider: SyncMcpToolCallbackProvider,
    tools: List<ToolCallback>,
) {
    private val log: Logger = LoggerFactory.getLogger(javaClass)
    private val allTools = (tools + toolCallbackProvider.toolCallbacks).sortedBy { it.toolDefinition.name() }

    fun generateReport(rawQuery: String): String {
        // Build the graph
        val graph = buildResearchReportGraph()
        val compiledGraph = graph.compile()

        // Execute the graph with the initial state
        val initialState =
            mapOf(
                ResearchReportState.RAW_USER_QUERY_KEY to rawQuery,
            )

        log.debug("Starting research report graph execution for query: {}", rawQuery)

        // Stream through the graph and get the final state
        val finalState: NodeOutput<ResearchReportState> =
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

        log.debug("Research report graph execution completed")

        // Extract the final report from the state
        return finalState
            .state()
            .value<String>(ResearchReportState.FINAL_REPORT_KEY)
            .orElseThrow { IllegalStateException("No final report in state") }
    }

    private fun buildResearchReportGraph(): StateGraph<ResearchReportState> {
        val guardrailsNode = GuardrailsNode(researchReportLlmService)
        val rejectionHandlerNode = RejectionHandlerNode()
        val plannerNode = PlannerNode(researchReportLlmService)
        val researchExecutorNode = ResearchExecutorNode(researchReportLlmService, allTools)
        val synthesizerNode = SynthesizerNode(researchReportLlmService)

        // Conditional edge for guardrails: check if rejected or continue to planner
        val guardrailsCondition =
            AsyncEdgeAction<ResearchReportState> { state ->
                CompletableFuture.completedFuture(if (state.rejected()) "reject" else "continue")
            }
        val guardrailsMappings =
            mutableMapOf(
                "reject" to "rejection_handler",
                "continue" to "planner",
            )

        // Conditional edge for research loop: check if more sections to research
        val researchCondition =
            AsyncEdgeAction<ResearchReportState> { state ->
                val currentIdx = state.currentSectionIndex()
                val totalSections = state.researchPlan().sections.size
                CompletableFuture.completedFuture(if (currentIdx < totalSections) "loop" else "done")
            }
        val researchMappings =
            mutableMapOf(
                "loop" to "research_executor",
                "done" to "synthesizer",
            )

        return StateGraph(ResearchReportState.SCHEMA) { initData -> ResearchReportState(initData) }
            .addNode("guardrails", node_async(guardrailsNode))
            .addNode("rejection_handler", node_async(rejectionHandlerNode))
            .addNode("planner", node_async(plannerNode))
            .addNode("research_executor", node_async(researchExecutorNode))
            .addNode("synthesizer", node_async(synthesizerNode))
            .addEdge(StateGraph.START, "guardrails")
            .addConditionalEdges("guardrails", guardrailsCondition, guardrailsMappings)
            .addEdge("rejection_handler", StateGraph.END)
            .addEdge("planner", "research_executor")
            .addConditionalEdges("research_executor", researchCondition, researchMappings)
            .addEdge("synthesizer", StateGraph.END)
    }

    // State class for the research report graph
    class ResearchReportState(
        initData: Map<String, Any>,
    ) : AgentState(initData) {
        companion object {
            const val RAW_USER_QUERY_KEY = "raw_user_query"
            const val SANITIZED_QUERY_KEY = "sanitized_query"
            const val SECTION_COUNT_KEY = "section_count"
            const val RESEARCH_PLAN_KEY = "research_plan"
            const val CURRENT_SECTION_INDEX_KEY = "current_section_index"
            const val RESEARCH_RESULTS_KEY = "research_results"
            const val FINAL_REPORT_KEY = "final_report"
            const val REJECTED_KEY = "rejected"
            const val REJECTION_REASON_KEY = "rejection_reason"

            // Schema only includes input keys that are provided in initial state
            // Node output keys (SANITIZED_QUERY_KEY, SECTION_COUNT_KEY, RESEARCH_PLAN_KEY, etc.)
            // don't need to be in schema as they use the default "last write wins" behavior
            val SCHEMA: Map<String, Channel<*>> =
                mapOf(
                    RAW_USER_QUERY_KEY to Channels.base<String>({ _, o2 -> o2 }, { "" }),
                )
        }

        fun rawUserQuery(): String = value<String>(RAW_USER_QUERY_KEY).orElseThrow()

        fun sanitizedQuery(): String = value<String>(SANITIZED_QUERY_KEY).orElseThrow()

        fun sectionCount(): Int = value<Int>(SECTION_COUNT_KEY).orElseThrow()

        fun researchPlan(): ResearchPlan = value<ResearchPlan>(RESEARCH_PLAN_KEY).orElseThrow()

        fun currentSectionIndex(): Int = value<Int>(CURRENT_SECTION_INDEX_KEY).orElse(0)

        fun researchResults(): List<SectionResearch> = value<List<SectionResearch>>(RESEARCH_RESULTS_KEY).orElse(emptyList())

        fun rejected(): Boolean = value<Boolean>(REJECTED_KEY).orElse(false)

        fun rejectionReason(): String? = value<String>(REJECTION_REASON_KEY).orElse(null)
    }

    // Node for validating user queries - thin wrapper around service
    class GuardrailsNode(
        private val researchReportLlmService: ResearchReportLlmService,
    ) : NodeAction<ResearchReportState> {
        override fun apply(state: ResearchReportState): Map<String, Any> {
            val rawQuery = state.rawUserQuery()

            val validationResult = researchReportLlmService.validateAndSanitizeQuery(rawQuery)

            return if (!validationResult.rejected) {
                mapOf(
                    ResearchReportState.SANITIZED_QUERY_KEY to validationResult.sanitizedQuery,
                    ResearchReportState.SECTION_COUNT_KEY to validationResult.sectionCount,
                    ResearchReportState.REJECTED_KEY to false,
                )
            } else {
                mapOf(
                    ResearchReportState.REJECTED_KEY to true,
                    ResearchReportState.REJECTION_REASON_KEY to validationResult.rejectionReason,
                )
            }
        }
    }

    // Node for handling rejected queries
    class RejectionHandlerNode : NodeAction<ResearchReportState> {
        override fun apply(state: ResearchReportState): Map<String, Any> {
            val reason = state.rejectionReason() ?: "Your query could not be processed"
            val rejectionMessage =
                """
                # Unable to Process Request

                I apologize, but I cannot help with this request.

                **Reason:** $reason

                Please rephrase your query or try a different topic.
                """.trimIndent()

            return mapOf(ResearchReportState.FINAL_REPORT_KEY to rejectionMessage)
        }
    }

    // Node for creating a research plan - thin wrapper around service
    class PlannerNode(
        private val researchReportLlmService: ResearchReportLlmService,
    ) : NodeAction<ResearchReportState> {
        override fun apply(state: ResearchReportState): Map<String, Any> {
            val sanitizedQuery = state.sanitizedQuery()
            val sectionCount = state.sectionCount()

            val plan = researchReportLlmService.createResearchPlan(sanitizedQuery, sectionCount)

            return mapOf(
                ResearchReportState.RESEARCH_PLAN_KEY to plan,
                ResearchReportState.CURRENT_SECTION_INDEX_KEY to 0,
            )
        }
    }

    // Node for executing research on a specific section - thin wrapper around service
    class ResearchExecutorNode(
        private val researchReportLlmService: ResearchReportLlmService,
        private val tools: List<ToolCallback>,
    ) : NodeAction<ResearchReportState> {
        private val log: Logger = LoggerFactory.getLogger(javaClass)

        override fun apply(state: ResearchReportState): Map<String, Any> {
            val plan = state.researchPlan()
            val currentIdx = state.currentSectionIndex()
            val section = plan.sections[currentIdx]
            val currentResults = state.researchResults().toMutableList()

            log.info("Researching section {}/{}: {}", currentIdx + 1, plan.sections.size, section.title)

            val sectionResearch = researchReportLlmService.conductResearch(section, tools)

            currentResults.add(sectionResearch)
            return mapOf(
                ResearchReportState.RESEARCH_RESULTS_KEY to currentResults,
                ResearchReportState.CURRENT_SECTION_INDEX_KEY to (currentIdx + 1),
            )
        }
    }

    // Node for synthesizing the final report - thin wrapper around service
    class SynthesizerNode(
        private val researchReportLlmService: ResearchReportLlmService,
    ) : NodeAction<ResearchReportState> {
        override fun apply(state: ResearchReportState): Map<String, Any> {
            val sanitizedQuery = state.sanitizedQuery()
            val researchResults = state.researchResults()

            val report = researchReportLlmService.synthesizeReport(sanitizedQuery, researchResults)

            return mapOf(ResearchReportState.FINAL_REPORT_KEY to report)
        }
    }
}
