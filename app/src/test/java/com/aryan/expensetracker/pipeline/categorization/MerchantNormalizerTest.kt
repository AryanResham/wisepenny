package com.aryan.expensetracker.pipeline.categorization

import com.aryan.expensetracker.core.config.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class MerchantNormalizerTest {

    private val normalizer = MerchantNormalizer(AppConfig.MERCHANT_NOISE_WORDS)

    @Test
    fun stripsUpiProvider() {
        assertEquals("SWIGGY", normalizer.normalize("swiggy@okhdfcbank"))
    }

    @Test
    fun dropsCorporateSuffixes() {
        assertEquals(
            "SWIGGY BUNDL TECHNOLOGIES",
            normalizer.normalize("SWIGGY BUNDL TECHNOLOGIES PVT LTD"),
        )
    }

    @Test
    fun keepsNumericUpiHandle() {
        assertEquals("9876543210", normalizer.normalize("9876543210@ybl"))
    }

    @Test
    fun collapsesExtraSpaces() {
        assertEquals("BLUE TOKAI", normalizer.normalize("  Blue   Tokai  "))
    }
}
