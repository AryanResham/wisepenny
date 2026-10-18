package com.aryan.expensetracker.pipeline.tagging

import com.aryan.expensetracker.core.config.AppConfig
import java.util.Locale

private val NON_NAME_CHARS = Regex("[^A-Z0-9 ]")
private val REPEATED_SPACES = Regex(" +")

class PayeeNormalizer(
    private val corporateSuffixes: Set<String> = AppConfig.PAYEE_CORPORATE_SUFFIXES,
) {

    // builds the stable key the learned rules and the tagger both look up; pure, no i/o
    fun normalize(raw: String): String {
        val handle = stripVpaProvider(raw).uppercase(Locale.ROOT)
        val cleaned = handle.replace(NON_NAME_CHARS, "").replace(REPEATED_SPACES, " ").trim()

        // an opaque handle survives punctuation stripping and is still a stable key
        if (cleaned.isBlank()) return handle.trim()
        return dropTrailingSuffixes(cleaned)
    }

    // "SWIGGY@YESPAY" and "9876543210@ybl" both lose the provider half
    private fun stripVpaProvider(raw: String): String {
        val atIndex = raw.indexOf('@')
        if (atIndex <= 0) return raw
        return raw.substring(0, atIndex)
    }

    // only trailing words go, so SWIGGY LIMITED joins SWIGGY while SWIGGYINSTAMART stays its own key
    private fun dropTrailingSuffixes(text: String): String {
        val words = text.split(" ").toMutableList()
        while (words.size > 1 && words.last() in corporateSuffixes) {
            words.removeAt(words.size - 1)
        }
        return words.joinToString(" ")
    }
}
