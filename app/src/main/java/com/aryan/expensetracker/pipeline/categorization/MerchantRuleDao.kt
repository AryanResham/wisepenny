package com.aryan.expensetracker.pipeline.categorization

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.db.entity.MerchantRuleEntity

@Dao
interface MerchantRuleDao {

    @Query(
        "SELECT * FROM merchant_rules WHERE normalizedMerchant = :normalizedMerchant " +
            "AND direction = :direction LIMIT 1"
    )
    suspend fun findByMerchant(normalizedMerchant: String, direction: String): MerchantRuleEntity?

    // recent user corrections, used as examples in the categorization prompt
    @Query(
        "SELECT * FROM merchant_rules WHERE source = '" + AppConfig.RULE_SOURCE_USER +
            "' ORDER BY updatedAt DESC LIMIT :limit"
    )
    suspend fun recentUserRules(limit: Int): List<MerchantRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: MerchantRuleEntity)

    @Query("DELETE FROM merchant_rules WHERE categoryId = :categoryId")
    suspend fun deleteByCategory(categoryId: Long)
}
