package com.aryan.expensetracker.pipeline.extraction

import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.money.parseAmountToPaise
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.regex.Matcher
import java.util.regex.Pattern

private const val GROUP_AMOUNT = "amount"
private const val GROUP_PAYEE = "payee"
private const val GROUP_ACCOUNT = "acct"
private const val GROUP_DATE = "date"
private const val GROUP_REFERENCE = "ref"
private const val GROUP_PLACE = "place"

private val BANK_ZONE: ZoneId = ZoneId.of(AppConfig.BANK_TIME_ZONE)
private val DATE_FORMATTERS: List<DateTimeFormatter> =
    AppConfig.BANK_DATE_FORMATS.map { DateTimeFormatter.ofPattern(it) }
private val YEAR_COMPLETING_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern(AppConfig.BANK_DATE_WITH_YEAR_FORMAT)

class RegexExtractor(private val templates: List<MessageTemplate>) {

    // first template whose match passes the sanity checks wins; a bad value is worse than a miss
    fun extract(body: String, receivedAt: Long): TransactionDraft? {
        val trimmed = body.trim()
        for (template in templates) {
            val matcher = template.pattern.matcher(trimmed)
            if (!matcher.find()) continue
            val draft = toDraft(template, matcher, receivedAt) ?: continue
            return draft
        }
        return null
    }

    // null means "treat this template as not matched", so the loop keeps trying the others
    private fun toDraft(
        template: MessageTemplate,
        matcher: Matcher,
        receivedAt: Long,
    ): TransactionDraft? {
        val amountPaise = parseAmountToPaise(groupOrNull(matcher, GROUP_AMOUNT) ?: return null)
        if (amountPaise == null || amountPaise <= 0) return null

        val payee = (groupOrNull(matcher, GROUP_PAYEE) ?: template.fixedPayee)?.trim()
        if (payee.isNullOrBlank() || payee.length > AppConfig.MAX_PAYEE_LENGTH) return null

        return TransactionDraft(
            amountPaise = amountPaise,
            direction = template.direction,
            payeeRaw = payee,
            referenceNumber = groupOrNull(matcher, GROUP_REFERENCE),
            accountTail = groupOrNull(matcher, GROUP_ACCOUNT)?.takeLast(AppConfig.ACCOUNT_TAIL_LENGTH),
            occurredAt = parseBankDate(groupOrNull(matcher, GROUP_DATE), receivedAt),
            source = AppConfig.SOURCE_REGEX,
            templateId = template.id,
            placeFromMessage = groupOrNull(matcher, GROUP_PLACE),
        )
    }
}

// Matcher throws for a name the pattern never declared, so the pattern text is asked first
private fun groupOrNull(matcher: Matcher, name: String): String? {
    if (!hasGroup(matcher.pattern(), name)) return null
    return matcher.group(name)?.takeIf { it.isNotBlank() }
}

private fun hasGroup(pattern: Pattern, name: String): Boolean =
    pattern.pattern().contains("(?<$name>")

// an unparseable date is not a reason to reject the match; the arrival time stands in for it
private fun parseBankDate(text: String?, receivedAt: Long): Long {
    if (text.isNullOrBlank()) return receivedAt
    for (formatter in DATE_FORMATTERS) {
        val parsed = parseWith(text, formatter) ?: continue
        return startOfDay(parsed)
    }
    return completeWithArrivalYear(text, receivedAt)
}

// the "Amt Sent" messages write only day and month, so the arrival year finishes them
private fun completeWithArrivalYear(text: String, receivedAt: Long): Long {
    val year = Instant.ofEpochMilli(receivedAt).atZone(BANK_ZONE).year
    val parsed = parseWith("${text.replace('/', '-')}-$year", YEAR_COMPLETING_FORMATTER)
    return if (parsed == null) receivedAt else startOfDay(parsed)
}

private fun parseWith(text: String, formatter: DateTimeFormatter): LocalDate? {
    return try {
        LocalDate.parse(text, formatter)
    } catch (error: DateTimeParseException) {
        null
    }
}

private fun startOfDay(date: LocalDate): Long =
    date.atStartOfDay(BANK_ZONE).toInstant().toEpochMilli()
