package org.skyphusion.prism

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient

/**
 * Client for the **Prism control plane** (metered inference, `play-proxy.skyphusion.org`).
 *
 * Auth is a long-lived device key: `Authorization: Bearer pcp_<key_id>_<secret>`.
 * Store the key in Android Keystore / EncryptedSharedPreferences; it is returned once at enrollment.
 *
 * Contract: prism-control-plane `docs/CONTRACT.md` + `docs/openapi.yaml`.
 */
class ControlPlaneClient(
  val http: HttpJson,
  clientKey: String? = null,
  /** Longer-timeout client for image/video (optional; defaults from [http] base URL). */
  nonChatHttp: HttpJson? = null,
) {
  /** Device key (`pcp_...`). Null until enroll or inject. */
  var clientKey: String? = clientKey
    private set

  private val mediaHttp: HttpJson =
    nonChatHttp ?: HttpJson(baseUrl = http.root, client = HttpJson.nonChatClient())

  /** Short client for async 202 enqueue (job id only). */
  private val enqueueHttp: HttpJson =
    HttpJson(baseUrl = http.root, client = HttpJson.asyncEnqueueClient())

  constructor(
    baseUrl: String = PRODUCTION_BASE_URL,
    clientKey: String? = null,
  ) : this(HttpJson(baseUrl), clientKey)

  fun setClientKey(key: String?) {
    clientKey = key
  }

  private fun requireKey(): String {
    val key = clientKey
    if (key.isNullOrBlank()) throw PrismError.Unauthenticated
    return key
  }

  // --- Health ---

  fun health(): ControlPlaneHealth {
    val (body, _) = http.send<Unit?, ControlPlaneHealth>("GET", "/health", body = null)
    return body
  }

  // --- Enrollment ---

  /**
   * Exchange a single-use enrollment token for a device key (returned once).
   * Platform defaults to `android`.
   */
  fun enroll(
    enrollmentToken: String,
    label: String? = null,
    platform: String = "android",
  ): EnrollmentResponse {
    @Serializable
    data class Body(
      val enrollment_token: String,
      val label: String? = null,
      val platform: String = "android",
    )
    val (res, _) =
      http.send<Body, EnrollmentResponse>(
        "POST",
        "/v1/clients",
        body = Body(enrollment_token = enrollmentToken, label = label, platform = platform),
        okStatuses = setOf(200, 201),
      )
    clientKey = res.key
    return res
  }

  // --- Account ---

  fun me(): MeResponse {
    val key = requireKey()
    val (body, _) = http.send<Unit?, MeResponse>("GET", "/v1/me", body = null, bearer = key)
    return body
  }

  fun usage(): UsageSummary {
    val key = requireKey()
    val (body, _) = http.send<Unit?, UsageSummary>("GET", "/v1/usage", body = null, bearer = key)
    return body
  }

  // --- Models ---

  /** Entitled model list with prices. Absent models are not entitled. */
  fun listModels(): ControlPlaneModelList {
    val key = requireKey()
    val (body, _) =
      http.send<Unit?, ControlPlaneModelList>("GET", "/v1/models", body = null, bearer = key)
    return body
  }

  // --- Inference ---

  fun chatCompletions(request: ControlPlaneChatRequest): ChatCompletionResult {
    val key = requireKey()
    val payload = request.copy(stream = false)
    val (body, headers) =
      http.send<ControlPlaneChatRequest, ControlPlaneChatResponse>(
        "POST",
        "/v1/chat/completions",
        body = payload,
        bearer = key,
      )
    body.error?.let { err ->
      throw PrismError.Server(err.message ?: err.code ?: "control plane error")
    }
    return ChatCompletionResult(body, PrismMeterHeaders.from(headers))
  }

  /** Multi-turn chat (non-streaming). */
  fun chat(model: String, messages: List<ControlPlaneChatMessage>): String {
    val res = chatCompletions(ControlPlaneChatRequest(model = model, messages = messages, stream = false))
    val text = res.response.firstContent
    if (text.isNullOrEmpty()) throw PrismError.Server("Empty completion")
    return text
  }

  /** Single-turn helper. */
  fun chat(model: String, user: String, system: String? = null): String {
    val messages = buildList {
      if (!system.isNullOrEmpty()) add(ControlPlaneChatMessage(role = "system", content = system))
      add(ControlPlaneChatMessage(role = "user", content = user))
    }
    return chat(model, messages)
  }

  /**
   * Streaming chat. Yields [ChatStreamEvent] as OpenAI-compatible SSE frames arrive.
   * Collect on a background dispatcher; closes the HTTP body when the flow completes or cancels.
   */
  fun chatCompletionsStream(request: ControlPlaneChatRequest): Flow<ChatStreamEvent> =
    flow {
      val key = requireKey()
      val payload = request.copy(stream = true)
      val bodyJson = prismJsonEncode.encodeToString(ControlPlaneChatRequest.serializer(), payload)
      val req =
        http.request(
          method = "POST",
          path = "/v1/chat/completions",
          bodyJson = bodyJson,
          bearer = key,
          headers = mapOf("Accept" to "text/event-stream"),
        )
      // body already set via bodyJson
      val call = http.client.newCall(req)
      val res =
        try {
          call.execute()
        } catch (e: Exception) {
          throw PrismError.Transport("Transport failed: ${e.message}", e)
        }
      if (res.code !in 200..299) {
        val raw = res.body?.string().orEmpty()
        res.close()
        throw HttpJson.mapHttpError(res.code, raw)
      }
      val source = res.body?.byteStream() ?: run {
        res.close()
        throw PrismError.Transport("Empty stream body")
      }
      try {
        val reader = BufferedReader(InputStreamReader(source, StandardCharsets.UTF_8))
        val carry = StringBuilder()
        while (true) {
          val line = reader.readLine() ?: break
          if (line.isEmpty()) {
            val chunk = carry.toString() + "\n\n"
            carry.clear()
            for (ev in SseParser.parseChatEvents(chunk)) {
              emit(ev)
              if (ev is ChatStreamEvent.Error) {
                throw PrismError.Server(ev.message)
              }
            }
          } else {
            if (carry.isNotEmpty()) carry.append('\n')
            carry.append(line)
          }
        }
        if (carry.isNotEmpty()) {
          for (ev in SseParser.parseChatEvents(carry.toString() + "\n\n")) {
            emit(ev)
            if (ev is ChatStreamEvent.Error) throw PrismError.Server(ev.message)
          }
        }
      } finally {
        res.close()
      }
    }.flowOn(Dispatchers.IO)

  /** Collect stream deltas into one string (simple helper). */
  suspend fun chatStreamText(model: String, user: String, system: String? = null): String {
    val messages = buildList {
      if (!system.isNullOrEmpty()) add(ControlPlaneChatMessage(role = "system", content = system))
      add(ControlPlaneChatMessage(role = "user", content = user))
    }
    val sb = StringBuilder()
    chatCompletionsStream(
      ControlPlaneChatRequest(model = model, messages = messages, stream = true),
    ).collect { ev ->
      when (ev) {
        is ChatStreamEvent.Delta -> sb.append(ev.text)
        is ChatStreamEvent.Done -> if (sb.isEmpty() && !ev.fullText.isNullOrEmpty()) sb.append(ev.fullText)
        is ChatStreamEvent.Error -> throw PrismError.Server(ev.message)
        is ChatStreamEvent.Unknown -> Unit
      }
    }
    if (sb.isEmpty()) throw PrismError.Server("Empty stream completion")
    return sb.toString()
  }

  // --- Image / video (unit-priced) ---

  /**
   * `POST /v1/images/generations` -- `data[].b64_json`/`url`, or 202 job (gpt-image-2 / async).
   * When [ImageGenerationRequest.async] is true, sends Prefer: respond-async.
   */
  fun generateImage(request: ImageGenerationRequest): ImageGenerationResponse {
    val key = requireKey()
    val async = request.async == true
    val client = if (async) enqueueHttp else mediaHttp
    val headers = if (async) mapOf("Prefer" to "respond-async") else emptyMap()
    val (body, _) =
      client.send<ImageGenerationRequest, ImageGenerationResponse>(
        "POST",
        "/v1/images/generations",
        body = request,
        bearer = key,
        headers = headers,
        okStatuses = (200..299).toSet(),
      )
    body.error?.let { err ->
      throw PrismError.Server(err.message ?: err.code ?: "image generation error")
    }
    if (body.isAsyncAccept) {
      if (body.id.isNullOrBlank()) throw PrismError.Server("Async image job missing id")
      return body
    }
    if (body.firstBase64 == null && body.firstDisplayUrl == null) {
      throw PrismError.Server("Empty image payload")
    }
    return body
  }

  fun generateImage(
    model: String,
    prompt: String,
    image: String? = null,
    async: Boolean? = null,
  ): ImageGenerationResponse =
    generateImage(
      ImageGenerationRequest(model = model, prompt = prompt, image = image, async = async),
    )

  /**
   * `POST /v1/videos/generations`. Prefer [async] true (plane 0.4.29+): 202 + job id.
   * Poll with [getJob] / [waitForJob].
   */
  fun generateVideo(request: VideoGenerationRequest): VideoGenerationResponse {
    val key = requireKey()
    val async = request.async == true
    val client = if (async) enqueueHttp else mediaHttp
    val headers = if (async) mapOf("Prefer" to "respond-async") else emptyMap()
    // Always send model-correct duration (int vs "8s") so plane/CF get a valid clip length.
    val payload =
      if (request.duration != null) {
        request
      } else {
        request.copy(duration = VideoClipDuration.forModel(request.model))
      }
    val (body, _) =
      client.send<VideoGenerationRequest, VideoGenerationResponse>(
        "POST",
        "/v1/videos/generations",
        body = payload,
        bearer = key,
        headers = headers,
        okStatuses = (200..299).toSet(),
      )
    body.error?.let { err ->
      throw PrismError.Server(err.message ?: err.code ?: "video generation error")
    }
    if (body.isAsyncAccept) {
      if (body.id.isNullOrBlank()) throw PrismError.Server("Async video job missing id")
      return body
    }
    if (body.video.isNullOrEmpty()) throw PrismError.Server("Empty video payload")
    return body
  }

  fun generateVideo(
    model: String,
    prompt: String? = null,
    image: String? = null,
    async: Boolean = true,
    /** Clip length in seconds; wired per-model (int or Veo "Ns"). Null = model default. */
    durationSeconds: Int? = null,
  ): VideoGenerationResponse =
    generateVideo(
      VideoGenerationRequest(
        model = model,
        prompt = prompt,
        image = image,
        async = async,
        duration =
          if (durationSeconds != null) {
            VideoClipDuration.wire(model, durationSeconds)
          } else {
            VideoClipDuration.forModel(model)
          },
      ),
    )

  /** `GET /v1/jobs/:id` -- poll async video/music/speech job (plane 0.4.29+). */
  fun getJob(id: String): AsyncJobResponse {
    val key = requireKey()
    val encoded = id.trim()
    require(encoded.isNotEmpty()) { "job id required" }
    val (body, _) =
      enqueueHttp.send<Unit?, AsyncJobResponse>(
        "GET",
        "/v1/jobs/$encoded",
        body = null,
        bearer = key,
      )
    return body
  }

  /**
   * Poll until succeeded/failed or [timeoutMs] elapses.
   * @throws PrismError.Server if still non-terminal after timeout
   */
  fun waitForJob(
    id: String,
    pollIntervalMs: Long = 4_000,
    timeoutMs: Long = 420_000,
  ): AsyncJobResponse {
    val deadline = System.currentTimeMillis() + timeoutMs
    var last: AsyncJobResponse? = null
    while (System.currentTimeMillis() < deadline) {
      val job = getJob(id)
      last = job
      if (job.isTerminal) return job
      try {
        Thread.sleep(pollIntervalMs)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        throw PrismError.Transport("Job poll interrupted")
      }
    }
    last?.let {
      if (!it.isTerminal) {
        throw PrismError.Server("Job still running on the plane")
      }
      return it
    }
    throw PrismError.Server("Job $id timed out waiting for completion")
  }

  /**
   * Probe media URL until HEAD/GET succeeds (Grok R2 race after ZDR).
   * Returns true when the URL responds 2xx (or 206 Partial).
   */
  fun waitForMediaReady(
    mediaUrl: String,
    attempts: Int = 12,
    delayMs: Long = 2_500,
  ): Boolean {
    val url = mediaUrl.trim()
    if (url.isEmpty() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
      return true
    }
    val probe =
      OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    repeat(attempts) { i ->
      try {
        val head =
          okhttp3.Request.Builder()
            .url(url)
            .head()
            .header("Range", "bytes=0-1")
            .build()
        probe.newCall(head).execute().use { res ->
          if (res.code in 200..299 || res.code == 206) return true
        }
      } catch (_: Exception) {
        // retry
      }
      if (i < attempts - 1) {
        try {
          Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          return false
        }
      }
    }
    return false
  }

  // --- Speech TTS / STT / music (unit-priced) ---

  /** `POST /v1/audio/speech` -- metered TTS. Prefer [async] true (plane 0.4.32+). */
  fun generateSpeech(request: SpeechGenerationRequest): SpeechGenerationResponse {
    val key = requireKey()
    val async = request.async == true
    val client = if (async) enqueueHttp else mediaHttp
    val headers = if (async) mapOf("Prefer" to "respond-async") else emptyMap()
    val (body, _) =
      client.send<SpeechGenerationRequest, SpeechGenerationResponse>(
        "POST",
        "/v1/audio/speech",
        body = request,
        bearer = key,
        headers = headers,
        okStatuses = (200..299).toSet(),
      )
    body.error?.let { err ->
      throw PrismError.Server(err.message ?: err.code ?: "speech generation error")
    }
    if (body.isAsyncAccept) {
      if (body.id.isNullOrBlank()) throw PrismError.Server("Async speech job missing id")
      return body
    }
    if (body.audioBytes() == null) throw PrismError.Server("Empty speech audio payload")
    return body
  }

  fun generateSpeech(model: String, input: String, async: Boolean = true): SpeechGenerationResponse =
    generateSpeech(SpeechGenerationRequest(model = model, input = input, async = async))

  /** `POST /v1/audio/transcriptions` -- metered STT; [audio] is base64 or data: URL. */
  fun transcribe(request: TranscriptionRequest): TranscriptionResponse {
    val key = requireKey()
    val (body, _) =
      mediaHttp.send<TranscriptionRequest, TranscriptionResponse>(
        "POST",
        "/v1/audio/transcriptions",
        body = request,
        bearer = key,
      )
    body.error?.let { err ->
      throw PrismError.Server(err.message ?: err.code ?: "transcription error")
    }
    if (body.text.isNullOrBlank()) throw PrismError.Server("Empty transcription")
    return body
  }

  fun transcribe(model: String, audio: String): TranscriptionResponse =
    transcribe(TranscriptionRequest(model = model, audio = audio))

  /**
   * Mint a short-lived STT ticket for browser-style WebSocket auth.
   * Native Android prefers [openSttStream] with Bearer on the upgrade instead.
   */
  fun createSttSession(): SttSessionResponse {
    val key = requireKey()
    val (body, _) =
      http.send<Unit?, SttSessionResponse>(
        "POST",
        "/v1/stt/sessions",
        body = null,
        bearer = key,
      )
    body.error?.let { err ->
      throw PrismError.Server(err.message ?: err.code ?: "stt session failed")
    }
    if (body.ticket.isNullOrBlank()) throw PrismError.Server("Empty STT ticket")
    return body
  }

  /**
   * Open live mic STT WebSocket (`GET /v1/stt/stream`) with `Authorization: Bearer pcp_…`.
   * Client sends linear16 PCM @ 16 kHz binary frames; receives Deepgram Flux JSON.
   */
  fun openSttStream(listener: okhttp3.WebSocketListener): okhttp3.WebSocket {
    val key = requireKey()
    val req =
      http.request(
        method = "GET",
        path = "/v1/stt/stream",
        bodyJson = null,
        bearer = key,
        headers = mapOf("Upgrade" to "websocket", "Connection" to "Upgrade"),
      )
    return http.client.newWebSocket(req, listener)
  }

  /** Absolute stream URL (for diagnostics). */
  fun sttStreamUrl(): String = http.url("/v1/stt/stream")

  /** `POST /v1/music/generations` -- metered music. Prefer [async] true (plane 0.4.29+). */
  fun generateMusic(request: MusicGenerationRequest): MusicGenerationResponse {
    val key = requireKey()
    val async = request.async == true
    val client = if (async) enqueueHttp else mediaHttp
    val headers = if (async) mapOf("Prefer" to "respond-async") else emptyMap()
    val (body, _) =
      client.send<MusicGenerationRequest, MusicGenerationResponse>(
        "POST",
        "/v1/music/generations",
        body = request,
        bearer = key,
        headers = headers,
        okStatuses = (200..299).toSet(),
      )
    body.error?.let { err ->
      throw PrismError.Server(err.message ?: err.code ?: "music generation error")
    }
    if (body.isAsyncAccept) {
      if (body.id.isNullOrBlank()) throw PrismError.Server("Async music job missing id")
      return body
    }
    if (body.audio.isNullOrBlank()) throw PrismError.Server("Empty music audio payload")
    return body
  }

  fun generateMusic(
    model: String,
    prompt: String,
    lyrics: String? = null,
    async: Boolean = true,
  ): MusicGenerationResponse =
    generateMusic(
      MusicGenerationRequest(model = model, prompt = prompt, lyrics = lyrics, async = async),
    )

  // --- Store (prepaid credit) ---

  /**
   * Redeem a Google Play Billing purchase (`POST /v1/store/redeem`).
   * Plane 0.4.16+: verifies via Android Publisher when configured.
   */
  fun redeemGooglePlay(
    purchaseToken: String,
    productId: String,
    packageName: String = StoreProducts.PACKAGE_NAME,
  ): StoreRedeemResponse {
    val key = requireKey()
    val (body, _) =
      http.send<GooglePlayRedeemRequest, StoreRedeemResponse>(
        "POST",
        "/v1/store/redeem",
        body =
          GooglePlayRedeemRequest(
            purchaseToken = purchaseToken,
            productId = productId,
            packageName = packageName,
          ),
        bearer = key,
      )
    body.error?.let { err ->
      throw PrismError.Server(err.message ?: err.code ?: "store redeem failed")
    }
    return body
  }

  companion object {
    const val PRODUCTION_BASE_URL: String = "https://play-proxy.skyphusion.org"
    /** Sync fallback / poll budget; matches iOS musicTimeout 420s. */
    const val NON_CHAT_TIMEOUT_SECONDS: Long = 420
    const val JOB_POLL_TIMEOUT_MS: Long = 420_000
    const val SPEECH_JOB_POLL_TIMEOUT_MS: Long = 180_000
  }
}
