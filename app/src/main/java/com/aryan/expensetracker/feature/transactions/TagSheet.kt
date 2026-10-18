package com.aryan.expensetracker.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.db.entity.TransactionEntity
import com.aryan.expensetracker.core.money.formatPaise
import com.aryan.expensetracker.feature.common.amountColor
import com.aryan.expensetracker.feature.common.formatDateTime
import com.aryan.expensetracker.feature.common.tabularStyle

// the detail sheet: everything known about one row, then the two fields that change it
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagSheet(
    row: TransactionEntity,
    onSave: (String?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var pickedTag by remember(row.id) { mutableStateOf(row.tag) }
    var note by remember(row.id) { mutableStateOf(row.description.orEmpty()) }
    var isMessageExpanded by remember(row.id) { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = formatPaise(row.amountPaise, row.direction),
                style = tabularStyle(MaterialTheme.typography.headlineMedium),
                color = amountColor(row.direction),
            )
            Text(text = row.payeeRaw, style = MaterialTheme.typography.titleSmall)
            Text(
                text = formatDateTime(row.occurredAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OriginalMessage(row.rawMessage, isMessageExpanded) { isMessageExpanded = !isMessageExpanded }
            TagPicker(pickedTag) { tag -> pickedTag = if (tag == pickedTag) null else tag }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Description") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onSave(pickedTag, note.trim().ifBlank { null }) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }
}

// the same picker reached from selection mode, where there is no single row to describe
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkTagSheet(selectedCount: Int, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Tag $selectedCount selected",
                style = MaterialTheme.typography.titleMedium,
            )
            TagPicker(null, onPick)
        }
    }
}

// all thirteen tags wrap into view at once, so picking one is never a horizontal hunt
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagPicker(selectedTag: String?, onPick: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        for (tag in AppConfig.TAGS) {
            FilterChip(
                selected = tag == selectedTag,
                onClick = { onPick(tag) },
                label = { Text(tag) },
            )
        }
    }
}

// the bank's own words, kept short until asked for, so the sheet stays about the money
@Composable
private fun OriginalMessage(message: String, isExpanded: Boolean, onToggle: () -> Unit) {
    var isClipped by remember(message) { mutableStateOf(false) }
    Column {
        SelectionContainer {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (isExpanded) Int.MAX_VALUE else AppConfig.RAW_MESSAGE_COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { layout -> isClipped = layout.hasVisualOverflow },
            )
        }
        // a short message needs no toggle, so the control appears only once text is actually hidden
        if (isClipped || isExpanded) {
            TextButton(onClick = onToggle) {
                Text(if (isExpanded) "Show less" else "Show more")
            }
        }
    }
}
