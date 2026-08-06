package org.skyphusion.prism.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.skyphusion.prism.app.AppViewModel

/**
 * First-run plane path: welcome → enroll → tips (iOS OnboardingView 0.8.3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
  vm: AppViewModel,
  onOpenSettings: () -> Unit = {},
) {
  var step by remember { mutableIntStateOf(0) }

  // After enroll succeeds, advance to tips once.
  LaunchedEffect(vm.hasDeviceKey) {
    if (vm.hasDeviceKey && step == 1) step = 2
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            when (step) {
              0 -> "Get started"
              1 -> "Enroll this device"
              else -> "You're set"
            },
          )
        },
        actions = {
          IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
          }
        },
      )
    },
  ) { padding ->
    when (step) {
      0 ->
        Column(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding)
              .verticalScroll(rememberScrollState())
              .padding(24.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Text("Welcome to Prism", style = MaterialTheme.typography.headlineLarge)
          Text(
            "Commercial inference on the control plane: chat, image, video, audio, and music, " +
              "metered to your prepaid account.",
            style = MaterialTheme.typography.bodyLarge,
          )
          Text(
            "Chats stay on this device (the plane never stores prompts). You need a one-time " +
              "enrollment token from the operator, or a recovery pcp_ device key.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Bullet("Paste enrollment token or full pcp_ key from clipboard")
          Bullet("Optional: top up with Play credit packs after enroll")
          Bullet("More → Usage shows dual-pool balance and period meter")
          Spacer(Modifier.height(8.dp))
          Button(
            onClick = { step = 1 },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("Continue to enroll")
          }
          if (vm.showDeveloperSettings) {
            Text(
              "Developer options are on (playground available in Settings).",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      1 ->
        Column(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding),
        ) {
          EnrollScreen(vm = vm)
          if (vm.hasDeviceKey) {
            Button(
              onClick = { step = 2 },
              modifier =
                Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
              Text("Continue")
            }
          }
        }
      else ->
        Column(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding)
              .verticalScroll(rememberScrollState())
              .padding(24.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text("Quick start", style = MaterialTheme.typography.titleLarge)
          Bullet("Chat: pick a model, Stream optional, attach photos for vision models")
          Bullet("Image / Video tabs for generation; Seedance preferred for text-to-video")
          Bullet("More → Usage for dual-pool balance; Settings for credit packs")
          Bullet("Chats list: local sessions; export/import JSON for backup")
          Text(
            "You can open Settings anytime from the gear icon.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (vm.hasDeviceKey) {
            Text(
              "Device key stored. Open Chat to start.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
    }
  }
}

@Composable
private fun Bullet(text: String) {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("·", style = MaterialTheme.typography.bodyMedium)
    Text(text, style = MaterialTheme.typography.bodyMedium)
  }
}
