package com.aryan.expensetracker.core.llm

import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.db.entity.PayeeRuleEntity
import com.aryan.expensetracker.core.db.entity.TransactionEntity
import com.aryan.expensetracker.feature.transactions.TransactionDao
import com.aryan.expensetracker.pipeline.tagging.PayeeRuleDao
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

const val TOOL_FIND_TRANSACTIONS: String = "findTransactions"
const val TOOL_PROPOSE_TAGS: String = "proposeTags"

// the whole tools array of the request; built once so the tag list stays the one in AppConfig
private val TAG_ENUM: String = AppConfig.TAGS.joinToString(", ") { "\"$it\"" }

val TOOL_DECLARATIONS: String = """
[
  {
    "functionDeclarations": [
      {
        "name": "$TOOL_FIND_TRANSACTIONS",
        "description": "Search the user's transactions on their phone. Returns matching rows, newest first. At least one argument is required.",
        "parameters": {
          "type": "object",
          "properties": {
            "from":   { "type": "string", "description": "earliest transaction date, inclusive, as YYYY-MM-DD" },
            "to":     { "type": "string", "description": "latest transaction date, inclusive, as YYYY-MM-DD" },
            "search": { "type": "string", "description": "text to look for in the payee or the description, case-insensitive" },
            "tag":    { "type": "string", "enum": [$TAG_ENUM] },
            "limit":  { "type": "integer", "description": "maximum rows to return, at most ${AppConfig.MAX_TOOL_ROWS}" }
          }
        }
      },
      {
        "name": "$TOOL_PROPOSE_TAGS",
        "description": "Propose a tag and a description for transactions the user described. Writes nothing: the user sees a card and confirms it.",
        "parameters": {
          "type": "object",
          "properties": {
            "ids":         { "type": "array", "items": { "type": "integer" }, "description": "ids from a findTransactions result" },
            "tag":         { "type": "string", "enum": [$TAG_ENUM] },
            "description": { "type": "string", "description": "a few words in the user's own wording, such as 'dinner with indra'" }
          },
          "required": ["ids", "tag"]
        }
      }
    ]
  }
]
""".trimIndent()

// runs the model's tool calls against the local database; nothing here writes without a confirmed tap
class AgentTools(
    private val transactionDao: TransactionDao,
    private val payeeRuleDao: PayeeRuleDao,
    private val json: Json,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    // the functionResponse body for a search; an unscoped call is refused instead of dumping the table
    suspend fun findTransactions(arguments: JsonObject): String {
        val from = readText(arguments, "from")
        val to = readText(arguments, "to")
        val search = readText(arguments, "search")
        val tag = readText(arguments, "tag")
        if (from == null && to == null && search == null && tag == null) {
            return errorResponse("no filters given: ask for a date range, a search term or a tag")
        }

        val rows = snapshot().filter { matches(it, dayStart(from), dayAfter(to), search, tag) }
            .take(effectiveLimit(arguments))
        return json.encodeToString(
            buildJsonObject {
                put("count", rows.size)
                put("rows", buildJsonArray { for (row in rows) add(toModelRow(row)) })
            }
        )
    }

    // the rows behind a proposal, so the card shows what the model actually picked out
    suspend fun rowsFor(ids: List<Long>): List<TransactionEntity> {
        if (ids.isEmpty()) return emptyList()
        return snapshot().filter { ids.contains(it.id) }
    }

    // the only write in chat, and it runs after the confirm tap: the tag, the note, and the learned rule
    suspend fun applyTags(ids: List<Long>, tag: String, description: String?): Int {
        val rows = rowsFor(ids)
        for (row in rows) {
            transactionDao.update(row.copy(tag = tag, description = description ?: row.description))
            if (row.payeeNormalized.isBlank()) continue
            payeeRuleDao.upsert(PayeeRuleEntity(row.payeeNormalized, tag, System.currentTimeMillis()))
        }
        return rows.size
    }

    // rows arrive newest first from the dao, so one snapshot is all the ordering the tools need
    private suspend fun snapshot(): List<TransactionEntity> = transactionDao.observeAll().first()

    private fun matches(
        row: TransactionEntity,
        fromMillis: Long?,
        toMillis: Long?,
        search: String?,
        tag: String?,
    ): Boolean {
        if (fromMillis != null && row.occurredAt < fromMillis) return false
        if (toMillis != null && row.occurredAt >= toMillis) return false
        if (tag != null && !tag.equals(row.tag, ignoreCase = true)) return false
        if (search == null) return true
        return row.payeeRaw.contains(search, ignoreCase = true) ||
            row.description.orEmpty().contains(search, ignoreCase = true)
    }

    // the privacy line: id, date, rupees, payee, tag, description and nothing else ever leaves the phone
    private fun toModelRow(row: TransactionEntity): JsonObject = buildJsonObject {
        put("id", row.id)
        put("date", Instant.ofEpochMilli(row.occurredAt).atZone(zone).toLocalDate().toString())
        put("amount", toRupees(row.amountPaise, row.direction))
        put("payee", row.payeeRaw)
        put("tag", row.tag)
        put("description", row.description)
    }

    // signed rupees as a decimal string, so a credit reads positive and no money becomes a Double
    private fun toRupees(amountPaise: Long, direction: String): String {
        val signed = if (direction == AppConfig.DIRECTION_CREDIT) amountPaise else -amountPaise
        return BigDecimal(signed)
            .divide(BigDecimal(AppConfig.PAISE_PER_RUPEE))
            .setScale(AppConfig.MONEY_DECIMAL_PLACES, RoundingMode.HALF_UP)
            .toPlainString()
    }

    private fun effectiveLimit(arguments: JsonObject): Int {
        val asked = readInt(arguments, "limit") ?: return AppConfig.MAX_TOOL_ROWS
        if (asked < 1) return AppConfig.MAX_TOOL_ROWS
        return minOf(asked, AppConfig.MAX_TOOL_ROWS)
    }

    private fun dayStart(date: String?): Long? = parseDay(date)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()

    // "to" is inclusive to the user, so the cutoff is the start of the next day
    private fun dayAfter(date: String?): Long? =
        parseDay(date)?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()

    private fun parseDay(date: String?): LocalDate? {
        if (date.isNullOrBlank()) return null
        return try {
            LocalDate.parse(date)
        } catch (error: DateTimeParseException) {
            null
        }
    }

    private fun errorResponse(message: String): String =
        json.encodeToString(buildJsonObject { put("error", message) })
}

// a model may send a json null, an empty string or the wrong type; all of them mean "not given"
fun readText(arguments: JsonObject, name: String): String? {
    val value = arguments[name] ?: return null
    val text = try {
        value.jsonPrimitive.content
    } catch (error: IllegalArgumentException) {
        return null
    }
    if (text.isBlank() || text == "null") return null
    return text
}

fun readInt(arguments: JsonObject, name: String): Int? {
    val value = arguments[name] ?: return null
    return try {
        value.jsonPrimitive.int
    } catch (error: IllegalArgumentException) {
        null
    } catch (error: NumberFormatException) {
        null
    }
}

// ids come back as a json array of numbers; anything unreadable is dropped rather than guessed
fun readIds(arguments: JsonObject, name: String): List<Long> {
    val array = arguments[name] ?: return emptyList()
    return try {
        array.jsonArray.mapNotNull { element ->
            try {
                element.jsonPrimitive.long
            } catch (error: IllegalArgumentException) {
                null
            } catch (error: NumberFormatException) {
                null
            }
        }
    } catch (error: IllegalArgumentException) {
        emptyList()
    }
}
