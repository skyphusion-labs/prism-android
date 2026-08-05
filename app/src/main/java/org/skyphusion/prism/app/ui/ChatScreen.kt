package org.skyphusion.prism.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.skyphusion.prism.app.AppViewModel
import org.skyphusion.prism.app.ChatTurn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
  vm: AppViewModel,
  onOpenSettings: () -> Unit,
) {
  val listState = rememberLazyListState()
  LaunchedEffect(vm.turns.size, vm.turns.lastOrNull()?.text) {
    if (vm.turns.isNotEmpty()) {
      listState.animateScrollToItem(vm.turns.lastIndex)
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text("Prism")
            vm.balance?.let {
              Text(it, style = MaterialTheme.typography.labelSmall)
            }
          }
        },
        actions = {
          IconButton(onClick = { vm.refreshModels() }, enabled = !vm.isBusy) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh models")
          }
          IconButton(onClick = { vm.clearChat() }, enabled = vm.turns.isNotEmpty()) {
            Icon(Icons.Default.Delete, contentDescription = "Clear chat")
          }
          IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
          }
        },
      )
    },
  ) { padding ->
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(padding),
    ) {
      ModelPicker(vm = vm, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))

      Row(
        modifier = Modifier.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("Stream", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.weight(1f))
        Switch(checked = vm.useStream, onCheckedChange = { vm.useStream = it })
      }

      LazyColumn(
        state = listState,
        modifier =
          Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(vm.turns, key = { it.id }) { turn ->
          TurnBubble(turn)
        }
      }

      vm.errorMessage?.let { err ->
        Text(
          err,
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
      }

      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.Bottom,
      ) {
        OutlinedTextField(
          value = vm.draft,
          onValueChange = { vm.draft = it },
          modifier = Modifier.weight(1f),
          placeholder = { Text("Message") },
          maxLines = 5,
        )
        IconButton(
          onClick = { vm.send() },
          enabled = !vm.isBusy && vm.draft.isNotBlank() && vm.selectedModelId != null,
        ) {
          Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(vm: AppViewModel, modifier: Modifier = Modifier) {
  var expanded by remember { mutableStateOf(false) }
  val selected = vm.models.firstOrNull { it.id == vm.selectedModelId }
  val label =
    selected?.let { m ->
      val spend = if (m.spendable == false) " (unspendable)" else ""
      (m.displayName ?: m.id) + spend
    } ?: "Select model"

  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = it },
    modifier = modifier.fillMaxWidth(),
  ) {
    OutlinedTextField(
      value = label,
      onValueChange = {},
      readOnly = true,
      label = { Text("Model") },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
      modifier =
        Modifier
          .menuAnchor(MenuAnchorType.PrimaryNotEditable)
          .fillMaxWidth(),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      vm.models.forEach { m ->
        val spendable = m.spendable != false
        DropdownMenuItem(
          text = {
            Text(
              (m.displayName ?: m.id) + if (!spendable) " · unspendable" else "",
              color =
                if (spendable) {
                  MaterialTheme.colorScheme.onSurface
                } else {
                  MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                },
            )
          },
          onClick = {
            if (spendable) {
              vm.selectedModelId = m.id
              expanded = false
            }
          },
          enabled = spendable,
        )
      }
    }
  }
}

@Composable
private fun TurnBubble(turn: ChatTurn) {
  val isUser = turn.role == ChatTurn.Role.User
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
  ) {
    Box(
      modifier =
        Modifier
          .widthIn(max = 320.dp)
          .background(
            color =
              if (isUser) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.surfaceVariant
              },
            shape = RoundedCornerShape(12.dp),
          )
          .padding(12.dp),
    ) {
      Text(
        text = turn.text.ifEmpty { "…" },
        color =
          if (isUser) {
            MaterialTheme.colorScheme.onPrimary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
      )
    }
  }
}
