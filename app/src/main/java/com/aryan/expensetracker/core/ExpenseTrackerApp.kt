package com.aryan.expensetracker.core

import android.app.Application
import android.util.Log
import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.db.entity.CategoryEntity
import com.aryan.expensetracker.feature.categories.CategoryDao
import com.aryan.expensetracker.pipeline.MessageProcessingScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "ExpenseTrackerApp"

class ExpenseTrackerApp : Application() {

    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // builds the graph, then seeds categories off the main thread so startup never blocks on disk
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        scope.launch {
            try {
                seedDefaultCategoriesIfEmpty(container.categoryDao)
            } catch (error: Exception) {
                Log.e(TAG, "category seeding failed: ${error.javaClass.simpleName}")
            }
        }
        // catches up on sms that arrived while the app was not running, then kicks the worker
        scope.launch {
            container.inboxReader.catchUp()
            MessageProcessingScheduler.enqueue(this@ExpenseTrackerApp)
        }
    }
}

// inserts the starting categories once, on a fresh install
private suspend fun seedDefaultCategoriesIfEmpty(categoryDao: CategoryDao) {
    if (categoryDao.count() > 0) return

    var order = 0
    for (name in AppConfig.DEFAULT_EXPENSE_CATEGORIES) {
        categoryDao.insert(newCategory(name, AppConfig.CATEGORY_KIND_EXPENSE, order))
        order++
    }
    for (name in AppConfig.DEFAULT_INCOME_CATEGORIES) {
        categoryDao.insert(newCategory(name, AppConfig.CATEGORY_KIND_INCOME, order))
        order++
    }
    Log.i(TAG, "seeded $order default categories")
}

private fun newCategory(name: String, kind: String, sortOrder: Int): CategoryEntity =
    CategoryEntity(name = name, kind = kind, isDefault = true, sortOrder = sortOrder)
