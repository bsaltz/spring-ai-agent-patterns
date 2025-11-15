package com.github.bsaltz.springai.debug

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider
import org.springframework.ai.tool.ToolCallback
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.shell.command.annotation.Command

@Command(command = ["debug", "tools"])
class DebugToolCommands(
    private val toolCallbackProvider: SyncMcpToolCallbackProvider,
    tools: List<ToolCallback>,
) {
    private val objectMapper = Jackson2ObjectMapperBuilder.json().build<ObjectMapper>()
    private val tools = (tools + toolCallbackProvider.toolCallbacks).sortedBy { it.toolDefinition.name() }

    @Command(command = ["list"])
    fun listTools() {
        tools.forEach { tool ->
            println("Tool: ${tool.toolDefinition.name()}")
        }
    }

    @Command(command = ["describe"])
    fun describeTool(toolName: String) {
        val tool = tools.find { it.toolDefinition.name() == toolName }
        if (tool == null) {
            println("Tool not found: $toolName")
            return
        }
        println("Tool: ${tool.toolDefinition.name()}")
        println("Description: ${tool.toolDefinition.description()}")
        val inputSchemaString = tool.toolDefinition.inputSchema()
        val inputSchemaPretty = objectMapper.readTree(inputSchemaString).toPrettyString()
        println("Schema:\n${inputSchemaPretty.prependIndent("  ")}")
    }
}
