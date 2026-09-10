package com.aryan.expensetracker.pipeline

import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.db.entity.CategoryEntity
import com.aryan.expensetracker.core.db.entity.MerchantRuleEntity
import com.aryan.expensetracker.core.db.entity.PendingMessageEntity
import com.aryan.expensetracker.core.db.entity.ProcessedMessageEntity
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val FOOD = CategoryEntity(1L, "Food", AppConfig.CATEGORY_KIND_EXPENSE, true, 0)
private val TRAVEL = CategoryEntity(2L, "Travel", AppConfig.CATEGORY_KIND_EXPENSE, true, 1)

private const val MESSAGE_HASH = "hash-one"
private const val SWIGGY_RULE = "SWIGGY"

private const val WITH_REFERENCE = """
{"isTransaction":true,"amount":"250.00","direction":"DEBIT","merchant":"SWIGGY",
 "referenceNumber":"REF123","accountTail":"XX9876"}
"""
private const val WITHOUT_REFERENCE = """
{"isTransaction":true,"amount":"250.00","direction":"DEBIT","merchant":"SWIGGY"}
"""

class MessagePipelineTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val transactionDao = FakeTransactionDao()
    private val processedMessageDao = FakeProcessedMessageDao()

    // the whole graph with fakes at every edge; each test swaps only what it is about
    private fun pipelineWith(
        client: FakeLlmClient,
        rules: List<MerchantRuleEntity> = emptyList(),
        isOnline: Boolean = true,
    ): MessagePipeline {
        val merchantRuleDao = FakeMerchantRuleDao(rules)
        return MessagePipeline(
            transactionDao,
            processedMessageDao,
            NetworkChecker { isOnline },
            LlmExtractor(client, json),
            MerchantNormalizer(AppConfig.MERCHANT_NOISE_WORDS),
            LocalCategorizer(merchantRuleDao),
            LlmCategorizer(client, FakeCategoryDao(listOf(FOOD, TRAVEL)), merchantRuleDao, json),
            DuplicateChecker(transactionDao),
            DatabaseWriter { block -> block() },
        )
    }

    private fun message(hash: String = MESSAGE_HASH): PendingMessageEntity = PendingMessageEntity(
        id = 1L,
        sender = "HDFCBK",
        body = "some bank message",
        receivedAt = System.currentTimeMillis(),
        attemptCount = 0,
        messageHash = hash,
        latitude = null,
        longitude = null,
    )

    private fun learnedFoodRule() =
        listOf(MerchantRuleEntity(SWIGGY_RULE, AppConfig.DIRECTION_DEBIT, FOOD.id, AppConfig.RULE_SOURCE_USER, 0L))

    @Test
    fun ignoresAMessageAlreadyProcessed() = runTest {
        processedMessageDao.records[MESSAGE_HASH] =
            ProcessedMessageEntity(MESSAGE_HASH, AppConfig.PROCESSED_SAVED, 7L, 0L)
        val client = FakeLlmClient(AppResult.Success(WITH_REFERENCE))

        val outcome = pipelineWith(client).process(message())

        assertEquals(PipelineOutcome.Ignored("repost"), outcome)
        assertEquals(0, client.extractionCalls)
        assertTrue(transactionDao.rows.isEmpty())
    }

    @Test
    fun waitsForNetworkWithoutCallingTheModel() = runTest {
        val client = FakeLlmClient(AppResult.Success(WITH_REFERENCE))

        val outcome = pipelineWith(client, isOnline = false).process(message())

        assertEquals(PipelineOutcome.WaitForNetwork, outcome)
        assertEquals(0, client.extractionCalls)
        assertTrue(transactionDao.rows.isEmpty())
        assertTrue(processedMessageDao.records.isEmpty())
    }

    @Test
    fun recordsANonTransactionAsIgnored() = runTest {
        val client = FakeLlmClient(AppResult.Success("""{"isTransaction":false}"""))

        val outcome = pipelineWith(client).process(message())

        assertTrue(outcome is PipelineOutcome.Ignored)
        assertEquals(AppConfig.PROCESSED_IGNORED, processedMessageDao.records[MESSAGE_HASH]?.outcome)
        assertTrue(transactionDao.rows.isEmpty())
    }

    @Test
    fun usesTheLearnedCategoryWithoutAModelCall() = runTest {
        val client = FakeLlmClient(AppResult.Success(WITH_REFERENCE))

        val outcome = pipelineWith(client, rules = learnedFoodRule()).process(message())

        assertTrue(outcome is PipelineOutcome.Saved)
        assertEquals(0, client.categorizationCalls)
        val saved = transactionDao.rows.single()
        assertEquals(FOOD.id, saved.categoryId)
        assertEquals(ReviewReason.LLM_EXTRACTED, saved.reviewReason)
        assertTrue(saved.needsReview)
    }

    @Test
    fun asksTheModelForAnUnknownMerchant() = runTest {
        val client = FakeLlmClient(
            AppResult.Success(WITH_REFERENCE),
            AppResult.Success("""{"category":"Travel"}"""),
        )

        val outcome = pipelineWith(client).process(message())

        assertTrue(outcome is PipelineOutcome.Saved)
        assertEquals(1, client.categorizationCalls)
        val saved = transactionDao.rows.single()
        assertEquals(TRAVEL.id, saved.categoryId)
        assertEquals(ReviewReason.NEW_MERCHANT, saved.reviewReason)
    }

    @Test
    fun flagsAKnownMerchantWithoutAReferenceNumber() = runTest {
        val client = FakeLlmClient(AppResult.Success(WITHOUT_REFERENCE))

        pipelineWith(client, rules = learnedFoodRule()).process(message())

        val saved = transactionDao.rows.single()
        assertEquals(ReviewReason.NO_REFERENCE_NUMBER, saved.reviewReason)
        assertEquals(FOOD.id, saved.categoryId)
    }

    // the rule this whole task exists for: a later bank message corrects facts, never decisions
    @Test
    fun aReplaceKeepsTheUsersOwnDecision() = runTest {
        val client = FakeLlmClient(AppResult.Success(WITH_REFERENCE))
        pipelineWith(client, rules = learnedFoodRule()).process(message())

        // the user has since confirmed the row and moved it to Travel
        val confirmed = transactionDao.rows.single()
            .copy(categoryId = TRAVEL.id, needsReview = false, reviewReason = null)
        transactionDao.update(confirmed)

        val corrected = FakeLlmClient(
            AppResult.Success(
                """{"isTransaction":true,"amount":"300.00","direction":"DEBIT","merchant":"SWIGGY",
                    "referenceNumber":"REF123","accountTail":"XX1111"}"""
            )
        )
        val second = message(hash = "hash-two")
        val outcome = pipelineWith(corrected, rules = learnedFoodRule()).process(second)

        assertEquals(PipelineOutcome.Saved(confirmed.id), outcome)
        val row = transactionDao.rows.single()
        assertEquals(30000L, row.amountPaise)
        assertEquals("hash-two", row.messageHash)
        assertEquals("1111", row.accountTail)
        assertEquals(TRAVEL.id, row.categoryId)
        assertEquals(false, row.needsReview)
        assertNull(row.reviewReason)
        // the superseded message keeps a mark of its own, so its re-delivery stays ignored
        assertEquals(AppConfig.PROCESSED_SUPERSEDED, processedMessageDao.records[MESSAGE_HASH]?.outcome)
        assertEquals(AppConfig.PROCESSED_SAVED, processedMessageDao.records["hash-two"]?.outcome)
    }

    @Test
    fun writesNothingWhenExtractionFails() = runTest {
        val client = FakeLlmClient(AppResult.Failure("http 503"))

        val outcome = pipelineWith(client).process(message())

        assertEquals(PipelineOutcome.Failed("http 503"), outcome)
        assertTrue(transactionDao.rows.isEmpty())
        assertTrue(processedMessageDao.records.isEmpty())
    }

    @Test
    fun rejectsADirectionTheModelInvented() = runTest {
        val client = FakeLlmClient(
            AppResult.Success("""{"isTransaction":true,"amount":"10","direction":"debit","merchant":"SWIGGY"}""")
        )

        val outcome = pipelineWith(client).process(message())

        assertEquals(PipelineOutcome.Failed("bad direction"), outcome)
        assertTrue(transactionDao.rows.isEmpty())
    }
}
