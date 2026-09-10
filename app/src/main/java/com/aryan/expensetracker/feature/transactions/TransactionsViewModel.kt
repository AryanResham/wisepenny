package com.aryan.expensetracker.feature.transactions

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aryan.expensetracker.core.AppContainer
import com.aryan.expensetracker.core.db.entity.CategoryEntity
import com.aryan.expensetracker.core.db.entity.MerchantRuleEntity
import com.aryan.expensetracker.core.db.entity.TransactionEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "TransactionsViewModel"
private const val RULE_SOURCE_USER = "USER"

class TransactionsViewModel(private val container: AppContainer) : ViewModel() {

    val transactions: StateFlow<List<TransactionEntity>> = container.transactionDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val categories: StateFlow<List<CategoryEntity>> = container.categoryDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingCount: StateFlow<Int> = container.pendingMessageDao.observeCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // prefs are not reactive; one read when the screen opens is enough for an "is capture alive" glance
    val lastSeenSmsAt: Long = container.appPrefs.getLastSeenSmsAt()

    // a correction here teaches the same merchant rule the review screen writes
    fun changeCategory(transaction: TransactionEntity, categoryId: Long) {
        viewModelScope.launch {
            try {
                container.transactionDao.update(transaction.copy(categoryId = categoryId))
                rememberMerchant(transaction, categoryId)
            } catch (error: Exception) {
                Log.e(TAG, "category change failed: ${error.javaClass.simpleName}")
            }
        }
    }

    private suspend fun rememberMerchant(transaction: TransactionEntity, categoryId: Long) {
        if (transaction.merchantNormalized.isBlank()) return
        container.merchantRuleDao.upsert(
            MerchantRuleEntity(
                normalizedMerchant = transaction.merchantNormalized,
                direction = transaction.direction,
                categoryId = categoryId,
                source = RULE_SOURCE_USER,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }
}
