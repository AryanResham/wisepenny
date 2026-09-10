package com.aryan.expensetracker.core.config

object AppConfig {

    // only messages from a sender containing this word are ours; the sole local filter in v1
    const val BANK_SENDER_KEYWORD: String = "HDFC"

    val MONEY_WORDS: List<String> = listOf(
        "debited", "credited", "sent", "paid", "spent", "withdrawn", "received",
    )

    const val MAX_MESSAGE_LENGTH: Int = 1000

    // two messages for one purchase normally arrive within a few days of each other
    const val DEDUP_LOOKBACK_MS: Long = 7L * 24 * 60 * 60 * 1000

    // bank timestamps carry no zone; HDFC sends IST
    const val BANK_TIME_ZONE: String = "Asia/Kolkata"

    const val GEMINI_MODEL: String = "gemini-2.5-flash"
    const val GEMINI_ENDPOINT: String =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"
    const val GEMINI_TIMEOUT_SECONDS: Long = 30

    const val MAX_CORRECTION_EXAMPLES: Int = 15

    const val MAX_PROCESSING_ATTEMPTS: Int = 10

    const val WORKER_BACKOFF_SECONDS: Long = 30

    const val MAX_LLM_CALLS_PER_DAY: Int = 200

    const val BACKFILL_DAYS: Int = 90

    val MERCHANT_NOISE_WORDS: Set<String> = setOf(
        "PVT", "LTD", "PRIVATE", "LIMITED", "INDIA", "IN", "POS", "PAYMENT",
    )

    // seeded on first run; the user can add, rename and delete freely afterwards
    val DEFAULT_EXPENSE_CATEGORIES: List<String> = listOf(
        "Food", "Travel", "Groceries", "Utilities", "Housing", "Entertainment", "Investments", "Misc",
    )
    val DEFAULT_INCOME_CATEGORIES: List<String> = listOf("Income", "Refund")

    const val DIRECTION_DEBIT: String = "DEBIT"
    const val DIRECTION_CREDIT: String = "CREDIT"

    // who decided a row: the model, or the user correcting it afterwards
    const val RULE_SOURCE_LLM: String = "LLM"
    const val RULE_SOURCE_USER: String = "USER"

    const val ACCOUNT_TAIL_LENGTH: Int = 4

    // what a finished message left behind, so a re-delivered sms is recognised and skipped
    const val PROCESSED_SAVED: String = "SAVED"
    const val PROCESSED_IGNORED: String = "IGNORED"
    const val PROCESSED_SUPERSEDED: String = "SUPERSEDED"

    const val CATEGORY_KIND_EXPENSE: String = "EXPENSE"
    const val CATEGORY_KIND_INCOME: String = "INCOME"

    const val CURRENCY_SYMBOL: String = "₹"
    const val PAISE_PER_RUPEE: Int = 100
    const val MONEY_DECIMAL_PLACES: Int = 2

    const val DATABASE_NAME: String = "expense-tracker.db"
    const val PREFS_NAME: String = "expense-tracker-prefs"
}
