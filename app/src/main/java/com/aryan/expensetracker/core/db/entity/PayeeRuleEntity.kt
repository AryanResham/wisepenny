package com.aryan.expensetracker.core.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

// the learning table: one row per payee the user has tagged, so it is never guessed again
@Entity(tableName = "payee_rules")
data class PayeeRuleEntity(
    @PrimaryKey val payeeNormalized: String,
    val tag: String,
    val updatedAt: Long,
)
