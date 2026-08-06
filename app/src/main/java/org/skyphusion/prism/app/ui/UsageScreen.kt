package org.skyphusion.prism.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import org.skyphusion.prism.app.AppViewModel
import org.skyphusion.prism.app.Haptics

/**
 * Control-plane dual-pool balance + period meter (`GET /v1/usage` + last `/v1/me`).
 * Parity with iOS UsageView (0.8.3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
  vm: AppViewModel,
  onBack: () -> Unit,
) {
  val view = LocalView.current

  LaunchedEffect(Unit) {
    vm.refreshUsageDetail()
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Usage") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          IconButton(
            onClick = {
              Haptics.light(view)
              vm.refreshUsageDetail()
            },
            enabled = !vm.usageBusy && vm.hasDeviceKey,
          ) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh usage")
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
      Text("Account", style = MaterialTheme.typography.titleMedium)
      vm.balance?.let {
        Text("Spendable: $it", style = MaterialTheme.typography.bodyLarge)
      }
      Text(
        "Plane: ${vm.planeHealthLabel}",
        style = MaterialTheme.typography.bodyMedium,
        color =
          when (vm.planeHealthOk) {
            false -> MaterialTheme.colorScheme.error
            true -> MaterialTheme.colorScheme.primary
            null -> MaterialTheme.colorScheme.onSurface
          },
      )
      Text(
        "Allowance spends first; unused expires at period roll. Prepaid credit never expires.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (vm.usageBusy) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
          Text("Refreshing…", style = MaterialTheme.typography.bodySmall)
        }
      } else {
        Button(
          onClick = {
            Haptics.light(view)
            vm.refreshUsageDetail()
          },
          enabled = vm.hasDeviceKey,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Refresh usage")
        }
      }

      HorizontalDivider(Modifier.padding(vertical = 4.dp))
      Text("Dual pool", style = MaterialTheme.typography.titleMedium)
      val pool =
        vm.usageDetail?.dualPoolLines()?.takeIf { it.isNotEmpty() }
          ?: vm.planeUsageLines.toList()
      if (pool.isEmpty()) {
        Text(
          if (vm.hasDeviceKey) {
            "Pull refresh or open after a metered call."
          } else {
            "Enroll a device key first."
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        pool.forEach { line ->
          Text(line, style = MaterialTheme.typography.bodyMedium)
        }
      }

      val period = vm.usageDetail?.periodDetailLines().orEmpty()
      if (period.isNotEmpty()) {
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("This period", style = MaterialTheme.typography.titleMedium)
        period.forEach { line ->
          Text(line, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
          "Unmetered means the plane served the call but could not price it. " +
            "Prefer reconciled spend when showing one number.",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      vm.usageError?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }
    }
  }
}
