package com.aryan.expensetracker.feature.transactions

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.db.entity.PayeeRuleEntity
import com.aryan.expensetracker.core.db.entity.TransactionEntity
import com.aryan.expensetracker.core.prefs.AppPrefs
import com.aryan.expensetracker.feature.common.MonthSummary
import com.aryan.expensetracker.feature.common.monthStartMillis
import com.aryan.expensetracker.pipeline.capture.PendingMessageDao
import com.aryan.expensetracker.pipeline.tagging.PayeeRuleDao
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "TransactionsViewModel"

// what a tag write replaced, held in memory so undo costs no table and dies with the screen
private data class TagUndo(
    val previousRows: List<TransactionEntity>,
    val previousRules: Map<String, PayeeRuleEntity?>,
)

// the id makes two identical messages distinct, so the second snackbar is never swallowed
data class TagEvent(val id: Long, val message: String)

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModel(
    private val transactionDao: TransactionDao,
    private val payeeRuleDao: PayeeRuleDao,
    private val pendingMessageDao: PendingMessageDao,
    private val appPrefs: AppPrefs,
) : ViewModel() {

    private val monthState = MutableStateFlow(YearMonth.now())

    private val queryState = MutableStateFlow("")
    val query: StateFlow<String> = queryState.asStateFlow()

    private val selectedIdsState = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = selectedIdsState.asStateFlow()

    private val tagEventState = MutableStateFlow<TagEvent?>(null)
    val tagEvent: StateFlow<TagEvent?> = tagEventState.asStateFlow()

    private val lastSeenSmsAtState = MutableStateFlow(appPrefs.getLastSeenSmsAt())

    private var pendingUndo: TagUndo? = null
    private var tagEventCount = 0L

    private val monthRows: StateFlow<List<TransactionEntity>> = monthState
        .flatMapLatest { month ->
            transactionDao.observeBetween(
                monthStartMillis(month),
                monthStartMillis(month.plusMonths(1)),
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // a search ignores the month on purpose: the user is hunting for one row, not browsing a window
    val rows: StateFlow<List<TransactionEntity>> = queryState
        .flatMapLatest { text ->
            if (text.isBlank()) monthRows else transactionDao.observeSearch(text.trim())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // the header keeps reporting the month even while the list below shows search hits
    val summary: StateFlow<MonthSummary> = combine(
        monthState,
        monthRows,
        pendingMessageDao.observeCount(),
        lastSeenSmsAtState,
    ) { month, rows, pendingCount, lastSeenSmsAt ->
        MonthSummary(
            month = month,
            spentPaise = sumByDirection(rows, AppConfig.DIRECTION_DEBIT),
            receivedPaise = sumByDirection(rows, AppConfig.DIRECTION_CREDIT),
            lastSmsAt = lastSeenSmsAt,
            pendingCount = pendingCount,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MonthSummary(YearMonth.now(), 0L, 0L, 0L, 0))

    val isDatabaseEmpty: StateFlow<Boolean> = transactionDao.observeTotalCount()
        .map { count -> count == 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // prefs are not reactive, so the capture mark is re-read whenever the screen comes forward
    fun refreshStatus() {
        lastSeenSmsAtState.value = appPrefs.getLastSeenSmsAt()
    }

    fun changeMonth(step: Int) {
        monthState.value = monthState.value.plusMonths(step.toLong())
    }

    fun setQuery(text: String) {
        queryState.value = text
    }

    // a long press starts selection mode, and a later one just adds to what is already selected
    fun startSelection(id: Long) {
        selectedIdsState.value = selectedIdsState.value + id
    }

    fun toggleSelection(id: Long) {
        val current = selectedIdsState.value
        selectedIdsState.value = if (current.contains(id)) current - id else current + id
    }

    fun clearSelection() {
        selectedIdsState.value = emptySet()
    }

    fun clearTagEvent() {
        tagEventState.value = null
    }

    // Step 1: snapshot for undo, Step 2: write tag and note, Step 3: teach the payee rule
    fun tagOne(row: TransactionEntity, tag: String?, description: String?) {
        viewModelScope.launch {
            try {
                val ids = listOf(row.id)
                captureUndo(ids)
                transactionDao.setTag(ids, tag, description)
                if (tag != null) learnPayees(listOf(row.payeeNormalized), tag)
                announceTagWrite(ids.size, tag)
            } catch (error: Exception) {
                Log.e(TAG, "single tag write failed: ${error.javaClass.simpleName}")
            }
        }
    }

    fun tagSelected(tag: String) {
        val ids = selectedIdsState.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                val previousRows = captureUndo(ids)
                transactionDao.setTagOnly(ids, tag)
                learnPayees(previousRows.map { it.payeeNormalized }, tag)
                announceTagWrite(ids.size, tag)
                selectedIdsState.value = emptySet()
            } catch (error: Exception) {
                Log.e(TAG, "bulk tag write failed: ${error.javaClass.simpleName}, rows=${ids.size}")
            }
        }
    }

    // puts the rows back as they were and unlearns any rule the write had just created
    fun undoLastTag() {
        val undo = pendingUndo ?: return
        pendingUndo = null
        viewModelScope.launch {
            try {
                for (row in undo.previousRows) transactionDao.update(row)
                for ((payeeNormalized, rule) in undo.previousRules) {
                    if (rule == null) payeeRuleDao.deleteByPayee(payeeNormalized)
                    else payeeRuleDao.upsert(rule)
                }
            } catch (error: Exception) {
                Log.e(TAG, "undo failed: ${error.javaClass.simpleName}")
            }
        }
    }

    private suspend fun captureUndo(ids: List<Long>): List<TransactionEntity> {
        val previousRows = transactionDao.byIds(ids)
        val previousRules = mutableMapOf<String, PayeeRuleEntity?>()
        for (row in previousRows) {
            if (row.payeeNormalized.isBlank()) continue
            previousRules[row.payeeNormalized] = payeeRuleDao.findByPayee(row.payeeNormalized)
        }
        pendingUndo = TagUndo(previousRows, previousRules)
        return previousRows
    }

    // the learning loop: once a payee is tagged by hand, the next message from it arrives pre-tagged
    private suspend fun learnPayees(payees: List<String>, tag: String) {
        val now = System.currentTimeMillis()
        for (payeeNormalized in payees.distinct()) {
            if (payeeNormalized.isBlank()) continue
            payeeRuleDao.upsert(PayeeRuleEntity(payeeNormalized, tag, now))
        }
    }

    private fun announceTagWrite(count: Int, tag: String?) {
        tagEventCount++
        if (tag == null) {
            tagEventState.value = TagEvent(tagEventCount, "Saved")
            return
        }
        val message = if (count == 1) "Tagged as $tag" else "Tagged $count as $tag"
        tagEventState.value = TagEvent(tagEventCount, message)
    }

    // totals come from the rows already on screen, so there is no SUM query to drift from them
    private fun sumByDirection(rows: List<TransactionEntity>, direction: String): Long {
        var total = 0L
        for (row in rows) {
            if (row.direction == direction) total += row.amountPaise
        }
        return total
    }
}
