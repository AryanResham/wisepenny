package com.aryan.expensetracker.feature.categories

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.aryan.expensetracker.core.AppContainer
import com.aryan.expensetracker.core.db.entity.CategoryEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "CategoriesViewModel"

class CategoriesViewModel(private val container: AppContainer) : ViewModel() {

    val categories: StateFlow<List<CategoryEntity>> = container.categoryDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // names are unique in the schema, so a duplicate is a refusal rather than a crash
    fun add(name: String, kind: String) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        viewModelScope.launch {
            try {
                val sortOrder = (categories.value.maxOfOrNull { it.sortOrder } ?: -1) + 1
                container.categoryDao.insert(
                    CategoryEntity(name = cleanName, kind = kind, isDefault = false, sortOrder = sortOrder)
                )
            } catch (error: Exception) {
                Log.e(TAG, "add category failed: ${error.javaClass.simpleName}")
            }
        }
    }

    fun rename(category: CategoryEntity, name: String) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        viewModelScope.launch {
            try {
                container.categoryDao.update(category.copy(name = cleanName))
            } catch (error: Exception) {
                Log.e(TAG, "rename category failed: ${error.javaClass.simpleName}")
            }
        }
    }

    // all three writes or none: a half-done delete would leave rules pointing at a dead category
    fun delete(category: CategoryEntity, moveTransactionsTo: Long) {
        if (moveTransactionsTo == category.id) return
        if (categories.value.count { it.kind == category.kind } <= 1) {
            Log.w(TAG, "refused to delete the last ${category.kind} category")
            return
        }
        viewModelScope.launch {
            try {
                container.database.withTransaction {
                    container.transactionDao.reassignCategory(category.id, moveTransactionsTo)
                    container.merchantRuleDao.deleteByCategory(category.id)
                    container.categoryDao.delete(category)
                }
            } catch (error: Exception) {
                Log.e(TAG, "delete category failed: ${error.javaClass.simpleName}")
            }
        }
    }
}
