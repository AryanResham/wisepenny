package com.aryan.expensetracker.pipeline.categorization

import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.db.entity.CategoryEntity
import com.aryan.expensetracker.core.db.entity.MerchantRuleEntity
import com.aryan.expensetracker.core.llm.LlmClient
import com.aryan.expensetracker.core.result.AppResult
import com.aryan.expensetracker.feature.categories.CategoryDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val FOOD = CategoryEntity(1L, "Food", AppConfig.CATEGORY_KIND_EXPENSE, true, 0)
private val TRAVEL = CategoryEntity(2L, "Travel", AppConfig.CATEGORY_KIND_EXPENSE, true, 1)
private val INCOME = CategoryEntity(3L, "Income", AppConfig.CATEGORY_KIND_INCOME, true, 2)

// records the prompt so a test can prove which category names actually left the phone
private class FakeLlmClient(private val response: AppResult<String>) : LlmClient {
    var lastUserText: String = ""

    override suspend fun generateJson(
        systemInstruction: String,
        userText: String,
        responseSchema: String,
    ): AppResult<String> {
        lastUserText = userText
        return response
    }
}

private fun clientAnswering(responseText: String) = FakeLlmClient(AppResult.Success(responseText))

private class FakeCategoryDao(private val categories: List<CategoryEntity>) : CategoryDao {
    override fun observeAll(): Flow<List<CategoryEntity>> = flowOf(categories)
    override suspend fun getAll(): List<CategoryEntity> = categories
    override suspend fun findIdByName(name: String): Long? =
        categories.firstOrNull { it.name == name }?.id
    override suspend fun count(): Int = categories.size
    override suspend fun insert(category: CategoryEntity): Long = 0L
    override suspend fun update(category: CategoryEntity) = Unit
    override suspend fun delete(category: CategoryEntity) = Unit
}

private class FakeMerchantRuleDao(private val rules: List<MerchantRuleEntity>) : MerchantRuleDao {
    override suspend fun findByMerchant(
        normalizedMerchant: String,
        direction: String,
    ): MerchantRuleEntity? = rules.firstOrNull {
        it.normalizedMerchant == normalizedMerchant && it.direction == direction
    }

    override suspend fun recentUserRules(limit: Int): List<MerchantRuleEntity> = rules.take(limit)
    override suspend fun upsert(rule: MerchantRuleEntity) = Unit
    override suspend fun deleteByCategory(categoryId: Long) = Unit
}

class LlmCategorizerTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val userRules = listOf(
        MerchantRuleEntity("ZOMATO", AppConfig.DIRECTION_DEBIT, FOOD.id, "USER", 0L)
    )

    private fun categorizerUsing(client: FakeLlmClient) =
        LlmCategorizer(
            client,
            FakeCategoryDao(listOf(FOOD, TRAVEL, INCOME)),
            FakeMerchantRuleDao(userRules),
            json,
        )

    private suspend fun categoryIdFrom(categorizer: LlmCategorizer, merchant: String, direction: String) =
        (categorizer.categoryIdFor(merchant, direction) as AppResult.Success).data

    @Test
    fun mapsChosenNameToItsId() = runTest {
        val categorizer = categorizerUsing(clientAnswering("""{"category":"Food"}"""))

        assertEquals(FOOD.id, categoryIdFrom(categorizer, "SWIGGY", AppConfig.DIRECTION_DEBIT))
    }

    @Test
    fun returnsNullForAnUnknownName() = runTest {
        val categorizer = categorizerUsing(clientAnswering("""{"category":"Nonsense"}"""))

        assertNull(categoryIdFrom(categorizer, "SWIGGY", AppConfig.DIRECTION_DEBIT))
    }

    @Test
    fun returnsNullWhenTheModelDeclines() = runTest {
        val categorizer = categorizerUsing(clientAnswering("""{"category":null}"""))

        assertNull(categoryIdFrom(categorizer, "SOME NEW SHOP", AppConfig.DIRECTION_DEBIT))
    }

    // the reason has to survive the call, or the pipeline has nothing to log
    @Test
    fun aClientFailureComesBackWithItsReason() = runTest {
        val categorizer = categorizerUsing(FakeLlmClient(AppResult.Failure("http 503")))

        val result = categorizer.categoryIdFor("SWIGGY", AppConfig.DIRECTION_DEBIT)

        assertEquals("http 503", (result as AppResult.Failure).reason)
    }

    @Test
    fun creditOnlySeesIncomeCategories() = runTest {
        val client = clientAnswering("""{"category":"Income"}""")
        val categorizer = categorizerUsing(client)

        assertEquals(INCOME.id, categoryIdFrom(categorizer, "ACME PAYROLL", AppConfig.DIRECTION_CREDIT))
        assertTrue(client.lastUserText.contains("Income"))
        assertFalse(client.lastUserText.contains("Food"))
        assertFalse(client.lastUserText.contains("Travel"))
    }
}
