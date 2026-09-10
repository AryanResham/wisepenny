package com.aryan.expensetracker.pipeline.capture

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aryan.expensetracker.core.db.entity.PendingMessageEntity

@Dao
interface PendingMessageDao {

    @Query("SELECT * FROM pending_messages ORDER BY receivedAt ASC")
    suspend fun getAll(): List<PendingMessageEntity>

    // IGNORE, not REPLACE: a re-delivered sms must not reset attemptCount
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: PendingMessageEntity)

    @Query("UPDATE pending_messages SET attemptCount = attemptCount + 1 WHERE id = :id")
    suspend fun incrementAttempts(id: Long)

    @Delete
    suspend fun delete(message: PendingMessageEntity)
}
