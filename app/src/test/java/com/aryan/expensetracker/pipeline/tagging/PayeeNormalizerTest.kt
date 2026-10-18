package com.aryan.expensetracker.pipeline.tagging

import org.junit.Assert.assertEquals
import org.junit.Test

private val SWIGGY_FAMILY = listOf(
    "SWIGGY@YESPAY",
    "Swiggy Limited",
    "SWIGGY LTD",
    "swiggy",
    "Swiggy Private Limited",
    "SWIGGYINSTAMART@YESPAY",
    "swiggyinstamart",
    "Swiggy.Instamart",
)

class PayeeNormalizerTest {

    private val normalizer = PayeeNormalizer()

    // the measured requirement: the brand collapses but its sub-brand keeps its own key
    @Test
    fun theSwiggyFamilyCollapsesToTwoKeys() {
        val keys = SWIGGY_FAMILY.map { normalizer.normalize(it) }.toSet()

        assertEquals(setOf("SWIGGY", "SWIGGYINSTAMART"), keys)
    }

    @Test
    fun stripsTheVpaProvider() {
        assertEquals("9876543210", normalizer.normalize("9876543210@ybl"))
        assertEquals("SWIGGY", normalizer.normalize("SWIGGY@YESPAY"))
    }

    @Test
    fun dropsOnlyTrailingCorporateSuffixes() {
        assertEquals("ZERODHA BROKING", normalizer.normalize("ZERODHA BROKING LIMITED"))
        assertEquals("INDIA MART", normalizer.normalize("India Mart"))
    }

    @Test
    fun keepsAnOpaqueHandleAsItsOwnKey() {
        assertEquals("Q30103640", normalizer.normalize("Q30103640"))
        assertEquals("PAYTMQR281005", normalizer.normalize("paytmqr281005@paytm"))
    }
}
