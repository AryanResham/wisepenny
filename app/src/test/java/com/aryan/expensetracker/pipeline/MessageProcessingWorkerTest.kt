package com.aryan.expensetracker.pipeline

import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.db.entity.CategoryEntity
import com.aryan.expensetracker.core.db.entity.PendingMessageEntity
import com.aryan.expensetracker.core.db.entity.ReviewReason
import com.aryan.expensetracker.core.net.NetworkChecker
import com.aryan.expensetracker.core.result.AppResult
import com.aryan.expensetracker.pipeline.categorization.LlmCategorizer
import com.aryan.expensetracker.pipeline.categorization.LocalCategorizer
import com.aryan.expensetracker.pipeline.categorization.MerchantNormalizer
import com.aryan.expensetracker.pipeline.dedup.DuplicateChecker
import com.aryan.expensetracker.pipeline.extraction.LlmExtractor
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val FOOD = CategoryEntity(1L, "Food", AppConfig.CATEGORY_KIND_EXPENSE, true, 0)

private const val A_TRANSACTION = """
{"isTransaction":true,"amount":"250.00","direction":"DEBIT","merchant":"SWIGGY","referenceNumber":"REF1"}
"""

class MessageProcessingWorkerTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val transactionDao = FakeTransactionDao()

    private fun pipelineWith(client: FakeLlmClient, isOnline: Boolean): MessagePipeline {
        val merchantRuleDao = FakeMerchantRuleDao(emptyList())
        return MessagePipeline(
            transactionDao,
            FakeProcessedMessageDao(),
            NetworkChecker { isOnline },
            LlmExtractor(client, json),
            MerchantNormalizer(AppConfig.MERCHANT_NOISE_WORDS),
            LocalCategorizer(merchantRuleDao),
            LlmCategorizer(client, FakeCategoryDao(listOf(FOOD)), merchantRuleDao, json),
            DuplicateChecker(transactionDao),
            DatabaseWriter { block -> block() },
        )
    }

    private fun queued(attemptCount: Int): PendingMessageEntity = PendingMessageEntity(
        id = 1L,
        sender = "HDFCBK",
        body = "some bank message",
        receivedAt = System.currentTimeMillis(),
        attemptCount = attemptCount,
        messageHash = "hash-one",
        latitude = null,
        longitude = null,
    )

    @Test
    fun offlineLeavesTheMessageAndItsAttemptCountAlone() = runTest {
        val pendingMessageDao = FakePendingMessageDao(listOf(queued(attemptCount = 3)))
        val pipeline = pipelineWith(FakeLlmClient(AppResult.Success(A_TRANSACTION)), isOnline = false)

        val report = drainQueue(pendingMessageDao, transactionDao, pipeline)

        assertEquals(1, report.stillWaiting)
        assertEquals(3, pendingMessageDao.queue.single().attemptCount)
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
    fun aMessageOutOfAttemptsBecomesAReviewRowInsteadOfVanishing() = runTest {
        val pendingMessageDao =
            FakePendingMessageDao(listOf(queued(attemptCount = AppConfig.MAX_PROCESSING_ATTEMPTS)))
        val pipeline = pipelineWith(FakeLlmClient(AppResult.Failure("http 503")), isOnline = true)

        val report = drainQueue(pendingMessageDao, transactionDao, pipeline)

        assertEquals(0, report.stillWaiting)
        assertTrue(pendingMessageDao.queue.isEmpty())
        val stub = transactionDao.rows.single()
        assertEquals(0L, stub.amountPaise)
        assertEquals(ReviewReason.COULD_NOT_PROCESS, stub.reviewReason)
        assertTrue(stub.needsReview)
        assertEquals("some bank message", stub.rawMessage)
    }

    @Test
    fun aSavedMessageLeavesTheQueue() = runTest {
        val pendingMessageDao = FakePendingMessageDao(listOf(queued(attemptCount = 0)))
        val client = FakeLlmClient(
            AppResult.Success(A_TRANSACTION),
            AppResult.Success("""{"category":"Food"}"""),
        )

        val report = drainQueue(pendingMessageDao, transactionDao, pipelineWith(client, isOnline = true))

        assertEquals(0, report.stillWaiting)
        assertEquals(1, report.handled)
        assertTrue(pendingMessageDao.queue.isEmpty())
        assertEquals(FOOD.id, transactionDao.rows.single().categoryId)
    }
}
