package com.aryan.expensetracker.pipeline.extraction

import com.aryan.expensetracker.core.config.AppConfig
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// the real bank corpus: gitignored personal data, read here only as a regression fixture
@Serializable
data class SampleMessage(val date: Long, val addr: String, val body: String)

private val json = Json { ignoreUnknownKeys = true }

private val TEMPLATE_PATHS = listOf(
    "src/main/assets/${AppConfig.TEMPLATES_ASSET}",
    "app/src/main/assets/${AppConfig.TEMPLATES_ASSET}",
)

// the shipped asset is the single source of truth, so a test can never pass against a stale copy
fun realTemplates(): List<MessageTemplate> {
    val file = TEMPLATE_PATHS.map { File(it) }.firstOrNull { it.exists() }
        ?: error("templates.json not found from ${File(".").absolutePath}")
    return loadTemplates(file.readText(), json)
}

// null when the corpus is absent, so a checkout without the fixture still runs the inline tests
fun loadSampleCorpus(): List<SampleMessage>? {
    val stream = SampleMessage::class.java.getResourceAsStream("/samples.json") ?: return null
    return stream.bufferedReader().use { json.decodeFromString<List<SampleMessage>>(it.readText()) }
}
