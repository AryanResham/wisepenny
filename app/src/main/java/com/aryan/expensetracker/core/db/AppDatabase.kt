package com.aryan.expensetracker.core.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.db.entity.PayeeRuleEntity
import com.aryan.expensetracker.core.db.entity.PendingMessageEntity
import com.aryan.expensetracker.core.db.entity.ProcessedMessageEntity
import com.aryan.expensetracker.core.db.entity.TransactionEntity
import com.aryan.expensetracker.feature.transactions.TransactionDao
import com.aryan.expensetracker.pipeline.capture.PendingMessageDao
import com.aryan.expensetracker.pipeline.capture.ProcessedMessageDao
import com.aryan.expensetracker.pipeline.tagging.PayeeRuleDao

@Database(
    entities = [
        TransactionEntity::class,
        PayeeRuleEntity::class,
        PendingMessageEntity::class,
        ProcessedMessageEntity::class,
    ],
    version = AppConfig.DATABASE_VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun payeeRuleDao(): PayeeRuleDao
    abstract fun pendingMessageDao(): PendingMessageDao
    abstract fun processedMessageDao(): ProcessedMessageDao
}
