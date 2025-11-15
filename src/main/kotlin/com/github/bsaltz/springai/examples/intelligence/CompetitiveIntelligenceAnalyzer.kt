package com.github.bsaltz.springai.examples.intelligence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectWriter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.shell.command.annotation.Command

@Command
class CompetitiveIntelligenceAnalyzer(
    private val competitiveIntelligenceGraphService: CompetitiveIntelligenceGraphService,
) {
    private val log: Logger = LoggerFactory.getLogger(javaClass)
    private val objectWriter: ObjectWriter =
        Jackson2ObjectMapperBuilder
            .json()
            .build<ObjectMapper>()
            .writerWithDefaultPrettyPrinter()

    @Command(command = ["examples", "run", "CompetitiveIntelligenceAnalyzer"])
    fun runExample(companyName: String = "Anthropic") {
        log.info("Running Competitive Intelligence Analyzer for: {}", companyName)
        log.info("This may take several minutes as the supervisor orchestrates multiple specialized agents...")

        val report = competitiveIntelligenceGraphService.analyzeCompany(companyName)

        log.info("\n{}", "=".repeat(80))
        log.info("COMPETITIVE INTELLIGENCE REPORT")
        log.info("{}\n", "=".repeat(80))
        log.info("\n{}", report)
    }
}

/**
 * A single finding with its source citation
 */
data class Finding(
    val finding: String,
    val source: String,
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Structured findings extraction result
 */
data class FindingsExtraction(
    val findings: List<Finding>,
    val confidence: String,
    val dataAvailability: String,
    val usedTools: Boolean = false,
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Agent findings data structure
 */
data class AgentFindings(
    val agentName: String,
    val findings: List<Finding>,
    val confidence: String,
    val dataAvailability: String,
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Supervisor decision data structure
 */
data class SupervisorDecision(
    val nextAgent: String,
    val reasoning: String,
    val skipReason: String? = null,
    val researchFocus: String? = null,
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
