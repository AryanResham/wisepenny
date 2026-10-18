package com.aryan.expensetracker.feature.chat

// one line of the visible conversation; history is in memory only, so these die with the process
sealed interface ChatMessage {

    val id: Long

    data class User(override val id: Long, val text: String) : ChatMessage

    data class Assistant(override val id: Long, val text: String) : ChatMessage

    // the quiet "Looking through October..." line, so the wait always says why it is waiting
    data class ToolRun(override val id: Long, val label: String) : ChatMessage

    data class Error(override val id: Long, val text: String) : ChatMessage

    // the confirmation card; nothing is written while outcome is null and it stays as a record after
    data class Proposal(
        override val id: Long,
        val tag: String,
        val description: String?,
        val rows: List<ProposalRow>,
        val ticked: Set<Long>,
        val outcome: String? = null,
    ) : ChatMessage
}

data class ProposalRow(val transactionId: Long, val payee: String, val amountLabel: String)
