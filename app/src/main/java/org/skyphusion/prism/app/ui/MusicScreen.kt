package org.skyphusion.prism.app.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import org.skyphusion.prism.app.AppViewModel
import org.skyphusion.prism.app.Haptics
import org.skyphusion.prism.app.MediaUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(
  vm: AppViewModel,
  onOpenSettings: () -> Unit,
  onBack: (() -> Unit)? = null,
) {
  val context = LocalContext.current
  val view = LocalView.current
  var expanded by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text("Music")
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

      OutlinedTextField(
        value = vm.modelSearch,
        onValueChange = { vm.modelSearch = it },
        label = { Text("Search models") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )

      ModelDropdown(
        label = "Music model",
        models = vm.musicModels,
        selectedId = vm.selectedMusicModelId,
        expanded = expanded,
        onExpandedChange = { expanded = it },
        onSelect = {
          vm.selectedMusicModelId = it
          vm.persistUIPrefs()
          expanded = false
        },
      )
      vm.musicSpendPreview?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      OutlinedTextField(
        value = vm.musicPrompt,
        onValueChange = { vm.musicPrompt = it },
        label = { Text("Music prompt") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = vm.musicLyrics,
        onValueChange = { vm.musicLyrics = it },
        label = { Text("Optional lyrics") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
      )
      Text(
        "Unit-priced music door. Prompt describes style/mood; lyrics optional.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      if (vm.musicBusy) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          CircularProgressIndicator(modifier = Modifier.height(22.dp), strokeWidth = 2.dp)
          Text(vm.musicStatus ?: "Working…", style = MaterialTheme.typography.bodySmall)
        }
      } else {
        Button(
          onClick = {
            Haptics.light(view)
            vm.generateMusic()
          },
          enabled = vm.musicModels.isNotEmpty() && vm.musicPrompt.isNotBlank(),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Generate music")
        }
      }

      vm.lastMusicUrl?.let { url ->
        TextButton(
          onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
          },
        ) {
          Text("Open music URL")
        }
      }
      if (vm.lastMusicBase64 != null || vm.lastMusicUrl != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(
            onClick = {
              Haptics.light(view)
              if (vm.lastMusicBase64 != null) {
                vm.playLastMusic()
              } else {
                vm.lastMusicUrl?.let { url ->
                  context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
              }
            },
          ) {
            Text(if (vm.isMusicPlaying) "Stop" else "Play")
          }
          if (vm.isMusicPlaying) {
            TextButton(onClick = { vm.stopMusicPlayback() }) {
              Text("Stop")
            }
          }
          vm.lastMusicBase64?.let { b64 ->
            TextButton(
              onClick = {
                val ok = MediaUtils.shareAudioBase64(context, b64, "mp3")
                if (ok) Haptics.light(view) else vm.musicError = "Could not share audio"
              },
            ) {
              Text("Share")
            }
          }
        }
      }
      if (vm.musicBusy) {
        TextButton(
          onClick = {
            Haptics.light(view)
            vm.cancelMusic()
          },
        ) {
          Text("Cancel generation")
        }
      }

      vm.musicStatus?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      vm.musicError?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }
    }
  }
}
