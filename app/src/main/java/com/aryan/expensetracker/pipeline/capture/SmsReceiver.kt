package com.aryan.expensetracker.pipeline.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.aryan.expensetracker.core.ExpenseTrackerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "SmsReceiver"

class SmsReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // hands every incoming sms to MessageCapture, which owns all the filtering
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts == null || parts.isEmpty()) return

        // a long sms arrives split; only the joined body carries the whole transaction
        val body = buildString {
            for (part in parts) append(part.displayMessageBody.orEmpty())
        }
        val sender = parts[0].displayOriginatingAddress.orEmpty()
        val receivedAt = parts[0].timestampMillis

        // goAsync keeps the process alive past onReceive so the insert can finish
        val pendingResult = goAsync()
        val messageCapture = (context.applicationContext as ExpenseTrackerApp).container.messageCapture
        scope.launch {
            try {
                messageCapture.capture(sender, body, receivedAt)
            } catch (error: Exception) {
                Log.e(TAG, "receive failed: ${error.javaClass.simpleName}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
