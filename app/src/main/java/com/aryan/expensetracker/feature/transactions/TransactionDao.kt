package com.aryan.expensetracker.feature.transactions

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.aryan.expensetracker.core.db.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    // same-transaction check; direction is matched too so a refund never collapses into its payment
    @Query(
        "SELECT * FROM transactions WHERE referenceNumber = :reference " +
            "AND direction = :direction AND occurredAt >= :since LIMIT 1"
    )
    suspend fun findByReference(reference: String, direction: String, since: Long): TransactionEntity?

    // how often this payee has been seen before, which is what decides a Daily spends auto-tag
    @Query("SELECT COUNT(*) FROM transactions WHERE payeeNormalized = :payeeNormalized")
    suspend fun countByPayee(payeeNormalized: String): Int

    @Insert
    suspend fun insert(transaction: TransactionEntity): Long

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Delete
    suspend fun delete(transaction: TransactionEntity)
}
