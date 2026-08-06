package org.skyphusion.prism.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.skyphusion.prism.app.AppViewModel
import org.skyphusion.prism.app.ChatSession
import org.skyphusion.prism.app.ChatSessionStore
import org.skyphusion.prism.app.Haptics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
  vm: AppViewModel,
  onBack: () -> Unit,
) {
  val view = LocalView.current
  val timeFmt = SimpleDateFormat("MMM d · HH:mm", Locale.getDefault())

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Chats") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          IconButton(
            onClick = {
              Haptics.light(view)
              vm.newChat()
              onBack()
            },
          ) {
            Icon(Icons.Default.Add, contentDescription = "New chat")
          }
        },
      )
    },
  ) { padding ->
    LazyColumn(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(padding)
          .padding(horizontal = 12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      item {
        Text(
          "Stored on this device only (not on the plane). Max ${ChatSessionStore.SESSION_CAP} chats.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = 8.dp),
        )
      }
      if (vm.sessions.isEmpty()) {
        item {
          Text(
            "No saved chats yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
          )
        }
      }
      items(vm.sessions, key = { it.id }) { session ->
        SessionRow(
          session = session,
          isCurrent = session.id == vm.currentSessionId,
          meta =
            run {
              val n = session.turns.count {
                it.role == org.skyphusion.prism.app.ChatTurn.Role.User ||
                  it.role == org.skyphusion.prism.app.ChatTurn.Role.Assistant
              }
              val turns = "$n turn${if (n == 1) "" else "s"}"
              val whenStr = timeFmt.format(Date(session.updatedAtMs))
              "$turns · $whenStr"
            },
          onOpen = {
            Haptics.light(view)
            vm.openSession(session.id)
            onBack()
          },
          onDelete = {
            Haptics.light(view)
            vm.deleteSession(session.id)
          },
        )
      }
    }
  }
}

@Composable
private fun SessionRow(
  session: ChatSession,
  isCurrent: Boolean,
  meta: String,
  onOpen: () -> Unit,
  onDelete: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(onClick = onOpen)
        .padding(vertical = 10.dp, horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        session.title,
        style =
          if (isCurrent) MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
          else MaterialTheme.typography.bodyLarge,
        maxLines = 2,
      )
      Text(
        meta,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (isCurrent) {
      Icon(
        Icons.Default.CheckCircle,
        contentDescription = "Current chat",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(end = 4.dp),
      )
    }
    IconButton(onClick = onDelete) {
      Icon(
        Icons.Default.Delete,
        contentDescription = "Delete chat",
        tint = MaterialTheme.colorScheme.error,
      )
    }
  }
}
