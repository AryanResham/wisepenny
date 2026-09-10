package com.aryan.expensetracker.pipeline.extraction

import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.llm.GeminiSchemas
import com.aryan.expensetracker.core.llm.LlmClient
import com.aryan.expensetracker.core.money.parseAmountToPaise
import com.aryan.expensetracker.core.result.AppResult
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val SYSTEM_INSTRUCTION = """
You read SMS messages from Indian banks and return structured data about the transaction they describe.

Set isTransaction to false when the message is not a completed money movement. One-time passwords,
balance alerts, offers, reminders, due-date nudges and marketing are all false. "Cashback on your
next order" is marketing, not a transaction.

amount is the rupee value as a plain decimal string: no currency symbol, no thousands separators.
direction is DEBIT when money left the account and CREDIT when money arrived.
merchant is the counterparty EXACTLY as written in the message. Copy it character for character.
Do not expand abbreviations, do not strip suffixes, do not tidy spacing, do not guess a brand name.
occurredOn is the transaction date as YYYY-MM-DD, or null when the message does not state one.
"""

@Serializable
private data class ExtractionJson(
    val isTransaction: Boolean,
    val amount: String? = null,
    val direction: String? = null,
    val merchant: String? = null,
    val referenceNumber: String? = null,
    val accountTail: String? = null,
    val occurredOn: String? = null,
)

class LlmExtractor(private val llmClient: LlmClient, private val json: Json) {

    // turns one message body into a draft; every failure comes back as a reason for the caller to log
    suspend fun extract(body: String, receivedAt: Long): ExtractionOutcome {
        val response = llmClient.generateJson(SYSTEM_INSTRUCTION, body, GeminiSchemas.EXTRACTION)
        val responseText = when (response) {
            is AppResult.Failure -> return ExtractionOutcome.Failed(response.reason)
            is AppResult.Success -> response.data
        }

        val parsed = parseResponse(responseText) ?: return ExtractionOutcome.Failed("unreadable json")
        if (!parsed.isTransaction) return ExtractionOutcome.NotATransaction

        val draft = toDraft(parsed, receivedAt) ?: return ExtractionOutcome.Failed("incomplete fields")
        // a stray "debit" would quietly miss every direction-keyed rule and dedup lookup
        if (!isAllowedDirection(draft.direction)) return ExtractionOutcome.Failed("bad direction")
        return ExtractionOutcome.Transaction(draft)
    }

    private fun isAllowedDirection(direction: String): Boolean =
        direction == AppConfig.DIRECTION_DEBIT || direction == AppConfig.DIRECTION_CREDIT

    private fun parseResponse(responseText: String): ExtractionJson? {
        return try {
            json.decodeFromString<ExtractionJson>(responseText)
        } catch (error: Exception) {
            null
        }
    }

    // a "true" answer missing any of the three essentials is worse than no answer at all
    private fun toDraft(parsed: ExtractionJson, receivedAt: Long): TransactionDraft? {
        val amountPaise = parseAmountToPaise(parsed.amount ?: return null) ?: return null
        val direction = parsed.direction ?: return null
        val merchant = parsed.merchant ?: return null
        if (merchant.isBlank()) return null

        return TransactionDraft(
            amountPaise = amountPaise,
            direction = direction,
            merchantRaw = merchant.trim(),
            referenceNumber = parsed.referenceNumber,
            accountTail = parsed.accountTail?.takeLast(AppConfig.ACCOUNT_TAIL_LENGTH),
            occurredAt = parseBankDate(parsed.occurredOn, receivedAt),
            source = AppConfig.RULE_SOURCE_LLM,
        )
    }

    // bank dates carry no zone, so they are read as IST; arrival time stands in when there is none
    private fun parseBankDate(isoDate: String?, receivedAt: Long): Long {
        if (isoDate.isNullOrBlank()) return receivedAt
        return try {
            LocalDate.parse(isoDate)
                .atStartOfDay(ZoneId.of(AppConfig.BANK_TIME_ZONE))
                .toInstant()
                .toEpochMilli()
        } catch (error: DateTimeParseException) {
            receivedAt
        }
    }
}
