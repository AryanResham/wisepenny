package com.aryan.expensetracker.feature.chat

import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.db.entity.TransactionEntity
import com.aryan.expensetracker.core.llm.LlmClient
import com.aryan.expensetracker.core.result.AppResult
import java.time.LocalDate
import java.time.ZoneId

val TEST_ZONE: ZoneId = ZoneId.of(AppConfig.BANK_TIME_ZONE)

// replays canned response bodies in order and keeps every request, so a test can read what was sent
class ReplayingLlmClient(private val responses: List<String>) : LlmClient {

    val sentContents: MutableList<String> = mutableListOf()
    val sentPrompts: MutableList<String> = mutableListOf()

    override suspend fun generateJson(
        systemInstruction: String,
        userText: String,
        responseSchema: String,
    ): AppResult<String> = AppResult.Failure("not used")

    override suspend fun generateWithTools(
        systemInstruction: String,
        contentsJson: String,
        toolsJson: String,
    ): AppResult<String> {
        sentPrompts.add(systemInstruction)
        sentContents.add(contentsJson)
        // the last canned body repeats, which is how a model that keeps calling tools is simulated
        val index = minOf(sentContents.size - 1, responses.size - 1)
        return AppResult.Success(responses[index])
    }
}

// a client that never answers, for the offline and missing-key lines
class FailingLlmClient(private val reason: String) : LlmClient {

    override suspend fun generateJson(
        systemInstruction: String,
        userText: String,
        responseSchema: String,
    ): AppResult<String> = AppResult.Failure(reason)

    override suspend fun generateWithTools(
        systemInstruction: String,
        contentsJson: String,
        toolsJson: String,
    ): AppResult<String> = AppResult.Failure(reason)
}

fun functionCallBody(name: String, argumentsJson: String): String =
    """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"$name","args":$argumentsJson}}]}}]}"""

fun textBody(text: String): String =
    """{"candidates":[{"content":{"role":"model","parts":[{"text":"$text"}]}}]}"""

fun testRow(
    id: Long,
    amountPaise: Long,
    payeeRaw: String,
    day: String,
    tag: String? = null,
    description: String? = null,
): TransactionEntity = TransactionEntity(
    id = id,
    amountPaise = amountPaise,
    direction = AppConfig.DIRECTION_DEBIT,
    payeeRaw = payeeRaw,
    payeeNormalized = payeeRaw.uppercase(),
    tag = tag,
    description = description,
    occurredAt = epochOf(day),
    capturedAt = epochOf(day),
    referenceNumber = "992211",
    accountTail = "4321",
    source = AppConfig.SOURCE_REGEX,
    templateId = "upi-debit",
    placeFromMessage = null,
    latitude = 19.1234,
    longitude = 72.8765,
    rawMessage = "Rs.$amountPaise debited from a/c XX4321 to $payeeRaw ref 992211",
    messageHash = "hash-$id",
)

fun epochOf(day: String): Long =
    LocalDate.parse(day).atStartOfDay(TEST_ZONE).toInstant().toEpochMilli()
