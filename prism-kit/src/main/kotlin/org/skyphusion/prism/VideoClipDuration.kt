package org.skyphusion.prism

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Default video clip duration per model, aligned with control-plane
 * video-duration.ts / buildVideoParams. CF wants int seconds for most models,
 * and string "8s" for Google Veo (wrong type => CF 7003).
 *
 * Defaults: Veo 8s, Hailuo 6, everything else 5
 * (Grok, Seedance, Runway, Alibaba, PixVerse, Vidu).
 */
object VideoClipDuration {
  fun forModel(modelId: String): JsonElement {
    val id = modelId.trim()
    return when {
      id.startsWith("google/veo") -> JsonPrimitive("8s")
      id.startsWith("minimax/hailuo") -> JsonPrimitive(6)
      else -> JsonPrimitive(5)
    }
  }

  fun labelForModel(modelId: String): String {
    val p = forModel(modelId) as? JsonPrimitive ?: return "?"
    return if (p.isString) p.content else "${p.content}s"
  }

  fun rangeHint(modelId: String): String {
    val id = modelId.trim()
    return when {
      id.startsWith("google/veo") -> "4s / 6s / 8s (we send 8s)"
      id.startsWith("minimax/hailuo") -> "6 or 10 (we send 6)"
      id.startsWith("bytedance/seedance") -> "4-12 (we send 5)"
      id.startsWith("xai/grok-imagine-video") -> "1-15 (we send 5)"
      id.startsWith("runwayml/") -> "2-10 (we send 5)"
      id.startsWith("alibaba/hh") -> "3-15 (we send 5)"
      id.startsWith("alibaba/") -> "2-15 (we send 5)"
      id.startsWith("pixverse/v6") -> "1-15 (we send 5)"
      id.startsWith("pixverse/") -> "5 or 8 (we send 5)"
      id.startsWith("vidu/") -> "1-16 (we send 5)"
      else -> "default 5 (or 8s for Veo)"
    }
  }
}
