package com.aryan.expensetracker.pipeline.extraction

import android.util.Log
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "MessageTemplate"

// one compiled bank-message shape; fixedPayee names the counterparty when the text does not
data class MessageTemplate(
    val id: String,
    val direction: String,
    val fixedPayee: String?,
    val pattern: Pattern,
)

@Serializable
private data class TemplateJson(
    val id: String,
    val direction: String,
    val pattern: String,
    val fixedPayee: String? = null,
)

@Serializable
private data class TemplateFileJson(val templates: List<TemplateJson>)

// compiles the asset once at startup; a bad file is an empty list and a bad pattern is skipped
fun loadTemplates(fileContents: String, json: Json): List<MessageTemplate> {
    val parsed = try {
        json.decodeFromString<TemplateFileJson>(fileContents)
    } catch (error: Exception) {
        Log.e(TAG, "templates file unreadable: ${error.javaClass.simpleName}")
        return emptyList()
    }

    val compiled = ArrayList<MessageTemplate>()
    for (template in parsed.templates) {
        val pattern = compileOrNull(template) ?: continue
        compiled.add(MessageTemplate(template.id, template.direction, template.fixedPayee, pattern))
    }
    Log.i(TAG, "loaded ${compiled.size} of ${parsed.templates.size} templates")
    return compiled
}

private fun compileOrNull(template: TemplateJson): Pattern? {
    return try {
        Pattern.compile(template.pattern)
    } catch (error: PatternSyntaxException) {
        Log.e(TAG, "template ${template.id} has a bad pattern and was skipped")
        null
    }
}
