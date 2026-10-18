package com.aryan.expensetracker.feature.common

import android.text.format.DateUtils
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.aryan.expensetracker.core.config.AppConfig
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val MONTH_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
private val MONTH_NAME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM")
private val DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
private val DAY_WITH_YEAR_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")

fun formatMonth(month: YearMonth): String = month.format(MONTH_FORMATTER)

fun formatMonthName(month: YearMonth): String = month.format(MONTH_NAME_FORMATTER)

// stored timestamps are epoch millis, so a month window is resolved in the phone's own zone
fun monthStartMillis(month: YearMonth): Long =
    month.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

fun toEpochDay(epochMillis: Long): Long =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

// day headers read the way a person says them; the year appears only when it is not this one
fun formatDayHeader(epochDay: Long): String {
    val date = LocalDate.ofEpochDay(epochDay)
    val today = LocalDate.now()
    if (date == today) return "Today"
    if (date == today.minusDays(1)) return "Yesterday"
    if (date.year != today.year) return date.format(DAY_WITH_YEAR_FORMATTER)
    return date.format(DAY_FORMATTER)
}

fun formatDateTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DATE_TIME_FORMATTER)

fun describeLastSeen(epochMillis: Long): String {
    if (epochMillis <= 0L) return "never"
    return DateUtils.getRelativeTimeSpanString(
        epochMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

// tabular figures so the amount column scans vertically instead of wobbling from row to row
fun tabularStyle(style: TextStyle): TextStyle = style.copy(fontFeatureSettings = "tnum")

// colour carries exactly one meaning: direction. credits take the accent, debits stay plain text
@Composable
fun amountColor(direction: String): Color =
    if (direction == AppConfig.DIRECTION_CREDIT) MaterialTheme.colorScheme.tertiary
    else MaterialTheme.colorScheme.onSurface
