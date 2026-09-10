package com.aryan.expensetracker.feature.onboarding

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aryan.expensetracker.core.AppContainer
import com.aryan.expensetracker.core.result.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "OnboardingViewModel"

class OnboardingViewModel(private val container: AppContainer) : ViewModel() {

    // runs the history backfill once per install, off the main thread; a failure still lets the user in
    fun runBackfillOnce(onFinished: () -> Unit) {
        viewModelScope.launch {
            if (!container.appPrefs.isBackfillDone()) {
                val result = withContext(Dispatchers.IO) { container.inboxReader.backfill() }
                if (result is AppResult.Success) {
                    container.appPrefs.setBackfillDone(true)
                } else {
                    Log.w(TAG, "backfill did not complete; catch-up will retry on the next launch")
                }
            }
            onFinished()
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { OnboardingViewModel(container) }
        }
    }
}
