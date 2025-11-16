# Spring AI Agent Patterns

Patterns and examples for building autonomous AI agents with Spring AI and LangGraph4j. This project demonstrates
agentic workflows that integrate LLMs with external tools, structured state management, and error handling.

## Overview

This project showcases three progressively complex examples of agentic workflows, each demonstrating different patterns
and capabilities:

1. **[House PTR Parser](examples/house-ptr-parser/index.md)** — Linear pipeline with refinement patterns
2. **[Research Report Writer](examples/research-report-writer/index.md)** — Conditional routing with tool calling and loops
3. **[Competitive Intelligence Analyzer](examples/competitive-intelligence/index.md)** — Supervisor-agent pattern with dynamic multi-agent orchestration

Each example is fully functional, well-documented, and includes detailed architectural decisions, implementation
patterns, and debugging insights.

## Key Features

- **Tool Calling Integration**: MCP (Model Context Protocol) integration with Spring AI's ToolCallback system
- **State Management**: LangGraph4j for stateful, conditional execution graphs
- **Structured Output**: BeanOutputConverter for type-safe JSON parsing
- **Error Handling**: Graceful degradation patterns for production reliability
- **Local Model Support**: Optimized prompting enables reliable performance with 20B+ models
- **Comprehensive Documentation**: Architecture diagrams, design decisions, and lessons learned

## Interactive Application

The application is a Spring Shell application that runs in interactive mode. Start the application and run commands
interactively:

```bash
# Start the application
./gradlew bootRun

# Run examples
examples run HousePtrParser
examples run ResearchReportWriter
examples run ResearchReportWriter "Recent advances in quantum computing with 5 sections"
examples run CompetitiveIntelligenceAnalyzer "Anthropic"

# Get help
help

# Exit
exit
```

## Documentation Structure

### Getting Started

- **[Getting Started Guide](development/getting-started.md)** — Installation, configuration, and first steps
- **[Configuration Guide](development/configuration.md)** — Detailed configuration reference for models, MCP servers, and token management
- **[Architecture Overview](architecture.md)** — Design philosophy, patterns, and extensibility

### Examples

- **[Example Overview](examples/index.md)** — Complete guide to all examples with progression path
- **[House PTR Parser](examples/house-ptr-parser/index.md)** — PDF parsing with OCR and iterative refinement
- **[Research Report Writer](examples/research-report-writer/index.md)** — Autonomous research with tool calling
- **[Competitive Intelligence Analyzer](examples/competitive-intelligence/index.md)** — Multi-agent system with dynamic orchestration

### Technical Details

Each example includes:

- Complete architecture diagrams
- Node-by-node implementation walkthrough
- Design decisions with rationale
- Lessons learned from debugging real issues
- Quantitative results and performance metrics

### Testing

- **[Testing Architecture](architecture/testing.md)** — Service extraction pattern and comprehensive unit testing strategy
- Fast, reliable tests with no external dependencies
- Separate testing layers for business logic and orchestration

## Technical Stack

- **[Spring AI](https://docs.spring.io/spring-ai/reference/)** — LLM integration framework
- **[LangGraph4j](https://github.com/bsorrentino/langgraph4j)** — Stateful workflow orchestration
- **[Ollama](https://ollama.com/)** — Local LLM runtime
- **[MCP](https://modelcontextprotocol.io/)** — Model Context Protocol for tool integration
- **Kotlin** — Implementation language
- **Spring Shell** — Interactive CLI

## Quick Links

- [Installation & Setup](development/getting-started.md)
- [Configuration Reference](development/configuration.md)
- [Architecture Guide](architecture.md)
- [Browse Examples](examples/index.md)

## Project Philosophy

This project demonstrates practical patterns through:

- **Real Debugging**: Documentation includes actual challenges faced and solutions discovered
- **Quantitative Results**: Performance metrics and accuracy measurements throughout
- **Transparent Trade-offs**: Design decisions explain both benefits and costs
- **Extensible Design**: Reusable components and patterns for building your own workflows

Each example showcases not just what works, but *why* it works and how to debug when it doesn't.
