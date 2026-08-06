package org.skyphusion.prism.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * AAC/m4a microphone capture for plane STT (Whisper-friendly), mirroring iOS AudioRecorder.
 */
class MicRecorder(private val context: Context) {
  private var recorder: MediaRecorder? = null
  private var file: File? = null
  var isRecording: Boolean = false
    private set
  var errorMessage: String? = null
    private set
  var elapsedSeconds: Int = 0
    private set
  private var startedAtMs: Long = 0

  fun start(): Boolean {
    errorMessage = null
    stopInternal(deleteFile = true)
    val out = File(context.cacheDir, "prism-stt-${System.currentTimeMillis()}.m4a")
    return try {
      val rec =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          MediaRecorder(context)
        } else {
          @Suppress("DEPRECATION")
          MediaRecorder()
        }
      rec.setAudioSource(MediaRecorder.AudioSource.MIC)
      rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
      rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
      rec.setAudioSamplingRate(16_000)
      rec.setAudioChannels(1)
      rec.setAudioEncodingBitRate(64_000)
      rec.setOutputFile(out.absolutePath)
      rec.prepare()
      rec.start()
      recorder = rec
      file = out
      isRecording = true
      startedAtMs = System.currentTimeMillis()
      elapsedSeconds = 0
      true
    } catch (e: Exception) {
      errorMessage = e.message ?: "Could not start microphone recording."
      isRecording = false
      recorder = null
      file = null
      false
    }
  }

  /** Tick elapsed while recording (call from UI timer). */
  fun tickElapsed() {
    if (!isRecording) return
    elapsedSeconds = ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt()
  }

  /** Stop and return AAC/m4a bytes + path, or null on failure. */
  fun stop(): Pair<ByteArray, File>? {
    return try {
      val rec = recorder
      val out = file
      rec?.stop()
      rec?.release()
      recorder = null
      isRecording = false
      if (out == null || !out.exists() || out.length() == 0L) {
        errorMessage = "No audio captured."
        null
      } else {
        out.readBytes() to out
      }
    } catch (e: Exception) {
      errorMessage = e.message ?: "Stop failed"
      stopInternal(deleteFile = false)
      null
    }
  }

  fun cancel() {
    stopInternal(deleteFile = true)
    elapsedSeconds = 0
  }

  private fun stopInternal(deleteFile: Boolean) {
    try {
      recorder?.stop()
    } catch (_: Exception) {
    }
    try {
      recorder?.release()
    } catch (_: Exception) {
    }
    recorder = null
    isRecording = false
    if (deleteFile) {
      file?.delete()
    }
    file = null
  }
}
