package com.aryan.expensetracker.core.llm

import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.prefs.AppPrefs
import com.aryan.expensetracker.core.result.AppResult
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA_TYPE = "application/json".toMediaType()
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_UNAVAILABLE = 503

class GeminiClient(
    private val httpClient: OkHttpClient,
    private val appPrefs: AppPrefs,
    private val apiKey: String,
    private val json: Json,
) : LlmClient {

    private var lastCallAt: Long = 0L

    override suspend fun generateJson(
        systemInstruction: String,
        userText: String,
        responseSchema: String,
    ): AppResult<String> = withContext(Dispatchers.IO) {
        when (val body = post(schemaPayload(systemInstruction, userText, responseSchema))) {
            is AppResult.Failure -> body
            is AppResult.Success -> readGeneratedText(body.data)
        }
    }

    // the agent parses the envelope itself, because a candidate part may be a call instead of text
    override suspend fun generateWithTools(
        systemInstruction: String,
        contentsJson: String,
        toolsJson: String,
    ): AppResult<String> = withContext(Dispatchers.IO) {
        post(toolPayload(systemInstruction, contentsJson, toolsJson))
    }

    // the one gate every call goes through: key, daily cap, pacing, then one POST
    private suspend fun post(payload: String): AppResult<String> {
        // every failure below leaves as a reason; the caller is the one place that logs it
        // Step 1: a missing secrets.properties must degrade, never crash
        if (apiKey.isBlank()) return AppResult.Failure(AppConfig.LLM_REASON_NO_KEY)

        // Step 2: a stuck queue must not burn the whole quota in one afternoon
        if (isDailyCapReached()) return AppResult.Failure(AppConfig.LLM_REASON_DAILY_CAP)

        // Step 3: space the calls out; the worker runs them back to back otherwise
        waitForGap()

        // Step 4: one POST, key in a header
        try {
            httpClient.newCall(buildRequest(payload)).execute().use { response ->
                countCall()
                if (isThrottled(response.code)) return AppResult.Failure(AppConfig.LLM_REASON_RATE_LIMITED)
                if (!response.isSuccessful) return AppResult.Failure("http ${response.code}")
                val body = response.body?.string()
                if (body.isNullOrBlank()) return AppResult.Failure("empty body")
                return AppResult.Success(body)
            }
        } catch (error: IOException) {
            return AppResult.Failure("network error: ${error.javaClass.simpleName}")
        }
    }

    // 429 is too fast, 503 is google overloaded; either way the message deserves another go later
    private fun isThrottled(code: Int): Boolean = code == HTTP_TOO_MANY_REQUESTS || code == HTTP_UNAVAILABLE

    // sleeps off whatever is left of the minimum gap since the previous call
    private suspend fun waitForGap() {
        val remaining = lastCallAt + AppConfig.LLM_MIN_GAP_MS - System.currentTimeMillis()
        if (remaining > 0) delay(remaining)
        lastCallAt = System.currentTimeMillis()
    }

    // the stored count belongs to a date, so a new day is already back at zero
    private fun isDailyCapReached(): Boolean {
        if (appPrefs.getLlmCallsDate() != today()) return false
        return appPrefs.getLlmCallsToday() >= AppConfig.MAX_LLM_CALLS_PER_DAY
    }

    // Step 4: every completed http call counts, including the ones that came back as errors
    private fun countCall() {
        val today = today()
        if (appPrefs.getLlmCallsDate() != today) {
            appPrefs.setLlmCallsDate(today)
            appPrefs.setLlmCallsToday(1)
            return
        }
        appPrefs.setLlmCallsToday(appPrefs.getLlmCallsToday() + 1)
    }

    private fun today(): String = LocalDate.now(ZoneId.of(AppConfig.BANK_TIME_ZONE)).toString()

    private fun buildRequest(payload: String): Request = Request.Builder()
        .url(AppConfig.GEMINI_ENDPOINT.format(AppConfig.GEMINI_MODEL))
        .addHeader("x-goog-api-key", apiKey)
        .post(payload.toRequestBody(JSON_MEDIA_TYPE))
        .build()

    // encodeToString escapes both texts, so quotes in a message cannot break the payload
    private fun schemaPayload(
        systemInstruction: String,
        userText: String,
        responseSchema: String,
    ): String = """
        {
          "systemInstruction": { "parts": [ { "text": ${json.encodeToString(systemInstruction)} } ] },
          "contents": [ { "parts": [ { "text": ${json.encodeToString(userText)} } ] } ],
          "generationConfig": {
            "temperature": 0,
            "responseMimeType": "application/json",
            "responseSchema": $responseSchema
          }
        }
    """.trimIndent()

    // the function-calling shape: declarations under tools, the whole transcript under contents
    private fun toolPayload(
        systemInstruction: String,
        contentsJson: String,
        toolsJson: String,
    ): String = """
        {
          "systemInstruction": { "parts": [ { "text": ${json.encodeToString(systemInstruction)} } ] },
          "contents": $contentsJson,
          "tools": $toolsJson,
          "generationConfig": { "temperature": 0 }
        }
    """.trimIndent()

    // Step 5: dig the generated text out of the envelope; any other shape is a failure
    private fun readGeneratedText(responseBody: String): AppResult<String> {
        try {
            val candidates = json.parseToJsonElement(responseBody).jsonObject["candidates"]?.jsonArray
            if (candidates.isNullOrEmpty()) return AppResult.Failure("no candidates")
            val text = candidates[0].jsonObject["content"]
                ?.jsonObject?.get("parts")
                ?.jsonArray?.get(0)
                ?.jsonObject?.get("text")
                ?.jsonPrimitive?.content
            if (text == null) return AppResult.Failure("no text part")
            return AppResult.Success(text)
        } catch (error: Exception) {
            return AppResult.Failure("bad response shape: ${error.javaClass.simpleName}")
        }
    }
}
