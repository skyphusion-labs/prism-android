package org.skyphusion.prism.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import org.skyphusion.prism.app.AppViewModel
import org.skyphusion.prism.app.BiometricGate

/** Full-screen gate before the enrolled session is visible (iOS BiometricLockView). */
@Composable
fun BiometricLockScreen(vm: AppViewModel) {
  val context = LocalContext.current
  val activity = context as? FragmentActivity
  val label = BiometricGate.label(context)

  fun tryUnlock() {
    if (activity == null) {
      vm.errorMessage = "Unlock unavailable on this surface."
      return
    }
    if (!BiometricGate.isAvailable(context)) {
      // No biometrics/PIN: do not hard-lock the user out.
      vm.unlockBiometrics()
      return
    }
    BiometricGate.authenticate(
      activity = activity,
      reason = "Unlock your enrolled Prism session",
      onSuccess = { vm.unlockBiometrics() },
      onError = { msg -> vm.errorMessage = msg },
    )
  }

  LaunchedEffect(Unit) {
    tryUnlock()
  }

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .padding(32.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("Prism is locked", style = MaterialTheme.typography.headlineMedium)
    Text(
      "Use $label to unlock your enrolled session. Device key stays encrypted either way.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(vertical = 16.dp),
    )
    Button(onClick = { tryUnlock() }, modifier = Modifier.fillMaxWidth()) {
      Text("Unlock with $label")
    }
    vm.errorMessage?.let {
      Text(
        it,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 12.dp),
      )
    }
  }
}
