package com.aryan.expensetracker.core.db.entity

// why a transaction is sitting in the review list; highest priority wins
object ReviewReason {
    const val NEW_MERCHANT: String = "NEW_MERCHANT"
    const val LLM_EXTRACTED: String = "LLM_EXTRACTED"
    const val NO_REFERENCE_NUMBER: String = "NO_REFERENCE_NUMBER"
    const val COULD_NOT_PROCESS: String = "COULD_NOT_PROCESS"
}
