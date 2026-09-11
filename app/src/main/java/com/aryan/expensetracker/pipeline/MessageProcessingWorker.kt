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
data class DrainReport(
    val saved: Int,
    val ignored: Int,
    val waiting: Int,
    val failed: Int,
    val gaveUp: Int,
) {
    val handled: Int get() = saved + ignored + waiting + failed + gaveUp

    // a failed message is queued for another run, so it keeps the worker retrying just like a wait
    val stillWaiting: Int get() = waiting + failed
}

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
            // the one log line per run: counts only, and no reason repeated from the pipeline
            Log.i(
                TAG,
                "drained ${report.handled}: saved ${report.saved}, ignored ${report.ignored}, " +
                    "waiting ${report.waiting}, failed ${report.failed}, gave up ${report.gaveUp}",
            )
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
    var saved = 0
    var ignored = 0
    var waiting = 0
    var failed = 0
    var gaveUp = 0

    for (message in queue) {
        when (outcomeFor(messagePipeline, message)) {
            is PipelineOutcome.Saved -> {
                pendingMessageDao.delete(message)
                saved++
            }
            is PipelineOutcome.Ignored -> {
                pendingMessageDao.delete(message)
                ignored++
            }
            // being offline is not a failed attempt, so attemptCount is deliberately left alone
            PipelineOutcome.WaitForNetwork -> waiting++
            // the pipeline already logged why; here the message is only counted
            is PipelineOutcome.Failed -> {
                if (retryLater(pendingMessageDao, transactionDao, message)) failed++ else gaveUp++
            }
        }
    }
    return DrainReport(saved, ignored, waiting, failed, gaveUp)
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
