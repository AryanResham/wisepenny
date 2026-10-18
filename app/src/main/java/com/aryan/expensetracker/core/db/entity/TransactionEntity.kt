package com.aryan.expensetracker.core.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    indices = [
        Index("referenceNumber"),
        Index(value = ["messageHash"], unique = true),
        Index("occurredAt"),
        Index("payeeNormalized"),
        Index("tag"),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountPaise: Long,
    val direction: String,
    val payeeRaw: String,
    val payeeNormalized: String,
    // one of AppConfig.TAGS, or null; untagged is a normal resting state and nothing nags
    val tag: String?,
    val description: String?,
    val occurredAt: Long,
    val capturedAt: Long,
    val referenceNumber: String?,
    val accountTail: String?,
    val source: String,
    val templateId: String?,
    val placeFromMessage: String?,
    val latitude: Double?,
    val longitude: Double?,
    // kept on device so a row can be checked against what actually arrived; never logged
    val rawMessage: String,
    val messageHash: String,
)
