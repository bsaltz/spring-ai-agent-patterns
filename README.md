# Spring AI Agent Patterns

Production-grade patterns for building autonomous AI agents with Spring AI and LangGraph4j. This project demonstrates enterprise-ready agentic workflows that integrate LLMs with external tools, structured state management, and robust error handling.

## Overview

This repository showcases three progressively complex examples of agentic workflows:

1. **House PTR Parser** — Linear pipeline with refinement patterns
2. **Research Report Writer** — Conditional routing with tool calling and loops
3. **Competitive Intelligence Analyzer** — Supervisor-agent pattern with dynamic multi-agent orchestration

Each example is fully functional and documented with architectural decisions, implementation patterns, and debugging insights.

## Key Features

- **Tool Calling Integration**: MCP (Model Context Protocol) integration with Spring AI's ToolCallback system
- **State Management**: LangGraph4j for stateful, conditional execution graphs
- **Structured Output**: BeanOutputConverter for type-safe JSON parsing
- **Error Handling**: Graceful degradation patterns for production reliability
- **Local Model Support**: Optimized prompting enables reliable performance with 20B+ models
- **Comprehensive Documentation**: Architecture diagrams, design decisions, and lessons learned

## Quick Start

### Prerequisites

- Java 21+
- Ollama running locally
- Brave Search API key (for Research Report Writer)
- MCP servers (brave-search, wikipedia)

### Installation

```bash
# Clone the repository
git clone https://github.com/yourusername/spring-ai-agent-patterns.git
cd spring-ai-agent-patterns

# Build the project
./gradlew build

# Run the application
./gradlew bootRun
```

### Running Examples

```bash
# Interactive shell
./gradlew bootRun

# Run House PTR Parser
examples run HousePtrParser

# Run Research Report Writer (default query)
examples run ResearchReportWriter

# Run Research Report Writer (custom query)
examples run ResearchReportWriter "Recent advances in quantum computing with 5 sections"

# Run Competitive Intelligence Analyzer
examples run CompetitiveIntelligenceAnalyzer "Anthropic"
```

## Examples

### 1. House PTR Parser

Extracts structured data from PDF documents containing House stock transaction reports.

**Demonstrates:**
- Linear workflow patterns
- PDF OCR processing
- Iterative refinement with validation
- Custom data validation

**Complexity:** Beginner
**Documentation:** [docs/examples/house-ptr-parser/](docs/examples/house-ptr-parser/index.md)

### 2. Research Report Writer

Autonomously generates comprehensive research reports using web search.

**Demonstrates:**
- Tool calling with MCP integration
- Conditional routing and loop-based execution
- Explicit tool parameter guidance for reliable LLM behavior
- Token management for tool calling workflows
- Production error handling patterns

**Complexity:** Intermediate
**Documentation:** [docs/examples/research-report-writer/](docs/examples/research-report-writer/index.md)

**Key Achievement:** Reliable tool calling with local 20B models through careful prompt engineering and token configuration.

### 3. Competitive Intelligence Analyzer

Autonomously researches companies through a multi-agent system with specialized agents (financial, product, news, sentiment) coordinated by an intelligent supervisor.

**Demonstrates:**
- Supervisor-agent pattern for multi-agent orchestration
- Two-pass LLM pattern (research + extraction)
- Dynamic routing based on research context
- Manual list accumulation for complex state management
- Conditional agent selection with intelligent routing

**Complexity:** Advanced
**Documentation:** [docs/examples/competitive-intelligence/](docs/examples/competitive-intelligence/index.md)

**Key Achievement:** Autonomous multi-agent system that adapts research strategy based on discovered information.

## Technical Stack

- **Spring AI** — LLM integration framework
- **LangGraph4j** — Stateful workflow orchestration
- **Ollama** — Local LLM runtime
- **MCP** — Model Context Protocol for tool integration
- **Kotlin** — Implementation language
- **Spring Shell** — Interactive CLI

## Documentation

- **[Getting Started](docs/development/getting-started.md)** — Development setup and prerequisites
- **[Example Overview](docs/examples/index.md)** — Detailed example documentation
- **[Testing Architecture](docs/architecture/testing.md)** — Service extraction pattern and testing strategy
- **[Design Documents](docs/examples/)** — Architecture and implementation patterns

## Highlights

### Production-Ready Patterns

- **Graceful Degradation**: Workflows continue with partial results rather than failing
- **Type-Safe State**: Minimal schema with type-safe accessors
- **Structured Output**: Explicit JSON examples improve reliability
- **Token Budget Management**: Proper `num-ctx`/`numPredict` configuration
- **Comprehensive Testing**: Service extraction pattern enables fast, reliable testing with no external dependencies

### Debugging Insights

The documentation includes detailed "Lessons Learned" sections documenting:
- MCP tool parameter validation failures and solutions
- `null` vs `undefined` in Node.js-based MCP servers
- Token truncation causing malformed JSON
- Model capability requirements for tool calling

These insights demonstrate real-world problem-solving and production debugging skills.

## Configuration

### Model Configuration

```yaml
# application.yml
spring:
  ai:
    ollama:
      chat:
        model: gpt-oss:20b  # Local model
        options:
          num-ctx: 65536  # 64k context window for tool calling
```

### MCP Server Configuration

```yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          connections:
            brave-search:
              command: npx
              args: ['-y', '@brave/brave-search-mcp-server']
              env:
                BRAVE_API_KEY: "${com.brave.search.apiKey}"
```

## Project Structure

```
docs/
├── examples/            # Example documentation
├── architecture/        # Architecture and testing docs
└── development/         # Setup guides

src/main/kotlin/
└── com/github/bsaltz/springai/
    ├── examples/        # Example implementations
    │   ├── pdf/         # House PTR Parser
    │   ├── research/    # Research Report Writer
    │   └── intelligence/ # Competitive Intelligence Analyzer
    └── util/            # Shared utilities

src/main/resources/
└── examples/            # Example data files

src/test/kotlin/         # Unit tests
```

## Contributing

This is a portfolio project demonstrating agentic workflow patterns. While not currently accepting contributions, the patterns and approaches are free to use and adapt for your own projects.

## License

MIT License — See [LICENSE](LICENSE) file for details

## Author

Brian Saltz Jr.

- [LinkedIn](https://www.linkedin.com/in/brian-saltz-jr-4144393b/)
- [GitHub](https://github.com/bsaltz)

## Acknowledgments

- Built with [Spring AI](https://docs.spring.io/spring-ai/reference/)
- LangGraph4j by [bsorrentino](https://github.com/langgraph4j/langgraph4j)
- MCP protocol by [Anthropic](https://modelcontextprotocol.io/)
