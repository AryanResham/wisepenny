package com.aryan.expensetracker.feature.common

import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.db.entity.MerchantRuleEntity
import com.aryan.expensetracker.pipeline.categorization.MerchantRuleDao

// the learning loop both screens share: after this write the merchant never needs the model again
suspend fun rememberMerchant(
    merchantRuleDao: MerchantRuleDao,
    normalizedMerchant: String,
    direction: String,
    categoryId: Long,
) {
    if (normalizedMerchant.isBlank()) return
    merchantRuleDao.upsert(
        MerchantRuleEntity(
            normalizedMerchant = normalizedMerchant,
            direction = direction,
            categoryId = categoryId,
            source = AppConfig.RULE_SOURCE_USER,
            updatedAt = System.currentTimeMillis(),
        )
    )
}
