package org.skyphusion.prism

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Additive-friendly shapes for the commercial control plane
// (prism-control-plane docs/CONTRACT.md + openapi.yaml).

@Serializable
data class ControlPlaneHealth(
  val ok: Boolean,
  val service: String? = null,
)

@Serializable
data class AccountSummary(
  val id: String? = null,
  @SerialName("credit_micro_usd") val creditMicroUsd: Long? = null,
  val plan: String? = null,
  @SerialName("plan_id") val planId: String? = null,
  val status: String? = null,
)

@Serializable
data class EnrollmentResponse(
  @SerialName("client_id") val clientId: String,
  val key: String,
  val account: AccountSummary? = null,
)

@Serializable
data class ControlPlaneModelList(
  val `object`: String? = null,
  val data: List<ControlPlaneModel> = emptyList(),
)

@Serializable
data class ControlPlaneModel(
  val id: String,
  @SerialName("display_name") val displayName: String? = null,
  val modality: String? = null,
  val billing: String? = null,
  val tier: String? = null,
  val streaming: Boolean? = null,
  @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
  /** Whether the plane will run this model today (false => grey out, do not drop). */
  val spendable: Boolean? = null,
  /** Picker hints: `text-to-image`, `image-input`, `image-input-required`, `text-to-video`, … */
  val capabilities: List<String>? = null,
  val price: ControlPlaneTokenPrice? = null,
  @SerialName("unit_price") val unitPrice: ControlPlaneUnitPrice? = null,
) {
  fun priceSnippet(): String? {
    unitPrice?.microUsdPerUnit?.let { u ->
      val usd = u / 1_000_000.0
      val unitName = unitPrice.unit ?: "unit"
      if (usd == 0.0) return "included"
      return if (usd >= 0.01) String.format("$%.2f/%s", usd, unitName)
      else String.format("$%.4f/%s", usd, unitName)
    }
    val inp = price?.inputMicroUsdPerMTok
    val out = price?.outputMicroUsdPerMTok
    if (inp != null && out != null) {
      return String.format("$%.2f/$%.2f /MTok", inp / 1e6, out / 1e6)
    }
    return null
  }

  fun acceptsImageInput(): Boolean {
    val caps = capabilities.orEmpty()
    return caps.any { it == "image-input" || it == "image-input-required" }
  }

  fun requiresImageInput(): Boolean =
    capabilities.orEmpty().contains("image-input-required")

  /** Vision-capable chat models (image_url multiparty). */
  fun supportsVision(): Boolean {
    val caps = capabilities.orEmpty()
    return caps.any { it.contains("vision", ignoreCase = true) } ||
      caps.contains("image-input") ||
      acceptsImageInput()
  }

  /** Short capability tags for pickers (vision, stream, tier, unit). */
  fun capabilityTags(): List<String> {
    val tags = mutableListOf<String>()
    if (supportsVision()) tags.add("vision")
    if (streaming == true) tags.add("stream")
    tier?.takeIf { it.isNotBlank() }?.let { tags.add(it) }
    val p = priceSnippet()
    if (p != null) {
      when {
        p.contains("/request") || p.contains("/unit") || p.contains("/image") ||
          p.contains("/video") -> tags.add("unit")
        p.contains("MTok") || p.contains("/M") -> tags.add("token")
        p == "included" -> tags.add("included")
      }
    }
    if (spendable == false) tags.add("unspendable")
    return tags
  }
}

@Serializable
data class ControlPlaneTokenPrice(
  @SerialName("input_micro_usd_per_mtok") val inputMicroUsdPerMTok: Long? = null,
  @SerialName("output_micro_usd_per_mtok") val outputMicroUsdPerMTok: Long? = null,
  @SerialName("priced_at") val pricedAt: String? = null,
  val source: String? = null,
)

@Serializable
data class ControlPlaneUnitPrice(
  @SerialName("micro_usd_per_unit") val microUsdPerUnit: Long? = null,
  val unit: String? = null,
  @SerialName("priced_at") val pricedAt: String? = null,
  val source: String? = null,
)

@Serializable
data class MeResponse(
  val client: MeClientInfo? = null,
  val account: AccountSummary? = null,
  val plan: PlanSummary? = null,
  val usage: UsageSummary? = null,
)

@Serializable
data class MeClientInfo(
  val id: String? = null,
  val label: String? = null,
  val platform: String? = null,
  @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class PlanSummary(
  val id: String? = null,
  val name: String? = null,
  @SerialName("monthly_included_micro_usd") val monthlyIncludedMicroUsd: Long? = null,
)

@Serializable
data class UsageSummary(
  @SerialName("credit_micro_usd") val creditMicroUsd: Long? = null,
  @SerialName("spent_micro_usd") val spentMicroUsd: Long? = null,
  @SerialName("remaining_micro_usd") val remainingMicroUsd: Long? = null,
  @SerialName("monthly_included_micro_usd") val monthlyIncludedMicroUsd: Long? = null,
  @SerialName("allowance_spent_micro_usd") val allowanceSpentMicroUsd: Long? = null,
  @SerialName("allowance_remaining_micro_usd") val allowanceRemainingMicroUsd: Long? = null,
  @SerialName("spendable_remaining_micro_usd") val spendableRemainingMicroUsd: Long? = null,
  val overage: Boolean? = null,
  val period: String? = null,
  @SerialName("period_start") val periodStart: String? = null,
  @SerialName("period_end") val periodEnd: String? = null,
  @SerialName("period_micro_usd") val periodMicroUsd: Long? = null,
  @SerialName("period_requests") val periodRequests: Long? = null,
  @SerialName("period_unmetered_requests") val periodUnmeteredRequests: Long? = null,
  @SerialName("period_adjust_spend_micro_usd") val periodAdjustSpendMicroUsd: Long? = null,
  @SerialName("period_adjust_credit_micro_usd") val periodAdjustCreditMicroUsd: Long? = null,
  @SerialName("period_reconciled_micro_usd") val periodReconciledMicroUsd: Long? = null,
) {
  /** Human-readable balance line (micro-USD -> USD). */
  fun balanceDescription(): String {
    val spendable = spendableRemainingMicroUsd ?: remainingMicroUsd
    if (spendable != null) {
      val usd = spendable / 1_000_000.0
      val p = period?.let { " · $it" } ?: ""
      return String.format("$%.4f remaining%s", usd, p)
    }
    return "usage unknown"
  }

  private fun usd(micro: Long): String = String.format("$%.4f", micro / 1_000_000.0)

  /** Multi-line dual-pool summary for Settings / Usage / More hub. */
  fun dualPoolLines(): List<String> {
    val lines = mutableListOf<String>()
    val spendable = spendableRemainingMicroUsd ?: remainingMicroUsd
    if (spendable != null) {
      lines.add("Spendable: ${usd(spendable)}")
    }
    remainingMicroUsd?.let {
      lines.add("Prepaid remaining: ${usd(it)}")
    } ?: creditMicroUsd?.let {
      lines.add("Prepaid credit (lifetime grant): ${usd(it)}")
    }
    spentMicroUsd?.let {
      lines.add("Prepaid spent (lifetime): ${usd(it)}")
    }
    allowanceRemainingMicroUsd?.let {
      lines.add("Monthly remaining: ${usd(it)}")
    } ?: run {
      val incl = monthlyIncludedMicroUsd
      val spent = allowanceSpentMicroUsd
      if (incl != null && spent != null) {
        lines.add("Monthly remaining: ${usd((incl - spent).coerceAtLeast(0))}")
      }
    }
    monthlyIncludedMicroUsd?.let {
      lines.add("Monthly included: ${usd(it)}")
    }
    period?.let { lines.add("Period: $it") }
    if (overage == true) lines.add("Overage: yes")
    return lines
  }

  /** Period meter detail for the Usage screen. */
  fun periodDetailLines(): List<String> {
    val lines = mutableListOf<String>()
    periodRequests?.let { lines.add("Requests this period: $it") }
    periodUnmeteredRequests?.takeIf { it > 0 }?.let {
      lines.add("Unmetered requests: $it (plane could not price; still served)")
    }
    periodMicroUsd?.let { lines.add("Meter estimate: ${usd(it)}") }
    periodReconciledMicroUsd?.let { lines.add("Reconciled spend: ${usd(it)}") }
    periodAdjustSpendMicroUsd?.takeIf { it > 0 }?.let {
      lines.add("Reconcile +spend: ${usd(it)}")
    }
    periodAdjustCreditMicroUsd?.takeIf { it > 0 }?.let {
      lines.add("Reconcile credits: ${usd(it)}")
    }
    if (periodStart != null && periodEnd != null) {
      lines.add("Window: $periodStart → $periodEnd")
    }
    return lines
  }
}

/**
 * OpenAI-style chat message. When [imageDataUrls] is non-empty, encode uses multiparty
 * `content` (image_url parts + text) for vision models (plane 0.4.23+).
 */
@Serializable(with = ControlPlaneChatMessageSerializer::class)
data class ControlPlaneChatMessage(
  val role: String,
  val content: String,
  val imageDataUrls: List<String>? = null,
)

object ControlPlaneChatMessageSerializer :
  kotlinx.serialization.KSerializer<ControlPlaneChatMessage> {
  override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor =
    kotlinx.serialization.descriptors.buildClassSerialDescriptor("ControlPlaneChatMessage") {
      element("role", kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("role", kotlinx.serialization.descriptors.PrimitiveKind.STRING))
      element("content", JsonElement.serializer().descriptor)
    }

  override fun serialize(
    encoder: kotlinx.serialization.encoding.Encoder,
    value: ControlPlaneChatMessage,
  ) {
    val jsonEncoder =
      encoder as? kotlinx.serialization.json.JsonEncoder
        ?: error("ControlPlaneChatMessage requires JSON encoding")
    val contentEl: JsonElement =
      if (!value.imageDataUrls.isNullOrEmpty()) {
        buildJsonArray {
          for (url in value.imageDataUrls) {
            add(
              buildJsonObject {
                put("type", "image_url")
                put(
                  "image_url",
                  buildJsonObject {
                    put("url", url)
                  },
                )
              },
            )
          }
          add(
            buildJsonObject {
              put("type", "text")
              put("text", value.content.ifEmpty { " " })
            },
          )
        }
      } else {
        JsonPrimitive(value.content)
      }
    jsonEncoder.encodeJsonElement(
      buildJsonObject {
        put("role", value.role)
        put("content", contentEl)
      },
    )
  }

  override fun deserialize(
    decoder: kotlinx.serialization.encoding.Decoder,
  ): ControlPlaneChatMessage {
    val jsonDecoder =
      decoder as? kotlinx.serialization.json.JsonDecoder
        ?: error("ControlPlaneChatMessage requires JSON decoding")
    val obj = jsonDecoder.decodeJsonElement().jsonObject
    val role = obj["role"]?.jsonPrimitive?.content.orEmpty()
    val contentEl = obj["content"]
    val content =
      when (contentEl) {
        is kotlinx.serialization.json.JsonPrimitive -> contentEl.content
        is kotlinx.serialization.json.JsonArray ->
          contentEl.mapNotNull { part ->
            part.jsonObject["text"]?.jsonPrimitive?.content
          }.joinToString("")
        else -> ""
      }
    return ControlPlaneChatMessage(role = role, content = content)
  }
}

@Serializable
data class ControlPlaneChatRequest(
  val model: String,
  val messages: List<ControlPlaneChatMessage>,
  val stream: Boolean? = false,
  @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable
data class ControlPlaneChatResponse(
  val id: String? = null,
  val choices: List<Choice>? = null,
  val usage: ChatUsage? = null,
  val error: ControlPlaneErrorBody? = null,
) {
  @Serializable
  data class Choice(
    val index: Int? = null,
    val message: ControlPlaneChatMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
    val delta: Delta? = null,
  )

  @Serializable
  data class Delta(
    val role: String? = null,
    val content: String? = null,
  )

  val firstContent: String?
    get() = choices?.firstOrNull()?.message?.content
}

@Serializable
data class ChatUsage(
  @SerialName("prompt_tokens") val promptTokens: Int? = null,
  @SerialName("completion_tokens") val completionTokens: Int? = null,
  @SerialName("total_tokens") val totalTokens: Int? = null,
  @SerialName("tokens_in") val tokensIn: Int? = null,
  @SerialName("tokens_out") val tokensOut: Int? = null,
)

@Serializable
data class ControlPlaneErrorBody(
  val code: String? = null,
  val message: String? = null,
)

@Serializable
data class ErrorEnvelope(
  val error: ControlPlaneErrorBody? = null,
  // Some handlers put message at top level.
  val message: String? = null,
  val code: String? = null,
)

/** Metering facts from prism-* response headers (non-stream completions). */
data class PrismMeterHeaders(
  val requestId: String? = null,
  val apiVersion: String? = null,
  val model: String? = null,
  val maxTokensApplied: Int? = null,
  val usageMicroUsd: Long? = null,
  val metered: Boolean? = null,
  val usageRecorded: Boolean? = null,
  val stream: Boolean? = null,
  val period: String? = null,
  val creditMicroUsd: Long? = null,
  val spentMicroUsd: Long? = null,
  val creditRemainingMicroUsd: Long? = null,
  val monthlyIncludedMicroUsd: Long? = null,
  val allowanceRemainingMicroUsd: Long? = null,
) {
  /** One-line cost for chat UI (iOS PlaneMeterHeaders.costDescription). */
  fun costDescription(): String? {
    val u = usageMicroUsd ?: return null
    if (metered == false) return "Unmetered (plane could not price this call)"
    val usd = u / 1_000_000.0
    return if (usd >= 0.01) {
      String.format("This request: $%.4f", usd)
    } else {
      String.format("This request: $%.6f", usd)
    }
  }

  companion object {
    fun from(headers: Map<String, List<String>>): PrismMeterHeaders {
      fun one(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
          ?.value?.firstOrNull()

      fun long(name: String): Long? = one(name)?.toLongOrNull()
      fun int(name: String): Int? = one(name)?.toIntOrNull()
      fun bool(name: String): Boolean? =
        when (one(name)?.lowercase()) {
          "true" -> true
          "false" -> false
          else -> null
        }

      return PrismMeterHeaders(
        requestId = one("prism-request-id"),
        apiVersion = one("prism-api-version"),
        model = one("prism-model"),
        maxTokensApplied = int("prism-max-tokens-applied"),
        usageMicroUsd = long("prism-usage-micro-usd"),
        metered = bool("prism-metered"),
        usageRecorded = bool("prism-usage-recorded"),
        stream = bool("prism-stream"),
        period = one("prism-period"),
        creditMicroUsd = long("prism-credit-micro-usd"),
        spentMicroUsd = long("prism-spent-micro-usd"),
        creditRemainingMicroUsd = long("prism-credit-remaining-micro-usd"),
        monthlyIncludedMicroUsd = long("prism-monthly-included-micro-usd"),
        allowanceRemainingMicroUsd = long("prism-allowance-remaining-micro-usd"),
      )
    }
  }
}

data class ChatCompletionResult(
  val response: ControlPlaneChatResponse,
  val meters: PrismMeterHeaders,
)

/** Stream events from OpenAI-compatible SSE (control plane) or playground-shaped frames. */
sealed class ChatStreamEvent {
  data class Delta(val text: String) : ChatStreamEvent()
  data class Done(
    val fullText: String?,
    val usage: ChatUsage? = null,
    /** Playground multi-turn id when present on the done frame. */
    val conversationId: String? = null,
  ) : ChatStreamEvent()
  data class Error(val message: String) : ChatStreamEvent()
  data class Unknown(val raw: String) : ChatStreamEvent()
}

/** Ignore unknown JSON fields; keep decode resilient as the plane adds fields. */
val prismJson =
  kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = false
  }

/**
 * Request encoding: include defaults like `stream: false` and enrollment `platform`
 * so the wire body matches the contract examples.
 */
val prismJsonEncode =
  kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
  }

/** Holder for raw JSON elements we do not model yet (forward-compat escape). */
@Serializable
data class JsonBag(val value: JsonElement)

// --- Playground Worker catalog / auth / chat ---

/**
 * One catalog entry from `GET /api/models`.
 * Live playground publishes the id as `id`; older fixtures use `model`.
 */
@Serializable
data class PlaygroundModelEntry(
  val id: String? = null,
  val model: String? = null,
  val label: String? = null,
  val type: String? = null,
  val provider: String? = null,
  val streaming: Boolean? = null,
  val group: String? = null,
  val capabilities: List<String>? = null,
  val priceLabel: String? = null,
) {
  val modelId: String get() = model?.takeIf { it.isNotBlank() } ?: id.orEmpty()

  val isSpendable: Boolean
    get() = !(capabilities.orEmpty().contains("unspendable"))

  /** Map into the plane picker shape so UI can reuse chat/image filters. */
  fun toControlPlaneModel(): ControlPlaneModel =
    ControlPlaneModel(
      id = modelId,
      displayName = label,
      modality = type,
      streaming = streaming,
      spendable = isSpendable,
      capabilities = capabilities,
    )
}

@Serializable
data class GatewayStatus(
  val configured: Boolean? = null,
  val source: String? = null,
  @SerialName("gateway_id") val gatewayId: String? = null,
  @SerialName("cf_aig_token_set") val cfAigTokenSet: Boolean? = null,
  @SerialName("control_plane_configured") val controlPlaneConfigured: Boolean? = null,
  @SerialName("control_plane_key_set") val controlPlaneKeySet: Boolean? = null,
)

/** Envelope of `GET /api/models` (boot probe; no session required). */
@Serializable
data class PlaygroundModelsResponse(
  val models: List<PlaygroundModelEntry> = emptyList(),
  val mode: String? = null,
  val authenticated: Boolean? = null,
  val user: String? = null,
  val username: String? = null,
  val gateway: GatewayStatus? = null,
)

@Serializable
data class AuthUser(val username: String)

@Serializable
data class AuthSuccess(
  val user: AuthUser? = null,
  val ok: Boolean? = null,
  val error: String? = null,
)

/** Playground attachment (image / audio / document). Matches prism InputAttachment subset. */
@Serializable
data class ChatAttachment(
  val type: String,
  val data: String? = null,
  val mime: String? = null,
  val name: String? = null,
) {
  companion object {
    /** Image from a data URL (`data:image/png;base64,...`). */
    fun image(dataURL: String, name: String? = null): ChatAttachment {
      var mime = "image/jpeg"
      var b64 = dataURL
      if (dataURL.startsWith("data:")) {
        val comma = dataURL.indexOf(',')
        if (comma > 0) {
          val header = dataURL.substring(5, comma) // after "data:"
          val semi = header.indexOf(';')
          mime = if (semi >= 0) header.substring(0, semi) else header
          b64 = dataURL.substring(comma + 1)
        }
      }
      return ChatAttachment(type = "image", data = b64, mime = mime, name = name)
    }
  }
}

@Serializable
data class PlaygroundChatRequest(
  val model: String,
  @SerialName("user_input") val userInput: String,
  @SerialName("system_prompt") val systemPrompt: String? = null,
  @SerialName("conversation_id") val conversationId: String? = null,
  @SerialName("use_docs") val useDocs: Boolean? = null,
  @SerialName("use_web_search") val useWebSearch: Boolean? = null,
  val attachments: List<ChatAttachment>? = null,
)

/** One row from `GET /api/conversations` (playground server history). */
@Serializable
data class ConversationListItem(
  @SerialName("conversation_id") val conversationId: String,
  @SerialName("turn_count") val turnCount: Int? = null,
  @SerialName("first_input") val firstInput: String? = null,
  @SerialName("latest_model") val latestModel: String? = null,
  @SerialName("last_created_at") val lastCreatedAt: String? = null,
  @SerialName("first_created_at") val firstCreatedAt: String? = null,
)

@Serializable
data class ConversationListResponse(
  val conversations: List<ConversationListItem>? = null,
)

/** Chat table row from `GET /api/conversations/:id`. */
@Serializable
data class ConversationTurnRow(
  val role: String? = null,
  @SerialName("user_input") val userInput: String? = null,
  val output: String? = null,
  val model: String? = null,
  @SerialName("turn_index") val turnIndex: Int? = null,
  @SerialName("assistant_output") val assistantOutput: String? = null,
) {
  val resolvedOutput: String? get() = output ?: assistantOutput
}

@Serializable
data class PlaygroundChatResponse(
  val output: String? = null,
  @SerialName("conversation_id") val conversationId: String? = null,
  val model: String? = null,
  @SerialName("tokens_in") val tokensIn: Int? = null,
  @SerialName("tokens_out") val tokensOut: Int? = null,
  val error: String? = null,
)

// --- Conversation compact (playground Worker v0.175.7) ---

/**
 * Compact summary injected into model context instead of older raw turns.
 * UI transcript is unchanged; only the wire history shrinks.
 */
@Serializable
data class ConversationCompactState(
  val summary: String,
  @SerialName("through_turn_index") val throughTurnIndex: Int,
  @SerialName("keep_recent") val keepRecent: Int,
  val model: String,
  @SerialName("updated_at") val updatedAt: String? = null,
) {
  /** System block to prepend when compact is active. */
  val systemBlock: String
    get() = ConversationCompact.buildSystemBlock(summary)
}

/** `POST /api/conversations/:id/compact` body. */
@Serializable
data class ConversationCompactRequest(
  @SerialName("keep_recent") val keepRecent: Int = ConversationCompact.DEFAULT_KEEP_RECENT,
  val model: String? = null,
)

/** `POST /api/conversations/:id/compact` response. */
@Serializable
data class ConversationCompactResponse(
  @SerialName("conversation_id") val conversationId: String? = null,
  val compact: ConversationCompactState? = null,
  @SerialName("turns_summarized") val turnsSummarized: Int? = null,
  @SerialName("turns_kept_raw") val turnsKeptRaw: Int? = null,
  val error: String? = null,
  val code: String? = null,
)

/** `DELETE /api/conversations/:id/compact` response. */
@Serializable
data class ConversationCompactClearResponse(
  @SerialName("conversation_id") val conversationId: String? = null,
  val compact: ConversationCompactState? = null,
  val cleared: Boolean? = null,
  val error: String? = null,
)

/** `GET /api/conversations/:id` envelope (turns for full transcript; compact optional). */
@Serializable
data class ConversationDetailResponse(
  @SerialName("conversation_id") val conversationId: String? = null,
  val compact: ConversationCompactState? = null,
  val turns: List<ConversationTurnRow>? = null,
  val error: String? = null,
)

// --- Image / video (unit-priced doors) ---

@Serializable
data class ImageGenerationRequest(
  val model: String,
  val prompt: String,
  /** Optional https or data: URL for i2i / edit models. */
  val image: String? = null,
)

@Serializable
data class ImageGenerationResponse(
  val created: Long? = null,
  val model: String? = null,
  val data: List<ImageGenerationData>? = null,
  val error: ControlPlaneErrorBody? = null,
) {
  @Serializable
  data class ImageGenerationData(
    @SerialName("b64_json") val b64Json: String? = null,
    val url: String? = null,
  )

  /** Raw base64 when the field is real base64 (not an https URL). */
  val firstBase64: String?
    get() {
      val raw = data?.firstOrNull()?.b64Json?.takeIf { it.isNotEmpty() } ?: return null
      if (raw.startsWith("http://") || raw.startsWith("https://")) return null
      if (raw.startsWith("data:image/")) {
        val idx = raw.indexOf("base64,")
        if (idx >= 0) return raw.substring(idx + "base64,".length)
      }
      return raw
    }

  /** Explicit `url` or legacy URL stuffed into `b64_json`. */
  val firstDisplayUrl: String?
    get() {
      data?.firstOrNull()?.url?.takeIf { it.isNotEmpty() }?.let { return it }
      val raw = data?.firstOrNull()?.b64Json
      if (raw != null && (raw.startsWith("http://") || raw.startsWith("https://"))) return raw
      return null
    }
}

@Serializable
data class VideoGenerationRequest(
  val model: String,
  val prompt: String? = null,
  /** Optional i2v source (data: or https:). */
  val image: String? = null,
  /** plane 0.4.29+: 202 + job id instead of holding the connection. */
  val async: Boolean? = null,
  /**
   * Clip length for CF. **Type is model-specific:** integer seconds for most models,
   * string like `"8s"` for Google Veo. Use [VideoClipDuration.wire] for a user choice,
   * or [VideoClipDuration.forModel] for the model default when omitting.
   */
  val duration: JsonElement? = null,
)

@Serializable
data class VideoGenerationResponse(
  val model: String? = null,
  val video: String? = null,
  val error: ControlPlaneErrorBody? = null,
  /** Present on 202 async accept (plane 0.4.29+). */
  val id: String? = null,
  val status: String? = null,
  val kind: String? = null,
) {
  val isAsyncAccept: Boolean
    get() = !id.isNullOrBlank() && video.isNullOrBlank()
}

// --- Async jobs (`GET /v1/jobs/:id`, plane 0.4.29+) ---

@Serializable
data class AsyncJobResponse(
  val id: String,
  val kind: String? = null,
  val model: String? = null,
  val status: String,
  @SerialName("created_at") val createdAt: String? = null,
  @SerialName("updated_at") val updatedAt: String? = null,
  val result: AsyncJobResult? = null,
  val error: ControlPlaneErrorBody? = null,
) {
  val isTerminal: Boolean get() = status == "succeeded" || status == "failed"
  val isSuccess: Boolean get() = status == "succeeded"
}

@Serializable
data class AsyncJobResult(
  val video: String? = null,
  val audio: String? = null,
  val model: String? = null,
  val rehosted: Boolean? = null,
  val format: String? = null,
  @SerialName("audio_base64") val audioBase64: String? = null,
)

// --- Speech TTS (`POST /v1/audio/speech`) ---

@Serializable
data class SpeechGenerationRequest(
  val model: String,
  val input: String,
  /** plane 0.4.32+: Workflow + poll. */
  val async: Boolean? = null,
)

/** Plane TTS envelope: base64 audio (typically mp3) or async job accept. */
@Serializable
data class SpeechGenerationResponse(
  val model: String? = null,
  @SerialName("audio_base64") val audioBase64: String? = null,
  val format: String? = null,
  val error: ControlPlaneErrorBody? = null,
  val id: String? = null,
  val status: String? = null,
  val kind: String? = null,
) {
  val isAsyncAccept: Boolean
    get() = !id.isNullOrBlank() && audioBase64.isNullOrBlank()

  /** Decoded audio bytes when [audioBase64] is present (strips optional data: prefix). */
  fun audioBytes(): ByteArray? {
    val raw = audioBase64?.takeIf { it.isNotEmpty() } ?: return null
    var s = raw
    val idx = s.indexOf("base64,")
    if (idx >= 0) s = s.substring(idx + "base64,".length)
    return try {
      java.util.Base64.getDecoder().decode(s)
    } catch (_: IllegalArgumentException) {
      null
    }
  }
}

// --- STT (`POST /v1/audio/transcriptions`) ---

/** `audio` is raw base64 or a data: URL. */
@Serializable
data class TranscriptionRequest(
  val model: String,
  val audio: String,
)

@Serializable
data class TranscriptionResponse(
  val model: String? = null,
  val text: String? = null,
  val error: ControlPlaneErrorBody? = null,
)

/** `POST /v1/stt/sessions` -- short-lived browser ticket (native prefers Bearer on WS). */
@Serializable
data class SttSessionResponse(
  val ticket: String? = null,
  @SerialName("expires_at") val expiresAt: String? = null,
  @SerialName("expires_in") val expiresIn: Int? = null,
  val protocol: String? = null,
  @SerialName("stream_path") val streamPath: String? = null,
  val error: ControlPlaneErrorBody? = null,
)

// --- Music (`POST /v1/music/generations`) ---

@Serializable
data class MusicGenerationRequest(
  val model: String,
  val prompt: String,
  val lyrics: String? = null,
  /** plane 0.4.29+: lock-safe poll. */
  val async: Boolean? = null,
)

/** Plane music envelope: `audio` is a URL or inline base64/data asset. */
@Serializable
data class MusicGenerationResponse(
  val model: String? = null,
  val audio: String? = null,
  val error: ControlPlaneErrorBody? = null,
  val id: String? = null,
  val status: String? = null,
  val kind: String? = null,
) {
  val isAsyncAccept: Boolean
    get() = !id.isNullOrBlank() && audio.isNullOrBlank()

  fun audioBytes(): ByteArray? {
    val raw = audio?.takeIf { it.isNotEmpty() } ?: return null
    if (raw.startsWith("http://") || raw.startsWith("https://")) return null
    var s = raw
    val idx = s.indexOf("base64,")
    if (idx >= 0) s = s.substring(idx + "base64,".length)
    return try {
      java.util.Base64.getDecoder().decode(s)
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  val audioUrl: String?
    get() =
      audio?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}

/** `POST /v1/store/redeem` response (App Store or Google Play). */
@Serializable
data class StoreRedeemResponse(
  val applied: Boolean? = null,
  @SerialName("transaction_id") val transactionId: String? = null,
  @SerialName("product_id") val productId: String? = null,
  @SerialName("credit_granted_micro_usd") val creditGrantedMicroUsd: Long? = null,
  @SerialName("credit_micro_usd") val creditMicroUsd: Long? = null,
  @SerialName("spent_micro_usd") val spentMicroUsd: Long? = null,
  val environment: String? = null,
  val verified: String? = null,
  val platform: String? = null,
  val error: ControlPlaneErrorBody? = null,
)

@Serializable
data class GooglePlayRedeemRequest(
  val platform: String = "google_play",
  @SerialName("purchase_token") val purchaseToken: String,
  @SerialName("product_id") val productId: String,
  @SerialName("package_name") val packageName: String = StoreProducts.PACKAGE_NAME,
)

/**
 * Map control-plane / transport errors into short UI copy (parity with iOS prismUserFacingError).
 */
fun prismUserFacingError(error: Throwable): String {
  val msg = error.message.orEmpty()
  val lower = msg.lowercase()
  return when {
    error is PrismError.Unauthenticated -> "Not authenticated. Re-enroll or import a pcp_ key."
    error is PrismError.ClientRevoked -> "Device key revoked. Re-enroll."
    lower.contains("quota_exhausted") || lower.contains("below this model's unit rate") ||
      lower.contains("402") ->
      "Not enough balance for this model. Top up credit or pick a cheaper model."
    lower.contains("7003") ->
      "Provider rejected the request (7003). Prefer Veo or Seedance Fast for video."
    lower.contains("zdr") || lower.contains("upload_url") || lower.contains("0.4.14") ->
      "Grok video needs plane 0.4.14+ (ZDR upload path). Prefer Veo / Seedance Fast until then."
    lower.contains("requires an image") ||
      (lower.contains("i2v") && lower.contains("image")) ->
      "This model needs a reference image. Add a photo or https/data URL, or pick Veo / Seedance."
    lower.contains("model_unpriced") ->
      "Model has no unit rate yet. Refresh models or pick another."
    error is PrismError.HttpStatus -> error.bodyMessage?.takeIf { it.isNotBlank() } ?: msg
    msg.isNotBlank() -> msg
    else -> error.toString()
  }
}
