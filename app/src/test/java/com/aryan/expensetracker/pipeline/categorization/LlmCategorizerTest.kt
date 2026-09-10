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
private class FakeLlmClient(private val responseText: String) : LlmClient {
    var lastUserText: String = ""

    override suspend fun generateJson(
        systemInstruction: String,
        userText: String,
        responseSchema: String,
    ): AppResult<String> {
        lastUserText = userText
        return AppResult.Success(responseText)
    }
}

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

    @Test
    fun mapsChosenNameToItsId() = runTest {
        val categorizer = categorizerUsing(FakeLlmClient("""{"category":"Food"}"""))

        assertEquals(FOOD.id, categorizer.categoryIdFor("SWIGGY", AppConfig.DIRECTION_DEBIT))
    }

    @Test
    fun returnsNullForAnUnknownName() = runTest {
        val categorizer = categorizerUsing(FakeLlmClient("""{"category":"Nonsense"}"""))

        assertNull(categorizer.categoryIdFor("SWIGGY", AppConfig.DIRECTION_DEBIT))
    }

    @Test
    fun returnsNullWhenTheModelDeclines() = runTest {
        val categorizer = categorizerUsing(FakeLlmClient("""{"category":null}"""))

        assertNull(categorizer.categoryIdFor("SOME NEW SHOP", AppConfig.DIRECTION_DEBIT))
    }

    @Test
    fun creditOnlySeesIncomeCategories() = runTest {
        val client = FakeLlmClient("""{"category":"Income"}""")
        val categorizer = categorizerUsing(client)

        assertEquals(INCOME.id, categorizer.categoryIdFor("ACME PAYROLL", AppConfig.DIRECTION_CREDIT))
        assertTrue(client.lastUserText.contains("Income"))
        assertFalse(client.lastUserText.contains("Food"))
        assertFalse(client.lastUserText.contains("Travel"))
    }
}
