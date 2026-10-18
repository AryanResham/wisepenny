package com.aryan.expensetracker.feature.chat

import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.llm.AgentTools
import com.aryan.expensetracker.core.llm.GeminiAgent
import com.aryan.expensetracker.core.llm.LlmClient
import com.aryan.expensetracker.pipeline.FakePayeeRuleDao
import com.aryan.expensetracker.pipeline.FakeTransactionDao
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FIND_ARGS = """{"from":"2026-10-01","to":"2026-10-31"}"""
private const val PROPOSE_ARGS = """{"ids":[1,2],"tag":"Food","description":"dinner with indra"}"""

class ChatViewModelTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val transactionDao = FakeTransactionDao()
    private val payeeRuleDao = FakePayeeRuleDao()

    private fun buildViewModel(client: LlmClient, hasApiKey: Boolean = true): ChatViewModel {
        val tools = AgentTools(transactionDao, payeeRuleDao, json, TEST_ZONE)
        val agent = GeminiAgent(client, json, TEST_ZONE)
        return ChatViewModel(agent, tools, hasApiKey)
    }

    private fun seedTwoRows() {
        transactionDao.rows.add(testRow(1, 45000, "SWIGGY", "2026-10-17"))
        transactionDao.rows.add(testRow(2, 12000, "UBER", "2026-10-17"))
    }

    @Test
    fun `find call is dispatched locally and the rows are fed back`() = runTest {
        seedTwoRows()
        val client = ReplayingLlmClient(
            listOf(
                functionCallBody("findTransactions", FIND_ARGS),
                textBody("You spent 570 rupees over 2 transactions."),
            )
        )
        val viewModel = buildViewModel(client)

        viewModel.send("how much in october")

        val messages = viewModel.messages.value
        assertTrue(messages[0] is ChatMessage.User)
        assertTrue(messages[1] is ChatMessage.ToolRun)
        assertTrue((messages[1] as ChatMessage.ToolRun).label.startsWith("Looking through"))
        assertEquals(
            "You spent 570 rupees over 2 transactions.",
            (messages[2] as ChatMessage.Assistant).text,
        )

        // the second request must carry the tool result back, with the rows and nothing private
        val secondRequest = client.sentContents[1]
        assertTrue(secondRequest.contains("functionResponse"))
        assertTrue(secondRequest.contains("SWIGGY"))
        assertFalse(secondRequest.contains("4321"))
        assertFalse(secondRequest.contains("992211"))
        assertFalse(secondRequest.contains("19.1234"))
        assertFalse(secondRequest.contains("debited from"))
    }

    @Test
    fun `a proposal writes nothing until confirm`() = runTest {
        seedTwoRows()
        val viewModel = buildViewModel(
            ReplayingLlmClient(listOf(functionCallBody("proposeTags", PROPOSE_ARGS)))
        )

        viewModel.send("dinner with indra yesterday")

        val proposal = viewModel.messages.value.last() as ChatMessage.Proposal
        assertEquals("Food", proposal.tag)
        assertEquals(2, proposal.rows.size)
        assertNull(proposal.outcome)
        assertNull(transactionDao.rows[0].tag)
        assertNull(payeeRuleDao.findByPayee("SWIGGY"))
    }

    @Test
    fun `confirm writes the tags and the payee rules`() = runTest {
        seedTwoRows()
        val viewModel = buildViewModel(
            ReplayingLlmClient(listOf(functionCallBody("proposeTags", PROPOSE_ARGS)))
        )
        viewModel.send("dinner with indra yesterday")
        val proposal = viewModel.messages.value.last() as ChatMessage.Proposal

        viewModel.confirm(proposal.id)

        assertEquals("Food", transactionDao.rows[0].tag)
        assertEquals("dinner with indra", transactionDao.rows[0].description)
        assertEquals("Food", transactionDao.rows[1].tag)
        assertEquals("Food", payeeRuleDao.findByPayee("SWIGGY")?.tag)
        assertEquals("Food", payeeRuleDao.findByPayee("UBER")?.tag)
        assertEquals(
            "Tagged 2 as Food",
            (viewModel.messages.value.last() as ChatMessage.Proposal).outcome,
        )
    }

    @Test
    fun `cancel writes nothing and leaves a record`() = runTest {
        seedTwoRows()
        val viewModel = buildViewModel(
            ReplayingLlmClient(listOf(functionCallBody("proposeTags", PROPOSE_ARGS)))
        )
        viewModel.send("dinner with indra yesterday")
        val proposal = viewModel.messages.value.last() as ChatMessage.Proposal

        viewModel.cancel(proposal.id)

        assertNull(transactionDao.rows[0].tag)
        assertNull(payeeRuleDao.findByPayee("SWIGGY"))
        assertEquals("Cancelled", (viewModel.messages.value.last() as ChatMessage.Proposal).outcome)
    }

    @Test
    fun `an unticked row is left out of the write`() = runTest {
        seedTwoRows()
        val viewModel = buildViewModel(
            ReplayingLlmClient(listOf(functionCallBody("proposeTags", PROPOSE_ARGS)))
        )
        viewModel.send("dinner with indra yesterday")
        val proposal = viewModel.messages.value.last() as ChatMessage.Proposal

        viewModel.toggleRow(proposal.id, 2)
        viewModel.confirm(proposal.id)

        assertEquals("Food", transactionDao.rows[0].tag)
        assertNull(transactionDao.rows[1].tag)
        assertNull(payeeRuleDao.findByPayee("UBER"))
        assertEquals(
            "Tagged 1 as Food",
            (viewModel.messages.value.last() as ChatMessage.Proposal).outcome,
        )
    }

    @Test
    fun `the step cap stops a model that only calls tools`() = runTest {
        seedTwoRows()
        val client = ReplayingLlmClient(listOf(functionCallBody("findTransactions", FIND_ARGS)))
        val viewModel = buildViewModel(client)

        viewModel.send("how much in october")

        assertEquals(AppConfig.MAX_AGENT_STEPS, client.sentContents.size)
        val last = viewModel.messages.value.last() as ChatMessage.Assistant
        assertTrue(last.text.startsWith("Sorry"))
    }

    @Test
    fun `a missing key sends nothing, so the empty state is all the user sees`() = runTest {
        val client = ReplayingLlmClient(listOf(textBody("never sent")))
        val viewModel = buildViewModel(client, hasApiKey = false)

        viewModel.send("how much in october")

        assertEquals(0, client.sentContents.size)
        assertTrue(viewModel.messages.value.isEmpty())
    }

    // the bug this pins: a proposal left its functionCall unanswered and the next request 400'd
    @Test
    fun `every functionCall in a request has a matching functionResponse`() = runTest {
        seedTwoRows()
        val client = ReplayingLlmClient(
            listOf(functionCallBody("proposeTags", PROPOSE_ARGS), textBody("Done."))
        )
        val viewModel = buildViewModel(client)
        viewModel.send("dinner with indra yesterday")

        viewModel.send("and how much in october")

        val request = client.sentContents[1]
        assertEquals(count(request, "functionCall"), count(request, "functionResponse"))
        assertTrue(request.contains("awaiting_confirmation"))
    }

    @Test
    fun `a confirmed proposal reports its outcome in the next request`() = runTest {
        seedTwoRows()
        val client = ReplayingLlmClient(
            listOf(functionCallBody("proposeTags", PROPOSE_ARGS), textBody("Done."))
        )
        val viewModel = buildViewModel(client)
        viewModel.send("dinner with indra yesterday")
        viewModel.confirm((viewModel.messages.value.last() as ChatMessage.Proposal).id)

        viewModel.send("and how much in october")

        val request = client.sentContents[1]
        assertTrue(request.contains("confirmed"))
        assertFalse(request.contains("awaiting_confirmation"))
    }

    // the bug this pins: replies never reached the transcript, so "what about travel?" had no subject
    @Test
    fun `a second question carries the first answer as a model turn`() = runTest {
        seedTwoRows()
        val client = ReplayingLlmClient(
            listOf(textBody("You spent 570 rupees on food."), textBody("And 120 on travel."))
        )
        val viewModel = buildViewModel(client)
        viewModel.send("how much on food last month")

        viewModel.send("what about travel")

        val request = client.sentContents[1]
        assertTrue(request.contains("You spent 570 rupees on food."))
        assertEquals(1, count(request, "\"role\":\"model\""))
    }

    private fun count(text: String, part: String): Int = text.split(part).size - 1

    @Test
    fun `being offline or rate limited shows one plain line`() = runTest {
        val offline = buildViewModel(FailingLlmClient("network error: UnknownHostException"))
        offline.send("how much in october")
        assertTrue(
            (offline.messages.value.last() as ChatMessage.Error).text.startsWith("No connection")
        )

        val throttled = buildViewModel(FailingLlmClient(AppConfig.LLM_REASON_RATE_LIMITED))
        throttled.send("how much in october")
        assertTrue((throttled.messages.value.last() as ChatMessage.Error).text.contains("busy"))
    }

    @Test
    fun `the system prompt carries no row text`() = runTest {
        seedTwoRows()
        val client = ReplayingLlmClient(
            listOf(functionCallBody("findTransactions", FIND_ARGS), textBody("Nothing much."))
        )
        val viewModel = buildViewModel(client)

        viewModel.send("how much in october")

        for (prompt in client.sentPrompts) {
            assertFalse(prompt.contains("SWIGGY"))
            assertFalse(prompt.contains("450"))
        }
    }
}
