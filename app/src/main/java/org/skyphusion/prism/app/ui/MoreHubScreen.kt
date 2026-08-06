package org.skyphusion.prism.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import org.skyphusion.prism.PrismKit
import org.skyphusion.prism.app.AppViewModel
import org.skyphusion.prism.app.Haptics

/**
 * Secondary doors + account (iOS MoreHubView). Tab bar keeps Chat / Image / Video / More.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreHubScreen(
  vm: AppViewModel,
  onOpenAudio: () -> Unit,
  onOpenMusic: () -> Unit,
  onOpenUsage: () -> Unit = {},
  onOpenSettings: () -> Unit,
) {
  val view = LocalView.current

  Scaffold(
    topBar = {
      TopAppBar(title = { Text("More") })
    },
  ) { padding ->
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(padding)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        "Generate",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
      )
      HubRow(
        icon = Icons.Default.GraphicEq,
        title = "Audio",
        subtitle = "Text-to-speech and speech-to-text",
        onClick = {
          Haptics.light(view)
          onOpenAudio()
        },
      )
      HubRow(
        icon = Icons.Default.MusicNote,
        title = "Music",
        subtitle = "Prompted music generation",
        onClick = {
          Haptics.light(view)
          onOpenMusic()
        },
      )

      Spacer(Modifier.height(12.dp))
      HorizontalDivider()
      Text(
        "Account",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
      )
      if (vm.hasDeviceKey) {
        vm.balance?.let {
          Text("Balance: $it", style = MaterialTheme.typography.bodyMedium)
        }
        vm.planeUsageLines.take(3).forEach { line ->
          Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Text(
          "Plane health: ${vm.planeHealthLabel}",
          style = MaterialTheme.typography.bodyMedium,
          color =
            when (vm.planeHealthOk) {
              false -> MaterialTheme.colorScheme.error
              true -> MaterialTheme.colorScheme.primary
              null -> MaterialTheme.colorScheme.onSurface
            },
        )
        HubRow(
          icon = Icons.Default.AccountBalanceWallet,
          title = "Usage & spend",
          subtitle = "Dual-pool balance and period meter",
          onClick = {
            Haptics.light(view)
            onOpenUsage()
          },
        )
        TextButton(
          onClick = {
            Haptics.light(view)
            vm.probePlaneHealth()
            vm.refreshModels()
            vm.refreshAccount()
          },
        ) {
          Text("Refresh catalog / balance")
        }
      } else {
        Text(
          "Enroll a device key to use metered doors.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Spacer(Modifier.height(12.dp))
      HorizontalDivider()
      Text(
        "App",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
      )
      HubRow(
        icon = Icons.Default.Settings,
        title = "Settings",
        subtitle = "Billing, prefs, device key",
        onClick = {
          Haptics.light(view)
          onOpenSettings()
        },
      )
      Text(
        "PrismKit ${PrismKit.VERSION}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(vertical = 8.dp),
      )
      Text(
        "Chat, Image, and Video stay on the tab bar. Audio and Music live here so the bar stays readable.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun HubRow(
  icon: ImageVector,
  title: String,
  subtitle: String,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Icon(
      Icons.AutoMirrored.Filled.KeyboardArrowRight,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
