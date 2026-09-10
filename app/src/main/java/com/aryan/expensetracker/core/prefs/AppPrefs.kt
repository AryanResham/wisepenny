package com.aryan.expensetracker.core.prefs

import android.content.Context
import android.content.SharedPreferences
import com.aryan.expensetracker.core.config.AppConfig

private const val KEY_LAST_SEEN_SMS_AT = "lastSeenSmsAt"
private const val KEY_BACKFILL_DONE = "backfillDone"
private const val KEY_LLM_CALLS_TODAY = "llmCallsToday"
private const val KEY_LLM_CALLS_DATE = "llmCallsDate"

class AppPrefs(context: Context) {

    private val preferences: SharedPreferences =
        context.getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)

    fun getLastSeenSmsAt(): Long = preferences.getLong(KEY_LAST_SEEN_SMS_AT, 0L)

    fun setLastSeenSmsAt(timestamp: Long) {
        preferences.edit().putLong(KEY_LAST_SEEN_SMS_AT, timestamp).apply()
    }

    fun isBackfillDone(): Boolean = preferences.getBoolean(KEY_BACKFILL_DONE, false)

    fun setBackfillDone(done: Boolean) {
        preferences.edit().putBoolean(KEY_BACKFILL_DONE, done).apply()
    }

    fun getLlmCallsToday(): Int = preferences.getInt(KEY_LLM_CALLS_TODAY, 0)

    fun setLlmCallsToday(count: Int) {
        preferences.edit().putInt(KEY_LLM_CALLS_TODAY, count).apply()
    }

    fun getLlmCallsDate(): String = preferences.getString(KEY_LLM_CALLS_DATE, "").orEmpty()

    fun setLlmCallsDate(date: String) {
        preferences.edit().putString(KEY_LLM_CALLS_DATE, date).apply()
    }
}
