package com.aryan.expensetracker.feature.transactions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aryan.expensetracker.core.ExpenseTrackerApp
import com.aryan.expensetracker.core.db.entity.TransactionEntity
import com.aryan.expensetracker.feature.common.MonthHeader
import com.aryan.expensetracker.feature.common.formatDayHeader
import com.aryan.expensetracker.feature.common.formatMonthName
import com.aryan.expensetracker.feature.common.toEpochDay

private data class DayGroup(val epochDay: Long, val rows: List<TransactionEntity>)

private data class ListCallbacks(
    val onOpen: (TransactionEntity) -> Unit,
    val onToggleSelection: (Long) -> Unit,
    val onLongPress: (Long) -> Unit,
)

@Composable
fun TransactionsScreen() {
    val viewModel = rememberTransactionsViewModel()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val tagEvent by viewModel.tagEvent.collectAsState()

    var openedRow by remember { mutableStateOf<TransactionEntity?>(null) }
    var isBulkSheetOpen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // the capture mark lives in prefs, so it is re-read each time this screen comes forward
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshStatus()
        onPauseOrDispose {}
    }

    BackHandler(enabled = selectedIds.isNotEmpty()) { viewModel.clearSelection() }
    TagEventEffect(snackbarHostState, tagEvent, viewModel)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (selectedIds.isNotEmpty()) {
                SelectionBar(
                    selectedCount = selectedIds.size,
                    onTag = { isBulkSheetOpen = true },
                    onCancel = { viewModel.clearSelection() },
                )
            }
        },
    ) { insets ->
        MonthAndList(viewModel, Modifier.padding(insets)) { row -> openedRow = row }
    }

    RowSheet(openedRow, viewModel) { openedRow = null }
    if (isBulkSheetOpen) {
        BulkSheet(selectedIds.size, viewModel) { isBulkSheetOpen = false }
    }
}

@Composable
private fun rememberTransactionsViewModel(): TransactionsViewModel {
    val container = (LocalContext.current.applicationContext as ExpenseTrackerApp).container
    return viewModel(
        factory = viewModelFactory {
            initializer {
                TransactionsViewModel(
                    container.transactionDao,
                    container.payeeRuleDao,
                    container.pendingMessageDao,
                    container.captureMark,
                )
            }
        }
    )
}

// the screen under the bars: which month, what to search, then the rows or why there are none
@Composable
private fun MonthAndList(
    viewModel: TransactionsViewModel,
    modifier: Modifier,
    onOpen: (TransactionEntity) -> Unit,
) {
    val rows by viewModel.rows.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val query by viewModel.query.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val isDatabaseEmpty by viewModel.isDatabaseEmpty.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        MonthHeader(summary, viewModel::changeMonth)
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            placeholder = { Text("Search payee or description") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            if (rows.isEmpty()) {
                EmptyState(describeEmpty(query, formatMonthName(summary.month), isDatabaseEmpty))
            } else {
                TransactionList(
                    rows = rows,
                    selectedIds = selectedIds,
                    callbacks = ListCallbacks(
                        onOpen = onOpen,
                        onToggleSelection = viewModel::toggleSelection,
                        onLongPress = viewModel::startSelection,
                    ),
                )
            }
        }
    }
}

// every tag write offers its own way back, and the offer expires with the snackbar
@Composable
private fun TagEventEffect(
    hostState: SnackbarHostState,
    event: TagEvent?,
    viewModel: TransactionsViewModel,
) {
    LaunchedEffect(event) {
        if (event == null) return@LaunchedEffect
        val result = hostState.showSnackbar(
            message = event.message,
            actionLabel = "UNDO",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoLastTag()
        viewModel.clearTagEvent()
    }
}

@Composable
private fun RowSheet(
    row: TransactionEntity?,
    viewModel: TransactionsViewModel,
    onClose: () -> Unit,
) {
    if (row == null) return
    TagSheet(
        row = row,
        onSave = { tag, description ->
            viewModel.tagOne(row, tag, description)
            onClose()
        },
        onDismiss = onClose,
    )
}

@Composable
private fun BulkSheet(
    selectedCount: Int,
    viewModel: TransactionsViewModel,
    onClose: () -> Unit,
) {
    BulkTagSheet(
        selectedCount = selectedCount,
        onPick = { tag ->
            viewModel.tagSelected(tag)
            onClose()
        },
        onDismiss = onClose,
    )
}

// one continuous list: a quiet date header, then its rows, no card around either
@Composable
private fun TransactionList(
    rows: List<TransactionEntity>,
    selectedIds: Set<Long>,
    callbacks: ListCallbacks,
) {
    val isSelecting = selectedIds.isNotEmpty()
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        for (group in groupByDay(rows)) {
            item(key = "day-${group.epochDay}") { DayHeader(formatDayHeader(group.epochDay)) }
            items(group.rows, key = { it.id }) { row ->
                TransactionRow(
                    row = row,
                    selection = RowSelection(isSelecting, selectedIds.contains(row.id)),
                    actions = RowActions(
                        onTap = {
                            if (isSelecting) callbacks.onToggleSelection(row.id)
                            else callbacks.onOpen(row)
                        },
                        onLongPress = { callbacks.onLongPress(row.id) },
                    ),
                )
            }
        }
    }
}

@Composable
private fun DayHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// the bar that only exists in selection mode; the X leaves it, and so does back
@Composable
private fun SelectionBar(selectedCount: Int, onTag: () -> Unit, onCancel: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Exit selection")
            }
            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Button(onClick = onTag, modifier = Modifier.padding(end = 8.dp)) { Text("Tag") }
        }
    }
}

// rows arrive newest first, so equal days sit together and one pass groups them
private fun groupByDay(rows: List<TransactionEntity>): List<DayGroup> {
    val groups = mutableListOf<Pair<Long, MutableList<TransactionEntity>>>()
    for (row in rows) {
        val epochDay = toEpochDay(row.occurredAt)
        val current = groups.lastOrNull()
        if (current != null && current.first == epochDay) {
            current.second.add(row)
        } else {
            groups.add(epochDay to mutableListOf(row))
        }
    }
    return groups.map { DayGroup(it.first, it.second) }
}

// an empty screen always says why it is empty, naming the thing the user actually typed or picked
private fun describeEmpty(query: String, monthLabel: String, isDatabaseEmpty: Boolean): String {
    if (query.isNotBlank()) return "No matches for '${query.trim()}'."
    if (isDatabaseEmpty) return "Waiting for your first message."
    return "Nothing here for $monthLabel."
}
