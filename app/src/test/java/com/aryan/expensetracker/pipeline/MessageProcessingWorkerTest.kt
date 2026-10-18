package com.aryan.expensetracker.pipeline

import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.db.entity.PendingMessageEntity
import com.aryan.expensetracker.core.net.NetworkChecker
import com.aryan.expensetracker.core.result.AppResult
import com.aryan.expensetracker.pipeline.extraction.FallbackCounter
import com.aryan.expensetracker.pipeline.extraction.LlmFallbackExtractor
import com.aryan.expensetracker.pipeline.extraction.NonTransactionFilter
import com.aryan.expensetracker.pipeline.extraction.RegexExtractor
import com.aryan.expensetracker.pipeline.extraction.realTemplates
import com.aryan.expensetracker.pipeline.tagging.AutoTagger
import com.aryan.expensetracker.pipeline.tagging.PayeeNormalizer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NO_TEMPLATE_FITS = "An unreadable bank update in a shape we have never seen"

private const val A_MODEL_TRANSACTION = """
{"isTransaction":true,"amount":"250.00","direction":"DEBIT","merchant":"Some Shop","referenceNumber":"REF1"}
"""

class MessageProcessingWorkerTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val transactionDao = FakeTransactionDao()

    private fun pipelineWith(client: FakeLlmClient, isOnline: Boolean): MessagePipeline =
        MessagePipeline(
            transactionDao,
            FakeProcessedMessageDao(),
            NetworkChecker { isOnline },
            NonTransactionFilter(),
            RegexExtractor(realTemplates()),
            LlmFallbackExtractor(client, json, FallbackCounter {}),
            PayeeNormalizer(),
            AutoTagger(FakePayeeRuleDao(), transactionDao),
            DatabaseWriter { block -> block() },
        )

    private fun queued(attemptCount: Int): PendingMessageEntity = PendingMessageEntity(
        id = 1L,
        sender = "HDFCBK",
        body = NO_TEMPLATE_FITS,
        receivedAt = System.currentTimeMillis(),
        attemptCount = attemptCount,
        messageHash = "hash-one",
        latitude = null,
        longitude = null,
    )

    @Test
    fun offlineLeavesTheMessageAndItsAttemptCountAlone() = runTest {
        val pendingMessageDao = FakePendingMessageDao(listOf(queued(attemptCount = 3)))
        val pipeline = pipelineWith(FakeLlmClient(AppResult.Success(A_MODEL_TRANSACTION)), isOnline = false)

        val report = drainQueue(pendingMessageDao, transactionDao, pipeline)

        assertEquals(1, report.stillWaiting)
        assertEquals(3, pendingMessageDao.queue.single().attemptCount)
    }

    // one rate limit means every message behind it would get the same answer, so the drain stops
    @Test
    fun aRateLimitStopsTheDrainAndBurnsNoAttempts() = runTest {
        val first = queued(attemptCount = 2)
        val second = queued(attemptCount = 0).copy(id = 2L, messageHash = "hash-two", receivedAt = first.receivedAt + 1)
        val pendingMessageDao = FakePendingMessageDao(listOf(first, second))
        val client = FakeLlmClient(AppResult.Failure(AppConfig.LLM_REASON_RATE_LIMITED))

        val report = drainQueue(pendingMessageDao, transactionDao, pipelineWith(client, isOnline = true))

        assertEquals(2, report.waiting)
        assertEquals(1, client.extractionCalls)
        assertEquals(2, pendingMessageDao.queue.first { it.id == 1L }.attemptCount)
        assertTrue(transactionDao.rows.isEmpty())
    }

    @Test
    fun aFailureUnderTheCapCountsOneAttemptAndStaysQueued() = runTest {
        val pendingMessageDao = FakePendingMessageDao(listOf(queued(attemptCount = 0)))
        val pipeline = pipelineWith(FakeLlmClient(AppResult.Failure("http 503")), isOnline = true)

        val report = drainQueue(pendingMessageDao, transactionDao, pipeline)

        assertEquals(1, report.stillWaiting)
        assertEquals(1, pendingMessageDao.queue.single().attemptCount)
        assertTrue(transactionDao.rows.isEmpty())
    }

    // the rule that keeps the app honest: nothing leaves the queue without becoming visible somewhere
    @Test
    fun aMessageOutOfAttemptsBecomesAStubRowInsteadOfVanishing() = runTest {
        val pendingMessageDao =
            FakePendingMessageDao(listOf(queued(attemptCount = AppConfig.MAX_PROCESSING_ATTEMPTS)))
        val pipeline = pipelineWith(FakeLlmClient(AppResult.Failure("http 503")), isOnline = true)

        val report = drainQueue(pendingMessageDao, transactionDao, pipeline)

        assertEquals(0, report.stillWaiting)
        assertTrue(pendingMessageDao.queue.isEmpty())
        val stub = transactionDao.rows.single()
        assertEquals(0L, stub.amountPaise)
        assertEquals(AppConfig.STUB_PAYEE, stub.payeeRaw)
        assertEquals(NO_TEMPLATE_FITS, stub.rawMessage)
    }

    @Test
    fun aSavedMessageLeavesTheQueue() = runTest {
        val pendingMessageDao = FakePendingMessageDao(listOf(queued(attemptCount = 0)))
        val client = FakeLlmClient(AppResult.Success(A_MODEL_TRANSACTION))

        val report = drainQueue(pendingMessageDao, transactionDao, pipelineWith(client, isOnline = true))

        assertEquals(0, report.stillWaiting)
        assertEquals(1, report.handled)
        assertTrue(pendingMessageDao.queue.isEmpty())
        assertEquals(AppConfig.SOURCE_LLM, transactionDao.rows.single().source)
    }
}
