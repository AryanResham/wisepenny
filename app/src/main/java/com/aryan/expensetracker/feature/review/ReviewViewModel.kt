package com.aryan.expensetracker.feature.review

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

private const val TAG = "ReviewViewModel"
private const val RULE_SOURCE_USER = "USER"

class ReviewViewModel(private val container: AppContainer) : ViewModel() {

    val itemsToReview: StateFlow<List<TransactionEntity>> =
        container.transactionDao.observeNeedingReview()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val categories: StateFlow<List<CategoryEntity>> = container.categoryDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // accepts what the app guessed; a row without a category has nothing to confirm
    fun confirm(transaction: TransactionEntity) {
        val categoryId = transaction.categoryId ?: return
        confirmWithCategory(transaction, categoryId)
    }

    // applies the picked category, stops the asking, and teaches the merchant for next time
    fun confirmWithCategory(transaction: TransactionEntity, categoryId: Long) {
        viewModelScope.launch {
            try {
                container.transactionDao.update(
                    transaction.copy(categoryId = categoryId, needsReview = false, reviewReason = null)
                )
                rememberMerchant(transaction, categoryId)
            } catch (error: Exception) {
                Log.e(TAG, "confirm failed: ${error.javaClass.simpleName}")
            }
        }
    }

    fun discard(transaction: TransactionEntity) {
        viewModelScope.launch {
            try {
                container.transactionDao.delete(transaction)
            } catch (error: Exception) {
                Log.e(TAG, "discard failed: ${error.javaClass.simpleName}")
            }
        }
    }

    // this write is the learning loop: after it the merchant never needs the model again
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
