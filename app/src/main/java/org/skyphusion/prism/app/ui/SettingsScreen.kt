package org.skyphusion.prism.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.skyphusion.prism.ControlPlaneClient
import org.skyphusion.prism.PrismKit
import org.skyphusion.prism.app.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  vm: AppViewModel,
  onBack: () -> Unit,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Settings") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
          .padding(24.dp),
    ) {
      Text("Control plane", style = MaterialTheme.typography.titleMedium)
      Text(
        ControlPlaneClient.PRODUCTION_BASE_URL,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
      )
      Spacer(Modifier.height(8.dp))
      Text(
        "Device key: ${if (vm.hasDeviceKey) "stored (EncryptedSharedPreferences)" else "none"}",
        style = MaterialTheme.typography.bodyMedium,
      )
      vm.balance?.let {
        Spacer(Modifier.height(4.dp))
        Text("Balance: $it", style = MaterialTheme.typography.bodyMedium)
      }
      Spacer(Modifier.height(24.dp))
      Button(
        onClick = {
          vm.clearDeviceKey()
          onBack()
        },
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
          ),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Forget device key")
      }
      Spacer(Modifier.height(24.dp))
      Text(
        "PrismKit ${PrismKit.VERSION}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
      )
    }
  }
}
