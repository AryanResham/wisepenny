package com.aryan.expensetracker.core.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "pending_messages", indices = [Index(value = ["messageHash"], unique = true)])
data class PendingMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val attemptCount: Int,
    val messageHash: String,
    val latitude: Double?,
    val longitude: Double?,
)
