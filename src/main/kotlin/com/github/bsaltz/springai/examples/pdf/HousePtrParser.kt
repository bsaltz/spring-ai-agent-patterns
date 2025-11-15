package com.github.bsaltz.springai.examples.pdf

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectWriter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.shell.command.annotation.Command
import java.io.Serializable
import java.nio.file.Paths

@Command
class HousePtrParser(
    private val pdfParsingGraphService: PdfParsingGraphService,
) {
    private val log: Logger = LoggerFactory.getLogger(javaClass)
    private val objectWriter: ObjectWriter =
        Jackson2ObjectMapperBuilder
            .json()
            .build<ObjectMapper>()
            .writerWithDefaultPrettyPrinter()

    @Command(command = ["examples", "run", "HousePtrParser"])
    fun runExample() {
        log.info("Running HousePtrParser...")
        val path = Paths.get("src/main/resources/examples/house-ptr/20029060.pdf")
        val result = pdfParsingGraphService.parsePdf(path, parsingInstructions, HouseLlmOutput::class.java)
        log.info("Converting back to JSON for printing...")
        log.info("\n{}", objectWriter.writeValueAsString(result))
    }

    companion object {
        private val parsingInstructions =
            """
            The document header contains the filing ID, filer name, filer status ("Member" or "Candidate"), and the
            state/district (AA00, where AA is the state and 00 is the district number).
            
            The document's main content is a table of security transactions with the columns "ID", "Owner", "Asset",
            "Transaction Type", "Date", "Notification Date", "Amount", and "Cap. Gains > $200?". Also, "Asset" can contain a
            number of subfields, including "Filing Status", "Description", and "Comments". Try to extract these values from
            the parse result. Here's a description of the fields you should extract and include in the output.
            
            * ID: Almost always blank, never set to null
            * Owner: Blank (owner is the House member), SP (member's spouse), DC (member's dependent child), JT (joint
              with member)
            * Asset: Might be multiple lines, might also have the transaction type code accidentally included in it, and has
              square brackets around the asset type, e.g. '[ST]' for Common Stock
            * Transaction Type: P (purchase), S (sale) - sometimes the P comes across as the Cyrillic P because of some OCR
              weirdness
            * Date: m/d/y
            * Notification Date: m/d/y
            * Amount: A range of dollars
            * Cap. Gains > $200?: Usually not picked up by OCR, so you can ignore it
            * Filing Status: New or Amended
            * Certainty: A score from 1 to 100 estimating confidence in the output.
            
            Convert any Cyrillic characters to the look-alike Latin character, e.g. the Cyrillic R looks like a P, so return
            the Latin P, not the Latin R. Do not wrap the output in Markdown syntax, just return the JSON.
            
            Example values in output. Note that none of these values are nullable, so make them empty instead of null.
            
            {
              "filingId": "20029060",
              "filer": {
                "name": "Hon. David J. Taylor",
                "status": "Member",
                "stateDistrict": "OH02"
              },
              "transactions": [
                {
                  "id": "",
                  "owner": "",
                  "asset": "Amazon.com, Inc. - Common Stock (AMZN) [ST]",
                  "transactionType": "P",
                  "date": "03/27/2025",
                  "notificationDate": "03/31/2025",
                  "amount": "$1,001 - $15,000",
                  "filingStatus": "New",
                  "certainty": 85
                }
              ]
            }
            """.trimIndent()
    }
}

data class HouseLlmOutput(
    val filingId: String,
    val filer: HouseLlmFiler,
    val transactions: List<HouseLlmTransaction>,
    override val changes: List<Change>,
) : Refineable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class HouseLlmFiler(
    val name: String,
    val status: String,
    val stateDistrict: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class HouseLlmTransaction(
    val id: String,
    val owner: String,
    val asset: String,
    val transactionType: String,
    val date: String,
    val notificationDate: String,
    val amount: String,
    val filingStatus: String,
    val certainty: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
