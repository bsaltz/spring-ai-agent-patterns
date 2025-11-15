package com.github.bsaltz.springai.util.ocrmypdf

import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString

@Service
class PdfOcrService {
    /**
     * Runs ocrmypdf on the input PDF file and returns the path to the OCR'd PDF.
     * The OCR'd PDF is written to a temporary file that the caller is responsible for cleaning up.
     */
    fun runOcrmypdf(input: Path): Path {
        val tempDir = Files.createTempDirectory("house-ptr")
        val tempOutput = tempDir.resolve("temp-output.pdf")

        val ocrMyPdfProcess =
            ProcessBuilder(
                "ocrmypdf",
                "--force-ocr",
                input.absolutePathString(),
                tempOutput.absolutePathString(),
            ).start()
        ocrMyPdfProcess.waitFor(60, TimeUnit.SECONDS)

        // Exit value == 4 means that the output couldn't be fully validated, which happens with some PDFs.
        if (ocrMyPdfProcess.exitValue() != 0 && ocrMyPdfProcess.exitValue() != 4) {
            // Write the output of ocrmypdf to the console if it fails
            ocrMyPdfProcess.errorStream.copyTo(System.out)
            error("ocrmypdf failed with exit code ${ocrMyPdfProcess.exitValue()}")
        }

        return tempOutput
    }

    /**
     * Runs pdftotext on the input PDF file and returns the extracted text.
     */
    fun runPdftotext(input: Path): String {
        val tempTextFile = Files.createTempFile("pdftotext", ".txt")

        val pdfToTextProcess =
            ProcessBuilder(
                "pdftotext",
                input.absolutePathString(),
                tempTextFile.absolutePathString(),
            ).start()
        pdfToTextProcess.waitFor(60, TimeUnit.SECONDS)

        if (pdfToTextProcess.exitValue() != 0) {
            // Write the output of pdftotext to the console if it fails
            pdfToTextProcess.errorStream.copyTo(System.out)
            error("pdftotext failed with exit code ${pdfToTextProcess.exitValue()}")
        }

        return Files.readString(tempTextFile)
    }

    /**
     * Legacy method that combines both OCR and text extraction steps.
     * Runs ocrmypdf followed by pdftotext.
     */
    fun ocr(
        input: Path,
        output: Path,
    ) {
        val ocrPdf = runOcrmypdf(input)
        val text = runPdftotext(ocrPdf)
        Files.writeString(output, text)
    }
}
