package org.skyphusion.prism.app.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skyphusion.prism.app.LegalLinks

/** In-app AGPL-3.0 license text (bundled from repo LICENSE). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val text =
    remember {
      loadAsset(context, "LICENSE.txt")
        ?: loadAsset(context, "LICENSE")
        ?: (
          "${LegalLinks.COPYRIGHT_LINE}\n\n" +
            "Full license could not be loaded from the app bundle. " +
            "Read it online:\n${LegalLinks.LICENSE_ONLINE}"
          )
    }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("License (${LegalLinks.LICENSE_SHORT_NAME})") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
  ) { padding ->
    Text(
      text = text,
      style =
        MaterialTheme.typography.bodySmall.copy(
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
          lineHeight = 14.sp,
        ),
      modifier =
        Modifier
          .fillMaxSize()
          .padding(padding)
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
    )
  }
}

private fun loadAsset(context: Context, name: String): String? =
  try {
    context.assets.open(name).bufferedReader().use { it.readText() }
  } catch (_: Exception) {
    null
  }
