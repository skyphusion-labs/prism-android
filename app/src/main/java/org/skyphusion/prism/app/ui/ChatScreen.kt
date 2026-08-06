package org.skyphusion.prism.app.ui

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
// ExposedDropdownMenu is resolved via ExposedDropdownMenuBox scope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import org.skyphusion.prism.app.AppViewModel
import org.skyphusion.prism.app.ChatTurn
import org.skyphusion.prism.app.Haptics
import org.skyphusion.prism.app.MicRecorder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
  vm: AppViewModel,
  onOpenSettings: () -> Unit,
  onOpenSessions: () -> Unit = {},
) {
  val listState = rememberLazyListState()
  val view = LocalView.current
  val context = LocalContext.current
  var refreshing by remember { mutableStateOf(false) }
  var attachMenu by remember { mutableStateOf(false) }
  var chatMicRecording by remember { mutableStateOf(false) }
  val chatMic = remember { MicRecorder(context.applicationContext) }
  var cameraUri by remember { mutableStateOf<Uri?>(null) }

  DisposableEffect(Unit) {
    onDispose { chatMic.cancel() }
  }

  fun attachFromUri(uri: Uri) {
    try {
      val bytes =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
          ?: return
      vm.attachChatImageBytes(bytes)
      Haptics.light(view)
    } catch (e: Exception) {
      vm.errorMessage = e.message ?: "Could not read image"
    }
  }

  val pickChatImage =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      if (uri != null) attachFromUri(uri)
    }
  val takePicture =
    rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
      if (ok) cameraUri?.let { attachFromUri(it) }
    }
  val requestCamera =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        val f = File(context.cacheDir, "prism-chat-cam-${System.currentTimeMillis()}.jpg")
        val uri =
          FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        cameraUri = uri
        takePicture.launch(uri)
      } else {
        vm.errorMessage = "Camera permission denied."
      }
    }
  val requestMicChat =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        if (chatMic.start()) {
          chatMicRecording = true
          Haptics.light(view)
        } else {
          vm.errorMessage = chatMic.errorMessage ?: "Could not start mic"
        }
      } else {
        vm.errorMessage = "Microphone permission denied."
      }
    }

  fun pasteClipboardImage() {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = cm.primaryClip
    if (clip == null || clip.itemCount == 0) {
      vm.errorMessage = "No image on the clipboard."
      return
    }
    val item = clip.getItemAt(0)
    val uri = item.uri
    if (uri != null) {
      attachFromUri(uri)
      return
    }
    // Some OEMs put image as URI in description only
    val desc = clip.description
    if (desc?.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) == true) {
      val t = item.coerceToText(context)?.toString()
      if (t != null && t.startsWith("content:")) {
        attachFromUri(Uri.parse(t))
        return
      }
    }
    vm.errorMessage = "No image on the clipboard."
  }

  fun toggleChatMic() {
    if (chatMicRecording) {
      val cap = chatMic.stop()
      chatMicRecording = false
      if (cap != null) {
        val (bytes, _) = cap
        Haptics.success(view)
        vm.sttToChatDraft(bytes, "audio/mp4")
      } else {
        vm.errorMessage = chatMic.errorMessage ?: "No audio captured"
      }
      return
    }
    val ok =
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    if (!ok) {
      requestMicChat.launch(Manifest.permission.RECORD_AUDIO)
      return
    }
    if (chatMic.start()) {
      chatMicRecording = true
      Haptics.light(view)
    } else {
      vm.errorMessage = chatMic.errorMessage ?: "Could not start mic"
    }
  }

  LaunchedEffect(vm.turns.size, vm.turns.lastOrNull()?.text) {
    if (vm.turns.isNotEmpty()) {
      listState.animateScrollToItem(vm.turns.lastIndex)
    }
  }
  // Clear pull indicator when busy ends
  LaunchedEffect(vm.isBusy) {
    if (!vm.isBusy) refreshing = false
  }
  val canSend =
    !vm.isBusy &&
      !vm.chatSttBusy &&
      vm.selectedModelId != null &&
      (vm.draft.isNotBlank() || vm.draftImageDataUrls.isNotEmpty())

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text("Prism")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              vm.balance?.let {
                Text(it, style = MaterialTheme.typography.labelSmall)
              }
              if (vm.planeHealthOk == false) {
                Text(
                  "· plane down",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.error,
                )
              }
            }
          }
        },
        actions = {
          IconButton(
            onClick = {
              Haptics.light(view)
              onOpenSessions()
            },
          ) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Chat list")
          }
          IconButton(
            onClick = {
              Haptics.light(view)
              vm.newChat()
            },
          ) {
            Icon(Icons.Default.Add, contentDescription = "New chat")
          }
          IconButton(
            onClick = {
              Haptics.light(view)
              vm.refreshModels()
            },
            enabled = !vm.isBusy,
          ) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh models")
          }
          if (vm.canRegenerateLastReply) {
            IconButton(
              onClick = {
                Haptics.light(view)
                vm.regenerateLastReply()
              },
            ) {
              Icon(Icons.Default.Replay, contentDescription = "Regenerate last reply")
            }
          }
          if (vm.turns.isNotEmpty()) {
            IconButton(
              onClick = {
                val text = vm.chatTranscriptText()
                if (text.isNotEmpty()) {
                  Haptics.light(view)
                  val send =
                    Intent(Intent.ACTION_SEND).apply {
                      type = "text/plain"
                      putExtra(Intent.EXTRA_TEXT, text)
                    }
                  context.startActivity(Intent.createChooser(send, "Share transcript"))
                }
              },
            ) {
              Icon(Icons.Default.Share, contentDescription = "Share transcript")
            }
          }
          IconButton(
            onClick = {
              Haptics.light(view)
              vm.clearChat()
            },
            enabled = vm.turns.isNotEmpty(),
          ) {
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
      if (!vm.isNetworkSatisfied) {
        OfflineBanner()
      }

      OutlinedTextField(
        value = vm.modelSearch,
        onValueChange = { vm.modelSearch = it },
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        label = { Text("Search models") },
        singleLine = true,
      )

      ModelPicker(vm = vm, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))

      if (vm.canCompactConversation || vm.canExpandConversation || vm.isCompacted) {
        CompactBar(
          vm = vm,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
      }

      vm.chatSpendPreview?.let { preview ->
        Text(
          preview,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
      }

      Row(
        modifier = Modifier.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("Stream", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.weight(1f))
        Switch(checked = vm.useStream, onCheckedChange = { vm.updateUseStream(it) })
      }

      PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
          refreshing = true
          vm.probePlaneHealth()
          vm.refreshModels()
        },
        modifier = Modifier.weight(1f).fillMaxWidth(),
      ) {
        LazyColumn(
          state = listState,
          modifier =
            Modifier
              .fillMaxSize()
              .padding(horizontal = 12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (vm.turns.isEmpty()) {
            item {
              ChatEmptyState(vm = vm, modifier = Modifier.padding(top = 48.dp))
            }
          }
          items(vm.turns, key = { it.id }) { turn ->
            val streaming =
              vm.isBusy &&
                turn.role == ChatTurn.Role.Assistant &&
                turn.id == vm.turns.lastOrNull()?.id &&
                turn.text.isEmpty()
            val canRegen =
              vm.canRegenerateLastReply &&
                turn.id == vm.turns.lastOrNull()?.id &&
                turn.role == ChatTurn.Role.Assistant
            val canSpeak =
              turn.role == ChatTurn.Role.Assistant &&
                turn.text.isNotBlank() &&
                !turn.text.startsWith("(cancelled)") &&
                vm.canSpeakText
            TurnBubble(
              turn = turn,
              isStreaming = streaming,
              canRegenerate = canRegen,
              canSpeak = canSpeak,
              onRegenerate = {
                Haptics.light(view)
                vm.regenerateLastReply()
              },
              onUseAsDraft = {
                Haptics.light(view)
                vm.useTurnAsDraft(turn)
              },
              onSpeak = {
                Haptics.light(view)
                vm.speakText(turn.text)
              },
            )
          }
        }
      }

      vm.errorMessage?.let { err ->
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
          Text(
            err,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
          if (vm.canRetryLastChat && !vm.isBusy) {
            TextButton(
              onClick = {
                Haptics.light(view)
                vm.retryLastFailedChat()
              },
            ) {
              Text("Retry last message")
            }
          }
          if (vm.canRegenerateLastReply) {
            TextButton(
              onClick = {
                Haptics.light(view)
                vm.regenerateLastReply()
              },
            ) {
              Text("Regenerate reply")
            }
          }
        }
      }

      vm.chatSpendPreview?.let { preview ->
        Text(
          preview,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
      }
      if (vm.chatSttBusy) {
        Text(
          "Transcribing mic…",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(horizontal = 12.dp),
        )
      }
      if (chatMicRecording) {
        Text(
          "Recording… tap mic to stop and add to draft",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(horizontal = 12.dp),
        )
      }
      if (vm.draftImageDataUrls.isNotEmpty()) {
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          vm.draftImageDataUrls.forEachIndexed { idx, _ ->
            TextButton(onClick = { vm.removeDraftImage(idx) }) {
              Text("Image ${idx + 1} ×")
            }
          }
          TextButton(onClick = { vm.clearDraftImages() }) {
            Text("Clear photos")
          }
        }
      }
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
      ) {
        Box {
          IconButton(
            onClick = { attachMenu = true },
            enabled = !vm.isBusy && vm.draftImageDataUrls.size < 3,
          ) {
            Icon(Icons.Default.Image, contentDescription = "Attach photo")
          }
          DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
            DropdownMenuItem(
              text = { Text("Photo library") },
              onClick = {
                attachMenu = false
                pickChatImage.launch(
                  PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
              },
              leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
            )
            DropdownMenuItem(
              text = { Text("Take photo") },
              onClick = {
                attachMenu = false
                val camOk =
                  ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                if (camOk) {
                  val f =
                    File(context.cacheDir, "prism-chat-cam-${System.currentTimeMillis()}.jpg")
                  val uri =
                    FileProvider.getUriForFile(
                      context,
                      "${context.packageName}.fileprovider",
                      f,
                    )
                  cameraUri = uri
                  takePicture.launch(uri)
                } else {
                  requestCamera.launch(Manifest.permission.CAMERA)
                }
              },
              leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
            )
            DropdownMenuItem(
              text = { Text("Paste image") },
              onClick = {
                attachMenu = false
                pasteClipboardImage()
              },
              leadingIcon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
            )
          }
        }
        IconButton(
          onClick = { toggleChatMic() },
          enabled = !vm.isBusy && !vm.chatSttBusy && vm.canUseMediaDoors,
        ) {
          Icon(
            Icons.Default.Mic,
            contentDescription = if (chatMicRecording) "Stop mic STT" else "Mic to draft",
            tint =
              if (chatMicRecording) MaterialTheme.colorScheme.error
              else MaterialTheme.colorScheme.onSurface,
          )
        }
        OutlinedTextField(
          value = vm.draft,
          onValueChange = { vm.draft = it },
          modifier = Modifier.weight(1f),
          placeholder = { Text("Message") },
          maxLines = 5,
          enabled = !vm.isBusy && !vm.chatSttBusy,
        )
        if (vm.isBusy) {
          IconButton(onClick = { vm.cancelChat() }) {
            Icon(Icons.Default.Close, contentDescription = "Cancel generation")
          }
        } else {
          IconButton(
            onClick = {
              Haptics.light(view)
              vm.send()
            },
            enabled = canSend,
          ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
          }
        }
      }
    }
  }
}

@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
  Text(
    text = "Offline · reconnect to send or generate",
    style = MaterialTheme.typography.labelLarge,
    color = Color.White,
    modifier =
      modifier
        .fillMaxWidth()
        .background(Color(0xFFE65100))
        .padding(horizontal = 12.dp, vertical = 6.dp),
  )
}

/**
 * Compact / expand controls. UI transcript stays full; model context shrinks.
 * Plane: client-side summary. Playground: Worker compact when conversation_id is bound.
 */
@Composable
private fun CompactBar(vm: AppViewModel, modifier: Modifier = Modifier) {
  val view = LocalView.current
  Row(
    modifier = modifier.fillMaxWidth().height(32.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    when {
      vm.isCompacted ->
        Text(
          "Compacted",
          style = MaterialTheme.typography.labelMedium,
          color = Color(0xFFE65100),
        )
      vm.completedChatPairCount >= org.skyphusion.prism.ConversationCompact.MIN_TURNS_TO_COMPACT ->
        Text(
          "${vm.completedChatPairCount} turns · compact shrinks model context",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
        )
    }
    Spacer(Modifier.weight(1f))
    when {
      vm.compactBusy ->
        CircularProgressIndicator(
          modifier = Modifier.size(16.dp),
          strokeWidth = 2.dp,
        )
      vm.canExpandConversation ->
        TextButton(
          onClick = {
            Haptics.light(view)
            vm.expandConversation()
          },
        ) {
          Text("Expand")
        }
      vm.canCompactConversation ->
        TextButton(
          onClick = {
            Haptics.light(view)
            vm.compactConversation()
          },
        ) {
          Text("Compact")
        }
    }
  }
}

@Composable
private fun ChatEmptyState(vm: AppViewModel, modifier: Modifier = Modifier) {
  val view = LocalView.current
  Column(
    modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(
      Icons.AutoMirrored.Filled.Chat,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.height(40.dp),
    )
    Text("Start a conversation", style = MaterialTheme.typography.titleMedium)
    Text(
      "Messages stay on this device (plane privacy). Attach photos for vision models. " +
        "Compact summarizes older turns. Playground: sync cloud history from the chat list.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    vm.selectedChatModel?.let { m ->
      Text(
        "Using ${m.displayName ?: m.id}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (vm.planeHealthOk == false) {
      Text(
        "Plane health: unreachable. Pull to refresh or check Settings.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
      )
    }

    Column(
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        "Try a starter",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      AppViewModel.starterPrompts.forEach { prompt ->
        Text(
          text = prompt,
          style = MaterialTheme.typography.bodySmall,
          modifier =
            Modifier
              .fillMaxWidth()
              .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(10.dp),
              )
              .clickable {
                Haptics.light(view)
                vm.applyStarterPrompt(prompt)
              }
              .padding(10.dp),
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(vm: AppViewModel, modifier: Modifier = Modifier) {
  var expanded by remember { mutableStateOf(false) }
  val chatModels = vm.chatModels
  val selected = chatModels.firstOrNull { it.id == vm.selectedModelId }
  val label =
    selected?.let { m ->
      val price = m.priceSnippet()?.let { " · $it" } ?: ""
      val tags = m.capabilityTags().take(3).joinToString(" · ").let { t ->
        if (t.isEmpty()) "" else " · $t"
      }
      val spend = if (m.spendable == false) " (unspendable)" else ""
      (m.displayName ?: m.id) + price + tags + spend
    } ?: "Select model"

  Column(modifier = modifier.fillMaxWidth()) {
    ExposedDropdownMenuBox(
      expanded = expanded,
      onExpandedChange = { expanded = it },
      modifier = Modifier.fillMaxWidth(),
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
        chatModels.forEach { m ->
          val spendable = m.spendable != false
          val tags = m.capabilityTags().joinToString(" · ")
          DropdownMenuItem(
            text = {
              Column {
                Text(
                  (m.displayName ?: m.id) +
                    (m.priceSnippet()?.let { " · $it" } ?: "") +
                    if (!spendable) " · unspendable" else "",
                  color =
                    if (spendable) {
                      MaterialTheme.colorScheme.onSurface
                    } else {
                      MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    },
                )
                if (tags.isNotEmpty()) {
                  Text(
                    tags,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            },
            onClick = {
              if (spendable) {
                // Keep transcript when switching models (iOS parity).
                vm.selectChatModel(m.id)
                expanded = false
              }
            },
            enabled = spendable,
          )
        }
      }
    }
  }
}

@Composable
private fun TurnBubble(
  turn: ChatTurn,
  isStreaming: Boolean = false,
  canRegenerate: Boolean = false,
  canSpeak: Boolean = false,
  onRegenerate: (() -> Unit)? = null,
  onUseAsDraft: (() -> Unit)? = null,
  onSpeak: (() -> Unit)? = null,
) {
  val isUser = turn.role == ChatTurn.Role.User
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
  ) {
    Column(
      horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
      modifier = Modifier.widthIn(max = 320.dp),
    ) {
      if (!isUser && turn.modelId != null) {
        Text(
          turn.modelId,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 2.dp, start = 4.dp),
        )
      }
      turn.imageDataUrls?.takeIf { it.isNotEmpty() }?.let { urls ->
        Text(
          "${urls.size} photo${if (urls.size == 1) "" else "s"} attached",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 2.dp),
        )
      }
      Box(
        modifier =
          Modifier
            .background(
              color =
                if (isUser) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.surfaceVariant
                },
              shape = RoundedCornerShape(12.dp),
            )
            .padding(12.dp)
            .then(
              if (!isStreaming && turn.text.isNotEmpty() && onUseAsDraft != null) {
                Modifier.clickable { onUseAsDraft() }
              } else {
                Modifier
              },
            ),
      ) {
        if (isStreaming) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            CircularProgressIndicator(
              modifier = Modifier.size(14.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              "Thinking…",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        } else {
          val bubbleColor =
            if (isUser) {
              MaterialTheme.colorScheme.onPrimary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            }
          if (isUser || turn.text.isEmpty()) {
            Text(
              text = turn.text.ifEmpty { "…" },
              color = bubbleColor,
            )
          } else {
            MarkdownText(text = turn.text, color = bubbleColor)
          }
        }
      }
      if (!isUser && !isStreaming) {
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
          if (canRegenerate && onRegenerate != null) {
            TextButton(onClick = onRegenerate) {
              Text("Regenerate")
            }
          }
          if (canSpeak && onSpeak != null) {
            TextButton(onClick = onSpeak) {
              Text("Speak")
            }
          }
        }
      }
    }
  }
}
