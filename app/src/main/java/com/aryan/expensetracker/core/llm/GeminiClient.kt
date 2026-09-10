package com.aryan.expensetracker.core.llm

import android.util.Log
import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.prefs.AppPrefs
import com.aryan.expensetracker.core.result.AppResult
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "GeminiClient"
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

class GeminiClient(
    private val httpClient: OkHttpClient,
    private val appPrefs: AppPrefs,
    private val apiKey: String,
    private val json: Json,
) : LlmClient {

    override suspend fun generateJson(
        systemInstruction: String,
        userText: String,
        responseSchema: String,
    ): AppResult<String> = withContext(Dispatchers.IO) {
        // Step 1: a missing secrets.properties must degrade, never crash
        if (apiKey.isBlank()) {
            Log.e(TAG, "no api key configured")
            return@withContext AppResult.Failure("no api key")
        }

        // Step 2: a stuck queue must not burn the whole quota in one afternoon
        if (isDailyCapReached()) {
            Log.w(TAG, "daily call cap reached")
            return@withContext AppResult.Failure("daily cap")
        }

        // Step 3: one POST, key in a header, schema inline
        val request = buildRequest(systemInstruction, userText, responseSchema)
        try {
            httpClient.newCall(request).execute().use { response ->
                countCall()
                if (!response.isSuccessful) {
                    Log.e(TAG, "call failed with http ${response.code}")
                    return@withContext AppResult.Failure("http ${response.code}")
                }
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    Log.e(TAG, "call returned an empty body")
                    return@withContext AppResult.Failure("empty body")
                }
                return@withContext readGeneratedText(body)
            }
        } catch (error: IOException) {
            Log.e(TAG, "call could not complete: ${error.javaClass.simpleName}")
            return@withContext AppResult.Failure("network error")
        }
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

    // encodeToString escapes both texts, so quotes in a message cannot break the payload
    private fun buildRequest(
        systemInstruction: String,
        userText: String,
        responseSchema: String,
    ): Request {
        val payload = """
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

        return Request.Builder()
            .url(AppConfig.GEMINI_ENDPOINT.format(AppConfig.GEMINI_MODEL))
            .addHeader("x-goog-api-key", apiKey)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    // Step 5: dig the generated text out of the envelope; any other shape is a failure
    private fun readGeneratedText(responseBody: String): AppResult<String> {
        try {
            val candidates = json.parseToJsonElement(responseBody).jsonObject["candidates"]?.jsonArray
            if (candidates.isNullOrEmpty()) {
                Log.e(TAG, "response carried no candidates")
                return AppResult.Failure("no candidates")
            }
            val text = candidates[0].jsonObject["content"]
                ?.jsonObject?.get("parts")
                ?.jsonArray?.get(0)
                ?.jsonObject?.get("text")
                ?.jsonPrimitive?.content
            if (text == null) {
                Log.e(TAG, "response carried no text part")
                return AppResult.Failure("no text part")
            }
            return AppResult.Success(text)
        } catch (error: Exception) {
            Log.e(TAG, "response shape was unexpected: ${error.javaClass.simpleName}")
            return AppResult.Failure("bad response shape")
        }
    }
}
