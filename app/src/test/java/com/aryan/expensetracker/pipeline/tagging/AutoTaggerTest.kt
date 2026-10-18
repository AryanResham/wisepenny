package com.aryan.expensetracker.pipeline.tagging

import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.db.entity.PayeeRuleEntity
import com.aryan.expensetracker.core.db.entity.TransactionEntity
import com.aryan.expensetracker.pipeline.FakePayeeRuleDao
import com.aryan.expensetracker.pipeline.FakeTransactionDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val PAYEE = "SOMESHOP"
private const val SMALL_AMOUNT = 12000L
private const val LARGE_AMOUNT = 99000L

class AutoTaggerTest {

    private val transactionDao = FakeTransactionDao()

    private fun taggerWith(rules: List<PayeeRuleEntity>): AutoTagger =
        AutoTagger(FakePayeeRuleDao(rules), transactionDao)

    private suspend fun seenOnce() {
        transactionDao.insert(
            TransactionEntity(
                amountPaise = SMALL_AMOUNT,
                direction = AppConfig.DIRECTION_DEBIT,
                payeeRaw = "Some Shop",
                payeeNormalized = PAYEE,
                tag = null,
                description = null,
                occurredAt = 0L,
                capturedAt = 0L,
                referenceNumber = null,
                accountTail = null,
                source = AppConfig.SOURCE_REGEX,
                templateId = "upi_sent",
                placeFromMessage = null,
                latitude = null,
                longitude = null,
                rawMessage = "",
                messageHash = "hash-seen-once",
            )
        )
    }

    @Test
    fun theLearnedRuleWins() = runTest {
        seenOnce()
        val rules = listOf(PayeeRuleEntity(PAYEE, "Groceries", 0L))

        assertEquals("Groceries", taggerWith(rules).tagFor(PAYEE, LARGE_AMOUNT))
    }

    @Test
    fun aSmallSpendAtAPayeeSeenOnceBecomesDailySpends() = runTest {
        seenOnce()

        assertEquals(AppConfig.TAG_DAILY_SPENDS, taggerWith(emptyList()).tagFor(PAYEE, SMALL_AMOUNT))
    }

    // untagged is the resting state: a big amount, a brand new payee or a regular one all get null
    @Test
    fun anythingElseStaysUntagged() = runTest {
        seenOnce()
        val tagger = taggerWith(emptyList())

        assertNull(tagger.tagFor(PAYEE, LARGE_AMOUNT))
        assertNull(tagger.tagFor("NEVERSEEN", SMALL_AMOUNT))
        seenOnce()
        assertNull(tagger.tagFor(PAYEE, SMALL_AMOUNT))
    }
}
