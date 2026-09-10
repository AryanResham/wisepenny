package com.aryan.expensetracker.pipeline.dedup

import com.aryan.expensetracker.core.db.entity.TransactionEntity

// two cases, not an error type: "replaces this row" carries data a nullable return would lose
sealed interface DuplicateDecision {
    data object New : DuplicateDecision
    data class Replaces(val existing: TransactionEntity) : DuplicateDecision
}
