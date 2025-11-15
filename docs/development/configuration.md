# Configuration Guide

This guide provides detailed information about configuring the Spring AI Agent Patterns project for optimal performance and reliability.

## Overview

The project uses Spring Boot's configuration system with YAML files. The main configuration is in `src/main/resources/application.yml`, with environment-specific overrides in `application-local.yml`.

## Model Configuration

### Ollama Chat Model

The project uses Ollama for local LLM execution:

```yaml
spring:
  ai:
    ollama:
      chat:
        model: gpt-oss:20b
        options:
          num-ctx: 65536  # 64k context window
```

**Configuration Parameters:**

#### `model`

- **Type**: String
- **Example**: `gpt-oss:20b`, `gpt-oss:120b-cloud`, `llama3:8b`
- **Description**: The Ollama model to use for chat completions
- **Recommendation**:
  - For House PTR Parser: 7B-13B models work well
  - For Research Report Writer: 20B+ models recommended for reliable tool calling

**Pulling Models:**

```bash
# Pull the model before running
ollama pull gpt-oss:20b
```

#### `num-ctx` (Context Window)

- **Type**: Integer (tokens)
- **Default**: 2048
- **Recommended**: 65536 (64k)
- **Description**: Maximum total tokens (input + output) the model can process

**Why 64k for Tool Calling?**

Tool calling workflows have complex token requirements:

```
Total tokens = Input prompt + Tool schemas + Tool responses + LLM output
```

For the Research Report Writer:

- Input prompt: ~3k tokens
- Tool schemas (brave_web_search): ~1k tokens
- Tool responses: 5-10k tokens per search
- LLM output: Up to 32k tokens

**Formula**: `num-ctx >= input + tools + numPredict`

With multiple tool calls per section, 64k provides safety margin. Insufficient context causes:

- JSON truncation mid-generation
- Malformed tool calls
- Lost context between tool calls

### Per-Request Options

Some nodes override context settings per request:

```kotlin
// Research Executor Node
OllamaOptions.builder()
    .toolCallbacks(tools)
    .numPredict(32768)  // Max output tokens
    .build()

// Synthesizer Node
OllamaOptions.builder()
    .numPredict(16384)  // Smaller output for synthesis
    .build()
```

**`numPredict` (Output Limit)**

- **Type**: Integer (tokens)
- **Description**: Maximum tokens for this specific LLM generation
- **Trade-off**: Higher values allow longer outputs but consume more context budget

## MCP Server Configuration

The project uses MCP (Model Context Protocol) servers for tool integration.

### Brave Search

Web search tool for the Research Report Writer:

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        stdio:
          connections:
            brave-search:
              command: npx
              args:
                - -y
                - '@brave/brave-search-mcp-server'
              env:
                BRAVE_API_KEY: "${com.brave.search.apiKey}"
```

**Setup Steps:**

1. **Get API Key**: Register at [Brave Developer Portal](https://developers.brave.com/docs/api/authentication/)
2. **Configure Key**: Create `src/main/resources/application-local.yml`:
   ```yaml
   com.brave.search.apiKey: YOUR_API_KEY_HERE
   ```
3. **Verify NPX**: Ensure Node.js and npx are installed:
   ```bash
   npx -v
   ```

**Connection Parameters:**

- `command`: The executable to run (`npx`)
- `args`: Arguments passed to the command
  - `-y`: Auto-accept package installation
  - `@brave/brave-search-mcp-server`: NPM package name
- `env`: Environment variables for the MCP server
  - `BRAVE_API_KEY`: Your API key from application-local.yml

### Wikipedia

General knowledge tool (configured but not used in current examples):

```yaml
wikipedia:
  command: uvx
  args:
    - --from
    - wikipedia-mcp
    - wikipedia-mcp
```

**Setup Steps:**

1. **Install uv**: Follow [uv installation guide](https://docs.astral.sh/uv/getting-started/installation/)
2. **Verify uvx**:
   ```bash
   uvx --version
   ```

The wikipedia-mcp package will be auto-installed on first use via uvx.

## Spring AI Observability

Logging and monitoring configuration for debugging:

```yaml
spring:
  ai:
    tools:
      observations:
        include-content: true  # Include tool call parameters and responses
    chat:
      observations:
        include-error-logging: true  # Log LLM errors
        log-completion: true         # Log LLM outputs
        log-prompt: true             # Log prompts sent to LLM
```

**Use Cases:**

- **Development**: Enable all for maximum visibility
- **Production**: Disable `log-prompt` and `log-completion` to reduce log volume and protect PII
- **Debugging Tool Calls**: `include-content: true` shows exact parameters sent to tools

## Application Configuration

### Spring Shell

Interactive CLI configuration:

```yaml
spring:
  shell:
    interactive:
      enabled: true  # Enable interactive mode
```

### Application Type

```yaml
spring:
  main:
    banner-mode: off             # Disable Spring Boot banner
    web-application-type: none   # CLI app, not web server
```

## Environment-Specific Configuration

### Profile Activation

```yaml
spring:
  profiles:
    active: local  # Use application-local.yml
```

### Local Profile (application-local.yml)

Create this file for your local development settings:

```yaml
# Local overrides
com.brave.search.apiKey: YOUR_KEY_HERE

# Optional: Override model for local testing
spring:
  ai:
    ollama:
      chat:
        model: llama3:8b  # Smaller model for quick iteration
```

**Git Ignore**: `application-local.yml` should be in `.gitignore` to protect API keys.

## Model Selection Guide

### Choosing the Right Model

| Use Case | Model Size | Example | Rationale |
|----------|-----------|---------|-----------|
| **Quick Testing** | 7B-8B | `llama3:8b` | Fast iteration, lower resource usage |
| **PDF Parsing** | 7B-13B | `llama3:13b` | Structured output works well with medium models |
| **Tool Calling** | 20B+ | `gpt-oss:20b` | Reliable tool calls need instruction-tuned models |
| **Production** | 70B+ | `gpt-oss:120b-cloud` | Maximum reliability, cloud deployment |

### Token Budget by Use Case

| Workflow | num-ctx | numPredict (per node) | Rationale |
|----------|---------|----------------------|-----------|
| **House PTR Parser** | 16384 (default) | N/A (default) | Simple structured output, no tools |
| **Research Report Writer** | 65536 (64k) | 32768 (research)<br/>16384 (synthesis) | Multiple tool calls with large responses |

## Common Configuration Issues

### Issue 1: MCP Connection Failures

**Symptoms:**

```
Failed to connect to MCP server brave-search
```

**Solutions:**

1. Verify npx/uvx is installed and in PATH
2. Check API key is correctly set in application-local.yml
3. Test the MCP server manually:
   ```bash
   npx -y @modelcontextprotocol/inspector -e BRAVE_API_KEY="your_key_here" -- \
     npx -y @brave/brave-search-mcp-server
   ```

### Issue 2: Model Not Found

**Symptoms:**

```
model 'gpt-oss:20b' not found
```

**Solution:**

```bash
# Pull the model first
ollama pull gpt-oss:20b

# Verify it's available
ollama list
```

### Issue 3: Out of Context Window

**Symptoms:**

```
context length exceeded: 18000 > 16384
```

**Solution:**

Increase `num-ctx` in application.yml:

```yaml
spring:
  ai:
    ollama:
      chat:
        options:
          num-ctx: 65536  # Increase to 64k
```

### Issue 4: JSON Truncation

**Symptoms:**

```
error parsing tool call: raw='{"count=10",...', err=invalid character '"' after object key:value pair
```

**Root Cause:** LLM ran out of output tokens mid-generation.

**Solution:**

Increase `numPredict` in the node's OllamaOptions:

```kotlin
OllamaOptions.builder()
    .numPredict(32768)  // Increase from default
    .build()
```

## Performance Tuning

### For Faster Development Iteration

```yaml
spring:
  ai:
    ollama:
      chat:
        model: llama3:8b  # Smaller, faster model
        options:
          num-ctx: 8192   # Smaller context for speed
```

### For Maximum Reliability

```yaml
spring:
  ai:
    ollama:
      chat:
        model: gpt-oss:120b-cloud  # Large, capable model
        options:
          num-ctx: 131072  # 128k context
```

Per-node:

```kotlin
OllamaOptions.builder()
    .numPredict(65536)  # Very large output budget
    .temperature(0.1)   # Low temperature for consistency
    .build()
```

## Security Considerations

### API Key Management

**Never commit API keys to version control:**

1. Use environment-specific files:
   ```
   application-local.yml  # In .gitignore
   ```
2. Or environment variables:
   ```bash
   export BRAVE_API_KEY=your_key
   ./gradlew bootRun
   ```
3. Reference in YAML:
   ```yaml
   BRAVE_API_KEY: "${BRAVE_API_KEY}"
   ```

### Production Deployment

For production:
1. Disable verbose logging:
   ```yaml
   spring:
     ai:
       chat:
         observations:
           log-prompt: false      # Don't log user queries
           log-completion: false  # Don't log LLM outputs
   ```
2. Use externalized configuration (Kubernetes ConfigMaps, AWS Secrets Manager, Spring Cloud Config, etc.)
3. Set appropriate token limits to prevent runaway generation

## Complete Example Configuration

Here's a complete `application.yml` for the Research Report Writer in production:

```yaml
spring:
  application:
    name: spring-ai-agent-patterns
  profiles:
    active: prod
  ai:
    ollama:
      chat:
        model: gpt-oss:120b-cloud
        options:
          num-ctx: 65536
    mcp:
      client:
        enabled: true
        stdio:
          connections:
            brave-search:
              command: npx
              args: ['-y', '@brave/brave-search-mcp-server']
              env:
                BRAVE_API_KEY: "${BRAVE_API_KEY}"
    chat:
      observations:
        include-error-logging: true
        log-completion: false  # Disabled for production
        log-prompt: false      # Disabled for production

logging:
  level:
    root: INFO
    com.github.bsaltz.springai: DEBUG
```

## See Also

- [Getting Started Guide—](getting-started.md) — Initial setup and installation
- [Architecture Overview](../architecture.md) — Design patterns and decisions
- [Research Report Writer](../examples/research-report-writer/index.md) — Token management deep dive
