package com.aryan.expensetracker.pipeline.extraction

import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.llm.LlmClient
import com.aryan.expensetracker.core.result.AppResult
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RECEIVED_AT = 1_760_000_000_000L

// stands in for gemini so the tests never touch the network
private class FakeLlmClient(private val response: AppResult<String>) : LlmClient {
    override suspend fun generateJson(
        systemInstruction: String,
        userText: String,
        responseSchema: String,
    ): AppResult<String> = response
}

class LlmExtractorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun extractorReturning(responseText: String): LlmExtractor =
        LlmExtractor(FakeLlmClient(AppResult.Success(responseText)), json)

    @Test
    fun buildsDraftFromTransactionJson() = runTest {
        val extractor = extractorReturning(
            """
            {"isTransaction":true,"amount":"1,234.50","direction":"DEBIT",
             "merchant":"SWIGGY BUNDL TECHNOLOGIES","referenceNumber":"401234567890",
             "accountTail":"XX1234","occurredOn":"2026-02-14"}
            """
        )

        val outcome = extractor.extract("body", RECEIVED_AT)

        assertTrue(outcome is ExtractionOutcome.Transaction)
        val draft = (outcome as ExtractionOutcome.Transaction).draft
        assertEquals(123450L, draft.amountPaise)
        assertEquals(AppConfig.DIRECTION_DEBIT, draft.direction)
        assertEquals("SWIGGY BUNDL TECHNOLOGIES", draft.merchantRaw)
        assertEquals("401234567890", draft.referenceNumber)
        assertEquals("1234", draft.accountTail)
        assertEquals("LLM", draft.source)
        assertEquals(startOfDayInIst("2026-02-14"), draft.occurredAt)
    }

    @Test
    fun fallsBackToArrivalTimeWithoutADate() = runTest {
        val extractor = extractorReturning(
            """{"isTransaction":true,"amount":"450","direction":"CREDIT","merchant":"ACME"}"""
        )

        val outcome = extractor.extract("body", RECEIVED_AT)

        assertEquals(RECEIVED_AT, (outcome as ExtractionOutcome.Transaction).draft.occurredAt)
    }

    @Test
    fun reportsNotATransaction() = runTest {
        val extractor = extractorReturning("""{"isTransaction":false}""")

        assertTrue(extractor.extract("body", RECEIVED_AT) is ExtractionOutcome.NotATransaction)
    }

    @Test
    fun failsOnMalformedJson() = runTest {
        val extractor = extractorReturning("sorry, I could not read that message")

        assertTrue(extractor.extract("body", RECEIVED_AT) is ExtractionOutcome.Failed)
    }

    @Test
    fun failsWhenAmountIsMissing() = runTest {
        val extractor = extractorReturning(
            """{"isTransaction":true,"direction":"DEBIT","merchant":"ACME"}"""
        )

        assertTrue(extractor.extract("body", RECEIVED_AT) is ExtractionOutcome.Failed)
    }

    @Test
    fun failsWhenTheCallFailed() = runTest {
        val extractor = LlmExtractor(FakeLlmClient(AppResult.Failure("daily cap")), json)

        val outcome = extractor.extract("body", RECEIVED_AT)

        assertEquals("daily cap", (outcome as ExtractionOutcome.Failed).reason)
    }

    private fun startOfDayInIst(isoDate: String): Long =
        LocalDate.parse(isoDate)
            .atStartOfDay(ZoneId.of(AppConfig.BANK_TIME_ZONE))
            .toInstant()
            .toEpochMilli()
}
