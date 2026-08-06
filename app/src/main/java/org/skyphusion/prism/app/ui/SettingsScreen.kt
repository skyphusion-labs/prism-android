package org.skyphusion.prism.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.skyphusion.prism.ControlPlaneClient
import org.skyphusion.prism.PrismClient
import org.skyphusion.prism.PrismKit
import org.skyphusion.prism.StoreProducts
import org.skyphusion.prism.app.AppViewModel
import org.skyphusion.prism.app.BackendKind
import org.skyphusion.prism.app.BillingManager
import org.skyphusion.prism.app.Haptics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  vm: AppViewModel,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val view = LocalView.current
  val activity = context as? Activity
  val scope = rememberCoroutineScope()
  var confirmClearKey by remember { mutableStateOf(false) }
  val billing =
    remember {
      BillingManager(
        context = context,
        clientProvider = { vm.planeClientOrNull() },
        onRedeemed = { vm.refreshAccount() },
      )
    }
  DisposableEffect(Unit) {
    billing.start()
    onDispose { billing.end() }
  }
  val products by billing.products.collectAsState()
  val billingStatus by billing.status.collectAsState()
  val billingError by billing.error.collectAsState()
  val billingBusy by billing.busy.collectAsState()

  if (confirmClearKey) {
    AlertDialog(
      onDismissRequest = { confirmClearKey = false },
      title = { Text("Clear device key?") },
      text = {
        Text(
          "This device will need a new enrollment token (or paste of a pcp_ key) " +
            "before chatting or generating again.",
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            confirmClearKey = false
            vm.clearDeviceKey()
            onBack()
          },
        ) {
          Text("Clear key", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmClearKey = false }) {
          Text("Cancel")
        }
      },
    )
  }

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
          .verticalScroll(rememberScrollState())
          .padding(24.dp),
    ) {
      if (!vm.isNetworkSatisfied) {
        OfflineBanner(Modifier.padding(bottom = 12.dp))
      }

      Text("Session", style = MaterialTheme.typography.titleMedium)
      Text("Backend: ${vm.backend.title}", style = MaterialTheme.typography.bodyMedium)
      Text(
        "Mode: ${vm.authMode ?: if (vm.backend == BackendKind.ControlPlane) "plane" else "unknown"}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (vm.backend == BackendKind.Playground) {
        Text(
          "Signed in: ${if (vm.playgroundAuthenticated) (vm.sessionUsername ?: "yes") else "no"}",
          style = MaterialTheme.typography.bodyMedium,
        )
        if (vm.playgroundAuthenticated) {
          TextButton(onClick = { vm.playgroundLogout(); onBack() }) {
            Text("Sign out")
          }
        }
      } else {
        Text(
          "Device key: ${if (vm.hasDeviceKey) "stored (EncryptedSharedPreferences)" else "none"}",
          style = MaterialTheme.typography.bodyMedium,
        )
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
      }
      Text("Models: ${vm.models.size}", style = MaterialTheme.typography.bodySmall)
      Text(
        "PrismKit ${PrismKit.VERSION}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
      )
      TextButton(onClick = { vm.probePlaneHealth(); vm.refreshModels() }) {
        Text("Refresh models / health")
      }

      if (vm.backend == BackendKind.ControlPlane) {
        Spacer(Modifier.height(16.dp))
        Text("Control plane", style = MaterialTheme.typography.titleMedium)
        Text(
          ControlPlaneClient.PRODUCTION_BASE_URL,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        vm.balance?.let {
          Spacer(Modifier.height(4.dp))
          Text("Balance: $it", style = MaterialTheme.typography.bodyMedium)
        }
        vm.planeUsageLines.forEach { line ->
          Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Text(
          "Spendable is prepaid + monthly allowance. Top-ups redeem via Play Billing to the plane.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(Modifier.height(24.dp))
        Text("Credit top-up", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        if (billingBusy && products.isEmpty()) {
          CircularProgressIndicator(modifier = Modifier.padding(8.dp))
        } else if (products.isEmpty()) {
          Text(
            "No products loaded yet. SKUs: ${StoreProducts.allCreditPacks.joinToString()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          TextButton(
            onClick = { scope.launch { billing.queryProducts() } },
            enabled = !billingBusy,
          ) {
            Text("Retry product load")
          }
        } else {
          billing.sortedProducts.forEach { product ->
            val price = product.oneTimePurchaseOfferDetails?.formattedPrice ?: "—"
            val usd = StoreProducts.creditUsd(product.productId)
            Button(
              onClick = {
                if (activity != null) {
                  billing.launchPurchase(activity, product)
                }
              },
              enabled = vm.hasDeviceKey && !billingBusy && activity != null,
              modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
              Text(
                buildString {
                  append(product.name.ifBlank { product.productId })
                  if (usd != null) append(" · $$usd credit")
                  append(" · ")
                  append(price)
                },
              )
            }
          }
        }

        billingStatus?.let {
          Spacer(Modifier.height(8.dp))
          Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        billingError?.let {
          Spacer(Modifier.height(4.dp))
          Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(
          "Consumable Play packs. After purchase, the app sends the purchase token to the plane " +
            "(POST /v1/store/redeem, platform=google_play) and refreshes balance. " +
            "Plane 0.4.16+ with GOOGLE_PLAY_SERVICE_ACCOUNT_JSON for production verify.",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
          modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(Modifier.height(16.dp))
        Text("Enrollment", style = MaterialTheme.typography.titleMedium)
        Text(
          "Paste a one-time enrollment token or a full pcp_ device key from the clipboard.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        TextButton(
          onClick = {
            if (vm.pasteEnrollmentFromClipboard(readClipboardText(context))) {
              Haptics.light(view)
            }
          },
        ) {
          Text("Paste from clipboard")
        }
        vm.errorMessage?.let {
          Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(24.dp))
        Button(
          onClick = { confirmClearKey = true },
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.error,
            ),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Forget device key")
        }
      } else {
        Spacer(Modifier.height(16.dp))
        Text("Playground", style = MaterialTheme.typography.titleMedium)
        Text(
          PrismClient.PRODUCTION_BASE_URL,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Text(
          "Chat uses the Worker session. Image/video/audio/music doors require control plane.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
        )
      }

      Spacer(Modifier.height(16.dp))
      Text("Preferences", style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.height(8.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
      ) {
        Text("Hide unspendable models", style = MaterialTheme.typography.bodyMedium)
        Switch(
          checked = vm.hideUnspendable,
          onCheckedChange = { vm.updateHideUnspendable(it) },
        )
      }
      Spacer(Modifier.height(8.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
      ) {
        Text("Developer options", style = MaterialTheme.typography.bodyMedium)
        Switch(
          checked = vm.showDeveloperSettings,
          onCheckedChange = { vm.updateShowDeveloperSettings(it) },
        )
      }
      Text(
        "Unlocks playground backend switch. Product default is Control plane.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (vm.showDeveloperSettings) {
        Spacer(Modifier.height(12.dp))
        Text("Backend", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        BackendKind.entries.forEach { kind ->
          Button(
            onClick = {
              Haptics.light(view)
              vm.updateBackend(kind)
            },
            enabled = vm.backend != kind,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
          ) {
            Text(kind.title + if (vm.backend == kind) " (active)" else "")
          }
        }
      }

      Spacer(Modifier.height(24.dp))
      HorizontalDivider()
      Spacer(Modifier.height(16.dp))
      Text("About", style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.height(8.dp))
      AboutLink("skyphusion.org", "https://skyphusion.org")
      AboutLink("Privacy", "https://skyphusion.org/privacy.html")
      AboutLink("Playground", "https://play.skyphusion.org")
      AboutLink("Status", "https://status.skyphusion.org")
      TextButton(
        onClick = {
          context.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:support@skyphusion.org")),
          )
        },
      ) {
        Text("support@skyphusion.org")
      }
      Spacer(Modifier.height(12.dp))
      Text(
        "PrismKit ${PrismKit.VERSION}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
      )
    }
  }
}

@Composable
private fun AboutLink(label: String, url: String) {
  val context = LocalContext.current
  TextButton(
    onClick = {
      context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    },
  ) {
    Text(label)
  }
}
