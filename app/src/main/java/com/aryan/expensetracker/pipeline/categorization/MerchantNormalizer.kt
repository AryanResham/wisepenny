package com.aryan.expensetracker.pipeline.categorization

import java.util.Locale

private val NON_NAME_CHARS = Regex("[^A-Z ]")
private val REPEATED_SPACES = Regex(" +")

class MerchantNormalizer(private val noiseWords: Set<String>) {

    // builds the lookup key both the rule path and the model path must agree on; pure, no i/o
    fun normalize(raw: String): String {
        val handle = stripVpaDomain(raw).uppercase(Locale.ROOT)
        val letters = handle.replace(NON_NAME_CHARS, " ")
        val meaningful = dropNoiseWords(letters).replace(REPEATED_SPACES, " ").trim()

        // a numeric upi id reduces to nothing, so keep the handle itself as the key
        if (meaningful.isBlank()) return handle.trim()
        return meaningful
    }

    // "swiggy@okhdfcbank" and "9876543210@ybl" both lose the provider suffix
    private fun stripVpaDomain(raw: String): String {
        val atIndex = raw.indexOf('@')
        if (atIndex <= 0) return raw
        return raw.substring(0, atIndex)
    }

    // corporate suffixes and city noise vary between messages for the same shop
    private fun dropNoiseWords(text: String): String {
        val kept = ArrayList<String>()
        for (word in text.split(" ")) {
            if (word.isBlank()) continue
            if (word in noiseWords) continue
            kept.add(word)
        }
        return kept.joinToString(" ")
    }
}
