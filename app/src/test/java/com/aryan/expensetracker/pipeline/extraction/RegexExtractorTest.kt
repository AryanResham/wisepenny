package com.aryan.expensetracker.pipeline.extraction

import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.pipeline.capture.BankMessageFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MINIMUM_COVERAGE_PERCENT = 90.0

private const val A_SENT_MESSAGE =
    "Sent Rs.287.00 From HDFC Bank A/C *0904 To Amazon India On 12/05/25 Ref 131464924973"
private const val AN_OTP_MESSAGE =
    "123456 is your OTP for login. Do not share it with anyone."
private const val A_DECLINED_MESSAGE =
    "Your card transaction of Rs.500 was DECLINED due to insufficient funds."
private const val A_PROMOTIONAL_MESSAGE =
    "Dear Customer, you can apply now for a pre-approved loan. Check your balance online."

class RegexExtractorTest {

    private val extractor = RegexExtractor(realTemplates())
    private val bankMessageFilter = BankMessageFilter()
    private val nonTransactionFilter = NonTransactionFilter()

    @Test
    fun loadsEveryShippedTemplate() {
        assertEquals(8, realTemplates().size)
    }

    @Test
    fun readsAmountDirectionPayeeAndReferenceFromASentMessage() {
        val draft = extractor.extract(A_SENT_MESSAGE, 0L)

        assertNotNull(draft)
        assertEquals(28700L, draft?.amountPaise)
        assertEquals(AppConfig.DIRECTION_DEBIT, draft?.direction)
        assertEquals("Amazon India", draft?.payeeRaw)
        assertEquals("131464924973", draft?.referenceNumber)
        assertEquals("0904", draft?.accountTail)
        assertEquals("upi_sent", draft?.templateId)
        assertEquals(AppConfig.SOURCE_REGEX, draft?.source)
    }

    @Test
    fun treatsOtpDeclinedAndPromotionalMessagesAsNonTransactions() {
        for (body in listOf(AN_OTP_MESSAGE, A_DECLINED_MESSAGE, A_PROMOTIONAL_MESSAGE)) {
            assertTrue(nonTransactionFilter.isNonTransaction(body))
            assertNull(extractor.extract(body, 0L))
        }
    }

    // the number this task exists for; the corpus is read but never printed
    @Test
    fun coversAtLeastNinetyPercentOfTheRealCorpus() {
        val corpus = loadSampleCorpus()
        if (corpus == null) {
            println("samples.json is absent, so corpus coverage was not measured")
            return
        }

        var considered = 0
        var extracted = 0
        for (message in corpus) {
            if (!bankMessageFilter.looksLikeBankMessage(message.addr, message.body)) continue
            if (nonTransactionFilter.isNonTransaction(message.body)) continue
            considered++
            if (extractor.extract(message.body, message.date) != null) extracted++
        }

        val coverage = 100.0 * extracted / considered
        println("regex coverage: $extracted of $considered messages = ${"%.2f".format(coverage)}%")
        assertTrue("coverage $coverage% is under $MINIMUM_COVERAGE_PERCENT%", coverage >= MINIMUM_COVERAGE_PERCENT)
    }
}
