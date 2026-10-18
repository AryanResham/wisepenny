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

    // the month window the screen browses; the upper bound is the next month's first instant
    @Query(
        "SELECT * FROM transactions WHERE occurredAt >= :from AND occurredAt < :to " +
            "ORDER BY occurredAt DESC"
    )
    fun observeBetween(from: Long, to: Long): Flow<List<TransactionEntity>>

    // LIKE is case-insensitive for ascii; the caller escapes its own % and _ with a backslash first
    @Query(
        "SELECT * FROM transactions WHERE payeeRaw LIKE '%' || :query || '%' ESCAPE '\\' " +
            "OR description LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY occurredAt DESC"
    )
    fun observeSearch(query: String): Flow<List<TransactionEntity>>

    // the chat tools filter in sqlite, so one question costs its own rows and never the whole table
    @Query(
        "SELECT * FROM transactions WHERE occurredAt BETWEEN :from AND :to " +
            "AND (:tag IS NULL OR tag = :tag) ORDER BY occurredAt DESC LIMIT :limit"
    )
    suspend fun findForAgent(from: Long, to: Long, tag: String?, limit: Int): List<TransactionEntity>

    @Query(
        "SELECT * FROM transactions WHERE (payeeRaw LIKE '%' || :query || '%' " +
            "OR description LIKE '%' || :query || '%') AND occurredAt BETWEEN :from AND :to " +
            "ORDER BY occurredAt DESC LIMIT :limit"
    )
    suspend fun searchForAgent(
        query: String,
        from: Long,
        to: Long,
        limit: Int,
    ): List<TransactionEntity>

    // tells the empty state apart: no rows this month, or no rows ever
    @Query("SELECT COUNT(*) FROM transactions")
    fun observeTotalCount(): Flow<Int>

    @Query("UPDATE transactions SET tag = :tag, description = :description WHERE id IN (:ids)")
    suspend fun setTag(ids: List<Long>, tag: String?, description: String?)

    // bulk tagging leaves descriptions alone: they belong to individual rows, not to a selection
    @Query("UPDATE transactions SET tag = :tag WHERE id IN (:ids)")
    suspend fun setTagOnly(ids: List<Long>, tag: String)

    @Query("SELECT * FROM transactions WHERE id IN (:ids) ORDER BY occurredAt DESC")
    suspend fun byIds(ids: List<Long>): List<TransactionEntity>

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
