package com.aryan.expensetracker.pipeline

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.aryan.expensetracker.core.config.AppConfig
import java.util.concurrent.TimeUnit

private const val TAG = "MessageProcessingScheduler"
private const val UNIQUE_WORK_NAME = "message-processing"

object MessageProcessingScheduler {

    // no network constraint: the worker drains the queue and checks connectivity only when it needs it
    fun enqueue(context: Context) {
        try {
            val request = OneTimeWorkRequestBuilder<MessageProcessingWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    AppConfig.WORKER_BACKOFF_SECONDS,
                    TimeUnit.SECONDS,
                )
                .build()

            // KEEP, not APPEND: one run drains the whole queue, so a second request would add nothing
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        } catch (error: Exception) {
            Log.e(TAG, "could not enqueue processing work: ${error.javaClass.simpleName}")
        }
    }
}
