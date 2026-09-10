package com.aryan.expensetracker.core.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aryan.expensetracker.core.db.entity.CategoryEntity
import com.aryan.expensetracker.core.db.entity.MerchantRuleEntity
import com.aryan.expensetracker.core.db.entity.PendingMessageEntity
import com.aryan.expensetracker.core.db.entity.ProcessedMessageEntity
import com.aryan.expensetracker.core.db.entity.TransactionEntity
import com.aryan.expensetracker.feature.categories.CategoryDao
import com.aryan.expensetracker.feature.transactions.TransactionDao
import com.aryan.expensetracker.pipeline.capture.PendingMessageDao
import com.aryan.expensetracker.pipeline.capture.ProcessedMessageDao
import com.aryan.expensetracker.pipeline.categorization.MerchantRuleDao

@Database(
    entities = [
        TransactionEntity::class,
        CategoryEntity::class,
        MerchantRuleEntity::class,
        PendingMessageEntity::class,
        ProcessedMessageEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun merchantRuleDao(): MerchantRuleDao
    abstract fun pendingMessageDao(): PendingMessageDao
    abstract fun processedMessageDao(): ProcessedMessageDao
}
