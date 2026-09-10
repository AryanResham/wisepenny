package com.aryan.expensetracker.feature.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.aryan.expensetracker.core.db.entity.CategoryEntity

// the one category picker; scrolls sideways so a long category list never pushes buttons off screen
@Composable
fun CategoryPicker(categories: List<CategoryEntity>, selectedId: Long?, onPick: (Long) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(categories) { category ->
            FilterChip(
                selected = category.id == selectedId,
                onClick = { onPick(category.id) },
                label = { Text(category.name) },
            )
        }
    }
}
