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

  /** `POST /v1/images/generations` -- `data[].b64_json` and/or `data[].url`. */
  fun generateImage(request: ImageGenerationRequest): ImageGenerationResponse {
    val key = requireKey()
    val (body, _) =
      mediaHttp.send<ImageGenerationRequest, ImageGenerationResponse>(
        "POST",
        "/v1/images/generations",
        body = request,
        bearer = key,
      )
    body.error?.let { err ->
      throw PrismError.Server(err.message ?: err.code ?: "image generation error")
    }
    if (body.firstBase64 == null && body.firstDisplayUrl == null) {
      throw PrismError.Server("Empty image payload")
    }
    return body
  }

  fun generateImage(model: String, prompt: String, image: String? = null): ImageGenerationResponse =
    generateImage(ImageGenerationRequest(model = model, prompt = prompt, image = image))

  /** `POST /v1/videos/generations` -- `video` is a URL or inline asset. */
  fun generateVideo(request: VideoGenerationRequest): VideoGenerationResponse {
    val key = requireKey()
    val (body, _) =
      mediaHttp.send<VideoGenerationRequest, VideoGenerationResponse>(
        "POST",
        "/v1/videos/generations",
        body = request,
        bearer = key,
      )
    body.error?.let { err ->
      throw PrismError.Server(err.message ?: err.code ?: "video generation error")
    }
    if (body.video.isNullOrEmpty()) throw PrismError.Server("Empty video payload")
    return body
  }

  fun generateVideo(model: String, prompt: String? = null, image: String? = null): VideoGenerationResponse =
    generateVideo(VideoGenerationRequest(model = model, prompt = prompt, image = image))

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
    /** Client wait above plane non-chat ceiling (180s); matches iOS. */
    const val NON_CHAT_TIMEOUT_SECONDS: Long = 200
  }
}
