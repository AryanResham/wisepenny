package com.aryan.expensetracker.pipeline

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aryan.expensetracker.core.ExpenseTrackerApp

private const val TAG = "MessageProcessingWorker"

class MessageProcessingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    // task 05 replaces this body with the pipeline
    override suspend fun doWork(): Result {
        return try {
            val container = (applicationContext as ExpenseTrackerApp).container
            val pendingCount = container.pendingMessageDao.getAll().size
            Log.i(TAG, "worker ran with $pendingCount pending messages")
            Result.success()
        } catch (error: Exception) {
            Log.e(TAG, "worker failed: ${error.javaClass.simpleName}")
            Result.success()
        }
    }
}
