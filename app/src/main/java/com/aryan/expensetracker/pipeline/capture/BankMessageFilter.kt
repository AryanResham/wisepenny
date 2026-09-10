package com.aryan.expensetracker.pipeline.capture

import com.aryan.expensetracker.core.config.AppConfig
import java.util.Locale

class BankMessageFilter {

    // the only local gate before gemini: bank sender, a money word, sane length; keeps junk out of the queue
    fun looksLikeBankMessage(sender: String, body: String): Boolean {
        if (body.isBlank()) return false
        if (body.length > AppConfig.MAX_MESSAGE_LENGTH) return false
        if (!sender.contains(AppConfig.BANK_SENDER_KEYWORD, ignoreCase = true)) return false

        val lowercased = body.lowercase(Locale.ROOT)
        for (word in AppConfig.MONEY_WORDS) {
            if (lowercased.contains(word)) return true
        }
        return false
    }
}
