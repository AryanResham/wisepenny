package com.aryan.expensetracker.pipeline.capture

import java.security.MessageDigest

// stable identity for a captured message, so the same sms read twice is recognised as one
fun hashMessage(sender: String, body: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest("$sender|$body".toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}
