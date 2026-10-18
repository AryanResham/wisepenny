package com.aryan.expensetracker.core

import android.app.Application
import com.aryan.expensetracker.pipeline.MessageProcessingScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ExpenseTrackerApp : Application() {

    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // builds the graph, then catches up on sms that arrived while the app was not running
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        scope.launch {
            container.inboxReader.catchUp()
            MessageProcessingScheduler.enqueue(this@ExpenseTrackerApp)
        }
    }
}
