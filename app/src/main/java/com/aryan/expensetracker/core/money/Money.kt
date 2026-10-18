package com.aryan.expensetracker.core.money

import com.aryan.expensetracker.core.config.AppConfig
import java.math.BigDecimal
import java.math.RoundingMode

private val PAISE_PER_RUPEE = BigDecimal(AppConfig.PAISE_PER_RUPEE)

// turns a bank-style amount into paise via BigDecimal; null when the text is not an amount
fun parseAmountToPaise(text: String): Long? {
    val cleaned = text.replace(",", "").replace(AppConfig.CURRENCY_SYMBOL, "").trim()
    if (cleaned.isEmpty()) return null
    return try {
        BigDecimal(cleaned)
            .multiply(PAISE_PER_RUPEE)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    } catch (error: ArithmeticException) {
        null
    } catch (error: NumberFormatException) {
        null
    }
}

// renders paise for display; the sign says which way the money moved
fun formatPaise(paise: Long, direction: String): String {
    val sign = if (direction == AppConfig.DIRECTION_CREDIT) "+" else "-"
    return "$sign${formatPaisePlain(paise)}"
}

// the same amount with no sign at all, for a total that is a magnitude rather than a balance
fun formatPaisePlain(paise: Long): String {
    val rupees = BigDecimal(paise).abs()
        .divide(PAISE_PER_RUPEE)
        .setScale(AppConfig.MONEY_DECIMAL_PLACES, RoundingMode.HALF_UP)
    return "${AppConfig.CURRENCY_SYMBOL}${rupees.toPlainString()}"
}
