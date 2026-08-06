package org.skyphusion.prism.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.skyphusion.prism.app.AppViewModel
import org.skyphusion.prism.app.Haptics

/** Public playground signup / login (iOS LoginView). */
@Composable
fun LoginScreen(
  vm: AppViewModel,
  onOpenSettings: () -> Unit = {},
) {
  val view = LocalView.current
  var isSignup by remember { mutableStateOf(true) }

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(24.dp),
    verticalArrangement = Arrangement.Center,
  ) {
    if (!vm.isNetworkSatisfied) {
      OfflineBanner()
      Spacer(Modifier.height(12.dp))
    }
    Text("Prism playground", style = MaterialTheme.typography.headlineMedium)
    Text(
      "Sign in to play.skyphusion.org (session cookie). Metered image/video/audio stay on the control plane.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
      modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
    )

    OutlinedTextField(
      value = vm.playgroundUsername,
      onValueChange = { vm.playgroundUsername = it },
      label = { Text("Username") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
      value = vm.playgroundPassword,
      onValueChange = { vm.playgroundPassword = it },
      label = { Text("Password") },
      singleLine = true,
      visualTransformation = PasswordVisualTransformation(),
      modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Button(
      onClick = {
        Haptics.light(view)
        if (isSignup) vm.playgroundSignup() else vm.playgroundLogin()
      },
      enabled =
        !vm.isBusy &&
          vm.playgroundUsername.isNotBlank() &&
          vm.playgroundPassword.isNotBlank(),
      modifier = Modifier.fillMaxWidth(),
    ) {
      if (vm.isBusy) {
        CircularProgressIndicator(
          modifier = Modifier.height(20.dp),
          strokeWidth = 2.dp,
          color = MaterialTheme.colorScheme.onPrimary,
        )
      } else {
        Text(if (isSignup) "Create account" else "Log in")
      }
    }
    TextButton(
      onClick = { isSignup = !isSignup },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(if (isSignup) "Have an account? Log in" else "Need an account? Sign up")
    }
    TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
      Text("Settings / switch backend")
    }
    vm.errorMessage?.let { err ->
      Spacer(Modifier.height(12.dp))
      Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
  }
}
