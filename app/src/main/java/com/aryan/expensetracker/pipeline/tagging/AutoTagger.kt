package com.aryan.expensetracker.pipeline.tagging

import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.feature.transactions.TransactionDao

class AutoTagger(
    private val payeeRuleDao: PayeeRuleDao,
    private val transactionDao: TransactionDao,
) {

    // the learned rule first because it is free and certain; null is a normal answer, not a failure
    suspend fun tagFor(payeeNormalized: String, amountPaise: Long): String? {
        val learned = payeeRuleDao.findByPayee(payeeNormalized)
        if (learned != null) return learned.tag

        // a tiny spend at a payee seen once stays out of the way without ever being asked about
        if (amountPaise >= AppConfig.DAILY_SPEND_MAX_PAISE) return null
        if (transactionDao.countByPayee(payeeNormalized) != AppConfig.PAYEE_SEEN_ONCE) return null
        return AppConfig.TAG_DAILY_SPENDS
    }
}
