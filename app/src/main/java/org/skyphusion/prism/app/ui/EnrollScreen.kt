package org.skyphusion.prism.app.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.skyphusion.prism.app.AppViewModel
import org.skyphusion.prism.app.Haptics

@Composable
fun EnrollScreen(vm: AppViewModel) {
  // Import is primary (operator pcp_ keys); one-time enroll under advanced.
  var showEnroll by remember { mutableStateOf(false) }
  var importKey by remember { mutableStateOf("") }
  val view = LocalView.current
  val context = LocalContext.current

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState()),
  ) {
    if (!vm.isNetworkSatisfied) {
      OfflineBanner()
    }

    Column(
      modifier = Modifier.padding(24.dp),
      verticalArrangement = Arrangement.Center,
    ) {
      Text("Prism", style = MaterialTheme.typography.headlineMedium)
      Text(
        "Metered chat, image, and video via the control plane. Paste a pcp_ device key, " +
          "or use Advanced for a one-time enrollment token.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
      )

      OutlinedTextField(
        value = vm.deviceLabel,
        onValueChange = { vm.deviceLabel = it },
        label = { Text("Device label") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      Spacer(Modifier.height(12.dp))
      OutlinedTextField(
        value = importKey,
        onValueChange = { importKey = it },
        label = { Text("pcp_ device key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
      )
      Spacer(Modifier.height(12.dp))
      Button(
        onClick = {
          Haptics.success(view)
          vm.importDeviceKey(importKey)
          importKey = ""
        },
        enabled = !vm.isBusy && AppViewModel.normalizeSecret(importKey).startsWith("pcp_"),
        modifier = Modifier.fillMaxWidth(),
      ) {
        if (vm.isBusy) {
          CircularProgressIndicator(
            modifier = Modifier.height(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary,
          )
        } else {
          Text("Import key")
        }
      }

      TextButton(
        onClick = {
          val pasted = readClipboardText(context)
          if (vm.pasteEnrollmentFromClipboard(pasted)) {
            Haptics.light(view)
            // pcp_ path imports immediately; enr_ goes into advanced field.
            if (AppViewModel.normalizeSecret(pasted.orEmpty()).startsWith("pcp_")) {
              importKey = ""
            } else {
              showEnroll = true
            }
          }
        },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Paste from clipboard")
      }

      TextButton(
        onClick = { showEnroll = !showEnroll },
        modifier = Modifier.align(Alignment.CenterHorizontally),
      ) {
        Text(if (showEnroll) "Hide advanced" else "Advanced: one-time enrollment token")
      }

      if (showEnroll) {
        Text(
          "Enrollment tokens are single-use. Paste from clipboard accepts a token or a full pcp_ key " +
            "(pcp_ is imported automatically).",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
          modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedTextField(
          value = vm.enrollmentToken,
          onValueChange = { vm.enrollmentToken = it },
          label = { Text("Enrollment token (enr_…)") },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(
          onClick = { vm.enroll() },
          enabled = !vm.isBusy && vm.enrollmentToken.isNotBlank(),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Enroll this device")
        }
      }

      vm.errorMessage?.let { err ->
        Spacer(Modifier.height(16.dp))
        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

internal fun readClipboardText(context: Context): String? {
  val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
  val clip = cm.primaryClip ?: return null
  if (clip.itemCount < 1) return null
  val desc = clip.description
  if (desc != null &&
    !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
    !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
  ) {
    // Still try coerceToText -- some OEMs omit mime.
  }
  return clip.getItemAt(0).coerceToText(context)?.toString()
}
