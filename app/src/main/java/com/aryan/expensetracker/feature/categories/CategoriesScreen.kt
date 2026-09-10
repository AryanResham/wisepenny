package com.aryan.expensetracker.feature.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.aryan.expensetracker.core.config.AppConfig
import com.aryan.expensetracker.core.db.entity.CategoryEntity
import com.aryan.expensetracker.feature.common.CategoryPicker

@Composable
fun CategoriesScreen() {
    val container = (LocalContext.current.applicationContext as ExpenseTrackerApp).container
    val viewModel: CategoriesViewModel = viewModel(
        factory = viewModelFactory { initializer { CategoriesViewModel(container) } }
    )
    val categories by viewModel.categories.collectAsState()
    var isAdding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<CategoryEntity?>(null) }
    var deleting by remember { mutableStateOf<CategoryEntity?>(null) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "add") {
            Button(onClick = { isAdding = true }, modifier = Modifier.padding(16.dp)) {
                Text("Add category")
            }
        }
        for (kind in listOf(AppConfig.CATEGORY_KIND_EXPENSE, AppConfig.CATEGORY_KIND_INCOME)) {
            item(key = kind) { SectionHeader(if (kind == AppConfig.CATEGORY_KIND_EXPENSE) "Expense" else "Income") }
            items(categories.filter { it.kind == kind }, key = { it.id }) { category ->
                CategoryRow(
                    category = category,
                    onRename = { renaming = category },
                    onDelete = { deleting = category },
                )
            }
        }
    }

    if (isAdding) {
        AddCategoryDialog(
            onDismiss = { isAdding = false },
            onAdd = { name, kind -> viewModel.add(name, kind) },
        )
    }

    val categoryToRename = renaming
    if (categoryToRename != null) {
        RenameCategoryDialog(
            category = categoryToRename,
            onDismiss = { renaming = null },
            onRename = { name -> viewModel.rename(categoryToRename, name) },
        )
    }

    val categoryToDelete = deleting
    if (categoryToDelete != null) {
        DeleteCategoryDialog(
            category = categoryToDelete,
            destinations = categories.filter {
                it.kind == categoryToDelete.kind && it.id != categoryToDelete.id
            },
            onDismiss = { deleting = null },
            onDelete = { destinationId -> viewModel.delete(categoryToDelete, destinationId) },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun CategoryRow(category: CategoryEntity, onRename: () -> Unit, onDelete: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = category.name, modifier = Modifier.weight(1f))
            TextButton(onClick = onRename) { Text("Rename") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
        HorizontalDivider()
    }
}

@Composable
private fun AddCategoryDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(AppConfig.CATEGORY_KIND_EXPENSE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New category") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == AppConfig.CATEGORY_KIND_EXPENSE,
                        onClick = { kind = AppConfig.CATEGORY_KIND_EXPENSE },
                        label = { Text("Expense") },
                    )
                    FilterChip(
                        selected = kind == AppConfig.CATEGORY_KIND_INCOME,
                        onClick = { kind = AppConfig.CATEGORY_KIND_INCOME },
                        label = { Text("Income") },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAdd(name, kind)
                    onDismiss()
                },
                enabled = name.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameCategoryDialog(
    category: CategoryEntity,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(category.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename category") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onRename(name)
                    onDismiss()
                },
                enabled = name.isNotBlank(),
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// deleting never deletes money: the destination pick is what unlocks the button
@Composable
private fun DeleteCategoryDialog(
    category: CategoryEntity,
    destinations: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    var destinationId by remember { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${category.name}") },
        text = {
            Column {
                Text("Move its transactions to:")
                CategoryPicker(
                    categories = destinations,
                    selectedId = destinationId,
                    onPick = { destinationId = it },
                )
            }
        },
        confirmButton = {
            val selectedId = destinationId
            TextButton(
                onClick = {
                    if (selectedId != null) onDelete(selectedId)
                    onDismiss()
                },
                enabled = selectedId != null,
            ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
