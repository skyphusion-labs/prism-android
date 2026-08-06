package org.skyphusion.prism.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.skyphusion.prism.app.AppViewModel
import org.skyphusion.prism.app.MediaKind

@Composable
fun PlaneShell(
  vm: AppViewModel,
  onOpenSettings: () -> Unit,
  onOpenSessions: () -> Unit = {},
) {
  var tab by rememberSaveable { mutableIntStateOf(0) }
  Scaffold(
    bottomBar = {
      NavigationBar {
        NavigationBarItem(
          selected = tab == 0,
          onClick = { tab = 0 },
          icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
          label = { Text("Chat") },
        )
        NavigationBarItem(
          selected = tab == 1,
          onClick = { tab = 1 },
          icon = { Icon(Icons.Default.Photo, contentDescription = null) },
          label = { Text("Image") },
        )
        NavigationBarItem(
          selected = tab == 2,
          onClick = { tab = 2 },
          icon = { Icon(Icons.Default.Movie, contentDescription = null) },
          label = { Text("Video") },
        )
        NavigationBarItem(
          selected = tab == 3,
          onClick = { tab = 3 },
          icon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
          label = { Text("Audio") },
        )
        NavigationBarItem(
          selected = tab == 4,
          onClick = { tab = 4 },
          icon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
          label = { Text("Music") },
        )
      }
    },
  ) { padding ->
    androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
      when (tab) {
        0 ->
          ChatScreen(
            vm = vm,
            onOpenSettings = onOpenSettings,
            onOpenSessions = onOpenSessions,
          )
        1 -> MediaScreen(vm = vm, kind = MediaKind.Image, onOpenSettings = onOpenSettings)
        2 -> MediaScreen(vm = vm, kind = MediaKind.Video, onOpenSettings = onOpenSettings)
        3 -> AudioScreen(vm = vm, onOpenSettings = onOpenSettings)
        else -> MusicScreen(vm = vm, onOpenSettings = onOpenSettings)
      }
    }
  }
}
