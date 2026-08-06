package org.skyphusion.prism.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/** Shared helpers for photo refs, gallery save, and local audio playback. */
object MediaUtils {
  fun bytesToDataUrl(bytes: ByteArray, mime: String = "image/jpeg"): String {
    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    val m = mime.ifBlank { "image/jpeg" }
    return "data:$m;base64,$b64"
  }

  fun decodeBase64Payload(raw: String): ByteArray? {
    var s = raw.trim()
    if (s.isEmpty()) return null
    val idx = s.indexOf("base64,")
    if (idx >= 0) s = s.substring(idx + "base64,".length)
    return try {
      Base64.decode(s, Base64.DEFAULT)
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  fun decodeBitmap(base64OrDataUrl: String): Bitmap? {
    val bytes = decodeBase64Payload(base64OrDataUrl) ?: return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
  }

  /**
   * Save PNG/JPEG bytes to the device Pictures/Prism album via MediaStore.
   * Returns true on success.
   */
  fun saveImageToGallery(
    context: Context,
    bytes: ByteArray,
    mime: String = "image/png",
    displayName: String = "prism_${System.currentTimeMillis()}.png",
  ): Boolean {
    return try {
      val values =
        ContentValues().apply {
          put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
          put(MediaStore.Images.Media.MIME_TYPE, mime)
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(
              MediaStore.Images.Media.RELATIVE_PATH,
              Environment.DIRECTORY_PICTURES + "/Prism",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
          }
        }
      val uri =
        context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
          ?: return false
      context.contentResolver.openOutputStream(uri)?.use { out ->
        out.write(bytes)
        out.flush()
      } ?: return false
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
      }
      true
    } catch (_: Exception) {
      false
    }
  }

  fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Boolean {
    val bytes =
      java.io.ByteArrayOutputStream().use { bos ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bos.toByteArray()
      }
    return saveImageToGallery(context, bytes, mime = "image/png")
  }

  /** Write base64 audio to cache and start [MediaPlayer]. Caller must release the player. */
  fun playAudioBase64(
    context: Context,
    base64: String,
    format: String = "mp3",
  ): MediaPlayer? {
    val bytes = decodeBase64Payload(base64) ?: return null
    val f = writeCacheFile(context, "prism-play.$format", bytes) ?: return null
    return MediaPlayer().apply {
      setDataSource(f.absolutePath)
      prepare()
      start()
    }
  }

  fun writeCacheFile(context: Context, name: String, bytes: ByteArray): File? {
    return try {
      val f = File(context.cacheDir, name)
      FileOutputStream(f).use { it.write(bytes) }
      f
    } catch (_: Exception) {
      null
    }
  }

  /** Share cache audio/image via [FileProvider]. */
  fun shareFile(
    context: Context,
    file: File,
    mime: String,
    chooserTitle: String = "Share",
  ): Boolean {
    return try {
      val uri =
        FileProvider.getUriForFile(
          context,
          "${context.packageName}.fileprovider",
          file,
        )
      val send =
        Intent(Intent.ACTION_SEND).apply {
          type = mime
          putExtra(Intent.EXTRA_STREAM, uri)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
      context.startActivity(Intent.createChooser(send, chooserTitle))
      true
    } catch (_: Exception) {
      false
    }
  }

  fun shareAudioBase64(
    context: Context,
    base64: String,
    format: String = "mp3",
  ): Boolean {
    val bytes = decodeBase64Payload(base64) ?: return false
    val f = writeCacheFile(context, "prism-share.$format", bytes) ?: return false
    val mime =
      when (format.lowercase()) {
        "wav" -> "audio/wav"
        "m4a", "mp4" -> "audio/mp4"
        else -> "audio/mpeg"
      }
    return shareFile(context, f, mime, "Share audio")
  }
}
