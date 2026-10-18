package com.aryan.expensetracker.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aryan.expensetracker.core.ExpenseTrackerApp

private const val EMPTY_PROMPT =
    "Ask about your spending, or tell me what something was.\n" +
        "Try: \"how much on food last month\""
private const val EMPTY_FOOTNOTE = "This conversation is not saved. Closing the app clears it."
private const val INPUT_HINT = "Ask or tell"

@Composable
fun ChatScreen() {
    val container = (LocalContext.current.applicationContext as ExpenseTrackerApp).container
    val viewModel: ChatViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ChatViewModel(container.geminiAgent, container.agentTools, container.hasGeminiKey)
            }
        }
    )
    val messages by viewModel.messages.collectAsState()
    val isWorking by viewModel.isWorking.collectAsState()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // a new line should be the one you can see, so the list follows the end of the conversation
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Box(modifier = Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                EmptyState(viewModel.hasApiKey)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageLine(message, viewModel)
                    }
                }
            }
        }
        HorizontalDivider()
        InputBar(
            draft = draft,
            isEnabled = viewModel.hasApiKey && !isWorking,
            onDraftChange = { draft = it },
            onSend = {
                viewModel.onSend(draft)
                draft = ""
            },
        )
    }
}

// one dispatch for the five message kinds; each one owns its alignment and weight
@Composable
private fun MessageLine(message: ChatMessage, viewModel: ChatViewModel) {
    when (message) {
        is ChatMessage.User -> UserBubble(message.text)
        is ChatMessage.Assistant -> Text(
            text = message.text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
        is ChatMessage.ToolRun -> Text(
            text = message.label,
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        is ChatMessage.Error -> Text(
            text = message.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
        is ChatMessage.Proposal -> ProposalCard(
            proposal = message,
            onToggleRow = { rowId -> viewModel.toggleRow(message.id, rowId) },
            onConfirm = { viewModel.onConfirm(message.id) },
            onCancel = { viewModel.cancel(message.id) },
        )
    }
}

// only the user's own words get a bubble; replies sit on the background so it reads as a conversation
@Composable
private fun UserBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.padding(vertical = 6.dp).fillMaxWidth(0.85f),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(hasApiKey: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (hasApiKey) EMPTY_PROMPT else NO_KEY_LINE,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = EMPTY_FOOTNOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun InputBar(
    draft: String,
    isEnabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text(INPUT_HINT) },
            enabled = isEnabled,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onSend,
            enabled = isEnabled && draft.isNotBlank(),
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text("Send")
        }
    }
}
