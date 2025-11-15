package com.github.bsaltz.springai.examples.intelligence

import org.bsc.langgraph4j.CompileConfig
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
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture

@Service
class CompetitiveIntelligenceGraphService(
    private val intelligenceService: CompetitiveIntelligenceLlmService,
) {
    private val log: Logger = LoggerFactory.getLogger(javaClass)

    fun analyzeCompany(companyName: String): String {
        val graph = buildGraph()
        val compiledGraph =
            graph.compile(
                CompileConfig
                    .builder()
                    .recursionLimit(30)
                    .build(),
            )

        val initialState =
            mapOf(
                CompetitiveIntelligenceState.COMPANY_NAME_KEY to companyName,
                CompetitiveIntelligenceState.REMAINING_AGENT_CALLS_KEY to 10,
            )

        log.debug("Starting competitive intelligence graph execution for company: {}", companyName)

        val finalState: NodeOutput<CompetitiveIntelligenceState> =
            compiledGraph
                .stream(initialState)
                .asSequence()
                .onEach { nodeOutput ->
                    log.debug(
                        "Graph node '{}' completed. State keys: {}",
                        nodeOutput.node(),
                        nodeOutput.state().data().keys,
                    )
                }.lastOrNull()
                ?: error("Graph execution produced no output")

        log.debug("Competitive intelligence graph execution completed")

        return finalState
            .state()
            .value<String>(CompetitiveIntelligenceState.FINAL_REPORT_KEY)
            .orElseThrow { IllegalStateException("No final report generated") }
    }

    private fun buildGraph(): StateGraph<CompetitiveIntelligenceState> {
        val supervisorNode = SupervisorNode(intelligenceService)
        val financialAgent = FinancialAgent(intelligenceService)
        val productAgent = ProductAgent(intelligenceService)
        val newsAgent = NewsAgent(intelligenceService)
        val sentimentAgent = SentimentAgent(intelligenceService)
        val synthesizerNode = SynthesizerNode(intelligenceService)

        // Conditional edge for supervisor routing
        val supervisorCondition =
            AsyncEdgeAction<CompetitiveIntelligenceState> { state ->
                val nextAgent = state.nextAgent() ?: "DONE"
                val destination =
                    when (nextAgent) {
                        "financial" -> "financial"
                        "product" -> "product"
                        "news" -> "news"
                        "sentiment" -> "sentiment"
                        "DONE" -> "done"
                        else -> {
                            log.warn("Unknown agent: $nextAgent, routing to done")
                            "done"
                        }
                    }
                CompletableFuture.completedFuture(destination)
            }
        val supervisorMappings =
            mutableMapOf(
                "financial" to "financial_agent",
                "product" to "product_agent",
                "news" to "news_agent",
                "sentiment" to "sentiment_agent",
                "done" to "synthesizer",
            )

        return StateGraph(CompetitiveIntelligenceState.SCHEMA) { initData ->
            CompetitiveIntelligenceState(initData)
        }.addNode("supervisor", node_async(supervisorNode))
            .addNode("financial_agent", node_async(financialAgent))
            .addNode("product_agent", node_async(productAgent))
            .addNode("news_agent", node_async(newsAgent))
            .addNode("sentiment_agent", node_async(sentimentAgent))
            .addNode("synthesizer", node_async(synthesizerNode))
            .addEdge(StateGraph.START, "supervisor")
            .addConditionalEdges("supervisor", supervisorCondition, supervisorMappings)
            .addEdge("financial_agent", "supervisor")
            .addEdge("product_agent", "supervisor")
            .addEdge("news_agent", "supervisor")
            .addEdge("sentiment_agent", "supervisor")
            .addEdge("synthesizer", StateGraph.END)
    }

    // State class
    class CompetitiveIntelligenceState(
        initData: Map<String, Any>,
    ) : AgentState(initData) {
        companion object {
            const val COMPANY_NAME_KEY = "company_name"
            const val REMAINING_AGENT_CALLS_KEY = "remaining_agent_calls"
            const val AGENT_FINDINGS_KEY = "agent_findings"
            const val AGENTS_CALLED_KEY = "agents_called"
            const val SUPERVISOR_REASONING_KEY = "supervisor_reasoning"
            const val NEXT_AGENT_KEY = "next_agent"
            const val RESEARCH_FOCUS_KEY = "research_focus"
            const val FINAL_REPORT_KEY = "final_report"

            // Schema only includes input keys that are provided in initial state
            // Node output keys use default "last write wins" behavior or manual accumulation
            val SCHEMA: Map<String, Channel<*>> =
                mapOf(
                    COMPANY_NAME_KEY to Channels.base<String>({ _, o2 -> o2 }, { "" }),
                    REMAINING_AGENT_CALLS_KEY to Channels.base<Int>({ _, o2 -> o2 }, { 10 }),
                )
        }

        fun companyName(): String = value<String>(COMPANY_NAME_KEY).orElseThrow()

        fun remainingAgentCalls(): Int = value<Int>(REMAINING_AGENT_CALLS_KEY).orElse(10)

        fun agentFindings(): List<AgentFindings> = value<List<AgentFindings>>(AGENT_FINDINGS_KEY).orElse(emptyList())

        fun agentsCalled(): List<String> = value<List<String>>(AGENTS_CALLED_KEY).orElse(emptyList())

        fun supervisorReasoning(): List<String> = value<List<String>>(SUPERVISOR_REASONING_KEY).orElse(emptyList())

        fun nextAgent(): String? = value<String>(NEXT_AGENT_KEY).orElse(null)

        fun researchFocus(): String? = value<String>(RESEARCH_FOCUS_KEY).orElse(null)
    }

    // Supervisor Node - thin wrapper around service
    class SupervisorNode(
        private val intelligenceService: CompetitiveIntelligenceLlmService,
    ) : NodeAction<CompetitiveIntelligenceState> {
        override fun apply(state: CompetitiveIntelligenceState): Map<String, Any> {
            // Extract inputs from state
            val company = state.companyName()
            val previousFindings = state.agentFindings()
            val calledAgents = state.agentsCalled()
            val remainingCalls = state.remainingAgentCalls()

            // Call service
            val decision = intelligenceService.decideSupervisor(company, previousFindings, calledAgents, remainingCalls)

            // Manually accumulate lists
            val updatedReasoning = state.supervisorReasoning().toMutableList()
            updatedReasoning.add(decision.reasoning)

            val updatedAgentsCalled = state.agentsCalled().toMutableList()
            updatedAgentsCalled.add(decision.nextAgent)

            val resultMap =
                mutableMapOf(
                    CompetitiveIntelligenceState.NEXT_AGENT_KEY to decision.nextAgent,
                    CompetitiveIntelligenceState.SUPERVISOR_REASONING_KEY to updatedReasoning,
                    CompetitiveIntelligenceState.AGENTS_CALLED_KEY to updatedAgentsCalled,
                )

            // Store research focus if provided
            if (decision.researchFocus != null) {
                resultMap[CompetitiveIntelligenceState.RESEARCH_FOCUS_KEY] = decision.researchFocus
            }

            return resultMap
        }
    }

    // Financial Agent - thin wrapper around service
    class FinancialAgent(
        private val intelligenceService: CompetitiveIntelligenceLlmService,
    ) : NodeAction<CompetitiveIntelligenceState> {
        override fun apply(state: CompetitiveIntelligenceState): Map<String, Any> {
            val company = state.companyName()
            val previousFindings = state.agentFindings()
            val researchFocus = state.researchFocus()

            val agentFindings = intelligenceService.conductFinancialResearch(company, previousFindings, researchFocus)

            val updatedFindings = state.agentFindings().toMutableList()
            updatedFindings.add(agentFindings)
            val remainingCalls = state.remainingAgentCalls() - 1

            return mapOf(
                CompetitiveIntelligenceState.AGENT_FINDINGS_KEY to updatedFindings,
                CompetitiveIntelligenceState.REMAINING_AGENT_CALLS_KEY to remainingCalls,
            )
        }
    }

    // Product Agent - thin wrapper around service
    class ProductAgent(
        private val intelligenceService: CompetitiveIntelligenceLlmService,
    ) : NodeAction<CompetitiveIntelligenceState> {
        override fun apply(state: CompetitiveIntelligenceState): Map<String, Any> {
            val company = state.companyName()
            val previousFindings = state.agentFindings()
            val researchFocus = state.researchFocus()

            val agentFindings = intelligenceService.conductProductResearch(company, previousFindings, researchFocus)

            val updatedFindings = state.agentFindings().toMutableList()
            updatedFindings.add(agentFindings)
            val remainingCalls = state.remainingAgentCalls() - 1

            return mapOf(
                CompetitiveIntelligenceState.AGENT_FINDINGS_KEY to updatedFindings,
                CompetitiveIntelligenceState.REMAINING_AGENT_CALLS_KEY to remainingCalls,
            )
        }
    }

    // News Agent - thin wrapper around service
    class NewsAgent(
        private val intelligenceService: CompetitiveIntelligenceLlmService,
    ) : NodeAction<CompetitiveIntelligenceState> {
        override fun apply(state: CompetitiveIntelligenceState): Map<String, Any> {
            val company = state.companyName()
            val previousFindings = state.agentFindings()
            val researchFocus = state.researchFocus()

            val agentFindings = intelligenceService.conductNewsResearch(company, previousFindings, researchFocus)

            val updatedFindings = state.agentFindings().toMutableList()
            updatedFindings.add(agentFindings)
            val remainingCalls = state.remainingAgentCalls() - 1

            return mapOf(
                CompetitiveIntelligenceState.AGENT_FINDINGS_KEY to updatedFindings,
                CompetitiveIntelligenceState.REMAINING_AGENT_CALLS_KEY to remainingCalls,
            )
        }
    }

    // Sentiment Agent - thin wrapper around service
    class SentimentAgent(
        private val intelligenceService: CompetitiveIntelligenceLlmService,
    ) : NodeAction<CompetitiveIntelligenceState> {
        override fun apply(state: CompetitiveIntelligenceState): Map<String, Any> {
            val company = state.companyName()
            val previousFindings = state.agentFindings()
            val researchFocus = state.researchFocus()

            val agentFindings = intelligenceService.conductSentimentResearch(company, previousFindings, researchFocus)

            val updatedFindings = state.agentFindings().toMutableList()
            updatedFindings.add(agentFindings)
            val remainingCalls = state.remainingAgentCalls() - 1

            return mapOf(
                CompetitiveIntelligenceState.AGENT_FINDINGS_KEY to updatedFindings,
                CompetitiveIntelligenceState.REMAINING_AGENT_CALLS_KEY to remainingCalls,
            )
        }
    }

    // Synthesizer Node - thin wrapper around service
    class SynthesizerNode(
        private val intelligenceService: CompetitiveIntelligenceLlmService,
    ) : NodeAction<CompetitiveIntelligenceState> {
        override fun apply(state: CompetitiveIntelligenceState): Map<String, Any> {
            val company = state.companyName()
            val allFindings = state.agentFindings()
            val supervisorReasoning = state.supervisorReasoning()
            val agentsCalled = state.agentsCalled()

            val report = intelligenceService.synthesizeReport(company, allFindings, supervisorReasoning, agentsCalled)

            return mapOf(CompetitiveIntelligenceState.FINAL_REPORT_KEY to report)
        }
    }
}
