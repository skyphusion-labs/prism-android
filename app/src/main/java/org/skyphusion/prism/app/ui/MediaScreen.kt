package org.skyphusion.prism.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.skyphusion.prism.app.AppViewModel
import org.skyphusion.prism.app.Haptics
import org.skyphusion.prism.app.MediaKind
import org.skyphusion.prism.app.MediaUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaScreen(
  vm: AppViewModel,
  kind: MediaKind,
  onOpenSettings: () -> Unit,
) {
  val context = LocalContext.current
  val view = LocalView.current
  val models = if (kind == MediaKind.Image) vm.imageModels else vm.videoModels
  val selectedId =
    if (kind == MediaKind.Image) vm.selectedImageModelId else vm.selectedVideoModelId
  val selected = models.firstOrNull { it.id == selectedId }
  var expanded by remember { mutableStateOf(false) }
  var durationExpanded by remember { mutableStateOf(false) }
  val spendPreview = if (kind == MediaKind.Image) vm.imageSpendPreview else vm.videoSpendPreview
  val history = vm.historyFor(kind)
  val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
  var saveMessage by remember { mutableStateOf<String?>(null) }
  val videoDurationLimits =
    remember(selectedId) {
      org.skyphusion.prism.VideoClipDuration.limits(selectedId.orEmpty())
    }

  val pickPhoto =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      try {
        val bytes =
          context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@rememberLauncherForActivityResult
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        if (kind == MediaKind.Image) {
          vm.setImageReferenceData(bytes, mime)
        } else {
          vm.setVideoReferenceData(bytes, mime)
        }
        Haptics.light(view)
      } catch (e: Exception) {
        vm.mediaError = e.message ?: "Could not read photo"
      }
    }

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
      if (!vm.isNetworkSatisfied) {
        OfflineBanner()
      }

      if (!vm.canUseMediaDoors) {
        Text(
          "Image and video doors are control-plane only. Switch backend in Settings → Developer, or enroll a pcp_ key.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return@Column
      }

      OutlinedTextField(
        value = vm.modelSearch,
        onValueChange = { vm.modelSearch = it },
        label = { Text("Search models") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )

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
                if (kind == MediaKind.Image) {
                  vm.selectedImageModelId = m.id
                  vm.persistUIPrefs()
                } else {
                  vm.selectVideoModel(m.id)
                }
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
        TextButton(
          onClick = {
            pickPhoto.launch(
              PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
          },
        ) {
          Text("Choose photo for reference")
        }
        if (vm.lastImageUrl != null || vm.lastImageBase64 != null) {
          TextButton(onClick = { vm.useLastImageAsReference(forVideo = false) }) {
            Text("Use last result as reference")
          }
        }
        if (vm.imageImageRef.isNotBlank()) {
          TextButton(onClick = { vm.clearImageReference() }) {
            Text("Clear reference")
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
        TextButton(
          onClick = {
            pickPhoto.launch(
              PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
          },
        ) {
          Text("Choose photo for i2v")
        }
        if (vm.lastImageUrl != null || vm.lastImageBase64 != null) {
          TextButton(onClick = { vm.useLastImageAsReference(forVideo = true) }) {
            Text("Use last image as first frame")
          }
        }
        if (vm.videoImageRef.isNotBlank()) {
          TextButton(onClick = { vm.clearVideoReference() }) {
            Text("Clear reference")
          }
        }

        // Clip length: user picks up to the model's CF max (iOS videoDurationPicker parity).
        ExposedDropdownMenuBox(
          expanded = durationExpanded,
          onExpandedChange = { durationExpanded = it },
        ) {
          OutlinedTextField(
            value = "${vm.videoDurationSeconds}s",
            onValueChange = {},
            readOnly = true,
            label = { Text("Clip length") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(durationExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
          )
          ExposedDropdownMenu(
            expanded = durationExpanded,
            onDismissRequest = { durationExpanded = false },
          ) {
            videoDurationLimits.pickerSeconds.forEach { sec ->
              DropdownMenuItem(
                text = { Text("${sec}s") },
                onClick = {
                  vm.setVideoDurationSeconds(sec)
                  durationExpanded = false
                },
              )
            }
          }
        }
        Text(
          org.skyphusion.prism.VideoClipDuration.rangeHint(selectedId.orEmpty()) +
            ". Longer clips take longer to generate.",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      spendPreview?.let {
        Text(
          it,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Text(
        if (kind == MediaKind.Image) {
          "Unit-priced image door. Prefer xAI / Flux when available. i2i models need a reference."
        } else {
          val mid = vm.selectedVideoModelId.orEmpty()
          val clip = org.skyphusion.prism.VideoClipDuration.labelFor(mid, vm.videoDurationSeconds)
          "Unit-priced video · clip $clip · ${org.skyphusion.prism.VideoClipDuration.rangeHint(mid)}. " +
            "Prefer Seedance Fast / Veo. Hailuo is i2v-only. " +
            "Long runs use plane async jobs (lock-safe after job id)."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      if (vm.mediaBusy) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          CircularProgressIndicator(modifier = Modifier.height(22.dp), strokeWidth = 2.dp)
          Text(
            (vm.mediaStatus ?: "Generating…") + " · ${vm.mediaElapsedSeconds}s",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Button(
          onClick = { vm.cancelMedia() },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Cancel generation")
        }
      } else {
        Button(
          onClick = {
            Haptics.light(view)
            if (kind == MediaKind.Image) vm.generateImage() else vm.generateVideo()
          },
          enabled = models.isNotEmpty(),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(if (kind == MediaKind.Image) "Generate image" else "Generate video")
        }
        if (kind == MediaKind.Image && vm.mediaError != null && vm.imagePrompt.isNotBlank()) {
          TextButton(
            onClick = {
              Haptics.light(view)
              vm.retryLastImage()
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("Retry image (same prompt)")
          }
        }
        if (kind == MediaKind.Video && vm.mediaError != null &&
          (vm.videoPrompt.isNotBlank() || vm.videoImageRef.isNotBlank())
        ) {
          TextButton(
            onClick = {
              Haptics.light(view)
              vm.retryLastVideo()
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("Retry video (same prompt)")
          }
        }
      }

      vm.mediaStatus?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      vm.mediaError?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }

      if (kind == MediaKind.Image) {
        val b64 = vm.lastImageBase64
        val bitmap =
          remember(b64) {
            b64?.let { raw ->
              MediaUtils.decodeBitmap(raw)
                ?: run {
                  try {
                    val bytes = Base64.decode(raw, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                  } catch (_: Exception) {
                    null
                  }
                }
            }
          }
        if (bitmap != null) {
          Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Generated image",
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            contentScale = ContentScale.Fit,
          )
        } else {
          vm.lastImageUrl?.let { url ->
            AsyncImage(
              model = url,
              contentDescription = "Generated image",
              modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
              contentScale = ContentScale.Fit,
            )
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (vm.lastImageBase64 != null || vm.lastImageUrl != null) {
            TextButton(
              onClick = {
                Haptics.light(view)
                vm.saveLastImageToGallery { ok, msg ->
                  saveMessage = msg
                  if (ok) Haptics.success(view) else Haptics.error(view)
                }
              },
            ) {
              Text("Save to Photos")
            }
          }
          vm.lastImageUrl?.let { url ->
            TextButton(
              onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
              },
            ) {
              Text("Open URL")
            }
            TextButton(
              onClick = {
                val send =
                  Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                  }
                context.startActivity(Intent.createChooser(send, "Share image URL"))
              },
            ) {
              Text("Share URL")
            }
          }
        }
        if (bitmap == null && vm.lastImageBase64 != null && vm.lastImageUrl == null) {
          Text(
            "Image returned as base64 (${vm.lastImageBase64!!.length} chars) but could not decode.",
            style = MaterialTheme.typography.bodySmall,
          )
        }
        saveMessage?.let {
          Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
      }

      if (kind == MediaKind.Video) {
        vm.lastVideoUrl?.let { url ->
          Text("Video ready", style = MaterialTheme.typography.titleSmall)
          if (url.startsWith("http://") || url.startsWith("https://")) {
            AndroidView(
              factory = { ctx ->
                VideoView(ctx).apply {
                  setVideoURI(Uri.parse(url))
                  val mc = MediaController(ctx)
                  mc.setAnchorView(this)
                  setMediaController(mc)
                  setOnPreparedListener { it.isLooping = false }
                }
              },
              modifier =
                Modifier
                  .fillMaxWidth()
                  .heightIn(min = 200.dp, max = 360.dp),
              update = { vv ->
                // keep URI; user presses play via MediaController
              },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              TextButton(
                onClick = {
                  context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                },
              ) {
                Text("Open externally")
              }
              TextButton(
                onClick = {
                  val send =
                    Intent(Intent.ACTION_SEND).apply {
                      type = "text/plain"
                      putExtra(Intent.EXTRA_TEXT, url)
                    }
                  context.startActivity(Intent.createChooser(send, "Share video URL"))
                },
              ) {
                Text("Share URL")
              }
            }
          } else {
            Text(url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }

      if (history.isNotEmpty()) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("History (this session)", style = MaterialTheme.typography.titleSmall)
          TextButton(
            onClick = {
              Haptics.light(view)
              vm.clearMediaHistory()
            },
          ) {
            Text("Clear")
          }
        }
        Text(
          "Newest first. Tap to restore. Not saved across launches.",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        history.forEach { item ->
          TextButton(
            onClick = { vm.restoreMediaHistoryItem(item) },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(Modifier.fillMaxWidth()) {
              Text(item.model, style = MaterialTheme.typography.labelMedium)
              Text(
                item.prompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
              )
              Text(
                timeFmt.format(Date(item.createdAtMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}
