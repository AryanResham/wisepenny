package com.aryan.expensetracker.pipeline.extraction

// three-case result: a message we understood, one that was never money, or a call we should retry
sealed interface ExtractionOutcome {
    data class Transaction(val draft: TransactionDraft) : ExtractionOutcome
    data object NotATransaction : ExtractionOutcome
    data class Failed(val reason: String) : ExtractionOutcome
}
