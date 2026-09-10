package com.aryan.expensetracker.pipeline.capture

import android.content.Context
import android.util.Log
import com.aryan.expensetracker.core.db.entity.PendingMessageEntity
import com.aryan.expensetracker.core.location.LocationProvider
import com.aryan.expensetracker.core.prefs.AppPrefs
import com.aryan.expensetracker.core.result.AppResult
import com.aryan.expensetracker.pipeline.MessageProcessingScheduler

private const val TAG = "MessageCapture"

enum class CaptureOutcome { QUEUED, SKIPPED }

class MessageCapture(
    private val pendingMessageDao: PendingMessageDao,
    private val processedMessageDao: ProcessedMessageDao,
    private val bankMessageFilter: BankMessageFilter,
    private val locationProvider: LocationProvider,
    private val appPrefs: AppPrefs,
    private val appContext: Context,
) {

    // the one door into the queue, used by both the receiver and the inbox reader; never throws
    suspend fun capture(sender: String, body: String, receivedAt: Long): AppResult<CaptureOutcome> {
        // Step 1: drop everything that is not a bank money message
        if (!bankMessageFilter.looksLikeBankMessage(sender, body)) return AppResult.Success(CaptureOutcome.SKIPPED)

        return try {
            // Step 2: a message a past run already finished with must not be queued again
            val messageHash = hashMessage(sender, body)
            if (processedMessageDao.exists(messageHash)) return AppResult.Success(CaptureOutcome.SKIPPED)

            // Step 3: the unique index on messageHash turns a re-delivered sms into a no-op insert
            val location = locationProvider.lastKnown()
            pendingMessageDao.insert(
                PendingMessageEntity(
                    sender = sender,
                    body = body,
                    receivedAt = receivedAt,
                    attemptCount = 0,
                    messageHash = messageHash,
                    latitude = location?.latitude,
                    longitude = location?.longitude,
                )
            )

            // Step 4: move the read mark forward so the next catch-up starts here
            if (receivedAt > appPrefs.getLastSeenSmsAt()) appPrefs.setLastSeenSmsAt(receivedAt)

            MessageProcessingScheduler.enqueue(appContext)
            AppResult.Success(CaptureOutcome.QUEUED)
        } catch (error: Exception) {
            Log.e(TAG, "capture failed: ${error.javaClass.simpleName}")
            AppResult.Failure("capture failed: ${error.javaClass.simpleName}")
        }
    }
}
