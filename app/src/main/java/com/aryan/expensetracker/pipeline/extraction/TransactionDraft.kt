package com.aryan.expensetracker.pipeline.extraction

data class TransactionDraft(
    val amountPaise: Long,
    val direction: String,
    val merchantRaw: String,
    val referenceNumber: String?,
    val accountTail: String?,
    val occurredAt: Long,
    val source: String,
)
