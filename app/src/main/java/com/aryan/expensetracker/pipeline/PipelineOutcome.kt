package com.aryan.expensetracker.pipeline

// what one message ended as; WaitForNetwork is separate from Failed because it costs no attempt
sealed interface PipelineOutcome {
    data class Saved(val transactionId: Long) : PipelineOutcome
    data class Ignored(val reason: String) : PipelineOutcome
    data object WaitForNetwork : PipelineOutcome
    data class Failed(val reason: String) : PipelineOutcome
}
