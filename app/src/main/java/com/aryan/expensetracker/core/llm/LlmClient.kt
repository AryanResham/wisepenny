package com.aryan.expensetracker.core.llm

import com.aryan.expensetracker.core.result.AppResult

// the one shape every model call takes; an interface so tests can hand back canned json
interface LlmClient {

    suspend fun generateJson(
        systemInstruction: String,
        userText: String,
        responseSchema: String,
    ): AppResult<String>

    // the function-calling call: transcript and tool declarations go out as json, the raw body comes back
    suspend fun generateWithTools(
        systemInstruction: String,
        contentsJson: String,
        toolsJson: String,
    ): AppResult<String>
}
