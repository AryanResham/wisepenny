package com.aryan.expensetracker.pipeline

// what one message ended as; the two Wait cases cost no attempt and stop the drain for this run
sealed interface PipelineOutcome {
    data class Saved(val transactionId: Long) : PipelineOutcome
    data class Ignored(val reason: String) : PipelineOutcome
    data object WaitForNetwork : PipelineOutcome
    data object WaitForQuota : PipelineOutcome
    data class Failed(val reason: String) : PipelineOutcome
}
