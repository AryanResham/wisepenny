package com.aryan.expensetracker.pipeline.extraction

import com.aryan.expensetracker.core.config.AppConfig
import java.util.Locale

class NonTransactionFilter(private val markers: List<String> = AppConfig.NON_TRANSACTION_MARKERS) {

    // keeps alerts, offers and reminders away from both the templates and the model; pure, no i/o
    fun isNonTransaction(body: String): Boolean {
        val lowercased = body.lowercase(Locale.ROOT)
        for (marker in markers) {
            if (lowercased.contains(marker.lowercase(Locale.ROOT))) return true
        }
        return false
    }
}
