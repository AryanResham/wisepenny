package com.aryan.expensetracker.pipeline.dedup

import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.feature.transactions.TransactionDao

class DuplicateChecker(private val transactionDao: TransactionDao) {

    // two messages sharing a reference describe one purchase; without a reference we cannot tell
    suspend fun check(referenceNumber: String?, direction: String): DuplicateDecision {
        if (referenceNumber.isNullOrBlank()) return DuplicateDecision.New

        val since = System.currentTimeMillis() - AppConfig.DEDUP_LOOKBACK_MS
        val existing = transactionDao.findByReference(referenceNumber, direction, since)
            ?: return DuplicateDecision.New
        return DuplicateDecision.Replaces(existing)
    }
}
