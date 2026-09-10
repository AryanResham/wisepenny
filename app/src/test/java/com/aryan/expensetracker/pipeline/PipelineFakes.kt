package com.aryan.expensetracker.pipeline

import com.aryan.expensetracker.core.db.entity.CategoryEntity
import com.aryan.expensetracker.core.db.entity.MerchantRuleEntity
import com.aryan.expensetracker.core.db.entity.PendingMessageEntity
import com.aryan.expensetracker.core.db.entity.ProcessedMessageEntity
import com.aryan.expensetracker.core.db.entity.TransactionEntity
import com.aryan.expensetracker.core.llm.GeminiSchemas
import com.aryan.expensetracker.core.llm.LlmClient
import com.aryan.expensetracker.core.result.AppResult
import com.aryan.expensetracker.feature.categories.CategoryDao
import com.aryan.expensetracker.feature.transactions.TransactionDao
import com.aryan.expensetracker.pipeline.capture.PendingMessageDao
import com.aryan.expensetracker.pipeline.capture.ProcessedMessageDao
import com.aryan.expensetracker.pipeline.categorization.MerchantRuleDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// in-memory stand-ins so the pipeline can be driven end to end without Room, a network or a phone

// the schema tells the two prompts apart, so a test can count extraction and categorization calls
class FakeLlmClient(
    private val extraction: AppResult<String>,
    private val categorization: AppResult<String> = AppResult.Success("""{"category":null}"""),
) : LlmClient {
    var extractionCalls: Int = 0
    var categorizationCalls: Int = 0

    override suspend fun generateJson(
        systemInstruction: String,
        userText: String,
        responseSchema: String,
    ): AppResult<String> {
        if (responseSchema == GeminiSchemas.CATEGORIZATION) {
            categorizationCalls++
            return categorization
        }
        extractionCalls++
        return extraction
    }
}

class FakeTransactionDao : TransactionDao {
    val rows: MutableList<TransactionEntity> = mutableListOf()
    private var nextId = 1L

    override fun observeAll(): Flow<List<TransactionEntity>> = flowOf(rows)

    override fun observeNeedingReview(): Flow<List<TransactionEntity>> =
        flowOf(rows.filter { it.needsReview })

    override suspend fun findByReference(
        reference: String,
        direction: String,
        since: Long,
    ): TransactionEntity? = rows.firstOrNull {
        it.referenceNumber == reference && it.direction == direction && it.occurredAt >= since
    }

    override suspend fun insert(transaction: TransactionEntity): Long {
        rows.add(transaction.copy(id = nextId))
        nextId++
        return nextId - 1
    }

    override suspend fun update(transaction: TransactionEntity) {
        val index = rows.indexOfFirst { it.id == transaction.id }
        if (index >= 0) rows[index] = transaction
    }

    override suspend fun delete(transaction: TransactionEntity) {
        rows.removeAll { it.id == transaction.id }
    }

    override suspend fun reassignCategory(fromCategoryId: Long, toCategoryId: Long) = Unit
}

class FakeProcessedMessageDao : ProcessedMessageDao {
    val records: MutableMap<String, ProcessedMessageEntity> = mutableMapOf()

    override suspend fun exists(messageHash: String): Boolean = records.containsKey(messageHash)

    override suspend fun insert(message: ProcessedMessageEntity) {
        records[message.messageHash] = message
    }
}

class FakeMerchantRuleDao(private val rules: List<MerchantRuleEntity>) : MerchantRuleDao {
    override suspend fun findByMerchant(
        normalizedMerchant: String,
        direction: String,
    ): MerchantRuleEntity? = rules.firstOrNull {
        it.normalizedMerchant == normalizedMerchant && it.direction == direction
    }

    override suspend fun recentUserRules(limit: Int): List<MerchantRuleEntity> = rules.take(limit)
    override suspend fun upsert(rule: MerchantRuleEntity) = Unit
    override suspend fun deleteByCategory(categoryId: Long) = Unit
}

class FakeCategoryDao(private val categories: List<CategoryEntity>) : CategoryDao {
    override fun observeAll(): Flow<List<CategoryEntity>> = flowOf(categories)
    override suspend fun getAll(): List<CategoryEntity> = categories
    override suspend fun findIdByName(name: String): Long? =
        categories.firstOrNull { it.name == name }?.id

    override suspend fun count(): Int = categories.size
    override suspend fun insert(category: CategoryEntity): Long = 0L
    override suspend fun update(category: CategoryEntity) = Unit
    override suspend fun delete(category: CategoryEntity) = Unit
}

class FakePendingMessageDao(messages: List<PendingMessageEntity>) : PendingMessageDao {
    val queue: MutableList<PendingMessageEntity> = messages.toMutableList()

    override suspend fun getAll(): List<PendingMessageEntity> = queue.sortedBy { it.receivedAt }
    override fun observeCount(): Flow<Int> = flowOf(queue.size)
    override suspend fun insert(message: PendingMessageEntity) {
        queue.add(message)
    }

    override suspend fun incrementAttempts(id: Long) {
        val index = queue.indexOfFirst { it.id == id }
        if (index >= 0) queue[index] = queue[index].copy(attemptCount = queue[index].attemptCount + 1)
    }

    override suspend fun delete(message: PendingMessageEntity) {
        queue.removeAll { it.id == message.id }
    }
}
