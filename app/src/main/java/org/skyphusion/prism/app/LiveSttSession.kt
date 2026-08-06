package org.skyphusion.prism.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.skyphusion.prism.ControlPlaneClient

/**
 * Live plane STT: AudioRecord linear16 @ 16 kHz → WebSocket `/v1/stt/stream` (Deepgram Flux).
 * iOS does not ship this door yet; Android exposes it for full plane product coverage.
 */
class LiveSttSession(
  private val client: ControlPlaneClient,
  private val onPartial: (String) -> Unit,
  private val onFinal: (String) -> Unit,
  private val onError: (String) -> Unit,
  private val onClosed: () -> Unit,
) {
  private val running = AtomicBoolean(false)
  private var webSocket: WebSocket? = null
  private var recordThread: Thread? = null
  private var audioRecord: AudioRecord? = null

  val isRunning: Boolean get() = running.get()

  fun start() {
    if (!running.compareAndSet(false, true)) return
    val listener =
      object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
          startMic(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
          handleJson(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
          running.set(false)
          onError(t.message ?: "Live STT failed")
          stopMic()
          onClosed()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
          webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
          running.set(false)
          stopMic()
          onClosed()
        }
      }
    try {
      webSocket = client.openSttStream(listener)
    } catch (e: Exception) {
      running.set(false)
      onError(e.message ?: "Could not open STT stream")
      onClosed()
    }
  }

  fun stop() {
    running.set(false)
    stopMic()
    try {
      webSocket?.close(1000, "client stop")
    } catch (_: Exception) {
    }
    webSocket = null
  }

  private fun startMic(ws: WebSocket) {
    val sampleRate = 16_000
    val minBuf =
      AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    if (minBuf <= 0) {
      onError("AudioRecord unsupported on this device")
      stop()
      return
    }
    val bufSize = minBuf * 2
    val ar =
      try {
        AudioRecord(
          MediaRecorder.AudioSource.VOICE_RECOGNITION,
          sampleRate,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
          bufSize,
        )
      } catch (e: Exception) {
        onError(e.message ?: "AudioRecord open failed")
        stop()
        return
      }
    if (ar.state != AudioRecord.STATE_INITIALIZED) {
      ar.release()
      onError("AudioRecord not initialized")
      stop()
      return
    }
    audioRecord = ar
    ar.startRecording()
    recordThread =
      Thread {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buf = ByteArray(bufSize)
        while (running.get()) {
          val n = ar.read(buf, 0, buf.size)
          if (n > 0 && running.get()) {
            try {
              ws.send(buf.toByteString(0, n))
            } catch (_: Exception) {
              break
            }
          } else if (n < 0) {
            break
          }
        }
      }.also {
        it.name = "prism-live-stt"
        it.start()
      }
  }

  private fun stopMic() {
    try {
      audioRecord?.stop()
    } catch (_: Exception) {
    }
    try {
      audioRecord?.release()
    } catch (_: Exception) {
    }
    audioRecord = null
    recordThread = null
  }

  private fun handleJson(raw: String) {
    try {
      val obj = JSONObject(raw)
      // Deepgram-style: channel.alternatives[0].transcript
      var t: String? = null
      val channel = obj.optJSONObject("channel")
      val alts = channel?.optJSONArray("alternatives")
      if (alts != null && alts.length() > 0) {
        t = alts.getJSONObject(0).optString("transcript").takeIf { it.isNotBlank() }
      }
      if (t == null) {
        t =
          obj.optString("transcript").takeIf { it.isNotBlank() }
            ?: obj.optString("text").takeIf { it.isNotBlank() }
      }
      if (t.isNullOrBlank()) return

      val type = obj.optString("type")
      if (type.contains("EndOfTurn", ignoreCase = true) || type == "TurnInfo") {
        onFinal(t)
        return
      }
      val isFinal =
        obj.optBoolean("is_final", false) ||
          obj.optBoolean("speech_final", false) ||
          type.contains("Final", ignoreCase = true)
      if (isFinal) onFinal(t) else onPartial(t)
    } catch (_: Exception) {
      // ignore non-JSON control frames
    }
  }
}
