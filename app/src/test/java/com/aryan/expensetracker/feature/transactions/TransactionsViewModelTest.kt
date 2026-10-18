package com.aryan.expensetracker.feature.transactions

import com.aryan.expensetracker.feature.chat.testRow
import com.aryan.expensetracker.pipeline.FakePayeeRuleDao
import com.aryan.expensetracker.pipeline.FakePendingMessageDao
import com.aryan.expensetracker.pipeline.FakeTransactionDao
import com.aryan.expensetracker.pipeline.tagging.AutoTagger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelTest {

    private val transactionDao = FakeTransactionDao()
    private val payeeRuleDao = FakePayeeRuleDao()
    private val autoTagger = AutoTagger(payeeRuleDao, transactionDao)

    // the view model launches on Main, so a test dispatcher stands in and runs those writes eagerly
    @Before
    fun useTestDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun releaseDispatcher() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): TransactionsViewModel = TransactionsViewModel(
        transactionDao,
        payeeRuleDao,
        FakePendingMessageDao(emptyList()),
        CaptureMark { 0L },
    )

    @Test
    fun `tagging a payee teaches the rule the next message is tagged by`() = runTest {
        val row = testRow(1, 45000, "SWIGGY", "2026-10-17")
        transactionDao.rows.add(row)
        val viewModel = buildViewModel()

        viewModel.tagOne(row, "Food", null)
        advanceUntilIdle()

        assertEquals("Food", transactionDao.rows[0].tag)
        assertEquals("Food", autoTagger.tagFor("SWIGGY", 45000))
    }

    // the bug this pins: the rule outlived the tag, so AutoTagger undid the user's untag
    @Test
    fun `clearing a tag unlearns the payee, so the next message arrives untagged`() = runTest {
        val row = testRow(1, 45000, "SWIGGY", "2026-10-17")
        transactionDao.rows.add(row)
        val viewModel = buildViewModel()
        viewModel.tagOne(row, "Food", null)
        advanceUntilIdle()

        viewModel.tagOne(transactionDao.rows[0], null, null)
        advanceUntilIdle()

        assertNull(transactionDao.rows[0].tag)
        assertNull(autoTagger.tagFor("SWIGGY", 45000))
    }
}
