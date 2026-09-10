package com.aryan.expensetracker.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log

private const val TAG = "LocationProvider"

// gps first: when both have a fix, the gps one is the more precise of the two
private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

data class DeviceLocation(val latitude: Double, val longitude: Double)

class LocationProvider(private val context: Context) {

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    // returns the fix the system already holds, never asks for a fresh one, so capture stays instant
    fun lastKnown(): DeviceLocation? {
        if (!hasLocationPermission()) return null
        val manager = locationManager ?: return null

        for (provider in PROVIDERS) {
            val location = readProvider(manager, provider) ?: continue
            return DeviceLocation(location.latitude, location.longitude)
        }
        return null
    }

    // a revoked permission or a provider the phone does not have must degrade to null, not throw
    private fun readProvider(manager: LocationManager, provider: String): android.location.Location? {
        return try {
            manager.getLastKnownLocation(provider)
        } catch (error: SecurityException) {
            null
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "provider $provider is not available")
            null
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }
}
