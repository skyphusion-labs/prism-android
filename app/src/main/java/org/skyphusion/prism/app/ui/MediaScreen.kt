package org.skyphusion.prism.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.skyphusion.prism.app.AppViewModel
import org.skyphusion.prism.app.MediaKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaScreen(
  vm: AppViewModel,
  kind: MediaKind,
  onOpenSettings: () -> Unit,
) {
  val context = LocalContext.current
  val models = if (kind == MediaKind.Image) vm.imageModels else vm.videoModels
  val selectedId =
    if (kind == MediaKind.Image) vm.selectedImageModelId else vm.selectedVideoModelId
  val selected = models.firstOrNull { it.id == selectedId }
  var expanded by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(if (kind == MediaKind.Image) "Image" else "Video")
            vm.balance?.let {
              Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        },
        actions = {
          IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
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
      ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
          value =
            selected?.let { m ->
              val price = m.priceSnippet()?.let { " · $it" } ?: ""
              "${m.displayName ?: m.id}$price"
            } ?: "Select model",
          onValueChange = {},
          readOnly = true,
          label = { Text("Model") },
          trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
          modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
          models.forEach { m ->
            val price = m.priceSnippet()?.let { " · $it" } ?: ""
            DropdownMenuItem(
              text = {
                Text(
                  "${m.displayName ?: m.id}$price",
                  color =
                    if (m.spendable == false) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.onSurface,
                )
              },
              onClick = {
                if (kind == MediaKind.Image) vm.selectedImageModelId = m.id
                else vm.selectedVideoModelId = m.id
                expanded = false
              },
            )
          }
        }
      }
      selectedId?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }

      OutlinedTextField(
        value = if (kind == MediaKind.Image) vm.imagePrompt else vm.videoPrompt,
        onValueChange = {
          if (kind == MediaKind.Image) vm.imagePrompt = it else vm.videoPrompt = it
        },
        label = { Text(if (kind == MediaKind.Image) "Image prompt" else "Video prompt") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
      )

      if (kind == MediaKind.Image && (selected?.acceptsImageInput() == true || selected?.requiresImageInput() == true)) {
        OutlinedTextField(
          value = vm.imageImageRef,
          onValueChange = { vm.imageImageRef = it },
          label = { Text("Optional reference (https or data:…)") },
          minLines = 2,
          modifier = Modifier.fillMaxWidth(),
        )
        if (vm.lastImageUrl != null || vm.lastImageBase64 != null) {
          TextButton(onClick = { vm.useLastImageAsReference(forVideo = false) }) {
            Text("Use last result as reference")
          }
        }
      }

      if (kind == MediaKind.Video) {
        OutlinedTextField(
          value = vm.videoImageRef,
          onValueChange = { vm.videoImageRef = it },
          label = { Text("Optional image URL / data:… (i2v)") },
          minLines = 2,
          modifier = Modifier.fillMaxWidth(),
        )
        if (vm.lastImageUrl != null || vm.lastImageBase64 != null) {
          TextButton(onClick = { vm.useLastImageAsReference(forVideo = true) }) {
            Text("Use last image as first frame")
          }
        }
      }

      Text(
        if (kind == MediaKind.Image) {
          "Unit-priced image door. Prefer xAI / Flux when available. i2i models need a reference."
        } else {
          "Unit-priced video door. Prefer Veo / Seedance Fast. Grok video needs plane 0.4.14+ (ZDR)."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      if (vm.mediaBusy) {
        Button(
          onClick = { vm.cancelMedia() },
          modifier = Modifier.fillMaxWidth(),
        ) {
          CircularProgressIndicator(
            modifier = Modifier.height(18.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary,
          )
          Spacer(Modifier.padding(8.dp))
          Text("Cancel")
        }
      } else {
        Button(
          onClick = {
            if (kind == MediaKind.Image) vm.generateImage() else vm.generateVideo()
          },
          enabled = models.isNotEmpty(),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(if (kind == MediaKind.Image) "Generate image" else "Generate video")
        }
      }

      vm.mediaStatus?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      vm.mediaError?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }

      if (kind == MediaKind.Image) {
        vm.lastImageUrl?.let { url ->
          AsyncImage(
            model = url,
            contentDescription = "Generated image",
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            contentScale = ContentScale.Fit,
          )
          TextButton(
            onClick = {
              context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            },
          ) {
            Text("Open image URL")
          }
        }
        if (vm.lastImageBase64 != null && vm.lastImageUrl == null) {
          Text(
            "Image returned as base64 (${vm.lastImageBase64!!.length} chars). Save/share from a later build.",
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }

      if (kind == MediaKind.Video) {
        vm.lastVideoUrl?.let { url ->
          Text("Video ready", style = MaterialTheme.typography.titleSmall)
          Text(url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Button(
            onClick = {
              context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("Open video")
          }
        }
      }
    }
  }
}
