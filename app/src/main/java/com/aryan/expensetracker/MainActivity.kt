package com.aryan.expensetracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.aryan.expensetracker.core.nav.AppNavHost
import com.aryan.expensetracker.core.ui.theme.ExpenseTrackerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ExpenseTrackerTheme {
                AppNavHost()
            }
        }
    }
}
