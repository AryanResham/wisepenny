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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    val container = (LocalContext.current.applicationContext as ExpenseTrackerApp).container
    val viewModel: TransactionsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                TransactionsViewModel(
                    container.transactionDao,
                    container.payeeRuleDao,
                    container.pendingMessageDao,
                    container.appPrefs,
                )
            }
        }
    )
    val rows by viewModel.rows.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val query by viewModel.query.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val isDatabaseEmpty by viewModel.isDatabaseEmpty.collectAsState()
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

    // every tag write offers its own way back, and the offer expires with the snackbar
    LaunchedEffect(tagEvent) {
        val event = tagEvent ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = event.message,
            actionLabel = "UNDO",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoLastTag()
        viewModel.clearTagEvent()
    }

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
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
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
                            onOpen = { row -> openedRow = row },
                            onToggleSelection = viewModel::toggleSelection,
                            onLongPress = viewModel::startSelection,
                        ),
                    )
                }
            }
        }
    }

    val opened = openedRow
    if (opened != null) {
        TagSheet(
            row = opened,
            onSave = { tag, description ->
                viewModel.tagOne(opened, tag, description)
                openedRow = null
            },
            onDismiss = { openedRow = null },
        )
    }

    if (isBulkSheetOpen) {
        BulkTagSheet(
            selectedCount = selectedIds.size,
            onPick = { tag ->
                viewModel.tagSelected(tag)
                isBulkSheetOpen = false
            },
            onDismiss = { isBulkSheetOpen = false },
        )
    }
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

// the bar that only exists in selection mode; Cancel stands in for the X, back works too
@Composable
private fun SelectionBar(selectedCount: Int, onTag: () -> Unit, onCancel: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = onTag) { Text("Tag") }
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
