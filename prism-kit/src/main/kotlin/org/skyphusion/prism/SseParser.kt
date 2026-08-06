package org.skyphusion.prism

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SSE parsers for control-plane OpenAI-compatible streams and playground frames.
 *
 * Control plane (`stream: true`): Cloudflare/OpenAI frames
 * `data: {"choices":[{"delta":{"content":"..."}}]}` ending with `data: [DONE]`.
 *
 * Playground (future): `{ "type": "delta", "text": "..." }`.
 */
object SseParser {
  fun parseChatEvents(from: String): List<ChatStreamEvent> {
    val events = mutableListOf<ChatStreamEvent>()
    val dataLines = mutableListOf<String>()

    fun flush() {
      if (dataLines.isEmpty()) return
      val payload = dataLines.joinToString("\n")
      dataLines.clear()
      if (payload.isEmpty() || payload == "[DONE]") {
        if (payload == "[DONE]") events.add(ChatStreamEvent.Done(fullText = null))
        return
      }
      events.add(decodeFrame(payload))
    }

    for (line in from.split("\n")) {
      when {
        line.isEmpty() -> flush()
        line.startsWith(":") -> Unit // comment
        line.startsWith("data:") -> {
          var rest = line.removePrefix("data:")
          if (rest.startsWith(" ")) rest = rest.drop(1)
          dataLines.add(rest)
        }
      }
    }
    flush()
    return events
  }

  private fun decodeFrame(raw: String): ChatStreamEvent {
    val el =
      try {
        prismJson.parseToJsonElement(raw)
      } catch (_: Exception) {
        return ChatStreamEvent.Unknown(raw)
      }
    val obj = el as? JsonObject ?: return ChatStreamEvent.Unknown(raw)

    // Playground shape
    val type = obj["type"]?.jsonPrimitive?.contentOrNull
    when (type) {
      "delta" -> return ChatStreamEvent.Delta(obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
      "done" -> {
        val out =
          obj["output"]?.jsonPrimitive?.contentOrNull
            ?: obj["text"]?.jsonPrimitive?.contentOrNull
        val cid = obj["conversation_id"]?.jsonPrimitive?.contentOrNull
        return ChatStreamEvent.Done(fullText = out, conversationId = cid)
      }
      "error" -> {
        val msg =
          obj["message"]?.jsonPrimitive?.contentOrNull
            ?: obj["error"]?.jsonPrimitive?.contentOrNull
            ?: raw
        return ChatStreamEvent.Error(msg)
      }
    }

    // OpenAI / control-plane shape
    val choices = obj["choices"]?.jsonArray
    if (choices != null && choices.isNotEmpty()) {
      val choice = choices[0].jsonObject
      val delta = choice["delta"]?.jsonObject
      val deltaContent = delta?.get("content")?.jsonPrimitive?.contentOrNull
      if (deltaContent != null) return ChatStreamEvent.Delta(deltaContent)
      val message = choice["message"]?.jsonObject
      val messageContent = message?.get("content")?.jsonPrimitive?.contentOrNull
      if (messageContent != null) {
        return ChatStreamEvent.Done(fullText = messageContent)
      }
      val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
      if (finish != null) {
        return ChatStreamEvent.Done(fullText = null)
      }
    }

    // Error envelope on stream
    val err = obj["error"]
    if (err != null) {
      val msg =
        when {
          err is JsonObject ->
            err["message"]?.jsonPrimitive?.contentOrNull
              ?: err["code"]?.jsonPrimitive?.contentOrNull
              ?: raw
          else -> err.jsonPrimitive.contentOrNull ?: raw
        }
      return ChatStreamEvent.Error(msg)
    }

    return ChatStreamEvent.Unknown(raw)
  }
}
