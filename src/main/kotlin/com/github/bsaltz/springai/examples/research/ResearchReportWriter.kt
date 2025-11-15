package com.github.bsaltz.springai.examples.research

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.shell.command.annotation.Command
import org.springframework.shell.command.annotation.Option
import java.io.Serializable

@Command
class ResearchReportWriter(
    private val researchReportGraphService: ResearchReportGraphService,
) {
    private val log: Logger = LoggerFactory.getLogger(javaClass)

    @Command(command = ["examples", "run", "ResearchReportWriter"])
    fun runExample(
        @Option(required = false, defaultValue = "Recent advances in quantum computing and their applications")
        query: String,
    ) {
        log.info("Running ResearchReportWriter with query: {}", query)
        val result = researchReportGraphService.generateReport(query)
        log.info("\n=== Generated Research Report ===\n")
        log.info("\n{}", result)
    }
}

/**
 * Represents a structured research plan with multiple sections
 */
data class ResearchPlan(
    val sections: List<Section>,
    val overallObjective: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Represents a section of the research plan
 */
data class Section(
    val title: String,
    val researchQuestions: List<String>,
    val keyTopics: List<String>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Represents the research findings for a specific section as markdown
 */
data class SectionResearch(
    val sectionTitle: String,
    val markdownContent: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Represents the validation result from guardrails
 */
data class GuardrailsValidation(
    val rejected: Boolean,
    val rejectionReason: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
