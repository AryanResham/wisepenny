package com.aryan.expensetracker.core.net

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

private const val TAG = "NetworkChecker"

// an interface so the pipeline's tests can go offline without a device
fun interface NetworkChecker {
    fun isOnline(): Boolean
}

class AndroidNetworkChecker(private val connectivityManager: ConnectivityManager) : NetworkChecker {

    // true only when the active network actually reaches the internet, not merely connected
    override fun isOnline(): Boolean {
        return try {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (error: SecurityException) {
            Log.w(TAG, "network state unavailable: ${error.javaClass.simpleName}")
            false
        }
    }
}
