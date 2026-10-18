package com.aryan.expensetracker.feature.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.llm.AgentStep
import com.aryan.expensetracker.core.llm.AgentTools
import com.aryan.expensetracker.core.llm.AgentTurn
import com.aryan.expensetracker.core.llm.GeminiAgent
import com.aryan.expensetracker.core.llm.TOOL_FIND_TRANSACTIONS
import com.aryan.expensetracker.core.llm.TOOL_PROPOSE_TAGS
import com.aryan.expensetracker.core.llm.readText
import com.aryan.expensetracker.core.money.formatPaise
import com.aryan.expensetracker.core.result.AppResult
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

private const val TAG = "ChatViewModel"

const val NO_KEY_LINE: String = "Add a Gemini key to use chat."
private const val GAVE_UP_LINE = "Sorry, I could not work that one out. Try asking it more simply."
private const val ROWS_GONE_LINE = "I could not find those transactions any more."
private const val NOTHING_PICKED = "Nothing tagged"
private const val CANCELLED = "Cancelled"

// what the proposeTags call reports back; v1beta rejects a request whose call has no response
private const val STATUS_AWAITING = """{"status":"awaiting_confirmation"}"""
private const val STATUS_CANCELLED = """{"status":"cancelled"}"""
private const val STATUS_ROWS_GONE = """{"status":"rows_not_found"}"""

class ChatViewModel(
    private val agent: GeminiAgent,
    private val tools: AgentTools,
    val hasApiKey: Boolean,
) : ViewModel() {

    private val mutableMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = mutableMessages.asStateFlow()

    private val mutableIsWorking = MutableStateFlow(false)
    val isWorking: StateFlow<Boolean> = mutableIsWorking.asStateFlow()

    // the model-facing transcript, separate from the visible one: it also carries the tool steps
    private val turns = mutableListOf<AgentTurn>()

    // the waiting functionResponse behind each card, so the user's answer can be written into it
    private val proposalResponses = mutableMapOf<Long, AgentTurn>()
    private var nextMessageId = 1L

    fun onSend(text: String) {
        viewModelScope.launch { send(text) }
    }

    fun onConfirm(proposalId: Long) {
        viewModelScope.launch { confirm(proposalId) }
    }

    // Step 1: refuse what cannot be sent; Step 2: show the question; Step 3: run the tool loop
    suspend fun send(text: String) {
        val question = text.trim()
        if (question.isEmpty() || mutableIsWorking.value || !hasApiKey) return
        append(ChatMessage.User(nextMessageId(), question))
        trimTurns()
        turns.add(agent.userTurn(question))
        mutableIsWorking.value = true
        try {
            runLoop()
        } finally {
            mutableIsWorking.value = false
        }
    }

    // the loop: model turn, local tool, model turn again, capped so a looping model always stops
    private suspend fun runLoop() {
        var step = 0
        while (step < AppConfig.MAX_AGENT_STEPS) {
            step++
            val next = agent.nextStep(turns)
            if (next is AppResult.Failure) {
                Log.e(TAG, "chat turn failed: ${next.reason}")
                append(ChatMessage.Error(nextMessageId(), describeFailure(next.reason)))
                return
            }
            when (val action = (next as AppResult.Success).data) {
                is AgentStep.Reply -> {
                    turns.add(agent.modelReplyTurn(action.text))
                    append(ChatMessage.Assistant(nextMessageId(), action.text))
                    return
                }
                is AgentStep.Find -> runFind(action.arguments)
                is AgentStep.Propose -> {
                    showProposal(action)
                    return
                }
            }
        }
        append(ChatMessage.Assistant(nextMessageId(), GAVE_UP_LINE))
    }

    // the search runs here on the device; only the rows it returns go back to the model
    private suspend fun runFind(arguments: JsonObject) {
        append(ChatMessage.ToolRun(nextMessageId(), describeFind(arguments)))
        turns.add(agent.modelCallTurn(TOOL_FIND_TRANSACTIONS, arguments))
        val result = try {
            tools.findTransactions(arguments)
        } catch (error: Exception) {
            Log.e(TAG, "find tool failed: ${error.javaClass.simpleName}")
            """{"error":"the search could not run"}"""
        }
        turns.add(agent.toolResultTurn(TOOL_FIND_TRANSACTIONS, result))
    }

    // proposeTags writes nothing: it becomes a card, and only the confirm tap reaches the database
    private suspend fun showProposal(action: AgentStep.Propose) {
        turns.add(agent.modelCallTurn(TOOL_PROPOSE_TAGS, action.arguments))
        val rows = try {
            tools.rowsFor(action.ids)
        } catch (error: Exception) {
            Log.e(TAG, "proposal lookup failed: ${error.javaClass.simpleName}")
            emptyList()
        }
        if (rows.isEmpty()) {
            answerProposalCall(STATUS_ROWS_GONE)
            append(ChatMessage.Assistant(nextMessageId(), ROWS_GONE_LINE))
            return
        }
        val cardRows = rows.map {
            ProposalRow(it.id, it.payeeRaw, formatPaise(it.amountPaise, it.direction))
        }
        val proposalId = nextMessageId()
        proposalResponses[proposalId] = answerProposalCall(STATUS_AWAITING)
        append(
            ChatMessage.Proposal(
                id = proposalId,
                tag = action.tag,
                description = action.description,
                rows = cardRows,
                ticked = cardRows.map { it.transactionId }.toSet(),
            )
        )
    }

    // the call is answered the moment the card appears; waiting for the tap would send a bare call
    private fun answerProposalCall(statusJson: String): AgentTurn {
        val turn = agent.toolResultTurn(TOOL_PROPOSE_TAGS, statusJson)
        turns.add(turn)
        return turn
    }

    // the answer replaces the waiting response in place, found by identity so a trim cannot misfire
    private fun settleProposalCall(proposalId: Long, statusJson: String) {
        val waiting = proposalResponses.remove(proposalId) ?: return
        val index = turns.indexOfFirst { it === waiting }
        if (index < 0) return
        turns[index] = agent.toolResultTurn(TOOL_PROPOSE_TAGS, statusJson)
    }

    fun toggleRow(proposalId: Long, transactionId: Long) {
        val proposal = findProposal(proposalId) ?: return
        if (proposal.outcome != null) return
        val ticked = proposal.ticked.toMutableSet()
        if (!ticked.remove(transactionId)) ticked.add(transactionId)
        replace(proposal.copy(ticked = ticked))
    }

    // the one write in chat; the card then goes flat so the conversation keeps a record of it
    suspend fun confirm(proposalId: Long) {
        val proposal = findProposal(proposalId) ?: return
        if (proposal.outcome != null) return
        val ids = proposal.rows.map { it.transactionId }.filter { proposal.ticked.contains(it) }
        if (ids.isEmpty()) {
            settleProposalCall(proposalId, STATUS_CANCELLED)
            replace(proposal.copy(outcome = NOTHING_PICKED))
            return
        }
        val written = try {
            tools.applyTags(ids, proposal.tag, proposal.description)
        } catch (error: Exception) {
            Log.e(TAG, "tag write failed: ${error.javaClass.simpleName}")
            append(ChatMessage.Error(nextMessageId(), "Could not save that. Nothing was changed."))
            return
        }
        settleProposalCall(proposalId, """{"status":"confirmed","tagged":$written}""")
        replace(proposal.copy(outcome = "Tagged $written as ${proposal.tag}"))
    }

    fun cancel(proposalId: Long) {
        val proposal = findProposal(proposalId) ?: return
        if (proposal.outcome != null) return
        settleProposalCall(proposalId, STATUS_CANCELLED)
        replace(proposal.copy(outcome = CANCELLED))
    }

    private fun findProposal(proposalId: Long): ChatMessage.Proposal? =
        mutableMessages.value.firstOrNull { it.id == proposalId } as? ChatMessage.Proposal

    private fun replace(proposal: ChatMessage.Proposal) {
        mutableMessages.value = mutableMessages.value.map {
            if (it.id == proposal.id) proposal else it
        }
    }

    private fun append(message: ChatMessage) {
        mutableMessages.value = mutableMessages.value + message
    }

    private fun nextMessageId(): Long {
        nextMessageId++
        return nextMessageId - 1
    }

    // a request has to start on a question, or trimming could leave a tool result without its call
    private fun trimTurns() {
        while (turns.size > AppConfig.CHAT_HISTORY_TURNS) turns.removeAt(0)
        while (turns.isNotEmpty() && !turns[0].isQuestion) turns.removeAt(0)
    }
}

// the quiet waiting line, built from the arguments so it says what is being looked through
private fun describeFind(arguments: JsonObject): String {
    val from = readText(arguments, "from")
    val to = readText(arguments, "to")
    if (from != null && to != null) return "Looking through ${monthLabel(from, to) ?: "$from to $to"}..."
    val search = readText(arguments, "search")
    if (search != null) return "Looking for $search..."
    val tag = readText(arguments, "tag")
    if (tag != null) return "Looking through $tag..."
    return "Looking through your transactions..."
}

// a range inside one month reads as "October 2026", which is how the question was asked
private fun monthLabel(from: String, to: String): String? {
    val start = parseDay(from) ?: return null
    val end = parseDay(to) ?: return null
    if (start.year != end.year || start.month != end.month) return null
    return "${start.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${start.year}"
}

private fun parseDay(date: String): LocalDate? = try {
    LocalDate.parse(date)
} catch (error: DateTimeParseException) {
    null
}

// every model failure becomes one plain line; the rest of the app is unaffected either way
private fun describeFailure(reason: String): String {
    if (reason == AppConfig.LLM_REASON_NO_KEY) return NO_KEY_LINE
    if (reason == AppConfig.LLM_REASON_RATE_LIMITED) return "Gemini is busy right now. Try again in a minute."
    if (reason == AppConfig.LLM_REASON_DAILY_CAP) return "Today's Gemini allowance is used up. Chat works again tomorrow."
    if (reason.startsWith("network")) return "No connection, so chat cannot answer right now."
    return "Chat could not reach Gemini. Try again."
}
