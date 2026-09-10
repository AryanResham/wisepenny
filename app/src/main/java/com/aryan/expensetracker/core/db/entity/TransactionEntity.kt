package com.aryan.expensetracker.core.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    indices = [
        Index("referenceNumber"),
        Index(value = ["messageHash"], unique = true),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountPaise: Long,
    val direction: String,
    val merchantRaw: String,
    val merchantNormalized: String,
    val categoryId: Long?,
    val referenceNumber: String?,
    val accountTail: String?,
    val occurredAt: Long,
    val capturedAt: Long,
    val source: String,
    val needsReview: Boolean,
    val reviewReason: String?,
    val messageHash: String,
    // kept on device so the review screen can show what actually arrived; never logged
    val rawMessage: String,
    val latitude: Double?,
    val longitude: Double?,
)
