package com.aryan.expensetracker.core.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

object Routes {
    const val TRANSACTIONS: String = "transactions"
    const val REVIEW: String = "review"
    const val CATEGORIES: String = "categories"
}

private val DESTINATIONS = listOf(
    Routes.TRANSACTIONS to "Transactions",
    Routes.REVIEW to "Review",
    Routes.CATEGORIES to "Categories",
)

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.TRANSACTIONS

    Scaffold(
        bottomBar = {
            NavigationBar {
                for ((route, label) in DESTINATIONS) {
                    NavigationBarItem(
                        selected = route == currentRoute,
                        onClick = { navigateToTab(navController, route) },
                        icon = {},
                        label = { Text(label) },
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.TRANSACTIONS,
            modifier = Modifier.padding(padding),
        ) {
            for ((route, label) in DESTINATIONS) {
                composable(route) { PlaceholderScreen(label) }
            }
        }
    }
}

// tabs swap rather than stack, so the back stack never grows while tapping around
private fun navigateToTab(navController: NavController, route: String) {
    if (navController.currentDestination?.route == route) return
    navController.navigate(route) {
        popUpTo(Routes.TRANSACTIONS) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun PlaceholderScreen(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(label)
    }
}
