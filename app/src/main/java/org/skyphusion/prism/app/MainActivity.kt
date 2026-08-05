package org.skyphusion.prism.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import org.skyphusion.prism.app.ui.ChatScreen
import org.skyphusion.prism.app.ui.EnrollScreen
import org.skyphusion.prism.app.ui.PrismTheme
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
            viewModel(factory = AppViewModel.Factory(secrets))
          var showSettings by remember { mutableStateOf(false) }

          when {
            showSettings && vm.hasDeviceKey ->
              SettingsScreen(
                vm = vm,
                onBack = { showSettings = false },
              )
            !vm.hasDeviceKey -> EnrollScreen(vm)
            else ->
              ChatScreen(
                vm = vm,
                onOpenSettings = { showSettings = true },
              )
          }
        }
      }
    }
  }
}
