package com.aryan.expensetracker.core.money

import com.aryan.expensetracker.core.config.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    @Test
    fun parsesGroupedAmount() {
        assertEquals(123450L, parseAmountToPaise("1,234.50"))
    }

    @Test
    fun parsesWholeRupees() {
        assertEquals(45000L, parseAmountToPaise("450"))
    }

    @Test
    fun parsesSingleRupee() {
        assertEquals(100L, parseAmountToPaise("1.00"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(parseAmountToPaise("not a number"))
    }

    @Test
    fun formatsDebitWithMinus() {
        assertEquals("-₹450.00", formatPaise(45000L, AppConfig.DIRECTION_DEBIT))
    }

    @Test
    fun formatsCreditWithPlus() {
        assertEquals("+₹1234.50", formatPaise(123450L, AppConfig.DIRECTION_CREDIT))
    }
}
