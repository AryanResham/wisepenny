package com.aryan.expensetracker.core.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

// remembers every message we finished with, so a re-delivered sms never costs a second gemini call
@Entity(tableName = "processed_messages")
data class ProcessedMessageEntity(
    @PrimaryKey val messageHash: String,
    val outcome: String,
    val transactionId: Long?,
    val processedAt: Long,
)
