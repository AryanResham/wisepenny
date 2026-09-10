package com.aryan.expensetracker.core.result

sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Failure(val reason: String) : AppResult<Nothing>
}
