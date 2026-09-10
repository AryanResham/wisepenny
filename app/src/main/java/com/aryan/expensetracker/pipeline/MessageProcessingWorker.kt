package com.aryan.expensetracker.pipeline

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aryan.expensetracker.core.ExpenseTrackerApp
import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.db.entity.PendingMessageEntity
import com.aryan.expensetracker.core.db.entity.ReviewReason
import com.aryan.expensetracker.core.db.entity.TransactionEntity
import com.aryan.expensetracker.feature.transactions.TransactionDao
import com.aryan.expensetracker.pipeline.capture.PendingMessageDao

private const val TAG = "MessageProcessingWorker"
private const val STUB_MERCHANT = "Could not process"

// what one pass over the queue left behind; the worker turns it into a WorkManager result
data class DrainReport(val handled: Int, val stillWaiting: Int)

class MessageProcessingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val container = (applicationContext as ExpenseTrackerApp).container
            val report = drainQueue(
                container.pendingMessageDao,
                container.transactionDao,
                container.messagePipeline,
            )
            Log.i(TAG, "worker handled ${report.handled} messages, ${report.stillWaiting} still queued")
            if (report.stillWaiting > 0) Result.retry() else Result.success()
        } catch (error: Exception) {
            Log.e(TAG, "worker failed: ${error.javaClass.simpleName}")
            Result.retry()
        }
    }
}

// oldest first, so a purchase and its correction reach the pipeline in the order the bank sent them
suspend fun drainQueue(
    pendingMessageDao: PendingMessageDao,
    transactionDao: TransactionDao,
    messagePipeline: MessagePipeline,
): DrainReport {
    val queue = pendingMessageDao.getAll()
    var stillWaiting = 0

    for (message in queue) {
        when (val outcome = outcomeFor(messagePipeline, message)) {
            is PipelineOutcome.Saved -> pendingMessageDao.delete(message)
            is PipelineOutcome.Ignored -> pendingMessageDao.delete(message)
            // being offline is not a failed attempt, so attemptCount is deliberately left alone
            PipelineOutcome.WaitForNetwork -> stillWaiting++
            is PipelineOutcome.Failed -> {
                Log.w(TAG, "message ${message.id} failed: ${outcome.reason}")
                if (retryLater(pendingMessageDao, transactionDao, message)) stillWaiting++
            }
        }
    }
    return DrainReport(queue.size, stillWaiting)
}

// the pipeline already swallows its own errors; this is the belt to that pair of braces
private suspend fun outcomeFor(
    messagePipeline: MessagePipeline,
    message: PendingMessageEntity,
): PipelineOutcome {
    return try {
        messagePipeline.process(message)
    } catch (error: Exception) {
        PipelineOutcome.Failed(error.javaClass.simpleName)
    }
}

// true while the message is worth another run; out of attempts it becomes a review row, never a deletion
private suspend fun retryLater(
    pendingMessageDao: PendingMessageDao,
    transactionDao: TransactionDao,
    message: PendingMessageEntity,
): Boolean {
    if (message.attemptCount < AppConfig.MAX_PROCESSING_ATTEMPTS) {
        pendingMessageDao.incrementAttempts(message.id)
        return true
    }

    transactionDao.insert(stubTransaction(message))
    pendingMessageDao.delete(message)
    Log.w(TAG, "message ${message.id} gave up after ${message.attemptCount} attempts")
    return false
}

// stands in for the transaction we could not read, so the user sees it in Review instead of losing it
private fun stubTransaction(message: PendingMessageEntity): TransactionEntity = TransactionEntity(
    amountPaise = 0L,
    direction = AppConfig.DIRECTION_DEBIT,
    merchantRaw = STUB_MERCHANT,
    merchantNormalized = "",
    categoryId = null,
    referenceNumber = null,
    accountTail = null,
    occurredAt = message.receivedAt,
    capturedAt = System.currentTimeMillis(),
    source = AppConfig.RULE_SOURCE_LLM,
    needsReview = true,
    reviewReason = ReviewReason.COULD_NOT_PROCESS,
    messageHash = message.messageHash,
    rawMessage = message.body,
    latitude = message.latitude,
    longitude = message.longitude,
)
