package com.aryan.expensetracker.core.llm

import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.feature.chat.TEST_ZONE
import com.aryan.expensetracker.feature.chat.testRow
import com.aryan.expensetracker.pipeline.FakePayeeRuleDao
import com.aryan.expensetracker.pipeline.FakeTransactionDao
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val transactionDao = FakeTransactionDao()
    private val payeeRuleDao = FakePayeeRuleDao()
    private val tools = AgentTools(transactionDao, payeeRuleDao, json, TEST_ZONE)

    private fun arguments(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    @Test
    fun `an unscoped search is refused instead of returning the table`() = runTest {
        transactionDao.rows.add(testRow(1, 45000, "SWIGGY", "2026-10-17"))

        val response = tools.findTransactions(arguments("""{"from":null,"search":null}"""))

        assertTrue(response.contains("error"))
        assertFalse(response.contains("SWIGGY"))
    }

    @Test
    fun `rows carry only the six allowed fields`() = runTest {
        transactionDao.rows.add(testRow(1, 45000, "SWIGGY", "2026-10-17", description = "lunch"))

        val response = tools.findTransactions(arguments("""{"from":"2026-10-01","to":"2026-10-31"}"""))

        val row = json.parseToJsonElement(response).jsonObject["rows"]!!.jsonArray[0].jsonObject
        assertEquals(setOf("id", "date", "amount", "payee", "tag", "description"), row.keys)
        assertEquals("-450.00", row["amount"].toString().trim('"'))
        assertEquals("2026-10-17", row["date"].toString().trim('"'))
    }

    @Test
    fun `a date range excludes what falls outside it`() = runTest {
        transactionDao.rows.add(testRow(1, 45000, "SWIGGY", "2026-10-17"))
        transactionDao.rows.add(testRow(2, 12000, "UBER", "2026-09-30"))

        val response = tools.findTransactions(arguments("""{"from":"2026-10-01","to":"2026-10-31"}"""))

        assertEquals(1, json.parseToJsonElement(response).jsonObject["count"].toString().toInt())
        assertFalse(response.contains("UBER"))
    }

    @Test
    fun `a search matches the payee or the description, case-insensitively`() = runTest {
        transactionDao.rows.add(testRow(1, 45000, "SWIGGY", "2026-10-17"))
        transactionDao.rows.add(testRow(2, 12000, "UBER", "2026-10-17", description = "Dinner out"))

        val response = tools.findTransactions(arguments("""{"search":"dinner"}"""))

        assertTrue(response.contains("UBER"))
        assertFalse(response.contains("SWIGGY"))
    }

    @Test
    fun `the row cap holds whatever limit the model asks for`() = runTest {
        for (index in 1..AppConfig.MAX_TOOL_ROWS + 10) {
            transactionDao.rows.add(testRow(index.toLong(), 1000, "SHOP$index", "2026-10-17"))
        }

        val response = tools.findTransactions(arguments("""{"search":"SHOP","limit":500}"""))

        assertEquals(
            AppConfig.MAX_TOOL_ROWS,
            json.parseToJsonElement(response).jsonObject["count"].toString().toInt(),
        )
    }
}
