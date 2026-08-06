package org.skyphusion.prism

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
  @SerialName("period_micro_usd") val periodMicroUsd: Long? = null,
  @SerialName("period_requests") val periodRequests: Long? = null,
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
}

@Serializable
data class ControlPlaneChatMessage(
  val role: String,
  val content: String,
)

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
  data class Done(val fullText: String?, val usage: ChatUsage? = null) : ChatStreamEvent()
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
)

@Serializable
data class VideoGenerationResponse(
  val model: String? = null,
  val video: String? = null,
  val error: ControlPlaneErrorBody? = null,
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
