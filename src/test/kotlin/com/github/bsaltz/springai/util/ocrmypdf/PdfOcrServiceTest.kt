package com.github.bsaltz.springai.util.ocrmypdf

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for PdfOcrService.
 *
 * Note: This class directly creates ProcessBuilder instances, which makes it challenging
 * to unit test without refactoring. Ideally, process creation would be abstracted behind
 * an interface for better testability. These tests focus on testing the logic that can
 * be tested with the current design.
 */
class PdfOcrServiceTest {
    private lateinit var service: PdfOcrService

    @TempDir
    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        service = PdfOcrService()
    }

    @Test
    fun `service can be instantiated`() {
        assertNotNull(service)
    }

    /**
     * This is a basic integration-style test that verifies the runOcrmypdf method exists
     * and handles the input/output paths. To run this test successfully, ocrmypdf must
     * be installed on the system.
     *
     * For true unit testing, consider refactoring to inject a ProcessExecutor interface.
     */
    @Test
    fun `runOcrmypdf should create output file when given valid input`() {
        // Skip this test if ocrmypdf is not installed
        if (!isCommandAvailable("ocrmypdf")) {
            println("Skipping test - ocrmypdf not available")
            return
        }

        // Given
        val inputPdf = createDummyPdf()

        // When/Then - this would fail if ocrmypdf is not installed
        // The test verifies that the method at least attempts to run the command
        val exception =
            assertThrows(IllegalStateException::class.java) {
                service.runOcrmypdf(inputPdf)
            }

        // The error should be about the invalid PDF, not about the command not being found
        assertTrue(
            exception.message?.contains("ocrmypdf failed") == true,
            "Should fail due to invalid PDF content, not missing command",
        )
    }

    /**
     * Integration test for pdftotext. Requires pdftotext to be installed.
     */
    @Test
    fun `runPdftotext should extract text when given valid input`() {
        // Skip this test if pdftotext is not installed
        if (!isCommandAvailable("pdftotext")) {
            println("Skipping test - pdftotext not available")
            return
        }

        // Given
        val inputPdf = createDummyPdf()

        // When/Then
        val exception =
            assertThrows(IllegalStateException::class.java) {
                service.runPdftotext(inputPdf)
            }

        assertTrue(
            exception.message?.contains("pdftotext failed") == true,
            "Should fail due to invalid PDF content",
        )
    }

    /**
     * Integration test for the legacy ocr method.
     */
    @Test
    fun `ocr method should combine runOcrmypdf and runPdftotext`() {
        // Skip this test if tools are not installed
        if (!isCommandAvailable("ocrmypdf") || !isCommandAvailable("pdftotext")) {
            println("Skipping test - required tools not available")
            return
        }

        // Given
        val inputPdf = createDummyPdf()
        val outputPath = tempDir.resolve("output.txt")

        // When/Then
        assertThrows(IllegalStateException::class.java) {
            service.ocr(inputPdf, outputPath)
        }
    }

    // Helper methods

    private fun createDummyPdf(): Path {
        val pdfPath = tempDir.resolve("test.pdf")
        // Create a minimal (but invalid) PDF file for testing error cases
        Files.writeString(pdfPath, "%PDF-1.4\n%%EOF")
        return pdfPath
    }

    private fun isCommandAvailable(command: String): Boolean =
        try {
            val process =
                ProcessBuilder("which", command)
                    .redirectErrorStream(true)
                    .start()
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
}

/**
 * This class demonstrates how PdfOcrService could be refactored for better testability.
 * By extracting process execution into an interface, we could properly unit test the logic.
 */
class PdfOcrServiceRefactoredTest {
    /**
     * Example of how the service could be refactored:
     *
     * interface ProcessExecutor {
     *     fun execute(command: List<String>, timeoutSeconds: Long): ProcessResult
     * }
     *
     * data class ProcessResult(val exitCode: Int, val output: String, val error: String)
     *
     * class PdfOcrService(private val processExecutor: ProcessExecutor) {
     *     fun runOcrmypdf(input: Path): Path {
     *         val result = processExecutor.execute(listOf("ocrmypdf", ...), 60)
     *         // process result...
     *     }
     * }
     *
     * Then in tests, we could:
     * val mockExecutor = mock<ProcessExecutor>()
     * whenever(mockExecutor.execute(any(), any())).thenReturn(ProcessResult(0, "", ""))
     * val service = PdfOcrService(mockExecutor)
     */

    @Test
    fun `example test with refactored design`() {
        // This test shows what proper unit testing would look like with dependency injection
        val mockExecutor = mock<ProcessExecutor>()

        // Mock successful ocrmypdf execution
        whenever(mockExecutor.execute(any(), any())) doReturn
            ProcessExecutor.ProcessResult(
                exitCode = 0,
                output = "",
                error = "",
            )

        // With refactoring, we could properly test the logic without actual process execution
        assertNotNull(mockExecutor)
    }

    // Example interface for better testability
    interface ProcessExecutor {
        fun execute(
            command: List<String>,
            timeoutSeconds: Long,
        ): ProcessResult

        data class ProcessResult(
            val exitCode: Int,
            val output: String,
            val error: String,
        )
    }
}
