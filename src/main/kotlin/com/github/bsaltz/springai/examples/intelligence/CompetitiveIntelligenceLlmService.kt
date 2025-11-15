package com.github.bsaltz.springai.examples.intelligence

import com.github.bsaltz.springai.util.BeanOutputConverterCache
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider
import org.springframework.ai.ollama.api.OllamaOptions
import org.springframework.ai.tool.ToolCallback
import org.springframework.stereotype.Service

/**
 * Service containing the core business logic for competitive intelligence gathering.
 * This service handles all LLM interactions and prompt construction for the competitive
 * intelligence workflow, separated from the graph orchestration logic.
 */
@Service
class CompetitiveIntelligenceLlmService(
    private val chatModel: ChatModel,
    private val toolCallbackProvider: SyncMcpToolCallbackProvider,
    private val beanOutputConverterCache: BeanOutputConverterCache,
) {
    private val log: Logger = LoggerFactory.getLogger(javaClass)

    /**
     * Builds a context prompt from previous agent findings
     */
    fun buildContextPrompt(previousFindings: List<AgentFindings>): String {
        val previousFindingsString =
            previousFindings
                .groupBy { it.agentName }
                .mapValues {
                    it.value
                        .flatMap { f -> f.findings }
                        .joinToString(separator = "\n  - ", prefix = "  - ") { f -> f.finding }
                }.entries
                .joinToString(separator = "\n") { (agentName, findings) -> "- $agentName:\n$findings" }
        return if (previousFindings.isNotEmpty()) {
            """
            |PREVIOUS FINDINGS CONTEXT:
            |$previousFindingsString
            """.trimMargin()
        } else {
            ""
        }
    }

    /**
     * Common two-pass extraction pattern:
     * 1. First ChatModel call with tools to gather information
     * 2. Second ChatModel call with format to extract structured findings
     * Returns null if no tool calls were made (indicating hallucination)
     */
    fun performTwoPassExtraction(
        prompt: String,
        tools: List<ToolCallback>,
        agentName: String,
    ): FindingsExtraction? {
        // First pass: Research with tools
        val researchText =
            try {
                log.debug("[$agentName] First pass - calling ChatModel with tools. Prompt: {}", prompt)
                val response =
                    chatModel.call(
                        Prompt
                            .builder()
                            .messages(UserMessage(prompt))
                            .chatOptions(
                                OllamaOptions
                                    .builder()
                                    .toolCallbacks(tools)
                                    .numCtx(524288)
                                    .numPredict(32768)
                                    .build(),
                            ).build(),
                    )

                val text = response.results[0].output.text ?: ""
                log.debug("[$agentName] First pass - ChatModel response: {}", text)
                text
            } catch (e: Exception) {
                log.warn("[$agentName] Research failed: ${e.message}", e)
                return null
            }

        // Second pass: Extract structured findings
        val extractionPrompt =
            """
            |Extract the research findings from the text below into structured data.
            |
            |Each finding should be a specific fact or insight paired with its source URL.
            |
            |INPUT TEXT:
            |$researchText
            |
            |Also extract:
            |- Confidence level (high/medium/low) based on source quality
            |- Data availability (comprehensive/limited/sparse) based on amount of information found
            |- Tool usage indicator: Look for "TOOL_USAGE: YES" or "TOOL_USAGE: NO" at the end of the text
            |  Set usedTools to true if you find "TOOL_USAGE: YES", false otherwise
            |
            |Output ONLY valid JSON matching this structure:
            |{
            |  "findings": [
            |    {"finding": "Specific fact or insight", "source": "https://..."},
            |    {"finding": "Another fact", "source": "https://..."}
            |  ],
            |  "confidence": "medium",
            |  "dataAvailability": "limited",
            |  "usedTools": true
            |}
            """.trimMargin()

        val converter = beanOutputConverterCache.getConverter(FindingsExtraction::class.java)
        val extraction =
            try {
                log.debug("[$agentName] Second pass - calling ChatModel for extraction. Prompt: {}", extractionPrompt)
                val extractionResponse =
                    chatModel.call(
                        Prompt
                            .builder()
                            .messages(UserMessage(extractionPrompt))
                            .chatOptions(
                                OllamaOptions
                                    .builder()
                                    .format(converter.jsonSchemaMap)
                                    .numCtx(524288)
                                    .numPredict(8192)
                                    .build(),
                            ).build(),
                    )
                val extractedText = extractionResponse.results[0].output.text ?: ""
                log.debug("[$agentName] Second pass - ChatModel response: {}", extractedText)
                converter.convert(extractedText)
            } catch (e: Exception) {
                log.warn("[$agentName] Extraction failed: ${e.message}", e)
                null
            }

        // Check if tools were actually used based on agent's self-report
        if (extraction != null && !extraction.usedTools) {
            log.warn("[$agentName] No tool calls detected - agent did not search for information")
            return null
        }

        return extraction
    }

    /**
     * Makes a supervisor decision about which agent to call next
     */
    fun decideSupervisor(
        company: String,
        previousFindings: List<AgentFindings>,
        calledAgents: List<String>,
        remainingCalls: Int,
    ): SupervisorDecision {
        val availableAgents = listOf("financial", "product", "news", "sentiment")

        if (remainingCalls <= 0) {
            return SupervisorDecision(
                nextAgent = "DONE",
                reasoning = "No remaining agent calls in budget",
                skipReason = null,
                researchFocus = null,
            )
        }

        val prompt =
            if (previousFindings.isEmpty()) {
                buildInitialDecisionPrompt(company, remainingCalls)
            } else {
                buildContinueDecisionPrompt(company, previousFindings, calledAgents, remainingCalls, availableAgents)
            }

        val converter = beanOutputConverterCache.getConverter(SupervisorDecision::class.java)

        log.debug("[SUPERVISOR] Calling ChatModel with prompt: {}", prompt)
        val llmResponse =
            chatModel
                .call(
                    Prompt
                        .builder()
                        .messages(UserMessage(prompt))
                        .chatOptions(
                            OllamaOptions
                                .builder()
                                .format(converter.jsonSchemaMap)
                                .build(),
                        ).build(),
                ).results[0]
                .output.text ?: error("No supervisor decision")

        log.debug("[SUPERVISOR] ChatModel response: {}", llmResponse)

        val decision =
            converter.convert(llmResponse)
                ?: error("Failed to parse supervisor decision")

        log.info("[SUPERVISOR DECISION] Next: ${decision.nextAgent}")
        log.info("[SUPERVISOR REASONING] ${decision.reasoning}")
        decision.skipReason?.let { log.info("[SKIP REASON] $it") }
        decision.researchFocus?.let { log.info("[RESEARCH FOCUS] $it") }

        return decision
    }

    /**
     * Conducts financial research on a company
     */
    fun conductFinancialResearch(
        company: String,
        previousFindings: List<AgentFindings>,
        researchFocus: String?,
    ): AgentFindings {
        val contextPrompt = buildContextPrompt(previousFindings)

        val additionalContext =
            if (previousFindings.isNotEmpty()) {
                "\n\nUse this context to focus your financial research on relevant areas."
            } else {
                ""
            }

        val focusDirective =
            if (researchFocus != null) {
                "\n\n**SPECIFIC RESEARCH FOCUS FROM SUPERVISOR:**\n$researchFocus\n\nPrioritize answering this specific question in your research."
            } else {
                ""
            }

        val prompt =
            """
            |You are a financial intelligence specialist researching: "$company"
            |
            |$contextPrompt$additionalContext$focusDirective
            |
            |Research these financial areas:
            |- Revenue, growth rates, and financial performance
            |- Funding, valuation, and capital structure
            |- Profitability and financial health
            |- Market cap (if public) or runway (if startup)
            |
            |**Tool Usage:**
            |Use the spring_ai_mcp_client_brave_search_brave_web_search tool with these parameters:
            |{
            |  "query": "your search query here",
            |  "count": 10,
            |  "offset": 0,
            |  "ui_lang": "en-US",
            |  "country": "US",
            |  "text_decorations": false,
            |  "spellcheck": true
            |}
            |
            |Search for information, then write your findings with source citations.
            |Be explicit when data is unavailable or estimated.
            |
            |**IMPORTANT:** At the END of your response, you MUST include one of these lines:
            |- If you called the search tool: "TOOL_USAGE: YES"
            |- If you did NOT call the search tool: "TOOL_USAGE: NO"
            """.trimMargin()

        log.info("[FINANCIAL AGENT] Conducting financial analysis...")
        val tools = toolCallbackProvider.toolCallbacks.toList()

        val extraction = performTwoPassExtraction(prompt, tools, "FINANCIAL AGENT")

        log.info("[FINANCIAL AGENT] Financial analysis completed.")

        return if (extraction != null) {
            AgentFindings(
                agentName = "Financial Analysis",
                findings = extraction.findings,
                confidence = extraction.confidence,
                dataAvailability = extraction.dataAvailability,
            )
        } else {
            AgentFindings(
                agentName = "Financial Analysis",
                findings = emptyList(),
                confidence = "low",
                dataAvailability = "sparse",
            )
        }
    }

    /**
     * Conducts product research on a company
     */
    fun conductProductResearch(
        company: String,
        previousFindings: List<AgentFindings>,
        researchFocus: String?,
    ): AgentFindings {
        val contextPrompt = buildContextPrompt(previousFindings)

        val focusDirective =
            if (researchFocus != null) {
                "\n\n**SPECIFIC RESEARCH FOCUS FROM SUPERVISOR:**\n$researchFocus\n\nPrioritize answering this specific question in your research."
            } else {
                ""
            }

        val prompt =
            """
            |You are a product intelligence specialist researching: "$company"
            |
            |$contextPrompt$focusDirective
            |
            |Research these product areas:
            |- Core products and services
            |- Key features and capabilities
            |- Pricing strategy and tiers
            |- Market positioning and differentiation
            |- Target customer segments
            |
            |**Tool Usage:**
            |Use the spring_ai_mcp_client_brave_search_brave_web_search tool with these parameters:
            |{
            |  "query": "your search query here",
            |  "count": 10,
            |  "offset": 0,
            |  "ui_lang": "en-US",
            |  "country": "US",
            |  "text_decorations": false,
            |  "spellcheck": true
            |}
            |
            |Search for information from official websites, product pages, review sites, and comparison sites.
            |Write your findings with source citations.
            |
            |**IMPORTANT:** At the END of your response, you MUST include one of these lines:
            |- If you called the search tool: "TOOL_USAGE: YES"
            |- If you did NOT call the search tool: "TOOL_USAGE: NO"
            """.trimMargin()

        log.info("[PRODUCT AGENT] Conducting product analysis...")
        val tools = toolCallbackProvider.toolCallbacks.toList()

        val extraction = performTwoPassExtraction(prompt, tools, "PRODUCT AGENT")

        log.info("[PRODUCT AGENT] Product analysis completed.")

        return if (extraction != null) {
            AgentFindings(
                agentName = "Product Analysis",
                findings = extraction.findings,
                confidence = extraction.confidence,
                dataAvailability = extraction.dataAvailability,
            )
        } else {
            AgentFindings(
                agentName = "Product Analysis",
                findings = emptyList(),
                confidence = "low",
                dataAvailability = "sparse",
            )
        }
    }

    /**
     * Conducts news research on a company
     */
    fun conductNewsResearch(
        company: String,
        previousFindings: List<AgentFindings>,
        researchFocus: String?,
    ): AgentFindings {
        val contextPrompt = buildContextPrompt(previousFindings)

        val focusDirective =
            if (researchFocus != null) {
                "\n\n**SPECIFIC RESEARCH FOCUS FROM SUPERVISOR:**\n$researchFocus\n\nPrioritize answering this specific question in your research."
            } else {
                ""
            }

        val prompt =
            """
            |You are a news intelligence specialist researching: "$company"
            |
            |$contextPrompt$focusDirective
            |
            |Research recent developments (last 6-12 months):
            |- Strategic announcements and major moves
            |- Partnerships, acquisitions, and alliances
            |- Leadership changes
            |- Product launches and feature releases
            |- Regulatory or legal matters
            |
            |**Tool Usage:**
            |Use the spring_ai_mcp_client_brave_search_brave_news_search tool with these parameters:
            |{
            |  "query": "your search query here",
            |  "count": 10,
            |  "offset": 0,
            |  "ui_lang": "en-US",
            |  "country": "US",
            |  "text_decorations": false,
            |  "spellcheck": true
            |}
            |
            |Search for recent news and write your findings with dates and source citations.
            |
            |**IMPORTANT:** At the END of your response, you MUST include one of these lines:
            |- If you called the search tool: "TOOL_USAGE: YES"
            |- If you did NOT call the search tool: "TOOL_USAGE: NO"
            """.trimMargin()

        log.info("[NEWS AGENT] Conducting news analysis...")
        val tools = toolCallbackProvider.toolCallbacks.toList()

        val extraction = performTwoPassExtraction(prompt, tools, "NEWS AGENT")

        log.info("[NEWS AGENT] News analysis completed.")

        return if (extraction != null) {
            AgentFindings(
                agentName = "Recent Developments",
                findings = extraction.findings,
                confidence = extraction.confidence,
                dataAvailability = extraction.dataAvailability,
            )
        } else {
            AgentFindings(
                agentName = "Recent Developments",
                findings = emptyList(),
                confidence = "low",
                dataAvailability = "sparse",
            )
        }
    }

    /**
     * Conducts sentiment research on a company
     */
    fun conductSentimentResearch(
        company: String,
        previousFindings: List<AgentFindings>,
        researchFocus: String?,
    ): AgentFindings {
        val contextPrompt = buildContextPrompt(previousFindings)

        val additionalContext =
            if (previousFindings.isNotEmpty()) {
                "\n\nUse this context to focus sentiment research (e.g., if product agent found pricing issues, look for pricing-related sentiment)."
            } else {
                ""
            }

        val focusDirective =
            if (researchFocus != null) {
                "\n\n**SPECIFIC RESEARCH FOCUS FROM SUPERVISOR:**\n$researchFocus\n\nPrioritize answering this specific question in your research."
            } else {
                ""
            }

        val prompt =
            """
            |You are a sentiment analysis specialist researching: "$company"
            |
            |$contextPrompt$additionalContext$focusDirective
            |
            |Research market perception and customer sentiment:
            |- Customer reviews and ratings (G2, Capterra, Trustpilot, app stores)
            |- Social media sentiment (Twitter/X, Reddit, LinkedIn)
            |- Brand reputation and trust signals
            |- Common praise and complaint themes
            |- How sentiment compares to competitors
            |
            |**Tool Usage:**
            |Use the spring_ai_mcp_client_brave_search_brave_web_search tool with these parameters:
            |{
            |  "query": "your search query here",
            |  "count": 10,
            |  "offset": 0,
            |  "ui_lang": "en-US",
            |  "country": "US",
            |  "text_decorations": false,
            |  "spellcheck": true
            |}
            |
            |Search for reviews and sentiment data, then write your findings with source citations.
            |
            |**IMPORTANT:** At the END of your response, you MUST include one of these lines:
            |- If you called the search tool: "TOOL_USAGE: YES"
            |- If you did NOT call the search tool: "TOOL_USAGE: NO"
            """.trimMargin()

        log.info("[SENTIMENT AGENT] Conducting sentiment analysis...")
        val tools = toolCallbackProvider.toolCallbacks.toList()

        val extraction = performTwoPassExtraction(prompt, tools, "SENTIMENT AGENT")

        log.info("[SENTIMENT AGENT] Sentiment analysis completed.")

        return if (extraction != null) {
            AgentFindings(
                agentName = "Market Sentiment",
                findings = extraction.findings,
                confidence = extraction.confidence,
                dataAvailability = extraction.dataAvailability,
            )
        } else {
            AgentFindings(
                agentName = "Market Sentiment",
                findings = emptyList(),
                confidence = "low",
                dataAvailability = "sparse",
            )
        }
    }

    /**
     * Synthesizes all findings into a final intelligence report
     */
    fun synthesizeReport(
        company: String,
        allFindings: List<AgentFindings>,
        supervisorReasoning: List<String>,
        agentsCalled: List<String>,
    ): String {
        val prompt =
            """
            |Create a comprehensive competitive intelligence report on "$company"
            |
            |AGENT FINDINGS:
            |${allFindings.joinToString("\n\n") { formatAgentFindings(it) }}
            |
            |SUPERVISOR'S ORCHESTRATION DECISIONS:
            |${supervisorReasoning.mapIndexed { i, r -> "${i + 1}. $r" }.joinToString("\n")}
            |
            |AGENTS DEPLOYED: ${agentsCalled.distinct().joinToString(", ")}
            |
            |CRITICAL REQUIREMENTS:
            |
            |1. THEMATIC ORGANIZATION (NOT by agent)
            |   Structure the report around business themes, synthesizing findings across agents:
            |   - Executive Summary (1-2 paragraphs with key strategic insights)
            |   - Company Overview & Market Position
            |   - Financial Health & Resources (if data available)
            |   - Product Strategy & Competitive Positioning
            |   - Recent Strategic Developments
            |   - Market Perception & Customer Sentiment (if data available)
            |   - Risk Factors & Challenges
            |   - Strategic Implications & Opportunities
            |
            |2. CROSS-AGENT SYNTHESIS
            |   Combine insights from multiple agents. Examples:
            |   - Financial runway + product roadmap → sustainability assessment
            |   - News on partnerships + product features → strategic positioning analysis
            |   - Sentiment trends + recent announcements → brand health trajectory
            |   - Pricing strategy + customer complaints → value perception analysis
            |
            |3. GAP ACKNOWLEDGMENT
            |   - Note which agents were NOT called and why (from supervisor reasoning)
            |   - Acknowledge data limitations (e.g., "Financial data limited for private company")
            |   - Indicate confidence levels where appropriate
            |
            |4. CITATIONS
            |   - Use numbered footnotes [1], [2], etc. in the body text
            |   - Include "## References" section at end with numbered URLs
            |   - Example: "Company raised $50M Series B in March 2024[1]"
            |
            |5. ACTIONABLE INSIGHTS
            |   - End with "## Strategic Takeaways" section
            |   - 3-5 bullet points of actionable intelligence
            |   - What should competitors/investors/partners know?
            |
            |Format as professional markdown suitable for executive stakeholders.
            """.trimMargin()

        log.info("[SYNTHESIZER] Compiling final intelligence report...")
        log.debug("[SYNTHESIZER] Calling ChatModel with synthesis prompt: {}", prompt)

        val report =
            chatModel
                .call(
                    Prompt
                        .builder()
                        .messages(UserMessage(prompt))
                        .chatOptions(
                            OllamaOptions
                                .builder()
                                .numCtx(524288)
                                .numPredict(65536) // 64k output for comprehensive report
                                .build(),
                        ).build(),
                ).results[0]
                .output.text ?: error("Synthesizer produced no output")

        log.debug("[SYNTHESIZER] ChatModel response: {}", report)
        log.info("[SYNTHESIZER] Final intelligence report compiled successfully.")

        return report
    }

    // Private helper methods

    private fun buildInitialDecisionPrompt(
        company: String,
        remainingCalls: Int,
    ): String =
        """
        |You are a competitive intelligence supervisor analyzing: "$company"
        |
        |**BUDGET CONSTRAINT:** You have $remainingCalls total agent calls.
        |Use this budget strategically - prioritize the most valuable agents for this company type.
        |
        |Available specialized agents:
        |
        |- financial: Revenue, funding, market cap, growth metrics, profitability
        |- product: Features, pricing, positioning, product lines, differentiation
        |- news: Recent announcements, partnerships, strategic moves, expansions
        |- sentiment: Customer reviews, social media, brand perception, satisfaction
        |
        |This is your FIRST decision. Analyze the company and decide:
        |
        |1. What TYPE of company is this?
        |   - Public or private? (affects financial data availability)
        |   - B2B or B2C? (affects sentiment sources)
        |   - Startup, growth-stage, or established? (affects data richness)
        |   - Tech, consumer, industrial, etc.? (affects relevant agents)
        |
        |2. Which agents should you call FIRST?
        |   - Start with 1-2 agents that will provide foundational context
        |   - Consider what data is likely to be publicly available
        |   - Some agents may be unproductive (e.g., financial for stealth startup)
        |
        |3. Provide clear reasoning for your choice
        |
        |Output ONLY valid JSON matching this exact structure:
        |{
        |  "nextAgent": "financial",
        |  "reasoning": "detailed explanation of why this agent first",
        |  "skipReason": null,
        |  "researchFocus": null
        |}
        |
        |Valid nextAgent values: "financial", "product", "news", "sentiment"
        |Choose ONE agent to call first. You'll make subsequent decisions after seeing results.
        """.trimMargin()

    private fun buildContinueDecisionPrompt(
        company: String,
        findings: List<AgentFindings>,
        called: List<String>,
        remainingCalls: Int,
        availableAgents: List<String>,
    ): String =
        """
        |You are orchestrating competitive intelligence on: "$company"
        |
        |AGENTS ALREADY CALLED:
        |${called.joinToString(", ")}
        |
        |Available agents you haven't called yet:
        |${availableAgents.filter { it !in called }.joinToString(", ")}
        |
        |**BUDGET CONSTRAINT:** You have $remainingCalls remaining agent calls out of ${called.size + remainingCalls} total calls.
        |
        |FINDINGS SO FAR:
        |${findings.joinToString("\n\n") { formatFindingsForPrompt(it) }}
        |
        |Decide your NEXT ACTION:
        |
        |Option 1: CALL A NEW AGENT
        |- If you need different types of information
        |- Consider what gaps exist in current findings
        |- Which agent would be most valuable next?
        |
        |Option 2: RE-CALL A PREVIOUS AGENT (STRONGLY ENCOURAGED)
        |- Look for contradictions, gaps, or follow-up questions
        |- Use the "researchFocus" field to communicate what specific information you need
        |- Examples:
        |  * News mentioned $50B investment → re-call financial with researchFocus: "How will they fund this $50B? What's the capital structure?"
        |  * Sentiment shows 1.4/5 on Trustpilot but 4.8/5 on G2 → re-call sentiment with researchFocus: "Investigate why Trustpilot ratings differ from G2"
        |  * News mentioned new product → re-call product with researchFocus: "Get detailed pricing and positioning for the new product line"
        |
        |Option 3: FINISH (set nextAgent to "DONE")
        |- If you have sufficient intelligence across key dimensions
        |- There are no logical gaps in the findings that require more research from another agent
        |- Typically after exhausting all agent calls
        |- Explain what key insights you've gathered
        |
        |Output ONLY valid JSON matching this exact structure:
        |{
        |  "nextAgent": "financial",
        |  "reasoning": "detailed explanation of this decision",
        |  "skipReason": null,
        |  "researchFocus": "Specific question or gap to investigate (when re-calling an agent)"
        |}
        |
        |The "researchFocus" field should be:
        |- null for first-time agent calls (let them do broad research)
        |- A specific question/directive when re-calling an agent to investigate gaps
        |- Examples: "How will they fund the $50B investment?", "Compare pricing to competitor X", "Why are Trustpilot ratings so low?"
        |
        |Valid nextAgent values: "financial", "product", "news", "sentiment", "DONE"
        """.trimMargin()

    private fun formatFindingsForPrompt(findings: AgentFindings): String =
        """
        |**${findings.agentName}** (Confidence: ${findings.confidence}, Data: ${findings.dataAvailability})
        |${findings.findings.joinToString("\n  - ") { "${it.finding} (${it.source})" }}
        """.trimMargin()

    private fun formatAgentFindings(findings: AgentFindings): String {
        val findingsWithSources =
            findings.findings.joinToString("\n") {
                "  - ${it.finding} [${it.source}]"
            }
        val uniqueSources = findings.findings.map { it.source }.distinct()
        val sourcesText = uniqueSources.take(5).joinToString("\n") { "  - $it" }
        val moreText = if (uniqueSources.size > 5) "\n  ... and ${uniqueSources.size - 5} more" else ""

        return """
            |**${findings.agentName}**
            |Confidence: ${findings.confidence} | Data Availability: ${findings.dataAvailability}
            |
            |Key Findings:
            |$findingsWithSources
            |
            |Sources Consulted (${uniqueSources.size}):
            |$sourcesText$moreText
            """.trimMargin()
    }
}
