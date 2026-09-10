package com.aryan.expensetracker.feature.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aryan.expensetracker.core.ExpenseTrackerApp
import com.aryan.expensetracker.core.db.entity.CategoryEntity
import com.aryan.expensetracker.core.db.entity.ReviewReason
import com.aryan.expensetracker.core.db.entity.TransactionEntity
import com.aryan.expensetracker.core.money.formatPaise
import com.aryan.expensetracker.feature.common.CategoryPicker
import com.aryan.expensetracker.feature.common.categoriesForDirection

@Composable
fun ReviewScreen() {
    val container = (LocalContext.current.applicationContext as ExpenseTrackerApp).container
    val viewModel: ReviewViewModel = viewModel(
        factory = viewModelFactory { initializer { ReviewViewModel(container) } }
    )
    val items by viewModel.itemsToReview.collectAsState()
    val categories by viewModel.categories.collectAsState()

    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing to review.")
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        items(items, key = { it.id }) { transaction ->
            ReviewCard(
                transaction = transaction,
                categories = categoriesForDirection(categories, transaction.direction),
                onConfirm = { categoryId ->
                    if (categoryId == transaction.categoryId) viewModel.confirm(transaction)
                    else viewModel.confirmWithCategory(transaction, categoryId)
                },
                onDiscard = { viewModel.discard(transaction) },
            )
        }
    }
}

// one item: what we think it is, why we are asking, and what actually arrived
@Composable
private fun ReviewCard(
    transaction: TransactionEntity,
    categories: List<CategoryEntity>,
    onConfirm: (Long) -> Unit,
    onDiscard: () -> Unit,
) {
    var pickedId by remember(transaction.id) { mutableStateOf(transaction.categoryId) }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = formatPaise(transaction.amountPaise, transaction.direction),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(text = transaction.merchantRaw, style = MaterialTheme.typography.bodyLarge)
            Text(text = explainReason(transaction), style = MaterialTheme.typography.labelMedium)
            CategoryPicker(categories = categories, selectedId = pickedId, onPick = { pickedId = it })
            Text(
                text = transaction.rawMessage,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Row {
                val selectedId = pickedId
                TextButton(
                    onClick = { if (selectedId != null) onConfirm(selectedId) },
                    enabled = selectedId != null,
                ) { Text("Looks right") }
                TextButton(onClick = onDiscard) { Text("Not a transaction") }
            }
        }
    }
}

private fun explainReason(transaction: TransactionEntity): String = when (transaction.reviewReason) {
    ReviewReason.NEW_MERCHANT -> "First time seeing this merchant"
    ReviewReason.LLM_EXTRACTED -> "Read by AI, no local rule matched"
    ReviewReason.NO_REFERENCE_NUMBER -> "No reference number, could not check for duplicates"
    ReviewReason.COULD_NOT_PROCESS -> "Could not be read after several tries"
    else -> "Needs a category"
}
