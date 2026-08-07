package org.skyphusion.prism.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.skyphusion.prism.app.AppViewModel
import org.skyphusion.prism.app.Haptics
import org.skyphusion.prism.app.MediaUtils
import org.skyphusion.prism.app.MicRecorder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioScreen(
  vm: AppViewModel,
  onOpenSettings: () -> Unit,
  onBack: (() -> Unit)? = null,
) {
  val context = LocalContext.current
  val view = LocalView.current
  var speechExpanded by remember { mutableStateOf(false) }
  var sttExpanded by remember { mutableStateOf(false) }
  val mic = remember { MicRecorder(context.applicationContext) }
  var recording by remember { mutableStateOf(false) }
  var recordElapsed by remember { mutableIntStateOf(0) }
  var sttAudioLabel by remember { mutableStateOf("") }

  DisposableEffect(Unit) {
    onDispose {
      mic.cancel()
      vm.stopLiveStt(commit = false)
    }
  }

  LaunchedEffect(recording) {
    while (recording) {
      delay(1_000)
      mic.tickElapsed()
      recordElapsed = mic.elapsedSeconds
    }
  }

  val pickAudio =
    rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
        val mime = context.contentResolver.getType(uri) ?: "audio/mpeg"
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        vm.setSttAudioBase64(mime, b64)
        sttAudioLabel = "Imported file (${bytes.size} bytes)"
      } catch (e: Exception) {
        vm.speechError = e.message ?: "Could not read audio file"
      }
    }

  val requestMic =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        if (mic.start()) {
          recording = true
          recordElapsed = 0
          Haptics.light(view)
        } else {
          vm.speechError = mic.errorMessage ?: "Could not start recording"
        }
      } else {
        vm.speechError = "Microphone permission denied. Enable it in Settings."
      }
    }

  val requestMicLive =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        Haptics.light(view)
        vm.startLiveStt()
      } else {
        vm.speechError = "Microphone permission denied. Enable it in Settings."
      }
    }

  fun startRecording() {
    val ok =
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    if (!ok) {
      requestMic.launch(Manifest.permission.RECORD_AUDIO)
      return
    }
    if (mic.start()) {
      recording = true
      recordElapsed = 0
      Haptics.light(view)
    } else {
      vm.speechError = mic.errorMessage ?: "Could not start recording"
    }
  }

  fun stopRecording() {
    val cap = mic.stop()
    recording = false
    if (cap != null) {
      val (bytes, _) = cap
      vm.setSttAudioBase64("audio/mp4", Base64.encodeToString(bytes, Base64.NO_WRAP))
      sttAudioLabel = "Recording (${recordElapsed}s, ${bytes.size} bytes)"
      Haptics.success(view)
    } else {
      vm.speechError = mic.errorMessage ?: "No audio captured"
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text("Audio")
            vm.balance?.let {
              Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        },
        navigationIcon = {
          if (onBack != null) {
            IconButton(onClick = onBack) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
          }
        },
        actions = {
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
          .padding(padding)
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (!vm.isNetworkSatisfied) {
        OfflineBanner()
      }

      Text("Text to speech", style = MaterialTheme.typography.titleMedium)
      OutlinedTextField(
        value = vm.modelSearch,
        onValueChange = { vm.modelSearch = it },
        label = { Text("Search models") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      ModelDropdown(
        label = "TTS model",
        models = vm.speechModels,
        selectedId = vm.selectedSpeechModelId,
        expanded = speechExpanded,
        onExpandedChange = { speechExpanded = it },
        onSelect = {
          vm.selectedSpeechModelId = it
          vm.persistUIPrefs()
          speechExpanded = false
        },
      )
      vm.speechSpendPreview?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      OutlinedTextField(
        value = vm.speechInput,
        onValueChange = { vm.speechInput = it },
        label = { Text("Text to speak") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
      )
      if (vm.speechBusy) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          CircularProgressIndicator(modifier = Modifier.height(22.dp), strokeWidth = 2.dp)
          Text(vm.speechStatus ?: "Working…", style = MaterialTheme.typography.bodySmall)
        }
      } else {
        Button(
          onClick = {
            Haptics.light(view)
            vm.generateSpeech(autoPlay = true)
          },
          enabled = vm.speechModels.isNotEmpty() && vm.speechInput.isNotBlank(),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Generate speech")
        }
      }
      if (vm.lastSpeechBase64 != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(
            onClick = {
              Haptics.light(view)
              vm.playLastSpeech()
            },
          ) {
            Text(if (vm.isSpeechPlaying) "Stop" else "Play")
          }
          if (vm.isSpeechPlaying) {
            TextButton(onClick = { vm.stopSpeechPlayback() }) {
              Text("Stop")
            }
          }
          TextButton(
            onClick = {
              val ok =
                MediaUtils.shareAudioBase64(
                  context,
                  vm.lastSpeechBase64!!,
                  vm.lastSpeechFormat ?: "mp3",
                )
              if (ok) Haptics.light(view) else vm.speechError = "Could not share audio"
            },
          ) {
            Text("Share")
          }
        }
      }

      HorizontalDivider(Modifier.padding(vertical = 8.dp))
      Text("Speech to text", style = MaterialTheme.typography.titleMedium)
      ModelDropdown(
        label = "STT model",
        models = vm.sttModels,
        selectedId = vm.selectedSttModelId,
        expanded = sttExpanded,
        onExpandedChange = { sttExpanded = it },
        onSelect = {
          vm.selectedSttModelId = it
          vm.persistUIPrefs()
          sttExpanded = false
        },
      )
      vm.sttSpendPreview?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Text(
        "Record from the mic (AAC/m4a) or pick a file. Clip is sent as base64 to the plane. " +
          "Live STT streams PCM over WebSocket (plane only; beyond iOS app surface).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      // Live STT (plane WebSocket)
      if (vm.liveSttRunning) {
        Text(
          "Live: ${vm.liveSttPartial.ifBlank { "listening…" }}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.primary,
        )
        Button(
          onClick = {
            Haptics.light(view)
            vm.stopLiveStt(commit = true)
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Stop live STT")
        }
      } else {
        Button(
          onClick = {
            val ok =
              ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (!ok) {
              requestMicLive.launch(Manifest.permission.RECORD_AUDIO)
              return@Button
            }
            Haptics.light(view)
            vm.startLiveStt()
          },
          enabled =
            !vm.speechBusy &&
              !recording &&
              vm.hasDeviceKey &&
              !vm.liveSttRunning,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Start live STT")
        }
      }
      if (recording) {
        Text(
          "Recording ${recordElapsed}s…",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
        )
        Button(
          onClick = { stopRecording() },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Stop and use recording")
        }
        TextButton(
          onClick = {
            mic.cancel()
            recording = false
            recordElapsed = 0
          },
        ) {
          Text("Cancel recording")
        }
      } else {
        Button(
          onClick = { startRecording() },
          enabled = !vm.speechBusy,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Record microphone")
        }
        TextButton(onClick = { pickAudio.launch("audio/*") }) {
          Text(if (vm.sttAudioDataUrl.isBlank()) "Import audio file" else "Replace audio file")
        }
      }
      if (vm.sttAudioDataUrl.isNotBlank()) {
        Text(
          sttAudioLabel.ifBlank { "Audio loaded (${vm.sttAudioDataUrl.length} chars data URL)" },
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
          onClick = {
            vm.sttAudioDataUrl = ""
            sttAudioLabel = ""
          },
        ) {
          Text("Clear audio")
        }
      }
      Button(
        onClick = {
          Haptics.light(view)
          vm.transcribeAudio()
        },
        enabled = vm.sttModels.isNotEmpty() && vm.sttAudioDataUrl.isNotBlank() && !vm.speechBusy && !recording,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Transcribe")
      }
      vm.lastTranscript?.let { text ->
        Text("Transcript", style = MaterialTheme.typography.titleSmall)
        Text(text, style = MaterialTheme.typography.bodyMedium)
        TextButton(
          onClick = {
            vm.draft = text
            Haptics.light(view)
          },
        ) {
          Text("Use as chat draft")
        }
      }

      vm.speechStatus?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      vm.speechError?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelDropdown(
  label: String,
  models: List<org.skyphusion.prism.ControlPlaneModel>,
  selectedId: String?,
  expanded: Boolean,
  onExpandedChange: (Boolean) -> Unit,
  onSelect: (String) -> Unit,
) {
  val selected = models.firstOrNull { it.id == selectedId }
  ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = onExpandedChange) {
    OutlinedTextField(
      value =
        selected?.let { m ->
          val price = m.priceSnippet()?.let { " · $it" } ?: ""
          "${m.displayName ?: m.id}$price"
        } ?: "Select model",
      onValueChange = {},
      readOnly = true,
      label = { Text(label) },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
      modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
      models.forEach { m ->
        val price = m.priceSnippet()?.let { " · $it" } ?: ""
        DropdownMenuItem(
          text = {
            Text(
              "${m.displayName ?: m.id}$price",
              color =
                if (m.spendable == false) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.onSurface,
            )
          },
          onClick = { if (m.spendable != false) onSelect(m.id) },
          enabled = m.spendable != false,
        )
      }
    }
  }
}
