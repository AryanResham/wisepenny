package com.aryan.expensetracker.core.db.entity

import androidx.room.Entity

// direction is part of the key on purpose: a Swiggy refund must not inherit Swiggy's Food rule
@Entity(tableName = "merchant_rules", primaryKeys = ["normalizedMerchant", "direction"])
data class MerchantRuleEntity(
    val normalizedMerchant: String,
    val direction: String,
    val categoryId: Long,
    val source: String,
    val updatedAt: Long,
)
