package com.aryan.expensetracker.pipeline

import android.util.Log
import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.db.entity.PendingMessageEntity
import com.aryan.expensetracker.core.db.entity.ProcessedMessageEntity
import com.aryan.expensetracker.core.db.entity.ReviewReason
import com.aryan.expensetracker.core.db.entity.TransactionEntity
import com.aryan.expensetracker.core.net.NetworkChecker
import com.aryan.expensetracker.core.result.AppResult
import com.aryan.expensetracker.feature.transactions.TransactionDao
import com.aryan.expensetracker.pipeline.capture.ProcessedMessageDao
import com.aryan.expensetracker.pipeline.categorization.LlmCategorizer
import com.aryan.expensetracker.pipeline.categorization.LocalCategorizer
import com.aryan.expensetracker.pipeline.categorization.MerchantNormalizer
import com.aryan.expensetracker.pipeline.dedup.DuplicateChecker
import com.aryan.expensetracker.pipeline.dedup.DuplicateDecision
import com.aryan.expensetracker.pipeline.extraction.ExtractionOutcome
import com.aryan.expensetracker.pipeline.extraction.LlmExtractor
import com.aryan.expensetracker.pipeline.extraction.TransactionDraft

private const val TAG = "MessagePipeline"

// groups the row write and its processed_messages mark; an interface so tests need no Room
fun interface DatabaseWriter {
    suspend fun inTransaction(block: suspend () -> Unit)
}

// the merchant key plus what it earned, carried together so the row builder takes one argument
private data class Categorization(
    val normalizedMerchant: String,
    val categoryId: Long?,
    val reviewReason: String,
)

class MessagePipeline(
    private val transactionDao: TransactionDao,
    private val processedMessageDao: ProcessedMessageDao,
    private val networkChecker: NetworkChecker,
    private val llmExtractor: LlmExtractor,
    private val merchantNormalizer: MerchantNormalizer,
    private val localCategorizer: LocalCategorizer,
    private val llmCategorizer: LlmCategorizer,
    private val duplicateChecker: DuplicateChecker,
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
        // Step 1: a message a past run finished with must never cost a second model call
        if (processedMessageDao.exists(message.messageHash)) return PipelineOutcome.Ignored("repost")

        // Step 2: v1 reads nothing without gemini, so no network waits rather than fails
        if (!networkChecker.isOnline()) return PipelineOutcome.WaitForNetwork

        // Step 3: the model turns the body into a draft, or says it was never money
        val extraction = llmExtractor.extract(message.body, message.receivedAt)
        val draft = when (extraction) {
            is ExtractionOutcome.NotATransaction -> {
                recordProcessed(message.messageHash, AppConfig.PROCESSED_IGNORED, null)
                Log.i(TAG, "message ${message.id} was not a transaction")
                return PipelineOutcome.Ignored("not a transaction")
            }
            is ExtractionOutcome.Failed -> {
                Log.w(TAG, "message ${message.id} extraction failed: ${extraction.reason}")
                return PipelineOutcome.Failed(extraction.reason)
            }
            is ExtractionOutcome.Transaction -> extraction.draft
        }

        // Step 4: normalize the merchant, then find its category locally before paying for a call
        val categorization = categorize(draft, message.id)

        // Step 5: insert or correct the existing row, and mark the message done in the same write
        val transactionId = store(message, draft, categorization)
        Log.i(TAG, "message ${message.id} saved as transaction $transactionId")
        return PipelineOutcome.Saved(transactionId)
    }

    // a learned rule wins; NEW_MERCHANT beats LLM_EXTRACTED beats NO_REFERENCE_NUMBER, one reason per row
    private suspend fun categorize(draft: TransactionDraft, messageId: Long): Categorization {
        val normalizedMerchant = merchantNormalizer.normalize(draft.merchantRaw)
        val learnedId = localCategorizer.categoryIdFor(normalizedMerchant, draft.direction)

        if (learnedId == null) {
            val guessedId = guessCategory(normalizedMerchant, draft.direction, messageId)
            return Categorization(normalizedMerchant, guessedId, ReviewReason.NEW_MERCHANT)
        }
        if (draft.referenceNumber.isNullOrBlank()) {
            return Categorization(normalizedMerchant, learnedId, ReviewReason.NO_REFERENCE_NUMBER)
        }
        return Categorization(normalizedMerchant, learnedId, ReviewReason.LLM_EXTRACTED)
    }

    // a failed guess is logged once here and then treated as no category; the row still gets saved
    private suspend fun guessCategory(
        normalizedMerchant: String,
        direction: String,
        messageId: Long,
    ): Long? {
        return when (val guess = llmCategorizer.categoryIdFor(normalizedMerchant, direction)) {
            is AppResult.Failure -> {
                Log.w(TAG, "message $messageId categorization failed: ${guess.reason}")
                null
            }
            is AppResult.Success -> guess.data
        }
    }

    // one transaction so a half-written replace can never lose the message's tombstone
    private suspend fun store(
        message: PendingMessageEntity,
        draft: TransactionDraft,
        categorization: Categorization,
    ): Long {
        val duplicate = duplicateChecker.check(draft.referenceNumber, draft.direction)
        var transactionId = 0L
        databaseWriter.inTransaction {
            transactionId = when (duplicate) {
                is DuplicateDecision.New -> transactionDao.insert(newRow(message, draft, categorization))
                is DuplicateDecision.Replaces -> correct(duplicate.existing, message, draft)
            }
            recordProcessed(message.messageHash, AppConfig.PROCESSED_SAVED, transactionId)
        }
        return transactionId
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
        categorization: Categorization,
    ): TransactionEntity = TransactionEntity(
        amountPaise = draft.amountPaise,
        direction = draft.direction,
        merchantRaw = draft.merchantRaw,
        merchantNormalized = categorization.normalizedMerchant,
        categoryId = categorization.categoryId,
        referenceNumber = draft.referenceNumber,
        accountTail = draft.accountTail,
        occurredAt = draft.occurredAt,
        capturedAt = System.currentTimeMillis(),
        source = draft.source,
        // every v1 row came out of the model, so every row gets a human look before it is trusted
        needsReview = true,
        reviewReason = categorization.reviewReason,
        messageHash = message.messageHash,
        rawMessage = message.body,
        latitude = message.latitude,
        longitude = message.longitude,
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
