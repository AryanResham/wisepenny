package com.aryan.expensetracker.pipeline.categorization

import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.db.entity.CategoryEntity
import com.aryan.expensetracker.core.llm.GeminiSchemas
import com.aryan.expensetracker.core.llm.LlmClient
import com.aryan.expensetracker.core.result.AppResult
import com.aryan.expensetracker.feature.categories.CategoryDao
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val SYSTEM_INSTRUCTION = """
You assign a spending category to a merchant. You are given the available category names, some of the
user's own past choices, and one merchant to classify.

Reply with exactly one of the given category names, copied exactly. Reply with null when none of them
fit: a wrong category is worse than no category, because no category asks the user instead of guessing.
When the user's past choices disagree with the obvious answer, follow the user. They know their spending.
"""

@Serializable
private data class CategorizationJson(val category: String? = null)

class LlmCategorizer(
    private val llmClient: LlmClient,
    private val categoryDao: CategoryDao,
    private val merchantRuleDao: MerchantRuleDao,
    private val json: Json,
) {

    // only the merchant name and direction leave the phone; a failure comes back as a reason to log
    suspend fun categoryIdFor(normalizedMerchant: String, direction: String): AppResult<Long?> {
        if (normalizedMerchant.isBlank()) return AppResult.Success(null)

        val kind = kindFor(direction)
        val categories = categoryDao.getAll().filter { it.kind == kind }
        if (categories.isEmpty()) return AppResult.Success(null)

        val response = llmClient.generateJson(
            SYSTEM_INSTRUCTION,
            buildPrompt(normalizedMerchant, categories),
            GeminiSchemas.CATEGORIZATION,
        )
        val responseText = when (response) {
            is AppResult.Failure -> return response
            is AppResult.Success -> response.data
        }

        val chosenName = readChosenCategory(responseText) ?: return AppResult.Success(null)
        val chosenId = categories.firstOrNull { it.name.equals(chosenName, ignoreCase = true) }?.id
        return AppResult.Success(chosenId)
    }

    // past corrections go in as examples; this is the app learning the person, not the shop
    private suspend fun buildPrompt(merchant: String, categories: List<CategoryEntity>): String {
        val names = categories.joinToString(", ") { it.name }
        val rules = merchantRuleDao.recentUserRules(AppConfig.MAX_CORRECTION_EXAMPLES)

        return buildString {
            appendLine("Available categories: $names")
            if (rules.isNotEmpty()) {
                appendLine()
                appendLine("The user has previously chosen:")
                for (rule in rules) {
                    val categoryName = categories.firstOrNull { it.id == rule.categoryId }?.name
                    if (categoryName != null) appendLine("${rule.normalizedMerchant} -> $categoryName")
                }
            }
            appendLine()
            append("Merchant to classify: $merchant")
        }
    }

    private fun readChosenCategory(responseText: String): String? {
        return try {
            json.decodeFromString<CategorizationJson>(responseText).category
        } catch (error: Exception) {
            null
        }
    }

    // money coming in is categorized against income categories, not expense ones
    private fun kindFor(direction: String): String {
        if (direction == AppConfig.DIRECTION_CREDIT) return AppConfig.CATEGORY_KIND_INCOME
        return AppConfig.CATEGORY_KIND_EXPENSE
    }
}
