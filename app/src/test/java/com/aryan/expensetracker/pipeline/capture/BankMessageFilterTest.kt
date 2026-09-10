package com.aryan.expensetracker.pipeline.capture

import com.aryan.expensetracker.core.config.AppConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BankMessageFilterTest {

    private val filter = BankMessageFilter()

    @Test
    fun `hdfc debit message passes`() {
        assertTrue(filter.looksLikeBankMessage("VM-HDFCBK", "Rs.450 debited from A/c XX1234 to SWIGGY"))
    }

    @Test
    fun `hdfc otp without a money word fails`() {
        assertFalse(filter.looksLikeBankMessage("VM-HDFCBK", "Your OTP is 4821"))
    }

    @Test
    fun `other bank with a money word fails`() {
        assertFalse(filter.looksLikeBankMessage("AX-ICICIB", "Rs.450 debited from A/c XX1234"))
    }

    @Test
    fun `over length message fails`() {
        val body = "Rs.450 debited " + "x".repeat(AppConfig.MAX_MESSAGE_LENGTH)
        assertFalse(filter.looksLikeBankMessage("VM-HDFCBK", body))
    }

    @Test
    fun `sender match is case insensitive`() {
        assertTrue(filter.looksLikeBankMessage("vm-hdfcbk", "Rs.450 debited from A/c XX1234"))
    }

    @Test
    fun `blank body fails`() {
        assertFalse(filter.looksLikeBankMessage("VM-HDFCBK", "   "))
    }
}
