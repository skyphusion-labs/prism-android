package org.skyphusion.prism.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import org.skyphusion.prism.app.ui.LoginScreen
import org.skyphusion.prism.app.ui.OnboardingScreen
import org.skyphusion.prism.app.ui.PlaneShell
import org.skyphusion.prism.app.ui.PrismTheme
import org.skyphusion.prism.app.ui.SessionListScreen
import org.skyphusion.prism.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val secrets = (application as PrismApplication).secrets
    setContent {
      PrismTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          val vm: AppViewModel =
            viewModel(
              factory =
                AppViewModel.Factory(
                  secrets = secrets,
                  appContext = applicationContext,
                ),
            )
          var showSettings by remember { mutableStateOf(false) }
          var showSessions by remember { mutableStateOf(false) }
          val lifecycleOwner = LocalLifecycleOwner.current

          DisposableEffect(Unit) {
            consumeDebugImport(intent, vm)
            onDispose { }
          }

          // Foreground: plane health + balance (iOS scenePhase .active).
          DisposableEffect(lifecycleOwner, vm) {
            val obs =
              LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) vm.onBecomeActive()
              }
            lifecycleOwner.lifecycle.addObserver(obs)
            onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
          }

          val inApp = vm.canChat
          when {
            showSettings && (inApp || vm.showDeveloperSettings || vm.needsPlaygroundLogin) ->
              SettingsScreen(
                vm = vm,
                onBack = { showSettings = false },
              )
            showSessions && inApp ->
              SessionListScreen(
                vm = vm,
                onBack = { showSessions = false },
              )
            vm.needsPlaygroundLogin ->
              LoginScreen(
                vm = vm,
                onOpenSettings = { showSettings = true },
              )
            vm.needsPlaneEnroll ->
              OnboardingScreen(
                vm = vm,
                onOpenSettings = { showSettings = true },
              )
            else ->
              PlaneShell(
                vm = vm,
                onOpenSettings = { showSettings = true },
                onOpenSessions = { showSessions = true },
              )
          }
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
  }

  private fun consumeDebugImport(intent: Intent?, vm: AppViewModel) {
    if (intent == null) return
    val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (!debuggable) return
    val key = intent.getStringExtra(EXTRA_PCP_KEY) ?: return
    intent.removeExtra(EXTRA_PCP_KEY)
    if (key.isNotBlank()) vm.importDeviceKey(key)
  }

  companion object {
    const val EXTRA_PCP_KEY = "pcp_key"
  }
}
