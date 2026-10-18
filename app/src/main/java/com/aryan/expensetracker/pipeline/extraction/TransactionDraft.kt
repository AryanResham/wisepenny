package com.aryan.expensetracker.pipeline.extraction

data class TransactionDraft(
    val amountPaise: Long,
    val direction: String,
    val payeeRaw: String,
    val referenceNumber: String?,
    val accountTail: String?,
    val occurredAt: Long,
    val source: String,
    val templateId: String?,
    val placeFromMessage: String?,
)
