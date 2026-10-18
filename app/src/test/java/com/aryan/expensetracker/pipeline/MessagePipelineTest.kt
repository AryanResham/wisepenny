package com.aryan.expensetracker.pipeline

import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.db.entity.PayeeRuleEntity
import com.aryan.expensetracker.core.db.entity.PendingMessageEntity
import com.aryan.expensetracker.core.db.entity.ProcessedMessageEntity
import com.aryan.expensetracker.core.net.NetworkChecker
import com.aryan.expensetracker.core.result.AppResult
import com.aryan.expensetracker.pipeline.extraction.FallbackCounter
import com.aryan.expensetracker.pipeline.extraction.LlmFallbackExtractor
import com.aryan.expensetracker.pipeline.extraction.NonTransactionFilter
import com.aryan.expensetracker.pipeline.extraction.RegexExtractor
import com.aryan.expensetracker.pipeline.extraction.realTemplates
import com.aryan.expensetracker.pipeline.tagging.AutoTagger
import com.aryan.expensetracker.pipeline.tagging.PayeeNormalizer
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MESSAGE_HASH = "hash-one"
private const val SHARED_REFERENCE = "999000111222"
private const val NO_TEMPLATE_FITS = "An unreadable bank update in a shape we have never seen"
private const val AN_OTP_MESSAGE = "123456 is your OTP. Do not share it."

private const val A_MODEL_TRANSACTION = """
{"isTransaction":true,"amount":"250.00","direction":"DEBIT","merchant":"Some Shop",
 "referenceNumber":"REF123","accountTail":"XX9876"}
"""

// bank dates must land inside the dedup lookback, so "today" is written the way HDFC writes it
private val TODAY: String = DateTimeFormatter.ofPattern("dd/MM/yy")
    .format(LocalDate.now(ZoneId.of(AppConfig.BANK_TIME_ZONE)))

private fun sentMessage(rupees: String, accountTail: String): String =
    "Sent Rs.$rupees From HDFC Bank A/C *$accountTail To Some Shop On $TODAY Ref $SHARED_REFERENCE"

class MessagePipelineTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val transactionDao = FakeTransactionDao()
    private val processedMessageDao = FakeProcessedMessageDao()
    private var fallbackCount = 0

    // the whole graph with fakes at every edge; each test swaps only what it is about
    private fun pipelineWith(
        client: FakeLlmClient,
        rules: List<PayeeRuleEntity> = emptyList(),
        isOnline: Boolean = true,
    ): MessagePipeline = MessagePipeline(
        transactionDao,
        processedMessageDao,
        NetworkChecker { isOnline },
        NonTransactionFilter(),
        RegexExtractor(realTemplates()),
        LlmFallbackExtractor(client, json, FallbackCounter { fallbackCount++ }),
        PayeeNormalizer(),
        AutoTagger(FakePayeeRuleDao(rules), transactionDao),
        DatabaseWriter { block -> block() },
    )

    private fun message(
        body: String = NO_TEMPLATE_FITS,
        hash: String = MESSAGE_HASH,
    ): PendingMessageEntity = PendingMessageEntity(
        id = 1L,
        sender = "HDFCBK",
        body = body,
        receivedAt = System.currentTimeMillis(),
        attemptCount = 0,
        messageHash = hash,
        latitude = null,
        longitude = null,
    )

    private fun anyClient() = FakeLlmClient(AppResult.Success(A_MODEL_TRANSACTION))

    @Test
    fun ignoresAMessageAlreadyProcessed() = runTest {
        processedMessageDao.records[MESSAGE_HASH] =
            ProcessedMessageEntity(MESSAGE_HASH, AppConfig.PROCESSED_SAVED, 7L, 0L)
        val client = anyClient()

        val outcome = pipelineWith(client).process(message())

        assertEquals(PipelineOutcome.Ignored("repost"), outcome)
        assertEquals(0, client.extractionCalls)
        assertTrue(transactionDao.rows.isEmpty())
    }

    // the rule the whole task rests on: a template match must never cost a model call
    @Test
    fun aTemplateMatchNeverReachesTheModel() = runTest {
        val client = anyClient()

        val outcome = pipelineWith(client).process(message(body = sentMessage("287.00", "0904")))

        assertTrue(outcome is PipelineOutcome.Saved)
        assertEquals(0, client.extractionCalls)
        assertEquals(0, fallbackCount)
        val saved = transactionDao.rows.single()
        assertEquals(28700L, saved.amountPaise)
        assertEquals(AppConfig.SOURCE_REGEX, saved.source)
        assertEquals("upi_sent", saved.templateId)
        assertEquals("SOME SHOP", saved.payeeNormalized)
        assertEquals(SHARED_REFERENCE, saved.referenceNumber)
    }

    @Test
    fun waitsForNetworkWithoutCallingTheModel() = runTest {
        val client = anyClient()

        val outcome = pipelineWith(client, isOnline = false).process(message())

        assertEquals(PipelineOutcome.WaitForNetwork, outcome)
        assertEquals(0, client.extractionCalls)
        assertTrue(transactionDao.rows.isEmpty())
        assertTrue(processedMessageDao.records.isEmpty())
    }

    @Test
    fun recordsANonTransactionAsIgnoredWithoutExtracting() = runTest {
        val client = anyClient()

        val outcome = pipelineWith(client).process(message(body = AN_OTP_MESSAGE))

        assertEquals(PipelineOutcome.Ignored("not a transaction"), outcome)
        assertEquals(0, client.extractionCalls)
        assertEquals(AppConfig.PROCESSED_IGNORED, processedMessageDao.records[MESSAGE_HASH]?.outcome)
        assertTrue(transactionDao.rows.isEmpty())
    }

    @Test
    fun fallsBackToTheModelWhenNoTemplateMatches() = runTest {
        val client = anyClient()

        val outcome = pipelineWith(client).process(message())

        assertTrue(outcome is PipelineOutcome.Saved)
        assertEquals(1, client.extractionCalls)
        assertEquals(1, fallbackCount)
        val saved = transactionDao.rows.single()
        assertEquals(AppConfig.SOURCE_LLM, saved.source)
        assertNull(saved.templateId)
    }

    @Test
    fun appliesTheLearnedTagWithoutAskingAnyone() = runTest {
        val rules = listOf(PayeeRuleEntity("SOME SHOP", "Food", 0L))

        val outcome = pipelineWith(anyClient(), rules = rules)
            .process(message(body = sentMessage("287.00", "0904")))

        assertTrue(outcome is PipelineOutcome.Saved)
        assertEquals("Food", transactionDao.rows.single().tag)
    }

    @Test
    fun leavesAnUnknownPayeeUntagged() = runTest {
        pipelineWith(anyClient()).process(message(body = sentMessage("2870.00", "0904")))

        assertNull(transactionDao.rows.single().tag)
    }

    // the rule this whole task exists for: a later bank message corrects facts, never decisions
    @Test
    fun aReplaceKeepsTheUsersOwnDecision() = runTest {
        pipelineWith(anyClient()).process(message(body = sentMessage("250.00", "0904")))

        // the user has since tagged the row and written a note on it
        val decided = transactionDao.rows.single().copy(tag = "Travel", description = "work trip")
        transactionDao.update(decided)

        val second = message(body = sentMessage("300.00", "1111"), hash = "hash-two")
        val outcome = pipelineWith(anyClient()).process(second)

        assertEquals(PipelineOutcome.Saved(decided.id), outcome)
        val row = transactionDao.rows.single()
        assertEquals(30000L, row.amountPaise)
        assertEquals("hash-two", row.messageHash)
        assertEquals("1111", row.accountTail)
        assertEquals("Travel", row.tag)
        assertEquals("work trip", row.description)
        // the superseded message keeps a mark of its own, so its re-delivery stays ignored
        assertEquals(AppConfig.PROCESSED_SUPERSEDED, processedMessageDao.records[MESSAGE_HASH]?.outcome)
        assertEquals(AppConfig.PROCESSED_SAVED, processedMessageDao.records["hash-two"]?.outcome)
    }

    @Test
    fun writesNothingWhenTheFallbackFails() = runTest {
        val client = FakeLlmClient(AppResult.Failure("http 503"))

        val outcome = pipelineWith(client).process(message())

        assertEquals(PipelineOutcome.Failed("http 503"), outcome)
        assertTrue(transactionDao.rows.isEmpty())
        assertTrue(processedMessageDao.records.isEmpty())
    }

    @Test
    fun aRateLimitIsAWaitNotAFailure() = runTest {
        val client = FakeLlmClient(AppResult.Failure(AppConfig.LLM_REASON_RATE_LIMITED))

        val outcome = pipelineWith(client).process(message())

        assertEquals(PipelineOutcome.WaitForQuota, outcome)
        assertTrue(transactionDao.rows.isEmpty())
        assertTrue(processedMessageDao.records.isEmpty())
    }

    @Test
    fun rejectsADirectionTheModelInvented() = runTest {
        val client = FakeLlmClient(
            AppResult.Success("""{"isTransaction":true,"amount":"10","direction":"debit","merchant":"Shop"}""")
        )

        val outcome = pipelineWith(client).process(message())

        assertEquals(PipelineOutcome.Failed("bad direction"), outcome)
        assertTrue(transactionDao.rows.isEmpty())
    }
}
