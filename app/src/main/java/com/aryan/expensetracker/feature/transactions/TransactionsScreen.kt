package com.aryan.expensetracker.feature.transactions

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aryan.expensetracker.core.ExpenseTrackerApp
import com.aryan.expensetracker.core.db.entity.CategoryEntity
import com.aryan.expensetracker.core.db.entity.TransactionEntity
import com.aryan.expensetracker.core.money.formatPaise
import com.aryan.expensetracker.feature.common.CategoryPicker
import com.aryan.expensetracker.feature.common.categoriesForDirection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen() {
    val container = (LocalContext.current.applicationContext as ExpenseTrackerApp).container
    val viewModel: TransactionsViewModel = viewModel(
        factory = viewModelFactory { initializer { TransactionsViewModel(container) } }
    )
    val transactions by viewModel.transactions.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    val lastSeenSmsAt by viewModel.lastSeenSmsAt.collectAsState()
    var editing by remember { mutableStateOf<TransactionEntity?>(null) }

    // the capture mark lives in prefs, so it is re-read each time this screen comes forward
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshStatus()
        onPauseOrDispose {}
    }

    Column(modifier = Modifier.fillMaxSize()) {
        StatusStrip(lastSeenSmsAt, pendingCount)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            for ((day, rows) in groupByDay(transactions)) {
                item(key = day) { DayHeader(day) }
                items(rows, key = { it.id }) { transaction ->
                    TransactionRow(transaction, categories) { editing = transaction }
                }
            }
        }
    }

    // plain remember + if: the sheet exists only while a row is selected
    val selected = editing
    if (selected != null) {
        ModalBottomSheet(onDismissRequest = { editing = null }) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = selected.merchantRaw, style = MaterialTheme.typography.titleMedium)
                CategoryPicker(
                    categories = categoriesForDirection(categories, selected.direction),
                    selectedId = selected.categoryId,
                    onPick = { categoryId ->
                        viewModel.changeCategory(selected, categoryId)
                        editing = null
                    },
                )
            }
        }
    }
}

// the silent-failure alarm: if capture dies, this line stops moving
@Composable
private fun StatusStrip(lastSeenSmsAt: Long, pendingCount: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text = "Last bank SMS: ${describeLastSeen(lastSeenSmsAt)}  •  Pending: $pendingCount",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun DayHeader(day: String) {
    Text(
        text = day,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun TransactionRow(
    transaction: TransactionEntity,
    categories: List<CategoryEntity>,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatPaise(transaction.amountPaise, transaction.direction),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(text = transaction.merchantRaw, style = MaterialTheme.typography.bodyMedium)
            }
            AssistChip(
                onClick = onClick,
                label = { Text(categoryName(categories, transaction.categoryId)) },
            )
        }
        HorizontalDivider()
    }
}

// rows arrive newest first, so equal days are always next to each other and one pass groups them
private fun groupByDay(
    transactions: List<TransactionEntity>,
): List<Pair<String, List<TransactionEntity>>> {
    val groups = mutableListOf<Pair<String, MutableList<TransactionEntity>>>()
    for (transaction in transactions) {
        val day = formatDay(transaction.occurredAt)
        val current = groups.lastOrNull()
        if (current != null && current.first == day) {
            current.second.add(transaction)
        } else {
            groups.add(day to mutableListOf(transaction))
        }
    }
    return groups
}

private fun formatDay(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().format(DAY_FORMATTER)

private fun describeLastSeen(epochMillis: Long): String {
    if (epochMillis <= 0L) return "never"
    return DateUtils.getRelativeTimeSpanString(
        epochMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
    ).toString()
}

private fun categoryName(categories: List<CategoryEntity>, categoryId: Long?): String {
    if (categoryId == null) return "Uncategorized"
    return categories.firstOrNull { it.id == categoryId }?.name ?: "Uncategorized"
}
