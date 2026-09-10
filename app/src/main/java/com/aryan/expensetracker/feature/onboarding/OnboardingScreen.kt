package com.aryan.expensetracker.feature.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aryan.expensetracker.core.ExpenseTrackerApp

private const val TAG = "OnboardingScreen"

private val PAGE_PADDING = 24.dp
private val ROW_GAP = 12.dp

// sms is required; location is asked for at the same time but the app works without it
private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
private val REQUESTED_PERMISSIONS = REQUIRED_PERMISSIONS + arrayOf(
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.ACCESS_FINE_LOCATION,
)

// the nav host asks this to decide whether onboarding is the start destination
fun hasRequiredPermissions(context: Context): Boolean {
    for (permission in REQUIRED_PERMISSIONS) {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false
    }
    return true
}

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as ExpenseTrackerApp).container
    val viewModel: OnboardingViewModel = viewModel(factory = OnboardingViewModel.factory(container))

    var isGranted by remember { mutableStateOf(hasRequiredPermissions(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { isGranted = hasRequiredPermissions(context) }

    Column(
        modifier = Modifier.fillMaxSize().padding(PAGE_PADDING),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        Text("Expense Tracker reads your HDFC bank SMS on this phone and turns them into transactions.")
        Text("Location is optional. Everything is stored on the device.")
        Text(if (isGranted) "SMS permission granted." else "SMS permission is still needed.")

        Button(onClick = { permissionLauncher.launch(REQUESTED_PERMISSIONS) }) {
            Text("Grant permissions")
        }
        Button(onClick = { openBatteryOptimizationSettings(context) }) {
            Text("Ignore battery optimization")
        }
        Button(onClick = { viewModel.runBackfillOnce(onDone) }, enabled = isGranted) {
            Text("Done")
        }
    }
}

// some builds hide this screen, so a missing activity must not take the app down
private fun openBatteryOptimizationSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    try {
        context.startActivity(intent)
    } catch (error: Exception) {
        Log.w(TAG, "battery optimization screen is unavailable: ${error.javaClass.simpleName}")
    }
}
