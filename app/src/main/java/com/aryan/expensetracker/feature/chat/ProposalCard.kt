package com.aryan.expensetracker.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// the confirmation card: the loudest thing on the screen while it waits, a flat record once answered
@Composable
fun ProposalCard(
    proposal: ChatMessage.Proposal,
    onToggleRow: (Long) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val isAnswered = proposal.outcome != null
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isAnswered) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isAnswered) 0.dp else 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = proposal.tag, style = MaterialTheme.typography.titleLarge)
            if (proposal.description != null) {
                Text(
                    text = proposal.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(modifier = Modifier.padding(top = 12.dp)) {
                for (row in proposal.rows) {
                    ProposalRowLine(
                        row = row,
                        isTicked = proposal.ticked.contains(row.transactionId),
                        isAnswered = isAnswered,
                        onToggle = { onToggleRow(row.transactionId) },
                    )
                }
            }
            if (proposal.outcome != null) {
                Text(
                    text = proposal.outcome,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
                return@Column
            }
            // confirm is the prominent button and cancel stays quiet text, because the write is the point
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(onClick = onConfirm, modifier = Modifier.padding(start = 8.dp)) {
                    Text("Confirm")
                }
            }
        }
    }
}

@Composable
private fun ProposalRowLine(
    row: ProposalRow,
    isTicked: Boolean,
    isAnswered: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = isTicked, onCheckedChange = { onToggle() }, enabled = !isAnswered)
        Text(
            text = row.amountLabel,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = row.payee,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}
