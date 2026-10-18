package com.aryan.expensetracker.pipeline

import com.aryan.expensetracker.core.db.entity.PayeeRuleEntity
import com.aryan.expensetracker.core.db.entity.PendingMessageEntity
import com.aryan.expensetracker.core.db.entity.ProcessedMessageEntity
import com.aryan.expensetracker.core.db.entity.TransactionEntity
import com.aryan.expensetracker.core.result.AppResult
import com.aryan.expensetracker.feature.transactions.TransactionDao
import com.aryan.expensetracker.pipeline.capture.PendingMessageDao
import com.aryan.expensetracker.pipeline.capture.ProcessedMessageDao
import com.aryan.expensetracker.pipeline.tagging.PayeeRuleDao
import com.aryan.expensetracker.core.llm.LlmClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// in-memory stand-ins so the pipeline can be driven end to end without Room, a network or a phone

// counts its calls, so a test can prove a template match never reached the model
class FakeLlmClient(private val extraction: AppResult<String>) : LlmClient {
    var extractionCalls: Int = 0

    override suspend fun generateJson(
        systemInstruction: String,
        userText: String,
        responseSchema: String,
    ): AppResult<String> {
        extractionCalls++
        return extraction
    }
}

class FakeTransactionDao : TransactionDao {
    val rows: MutableList<TransactionEntity> = mutableListOf()
    private var nextId = 1L

    override fun observeAll(): Flow<List<TransactionEntity>> = flowOf(rows)

    override suspend fun findByReference(
        reference: String,
        direction: String,
        since: Long,
    ): TransactionEntity? = rows.firstOrNull {
        it.referenceNumber == reference && it.direction == direction && it.occurredAt >= since
    }

    override suspend fun countByPayee(payeeNormalized: String): Int =
        rows.count { it.payeeNormalized == payeeNormalized }

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
}

class FakeProcessedMessageDao : ProcessedMessageDao {
    val records: MutableMap<String, ProcessedMessageEntity> = mutableMapOf()

    override suspend fun exists(messageHash: String): Boolean = records.containsKey(messageHash)

    override suspend fun insert(message: ProcessedMessageEntity) {
        records[message.messageHash] = message
    }
}

class FakePayeeRuleDao(rules: List<PayeeRuleEntity> = emptyList()) : PayeeRuleDao {
    private val stored = rules.associateBy { it.payeeNormalized }.toMutableMap()

    override suspend fun findByPayee(payeeNormalized: String): PayeeRuleEntity? =
        stored[payeeNormalized]

    override suspend fun upsert(rule: PayeeRuleEntity) {
        stored[rule.payeeNormalized] = rule
    }
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
