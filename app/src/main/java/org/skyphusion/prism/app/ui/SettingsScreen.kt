package org.skyphusion.prism.app.ui

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.skyphusion.prism.ControlPlaneClient
import org.skyphusion.prism.PrismKit
import org.skyphusion.prism.StoreProducts
import org.skyphusion.prism.app.AppViewModel
import org.skyphusion.prism.app.BillingManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  vm: AppViewModel,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val activity = context as? Activity
  val scope = rememberCoroutineScope()
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
