package com.aryan.expensetracker.feature.transactions

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aryan.expensetracker.core.AppContainer
import com.aryan.expensetracker.core.db.entity.CategoryEntity
import com.aryan.expensetracker.core.db.entity.TransactionEntity
import com.aryan.expensetracker.feature.common.rememberMerchant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "TransactionsViewModel"

class TransactionsViewModel(private val container: AppContainer) : ViewModel() {

    val transactions: StateFlow<List<TransactionEntity>> = container.transactionDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val categories: StateFlow<List<CategoryEntity>> = container.categoryDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingCount: StateFlow<Int> = container.pendingMessageDao.observeCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val mutableLastSeenSmsAt = MutableStateFlow(container.appPrefs.getLastSeenSmsAt())
    val lastSeenSmsAt: StateFlow<Long> = mutableLastSeenSmsAt.asStateFlow()

    // prefs are not reactive, so the screen re-reads the capture mark every time it comes forward
    fun refreshStatus() {
        mutableLastSeenSmsAt.value = container.appPrefs.getLastSeenSmsAt()
    }

    // a correction here teaches the same merchant rule the review screen writes
    fun changeCategory(transaction: TransactionEntity, categoryId: Long) {
        viewModelScope.launch {
            try {
                container.transactionDao.update(transaction.copy(categoryId = categoryId))
                rememberMerchant(
                    container.merchantRuleDao,
                    transaction.merchantNormalized,
                    transaction.direction,
                    categoryId,
                )
            } catch (error: Exception) {
                Log.e(TAG, "category change failed: ${error.javaClass.simpleName}")
            }
        }
    }
}
