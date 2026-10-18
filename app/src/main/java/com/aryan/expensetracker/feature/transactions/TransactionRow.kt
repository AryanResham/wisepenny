package com.aryan.expensetracker.feature.transactions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aryan.expensetracker.core.db.entity.TransactionEntity
import com.aryan.expensetracker.feature.common.TagChip
import com.aryan.expensetracker.feature.common.amountColor
import com.aryan.expensetracker.feature.common.formatRowAmount
import com.aryan.expensetracker.feature.common.tabularStyle

data class RowSelection(val isActive: Boolean, val isSelected: Boolean)

data class RowActions(val onTap: () -> Unit, val onLongPress: () -> Unit)

// one line of money: name on the left, amount pinned right, a hairline below and no container
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionRow(row: TransactionEntity, selection: RowSelection, actions: RowActions) {
    val secondaryLine = secondaryLineOf(row)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = actions.onTap, onLongClick = actions.onLongPress)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selection.isActive) {
                Checkbox(
                    checked = selection.isSelected,
                    onCheckedChange = null,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = primaryLineOf(row),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // an untagged row simply has no second line: no badge, no warning, nothing to fix
                if (row.tag != null || secondaryLine != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (row.tag != null) TagChip(row.tag)
                        if (secondaryLine != null) {
                            Text(
                                text = secondaryLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Text(
                text = formatRowAmount(row.amountPaise, row.direction),
                style = tabularStyle(MaterialTheme.typography.titleMedium),
                color = amountColor(row.direction),
                textAlign = TextAlign.End,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

// a description the user wrote beats a bank payee like AARYAN JAIDEEP KANERIA, so it leads
private fun primaryLineOf(row: TransactionEntity): String {
    val description = row.description
    if (description.isNullOrBlank()) return row.payeeRaw
    return description
}

private fun secondaryLineOf(row: TransactionEntity): String? {
    if (row.description.isNullOrBlank()) return null
    return row.payeeRaw
}
