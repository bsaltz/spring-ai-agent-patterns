# Testing Architecture

This document explains the testing strategy and service extraction pattern used throughout the Spring AI Agent Patterns
project.

## Overview

The project uses a **service extraction pattern** that separates business logic from graph orchestration, enabling
comprehensive unit testing without complex integration test infrastructure.

**Key Principle:** Extract all LLM calls and business logic into testable services, leaving graph classes with only thin
orchestration wrappers.

## Service Extraction Pattern

### The Problem

Testing LangGraph workflows is challenging:

- Async graph execution requires complex mocking
- State management adds test complexity
- Node interdependencies make isolation difficult
- LLM calls are slow and non-deterministic

**Traditional Approach:**

```kotlin
// Everything in one class - hard to test
class PdfParsingGraphService(
    private val chatModel: ChatModel,
    private val pdfOcrService: PdfOcrService,
    private val beanOutputConverterCache: BeanOutputConverterCache,
) {
    fun parsePdf(path: Path): Result {
        val graph = StateGraph(...)
            .addNode("ocr", node_async {
                // Business logic inline - can't unit test
                val ocrPath = pdfOcrService.runOcrmypdf(path)
                mapOf("ocr_path" to ocrPath)
            })
            .addNode("parse", node_async {
                // More inline logic - can't unit test
                val text = extractText(...)
                val result = chatModel.call(...)
                mapOf("result" to result)
            })
            .compile()

        return graph.stream(initialState).last()
    }
}
```

**Problems:**

- Can't unit test business logic without executing graph
- Mocking graph execution is complex
- Slow tests (real graph execution)
- Hard to test error cases

### The Solution

**Extract business logic into a separate service:**

**LlmService (business logic):**

```kotlin
@Service
class PdfParsingLlmService(
    private val chatModel: ChatModel,
    private val pdfOcrService: PdfOcrService,
    private val beanOutputConverterCache: BeanOutputConverterCache,
) {
    fun runOcr(input: Path): Path {
        return pdfOcrService.runOcrmypdf(input)
    }

    fun extractText(ocrPdfPath: Path): String {
        return pdfOcrService.runPdftotext(ocrPdfPath)
    }

    fun initialParse(ocrText: String, instructions: String, clazz: Class<T>): T {
        // LLM prompting and parsing logic
    }

    fun refineResult(ocrText: String, intermediateResult: T, clazz: Class<T>): T {
        // Refinement logic
    }
}
```

**GraphService (orchestration only):**

```kotlin
@Service
class PdfParsingGraphService(
    private val pdfParsingLlmService: PdfParsingLlmService,
) {
    fun parsePdf(path: Path): Result {
        val graph = buildPdfParsingGraph()
        // ... graph execution
    }

    private fun buildPdfParsingGraph(): StateGraph<PdfParsingState> {
        return StateGraph(...)
            .addNode("ocr", node_async(OcrPdfNode(pdfParsingLlmService)))
            .addNode("extract", node_async(ExtractTextNode(pdfParsingLlmService)))
            .addNode("parse", node_async(InitialParseNode(pdfParsingLlmService, clazz)))
            .addNode("refine", node_async(RefinementNode(pdfParsingLlmService, clazz)))
            .compile()
    }

    // Nodes are thin wrappers
    class OcrPdfNode(
        private val pdfParsingLlmService: PdfParsingLlmService,
    ) : NodeAction<PdfParsingState> {
        override fun apply(state: PdfParsingState): Map<String, Any> {
            val pdfPath = Path.of(state.pdfPath())
            val ocrPdfPath = pdfParsingLlmService.runOcr(pdfPath)
            return mapOf(PdfParsingState.OCR_PDF_PATH_KEY to ocrPdfPath.toString())
        }
    }

    class InitialParseNode(
        private val pdfParsingLlmService: PdfParsingLlmService,
        private val clazz: Class<T>,
    ) : NodeAction<PdfParsingState> {
        override fun apply(state: PdfParsingState): Map<String, Any> {
            val ocrText = state.ocrText()
            val instructions = state.parsingInstructions()
            val result = pdfParsingLlmService.initialParse(ocrText, instructions, clazz)
            return mapOf(PdfParsingState.INTERMEDIATE_RESULT_KEY to result)
        }
    }

    // ... other nodes
}
```

### Benefits

**Testability:**

- LlmService: Pure business logic, easy to mock dependencies
- GraphService: Simple orchestration, easy to verify service calls
- Nodes: Thin wrappers, verify extract→call→return pattern

**Clarity:**

- Clear separation: business logic vs orchestration
- Nodes are obviously simple (just wrappers)
- Easy to locate logic (always in service)

**Maintainability:**

- Change business logic: edit service only
- Change workflow: edit graph only
- Reuse logic: call service methods directly

## Testing Layers

### Layer 1: LlmService Unit Tests

**Goal:** Test all business logic with mocked dependencies

**Pattern:**

```kotlin
class PdfParsingLlmServiceTest {
    private lateinit var chatModel: ChatModel
    private lateinit var pdfOcrService: PdfOcrService
    private lateinit var beanOutputConverterCache: BeanOutputConverterCache
    private lateinit var service: PdfParsingLlmService

    @BeforeEach
    fun setUp() {
        chatModel = mock()
        pdfOcrService = mock()
        beanOutputConverterCache = mock()
        service = PdfParsingLlmService(chatModel, pdfOcrService, beanOutputConverterCache)
    }

    @Test
    fun `initialParse should parse OCR text successfully`() {
        // Given
        val ocrText = "Sample text"
        val instructions = "Parse this"
        val clazz = TestData::class.java
        val expectedResult = TestData("parsed")

        val converter = mock<BeanOutputConverter<TestData>>()
        whenever(beanOutputConverterCache.getConverter(clazz)) doReturn converter
        whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")
        whenever(converter.convert(any())) doReturn expectedResult

        val response = createChatResponse("""{"data": "parsed"}""")
        whenever(chatModel.call(any<Prompt>())) doReturn response

        // When
        val result = service.initialParse(ocrText, instructions, clazz)

        // Then
        assertNotNull(result)
        assertEquals("parsed", result.data)
        verify(chatModel).call(any<Prompt>())
    }

    @Test
    fun `initialParse should throw when ChatModel returns null`() {
        // Test error cases
    }

    // ... more tests
}
```

**Coverage:**

- Happy path: Verify correct output for valid inputs
- Error cases: Null responses, conversion failures, exceptions
- Edge cases: Empty inputs, malformed data
- Prompting: Verify prompts include all required context

### Layer 2: GraphService Node Tests

**Goal:** Test node orchestration with mocked service

**Pattern:**

```kotlin
class PdfParsingGraphServiceTest {
    private lateinit var pdfParsingLlmService: PdfParsingLlmService
    private lateinit var service: PdfParsingGraphService

    @BeforeEach
    fun setUp() {
        pdfParsingLlmService = mock()
        service = PdfParsingGraphService(pdfParsingLlmService)
    }

    @Test
    fun `InitialParseNode should call pdfParsingLlmService and return parsed result`() {
        // Given
        val ocrText = "Sample text"
        val instructions = "Parse this"
        val parsedResult = TestData("parsed")

        whenever(
            pdfParsingLlmService.initialParse(ocrText, instructions, TestData::class.java)
        ) doReturn parsedResult

        val node = PdfParsingGraphService.InitialParseNode(
            pdfParsingLlmService,
            TestData::class.java,
        )

        val state = PdfParsingGraphService.PdfParsingState(
            mapOf(
                PdfParsingState.OCR_TEXT_KEY to ocrText,
                PdfParsingState.PARSING_INSTRUCTIONS_KEY to instructions,
            ),
        )

        // When
        val result = node.apply(state)

        // Then
        assertNotNull(result)
        assertEquals(parsedResult, result[PdfParsingState.INTERMEDIATE_RESULT_KEY])
        verify(pdfParsingLlmService).initialParse(ocrText, instructions, TestData::class.java)
    }

    // ... more node tests
}
```

**Coverage:**

- State extraction: Nodes read correct keys
- Service calls: Correct parameters passed to service
- State updates: Correct keys written back
- Error propagation: Service errors bubble up

### Layer 3: Integration Tests (Optional)

**Goal:** Test complete workflow end-to-end

**Note:** Not currently implemented, but pattern would be:

```kotlin
@SpringBootTest
class PdfParsingIntegrationTest {
    @Autowired
    private lateinit var pdfParsingGraphService: PdfParsingGraphService

    @Test
    fun `parsePdf should process real PDF end-to-end`() {
        // Requires real Ollama, OCR tools, etc.
        val result = pdfParsingGraphService.parsePdf(testPdfPath)
        assertNotNull(result)
    }
}
```

**Trade-off:** Integration tests are slow and require external dependencies. Unit tests at Layers 1-2 provide sufficient
coverage.

## Testing Patterns

### Pattern 1: Mock LLM Responses

```kotlin
private fun createChatResponse(text: String): ChatResponse {
    val message = AssistantMessage(text)
    val generation = Generation(message)
    return ChatResponse(listOf(generation))
}

@Test
fun `test LLM call`() {
    val response = createChatResponse("""{"key": "value"}""")
    whenever(chatModel.call(any<Prompt>())) doReturn response

    val result = service.someMethod()

    assertEquals("value", result.key)
}
```

### Pattern 2: Mock BeanOutputConverter

```kotlin
@Test
fun `test structured output`() {
    val expectedObject = MyData("value")

    val converter = mock<BeanOutputConverter<MyData>>()
    whenever(beanOutputConverterCache.getConverter(MyData::class.java)) doReturn converter
    whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")
    whenever(converter.convert(any())) doReturn expectedObject

    val response = createChatResponse("""{"data": "value"}""")
    whenever(chatModel.call(any<Prompt>())) doReturn response

    val result = service.parseData()

    assertEquals(expectedObject, result)
}
```

### Pattern 3: Test Error Handling

```kotlin
@Test
fun `should handle null ChatModel output gracefully`() {
    val message = mock<AssistantMessage> {
        on { text } doReturn null
    }
    val generation = Generation(message)
    val response = ChatResponse(listOf(generation))
    whenever(chatModel.call(any<Prompt>())) doReturn response

    assertThrows(IllegalStateException::class.java) {
        service.parseData()
    }
}
```

### Pattern 4: Test Tool Usage

```kotlin
@Test
fun `should handle tool calling errors gracefully`() {
    whenever(chatModel.call(any<Prompt>())) doThrow RuntimeException("API Error")

    val result = service.conductResearch(section, tools)

    assertNotNull(result)
    assertTrue(result.markdownContent.contains("technical issues"))
}
```

### Pattern 5: Test Node State Flow

```kotlin
@Test
fun `node should extract state, call service, return updates`() {
    // Given - Mock service response
    whenever(service.someMethod(any())) doReturn "result"

    // Create state with test data
    val state = GraphService.MyState(mapOf("input" to "test"))

    val node = GraphService.MyNode(service)

    // When
    val result = node.apply(state)

    // Then
    // 1. Verify service called with extracted state
    verify(service).someMethod("test")

    // 2. Verify correct state updates returned
    assertEquals("result", result["output"])
}
```

## Test Maintainability

**Benefits of Service Extraction:**

- **Fast tests:** No LLM calls, all mocked (full suite runs in <10s)
- **Reliable tests:** No external dependencies (no Ollama, OCR tools needed)
- **Clear failures:** Test names describe what failed
- **Easy updates:** Change logic in one place, tests update accordingly

**Test Naming Convention:**

```kotlin
`methodName should expectedBehavior when condition`
```

Examples:

- `initialParse should parse OCR text successfully`
- `initialParse should throw when ChatModel returns null`
- `InitialParseNode should call pdfParsingLlmService and return parsed result`

## Best Practices

### 1. One Service Method = One Test Class Section

Group tests by the service method they test:

```kotlin
class MyServiceTest {
    // methodA tests
    @Test fun `methodA should handle case 1`() { }
    @Test fun `methodA should handle case 2`() { }

    // methodB tests
    @Test fun `methodB should handle case 1`() { }
    @Test fun `methodB should handle case 2`() { }
}
```

### 2. Test Both Happy Path and Error Cases

For each service method:

1. Happy path (valid inputs → expected output)
2. Null/empty inputs
3. LLM returns null
4. Conversion fails
5. External service throws

### 3. Mock External Dependencies, Not Internal Logic

**Good:**

```kotlin
whenever(chatModel.call(any())) doReturn mockResponse
```

**Bad:**

```kotlin
whenever(service.internalHelper()) doReturn mockValue
```

Internal methods are implementation details.

### 4. Use Helper Methods for Repetitive Setup

```kotlin
private fun setupSuccessfulExtraction(extraction: FindingsExtraction) {
    val converter = mock<BeanOutputConverter<FindingsExtraction>>()
    whenever(beanOutputConverterCache.getConverter(FindingsExtraction::class.java)) doReturn converter
    whenever(converter.jsonSchemaMap) doReturn mapOf("type" to "object")
    whenever(converter.convert(any())) doReturn extraction

    val response = createChatResponse("Research results\nTOOL_USAGE: YES")
    whenever(chatModel.call(any<Prompt>())) doReturn response
}
```

### 5. Verify Service Calls in Node Tests

Always verify the service was called with correct parameters:

```kotlin
verify(service).methodName(eq(expectedParam1), eq(expectedParam2))
```

This ensures nodes are correctly extracting state and passing it to services.

## Common Pitfalls

### Pitfall 1: Testing Implementation Details

**Bad:**

```kotlin
@Test
fun `should call private helper method`() {
    // Testing internal implementation
}
```

**Good:**

```kotlin
@Test
fun `should return expected result for given input`() {
    // Testing public behavior
}
```

### Pitfall 2: Not Testing Error Cases

Only testing happy paths leaves error handling untested. Always test:

- Null returns from LLM
- Conversion failures
- Exception propagation

### Pitfall 3: Over-Mocking

**Bad:**

```kotlin
whenever(service.method1()) doReturn mock1
whenever(service.method2()) doReturn mock2
whenever(service.method3()) doReturn mock3
// Service is completely mocked, no real code runs
```

**Good:**

```kotlin
// Only mock external dependencies
whenever(chatModel.call()) doReturn response
// Let service methods run real code
```

### Pitfall 4: Brittle Tests

**Bad:**

```kotlin
verify(chatModel).call(
    argThat { prompt ->
        prompt.toString().contains("exact string from prompt")
    }
)
```

Test breaks when prompt text changes (implementation detail).

**Good:**

```kotlin
verify(chatModel).call(any<Prompt>())
```

Just verify it was called, don't test prompt internals.

## Migration Guide

### Adding Tests to Existing Graph Class

**Step 1: Extract LlmService**

```kotlin
@Service
class MyLlmService(
    private val chatModel: ChatModel,
    // ... other dependencies
) {
    fun doSomething(input: String): Result {
        // Move logic from nodes here
    }
}
```

**Step 2: Refactor Nodes to Thin Wrappers**

```kotlin
class MyNode(
    private val myLlmService: MyLlmService,
) : NodeAction<MyState> {
    override fun apply(state: MyState): Map<String, Any> {
        val input = state.input()
        val result = myLlmService.doSomething(input)
        return mapOf("output" to result)
    }
}
```

**Step 3: Write LlmService Tests**

```kotlin
class MyLlmServiceTest {
    private lateinit var chatModel: ChatModel
    private lateinit var service: MyLlmService

    @BeforeEach
    fun setUp() {
        chatModel = mock()
        service = MyLlmService(chatModel)
    }

    @Test
    fun `doSomething should return expected result`() {
        // Test business logic
    }
}
```

**Step 4: Write GraphService Node Tests**

```kotlin
class MyGraphServiceTest {
    private lateinit var myLlmService: MyLlmService
    private lateinit var service: MyGraphService

    @BeforeEach
    fun setUp() {
        myLlmService = mock()
        service = MyGraphService(myLlmService)
    }

    @Test
    fun `MyNode should call service and return state updates`() {
        // Test orchestration
    }
}
```

## See Also

- [Architecture Overview](../architecture.md) - Overall patterns
- [Example Testing](../examples/competitive-intelligence/index.md#testing-strategy) - Example-specific tests
- [Getting Started](../development/getting-started.md) - Running tests locally
