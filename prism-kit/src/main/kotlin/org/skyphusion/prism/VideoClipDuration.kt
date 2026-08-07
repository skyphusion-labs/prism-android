package org.skyphusion.prism

import kotlin.math.abs
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Per-model video duration limits from Cloudflare AI model docs (2026-08-06).
 * Mirror of control-plane `video-duration.ts` and iOS `VideoDurationCatalog`.
 *
 * Wire type matters: most models want integer seconds; Google Veo wants `"4s"`/`"6s"`/`"8s"`.
 * Defaults when the user omits a choice: Veo 8, Hailuo 6, everything else 5.
 */
data class VideoDurationLimits(
  val min: Int,
  val max: Int,
  val defaultSeconds: Int,
  /** When set, only these second values are legal (Veo, Hailuo, PixVerse v5.6). */
  val allowed: List<Int>? = null,
  val wire: Wire = Wire.INT,
) {
  enum class Wire {
    INT,
    VEO_STRING,
  }

  /** Discrete options for a UI picker (allowed list, or every second from min…max). */
  val pickerSeconds: List<Int>
    get() = allowed?.sorted() ?: (min..max).toList()

  fun clamp(requested: Int?): Int {
    val base = requested ?: defaultSeconds
    if (!allowed.isNullOrEmpty()) {
      return allowed.minByOrNull { abs(it - base) } ?: defaultSeconds
    }
    return base.coerceIn(min, max)
  }

  /** Upstream JSON value for `duration` on POST /v1/videos/generations. */
  fun wireValue(seconds: Int): JsonElement {
    val s = clamp(seconds)
    return when (wire) {
      Wire.VEO_STRING -> JsonPrimitive("${s}s")
      Wire.INT -> JsonPrimitive(s)
    }
  }

  fun label(seconds: Int): String = "${clamp(seconds)}s"
}

object VideoClipDuration {
  fun limits(modelId: String): VideoDurationLimits {
    val id = modelId.trim()
    // xAI Grok: integer 1–15
    if (id.startsWith("xai/grok-imagine-video")) {
      return VideoDurationLimits(min = 1, max = 15, defaultSeconds = 5)
    }
    // ByteDance Seedance: integer 4–12, default 5
    if (id.startsWith("bytedance/seedance")) {
      return VideoDurationLimits(min = 4, max = 12, defaultSeconds = 5)
    }
    // Google Veo: string enum 4s | 6s | 8s
    if (id.startsWith("google/veo")) {
      return VideoDurationLimits(
        min = 4,
        max = 8,
        defaultSeconds = 8,
        allowed = listOf(4, 6, 8),
        wire = VideoDurationLimits.Wire.VEO_STRING,
      )
    }
    // MiniMax Hailuo: CF oneOf; docs/examples use 6 (and historically 6|10)
    if (id.startsWith("minimax/hailuo")) {
      return VideoDurationLimits(
        min = 6,
        max = 10,
        defaultSeconds = 6,
        allowed = listOf(6, 10),
      )
    }
    // Runway Gen-4.5: integer 2–10, default 5
    if (id.startsWith("runwayml/")) {
      return VideoDurationLimits(min = 2, max = 10, defaultSeconds = 5)
    }
    // Alibaba HappyHorse: 3–15
    if (
      id == "alibaba/hh1-t2v" ||
        id == "alibaba/hh1-i2v" ||
        id == "alibaba/hh1.1-t2v" ||
        id == "alibaba/hh1.1-i2v"
    ) {
      return VideoDurationLimits(min = 3, max = 15, defaultSeconds = 5)
    }
    // Alibaba Wan 2.7 i2v: 2–15
    if (id == "alibaba/wan-2.7-i2v" || id.startsWith("alibaba/wan")) {
      return VideoDurationLimits(min = 2, max = 15, defaultSeconds = 5)
    }
    // PixVerse v6: 1–15; v5.6 is discrete 5|8
    if (id == "pixverse/v6") {
      return VideoDurationLimits(min = 1, max = 15, defaultSeconds = 5)
    }
    if (id.startsWith("pixverse/")) {
      return VideoDurationLimits(
        min = 5,
        max = 8,
        defaultSeconds = 5,
        allowed = listOf(5, 8),
      )
    }
    // Vidu Q3: 1–16
    if (id.startsWith("vidu/")) {
      return VideoDurationLimits(min = 1, max = 16, defaultSeconds = 5)
    }
    return VideoDurationLimits(min = 1, max = 15, defaultSeconds = 5)
  }

  /** Default wire value when the client omits duration (model default, correct type). */
  fun forModel(modelId: String): JsonElement {
    val lim = limits(modelId)
    return lim.wireValue(lim.defaultSeconds)
  }

  /** Wire value for a user-chosen length (clamped to the model). */
  fun wire(modelId: String, seconds: Int): JsonElement = limits(modelId).wireValue(seconds)

  fun labelForModel(modelId: String): String {
    val lim = limits(modelId)
    return lim.label(lim.defaultSeconds)
  }

  fun labelFor(modelId: String, seconds: Int): String = limits(modelId).label(seconds)

  fun rangeHint(modelId: String): String {
    val lim = limits(modelId)
    return if (!lim.allowed.isNullOrEmpty()) {
      lim.allowed.joinToString(" / ") { "${it}s" } + " (default ${lim.defaultSeconds}s)"
    } else {
      "${lim.min}–${lim.max}s (default ${lim.defaultSeconds}s)"
    }
  }
}
