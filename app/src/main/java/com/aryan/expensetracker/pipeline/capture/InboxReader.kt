package com.aryan.expensetracker.pipeline.capture

import android.content.Context
import android.net.Uri
import android.util.Log
import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.prefs.AppPrefs
import com.aryan.expensetracker.core.result.AppResult
import com.aryan.expensetracker.pipeline.MessageProcessingScheduler
import java.util.concurrent.TimeUnit

private const val TAG = "InboxReader"

private val INBOX_URI: Uri = Uri.parse("content://sms/inbox")
private const val COLUMN_ADDRESS = "address"
private const val COLUMN_BODY = "body"
private const val COLUMN_DATE = "date"
private const val NEWER_THAN = "date > ?"
private const val OLDEST_FIRST = "date ASC"

class InboxReader(
    private val context: Context,
    private val messageCapture: MessageCapture,
    private val appPrefs: AppPrefs,
) {

    // first run only: walk the recent past so the app starts with history instead of nothing
    suspend fun backfill(): AppResult<Int> = readAndSchedule(oldestReadableTime())

    // every launch: pick up what arrived while the app was not running to receive it
    suspend fun catchUp(): AppResult<Int> {
        // before onboarding the read mark is still 0, and catching up would scan the whole inbox
        if (!appPrefs.isBackfillDone()) return AppResult.Success(0)
        return readAndSchedule(maxOf(appPrefs.getLastSeenSmsAt(), oldestReadableTime()))
    }

    // one worker run per scan, not per message, and it runs even if the scan died half way
    private suspend fun readAndSchedule(sinceMillis: Long): AppResult<Int> {
        val result = readSince(sinceMillis)
        MessageProcessingScheduler.enqueue(context)
        return result
    }

    private fun oldestReadableTime(): Long =
        System.currentTimeMillis() - TimeUnit.DAYS.toMillis(AppConfig.BACKFILL_DAYS.toLong())

    // reads oldest first so lastSeenSmsAt only ever moves forward; returns how many rows it looked at
    private suspend fun readSince(sinceMillis: Long): AppResult<Int> {
        var rowsSeen = 0
        try {
            val cursor = context.contentResolver.query(
                INBOX_URI,
                arrayOf(COLUMN_ADDRESS, COLUMN_BODY, COLUMN_DATE),
                NEWER_THAN,
                arrayOf(sinceMillis.toString()),
                OLDEST_FIRST,
            ) ?: return AppResult.Failure("sms inbox is unavailable")

            cursor.use { rows ->
                val addressColumn = rows.getColumnIndexOrThrow(COLUMN_ADDRESS)
                val bodyColumn = rows.getColumnIndexOrThrow(COLUMN_BODY)
                val dateColumn = rows.getColumnIndexOrThrow(COLUMN_DATE)
                while (rows.moveToNext()) {
                    rowsSeen++
                    messageCapture.capture(
                        rows.getString(addressColumn).orEmpty(),
                        rows.getString(bodyColumn).orEmpty(),
                        rows.getLong(dateColumn),
                    )
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "inbox read stopped after $rowsSeen rows: ${error.javaClass.simpleName}")
            return AppResult.Failure("inbox read failed: ${error.javaClass.simpleName}")
        }

        Log.i(TAG, "inbox read looked at $rowsSeen rows")
        return AppResult.Success(rowsSeen)
    }
}
