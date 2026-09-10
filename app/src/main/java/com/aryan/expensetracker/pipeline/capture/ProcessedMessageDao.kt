package com.aryan.expensetracker.pipeline.capture

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aryan.expensetracker.core.db.entity.ProcessedMessageEntity

@Dao
interface ProcessedMessageDao {

    @Query("SELECT EXISTS(SELECT 1 FROM processed_messages WHERE messageHash = :messageHash)")
    suspend fun exists(messageHash: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ProcessedMessageEntity)
}
