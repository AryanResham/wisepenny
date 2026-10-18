package com.aryan.expensetracker.core.config

object AppConfig {

    // only messages from a sender containing this word are ours; the first local gate
    const val BANK_SENDER_KEYWORD: String = "HDFC"

    // deliberately loose: this gate is before the queue, so anything it drops is lost for good.
    // "Money Transfer:Rs..." carries none of the verbs, which silently cost 201 real transactions.
    // junk that slips past is free - NON_TRANSACTION_MARKERS catches it downstream.
    val MONEY_WORDS: List<String> = listOf(
        "debited", "credited", "sent", "paid", "spent", "withdrawn", "received",
        "transfer", "upi", "a/c", "rs.",
    )

    const val MAX_MESSAGE_LENGTH: Int = 1000

    // two messages for one purchase normally arrive within a few days of each other
    const val DEDUP_LOOKBACK_MS: Long = 7L * 24 * 60 * 60 * 1000

    // bank timestamps carry no zone; HDFC sends IST
    const val BANK_TIME_ZONE: String = "Asia/Kolkata"

    // the shapes HDFC writes dates in; a two-field date borrows its year from the arrival time
    val BANK_DATE_FORMATS: List<String> = listOf(
        "dd/MM/yy", "dd-MM-yy", "dd/MM/yyyy", "dd-MM-yyyy", "yyyy-MM-dd",
    )
    const val BANK_DATE_WITH_YEAR_FORMAT: String = "dd-MM-yyyy"

    const val GEMINI_MODEL: String = "gemini-3.6-flash"
    const val GEMINI_ENDPOINT: String =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"
    const val GEMINI_TIMEOUT_SECONDS: Long = 30

    const val MAX_PROCESSING_ATTEMPTS: Int = 10

    const val WORKER_BACKOFF_SECONDS: Long = 30

    const val MAX_LLM_CALLS_PER_DAY: Int = 1000

    // gap between model calls so a big backfill cannot trip google's per-minute limit
    const val LLM_MIN_GAP_MS: Long = 1500

    // the model said slow down, or the day's quota is spent; both mean wait, not fail
    const val LLM_REASON_RATE_LIMITED: String = "rate limited"
    const val LLM_REASON_DAILY_CAP: String = "daily cap"

    // regex does the work now, so importing the whole inbox costs nothing
    const val BACKFILL_DAYS: Int = 3650

    const val TEMPLATES_ASSET: String = "templates.json"

    // a payee longer than this is a parse that ran past its terminator, not a shop name
    const val MAX_PAYEE_LENGTH: Int = 60

    // a message carrying any of these is an alert or an offer, never a completed payment
    val NON_TRANSACTION_MARKERS: List<String> = listOf(
        "OTP", "E-Mandate!", "SmartPay Scheduled", "will auto-debit", "will be deducted",
        "Forex Markup", "DECLINED", "Declined!", "Login Alert", "PIN Change",
        "Process Initiated", "has requested Rs", "Upcoming mandate", "Mandate Cancelled",
        "successfully cancelled", "successfully created", "registration on", "statement",
        "reward point", "Dear Customer", "will be charged", "balance", "apply now",
    )

    // trailing company words differ between messages for one shop, so they are dropped
    val PAYEE_CORPORATE_SUFFIXES: Set<String> = setOf(
        "LIMITED", "LTD", "PRIVATE", "PVT", "INDIA", "SERVICES", "ENTERPRISES",
    )

    val TAGS: List<String> = listOf(
        "Food", "Groceries", "Travel", "Shopping", "Entertainment", "Utilities",
        "Health", "Investments", "Rent", "Transfers", "Daily spends", "Income", "Other",
    )

    const val TAG_DAILY_SPENDS: String = "Daily spends"

    // a small spend at a payee seen once is noise; auto-tagging it keeps it from ever being asked about
    const val DAILY_SPEND_MAX_PAISE: Long = 30000
    const val PAYEE_SEEN_ONCE: Int = 1

    const val DIRECTION_DEBIT: String = "DEBIT"
    const val DIRECTION_CREDIT: String = "CREDIT"

    // who read the row: a template, or the model standing in for one
    const val SOURCE_REGEX: String = "REGEX"
    const val SOURCE_LLM: String = "LLM"

    // stands in for a message nothing could read, so it is visible instead of deleted
    const val STUB_PAYEE: String = "Could not process"

    const val ACCOUNT_TAIL_LENGTH: Int = 4

    // what a finished message left behind, so a re-delivered sms is recognised and skipped
    const val PROCESSED_SAVED: String = "SAVED"
    const val PROCESSED_IGNORED: String = "IGNORED"
    const val PROCESSED_SUPERSEDED: String = "SUPERSEDED"

    const val CURRENCY_SYMBOL: String = "₹"
    const val PAISE_PER_RUPEE: Int = 100
    const val MONEY_DECIMAL_PLACES: Int = 2

    const val DATABASE_NAME: String = "expense-tracker.db"
    const val DATABASE_VERSION: Int = 2
    const val PREFS_NAME: String = "expense-tracker-prefs"
}
