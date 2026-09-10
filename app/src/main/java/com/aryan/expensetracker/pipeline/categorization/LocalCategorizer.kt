package com.aryan.expensetracker.pipeline.categorization

class LocalCategorizer(private val merchantRuleDao: MerchantRuleDao) {

    // what the user taught wins; direction is part of the key so a refund never inherits a spend rule
    suspend fun categoryIdFor(normalizedMerchant: String, direction: String): Long? {
        if (normalizedMerchant.isBlank()) return null
        return merchantRuleDao.findByMerchant(normalizedMerchant, direction)?.categoryId
    }
}
