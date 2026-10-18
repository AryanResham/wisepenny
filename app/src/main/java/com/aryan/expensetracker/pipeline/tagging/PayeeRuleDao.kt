package com.aryan.expensetracker.pipeline.tagging

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aryan.expensetracker.core.db.entity.PayeeRuleEntity

@Dao
interface PayeeRuleDao {

    @Query("SELECT * FROM payee_rules WHERE payeeNormalized = :payeeNormalized LIMIT 1")
    suspend fun findByPayee(payeeNormalized: String): PayeeRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: PayeeRuleEntity)

    // undo of a tag write has to remove the rule that write created, not just the tag
    @Query("DELETE FROM payee_rules WHERE payeeNormalized = :payeeNormalized")
    suspend fun deleteByPayee(payeeNormalized: String)
}
