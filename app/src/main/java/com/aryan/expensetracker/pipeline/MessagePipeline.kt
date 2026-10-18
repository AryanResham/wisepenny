package com.aryan.expensetracker.pipeline

import android.util.Log
import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.db.entity.PendingMessageEntity
import com.aryan.expensetracker.core.db.entity.ProcessedMessageEntity
import com.aryan.expensetracker.core.db.entity.TransactionEntity
import com.aryan.expensetracker.core.net.NetworkChecker
import com.aryan.expensetracker.feature.transactions.TransactionDao
import com.aryan.expensetracker.pipeline.capture.ProcessedMessageDao
import com.aryan.expensetracker.pipeline.extraction.ExtractionOutcome
import com.aryan.expensetracker.pipeline.extraction.LlmFallbackExtractor
import com.aryan.expensetracker.pipeline.extraction.NonTransactionFilter
import com.aryan.expensetracker.pipeline.extraction.RegexExtractor
import com.aryan.expensetracker.pipeline.extraction.TransactionDraft
import com.aryan.expensetracker.pipeline.tagging.AutoTagger
import com.aryan.expensetracker.pipeline.tagging.PayeeNormalizer

private const val TAG = "MessagePipeline"

// groups the row write and its processed_messages mark; an interface so tests need no Room
fun interface DatabaseWriter {
    suspend fun inTransaction(block: suspend () -> Unit)
}

// the payee key plus what it earned, carried together so the row builder takes one argument
private data class Tagging(val payeeNormalized: String, val tag: String?)

class MessagePipeline(
    private val transactionDao: TransactionDao,
    private val processedMessageDao: ProcessedMessageDao,
    private val networkChecker: NetworkChecker,
    private val nonTransactionFilter: NonTransactionFilter,
    private val regexExtractor: RegexExtractor,
    private val llmFallbackExtractor: LlmFallbackExtractor,
    private val payeeNormalizer: PayeeNormalizer,
    private val autoTagger: AutoTagger,
    private val databaseWriter: DatabaseWriter,
) {

    // one message in, one outcome out; nothing below is allowed to throw past this point
    suspend fun process(message: PendingMessageEntity): PipelineOutcome {
        return try {
            runSteps(message)
        } catch (error: Exception) {
            Log.e(TAG, "message ${message.id} threw: ${error.javaClass.simpleName}")
            PipelineOutcome.Failed(error.javaClass.simpleName)
        }
    }

    private suspend fun runSteps(message: PendingMessageEntity): PipelineOutcome {
        // Step 1: a message a past run finished with must never cost a second look
        if (processedMessageDao.exists(message.messageHash)) return PipelineOutcome.Ignored("repost")

        // Step 2: alerts and offers are closed out here, before any template or model sees them
        if (nonTransactionFilter.isNonTransaction(message.body)) return ignore(message)

        // Step 3: the templates are the fast path and cost nothing
        val draft = regexExtractor.extract(message.body, message.receivedAt)
            ?: return fallBackToModel(message)

        return save(message, draft)
    }

    // Step 4: no template fit, so the model stands in; offline it waits rather than burning an attempt
    private suspend fun fallBackToModel(message: PendingMessageEntity): PipelineOutcome {
        if (!networkChecker.isOnline()) return PipelineOutcome.WaitForNetwork

        return when (val outcome = llmFallbackExtractor.extract(message.body, message.receivedAt)) {
            is ExtractionOutcome.NotATransaction -> ignore(message)
            is ExtractionOutcome.Failed -> {
                // google asking us to slow down is not this message's fault, so it keeps its attempts
                if (isQuotaReason(outcome.reason)) {
                    Log.i(TAG, "message ${message.id} waiting on quota: ${outcome.reason}")
                    return PipelineOutcome.WaitForQuota
                }
                Log.w(TAG, "message ${message.id} extraction failed: ${outcome.reason}")
                PipelineOutcome.Failed(outcome.reason)
            }
            is ExtractionOutcome.Transaction -> save(message, outcome.draft)
        }
    }

    private fun isQuotaReason(reason: String): Boolean =
        reason == AppConfig.LLM_REASON_RATE_LIMITED || reason == AppConfig.LLM_REASON_DAILY_CAP

    private suspend fun ignore(message: PendingMessageEntity): PipelineOutcome {
        recordProcessed(message.messageHash, AppConfig.PROCESSED_IGNORED, null)
        Log.i(TAG, "message ${message.id} was not a transaction")
        return PipelineOutcome.Ignored("not a transaction")
    }

    // Steps 5-7: key the payee, auto-tag it, then write the row and its mark in one transaction
    private suspend fun save(
        message: PendingMessageEntity,
        draft: TransactionDraft,
    ): PipelineOutcome {
        val payeeNormalized = payeeNormalizer.normalize(draft.payeeRaw)
        val tagging = Tagging(payeeNormalized, autoTagger.tagFor(payeeNormalized, draft.amountPaise))
        val existing = findSupersededRow(draft)

        var transactionId = 0L
        databaseWriter.inTransaction {
            transactionId = if (existing == null) {
                transactionDao.insert(newRow(message, draft, tagging))
            } else {
                correct(existing, message, draft)
            }
            recordProcessed(message.messageHash, AppConfig.PROCESSED_SAVED, transactionId)
        }
        Log.i(TAG, "message ${message.id} saved as transaction $transactionId from ${draft.source}")
        return PipelineOutcome.Saved(transactionId)
    }

    // two messages sharing a reference describe one purchase; without a reference we cannot tell
    private suspend fun findSupersededRow(draft: TransactionDraft): TransactionEntity? {
        val reference = draft.referenceNumber
        if (reference.isNullOrBlank()) return null

        val since = System.currentTimeMillis() - AppConfig.DEDUP_LOOKBACK_MS
        return transactionDao.findByReference(reference, draft.direction, since)
    }

    // a later bank message about the same purchase fixes the facts and nothing the user decided
    private suspend fun correct(
        existing: TransactionEntity,
        message: PendingMessageEntity,
        draft: TransactionDraft,
    ): Long {
        transactionDao.update(
            existing.copy(
                amountPaise = draft.amountPaise,
                occurredAt = draft.occurredAt,
                rawMessage = message.body,
                messageHash = message.messageHash,
                accountTail = draft.accountTail,
            )
        )
        // the row stopped carrying the old hash, so that message needs its own mark to stay ignored
        recordProcessed(existing.messageHash, AppConfig.PROCESSED_SUPERSEDED, existing.id)
        return existing.id
    }

    private fun newRow(
        message: PendingMessageEntity,
        draft: TransactionDraft,
        tagging: Tagging,
    ): TransactionEntity = TransactionEntity(
        amountPaise = draft.amountPaise,
        direction = draft.direction,
        payeeRaw = draft.payeeRaw,
        payeeNormalized = tagging.payeeNormalized,
        tag = tagging.tag,
        description = null,
        occurredAt = draft.occurredAt,
        capturedAt = System.currentTimeMillis(),
        referenceNumber = draft.referenceNumber,
        accountTail = draft.accountTail,
        source = draft.source,
        templateId = draft.templateId,
        placeFromMessage = draft.placeFromMessage,
        latitude = message.latitude,
        longitude = message.longitude,
        rawMessage = message.body,
        messageHash = message.messageHash,
    )

    private suspend fun recordProcessed(messageHash: String, outcome: String, transactionId: Long?) {
        processedMessageDao.insert(
            ProcessedMessageEntity(
                messageHash = messageHash,
                outcome = outcome,
                transactionId = transactionId,
                processedAt = System.currentTimeMillis(),
            )
        )
    }
}
