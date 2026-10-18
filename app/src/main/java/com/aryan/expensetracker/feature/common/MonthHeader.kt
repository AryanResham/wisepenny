package com.aryan.expensetracker.feature.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.money.formatPaise
import java.time.YearMonth

data class MonthSummary(
    val month: YearMonth,
    val spentPaise: Long,
    val receivedPaise: Long,
    val lastSmsAt: Long,
    val pendingCount: Int,
)

// the fixed top of the screen: which month, what it cost, and whether capture is still alive
@Composable
fun MonthHeader(summary: MonthSummary, onMonthChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonthArrow(step = -1, isEnabled = true, onMonthChange = onMonthChange)
            Text(
                text = formatMonth(summary.month),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            MonthArrow(
                step = 1,
                isEnabled = summary.month < YearMonth.now(),
                onMonthChange = onMonthChange,
            )
        }

        // the month's spend is the one number the user opened the app for, so nothing competes with it
        Text(
            text = formatPaise(summary.spentPaise, AppConfig.DIRECTION_DEBIT),
            style = tabularStyle(MaterialTheme.typography.displaySmall),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (summary.receivedPaise > 0L) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${formatPaise(summary.receivedPaise, AppConfig.DIRECTION_CREDIT)} received",
                style = tabularStyle(MaterialTheme.typography.titleSmall),
                color = amountColor(AppConfig.DIRECTION_CREDIT),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = buildHealthLine(summary),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// arrows are the only icon-only controls here, so each carries its own spoken label
@Composable
private fun MonthArrow(step: Int, isEnabled: Boolean, onMonthChange: (Int) -> Unit) {
    val glyph = if (step < 0) "‹" else "›"
    val label = if (step < 0) "Previous month" else "Next month"
    IconButton(
        onClick = { onMonthChange(step) },
        enabled = isEnabled,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Text(text = glyph, style = MaterialTheme.typography.headlineSmall)
    }
}

// the silent-failure alarm; the unread half is hidden at zero so a healthy queue says nothing
private fun buildHealthLine(summary: MonthSummary): String {
    val lastSms = "Last SMS: ${describeLastSeen(summary.lastSmsAt)}"
    if (summary.pendingCount <= 0) return lastSms
    return "$lastSms  •  Unread: ${summary.pendingCount}"
}
