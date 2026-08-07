package org.skyphusion.prism.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.skyphusion.prism.app.AppViewModel
import org.skyphusion.prism.app.MainTab
import org.skyphusion.prism.app.MediaKind

/** Destinations under the More hub (not primary tabs). */
private enum class MoreDest {
  Hub,
  Audio,
  Music,
  Usage,
}

@Composable
fun PlaneShell(
  vm: AppViewModel,
  onOpenSettings: () -> Unit,
  onOpenSessions: () -> Unit = {},
) {
  var moreDest by rememberSaveable { mutableStateOf(MoreDest.Hub.name) }
  val more = MoreDest.entries.find { it.name == moreDest } ?: MoreDest.Hub
  val tab = vm.selectedTab

  Scaffold(
    bottomBar = {
      NavigationBar {
        NavigationBarItem(
          selected = tab == MainTab.Chat,
          onClick = { vm.selectedTab = MainTab.Chat },
          icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
          label = { Text("Chat") },
        )
        NavigationBarItem(
          selected = tab == MainTab.Image,
          onClick = { vm.selectedTab = MainTab.Image },
          icon = { Icon(Icons.Default.Photo, contentDescription = null) },
          label = { Text("Image") },
        )
        NavigationBarItem(
          selected = tab == MainTab.Video,
          onClick = { vm.selectedTab = MainTab.Video },
          icon = { Icon(Icons.Default.Movie, contentDescription = null) },
          label = { Text("Video") },
        )
        NavigationBarItem(
          selected = tab == MainTab.More,
          onClick = {
            vm.selectedTab = MainTab.More
            moreDest = MoreDest.Hub.name
          },
          icon = { Icon(Icons.Default.MoreHoriz, contentDescription = null) },
          label = { Text("More") },
        )
      }
    },
  ) { padding ->
    androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
      when (tab) {
        MainTab.Chat ->
          ChatScreen(
            vm = vm,
            onOpenSettings = onOpenSettings,
            onOpenSessions = onOpenSessions,
          )
        MainTab.Image -> MediaScreen(vm = vm, kind = MediaKind.Image, onOpenSettings = onOpenSettings)
        MainTab.Video -> MediaScreen(vm = vm, kind = MediaKind.Video, onOpenSettings = onOpenSettings)
        MainTab.More ->
          when (more) {
            MoreDest.Hub ->
              MoreHubScreen(
                vm = vm,
                onOpenAudio = { moreDest = MoreDest.Audio.name },
                onOpenMusic = { moreDest = MoreDest.Music.name },
                onOpenUsage = { moreDest = MoreDest.Usage.name },
                onOpenSettings = onOpenSettings,
              )
            MoreDest.Audio ->
              AudioScreen(
                vm = vm,
                onOpenSettings = onOpenSettings,
                onBack = { moreDest = MoreDest.Hub.name },
              )
            MoreDest.Music ->
              MusicScreen(
                vm = vm,
                onOpenSettings = onOpenSettings,
                onBack = { moreDest = MoreDest.Hub.name },
              )
            MoreDest.Usage ->
              UsageScreen(
                vm = vm,
                onBack = { moreDest = MoreDest.Hub.name },
              )
          }
      }
    }
  }
}
