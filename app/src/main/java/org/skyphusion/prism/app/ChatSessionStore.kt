package org.skyphusion.prism.app

import android.content.Context
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.skyphusion.prism.ConversationCompactState

/** Preview of a chat JSON import (count + sample title). */
data class ChatImportPreview(val count: Int, val titleSample: String?)

/** One local chat session (device-only; not on the plane). */
data class ChatSession(
  val id: String = UUID.randomUUID().toString(),
  var title: String = "New chat",
  var turns: List<ChatTurn> = emptyList(),
  var selectedModelId: String? = null,
  /** Playground server conversation id when known (cloud sync). */
  var conversationId: String? = null,
  var compact: ConversationCompactState? = null,
  var createdAtMs: Long = System.currentTimeMillis(),
  var updatedAtMs: Long = System.currentTimeMillis(),
) {
  companion object {
    fun makeTitle(turns: List<ChatTurn>): String {
      val first = turns.firstOrNull { it.role == ChatTurn.Role.User } ?: return "New chat"
      val t = first.text.trim()
      if (t.isEmpty() && !first.imageDataUrls.isNullOrEmpty()) return "Photo chat"
      if (t.isEmpty()) return "New chat"
      if (t.length <= 48) return t
      return t.take(45) + "..."
    }
  }
}

/**
 * Persist chat sessions as JSON under app files dir (iOS Application Support parity).
 * Cap: [SESSION_CAP].
 */
class ChatSessionStore(context: Context) {
  private val file = File(context.filesDir, "chat-sessions.json")

  data class Snapshot(
    val sessions: List<ChatSession>,
    val currentId: String?,
  )

  fun load(): Snapshot {
    if (!file.exists()) return Snapshot(emptyList(), null)
    return try {
      val root = JSONObject(file.readText())
      val arr = root.optJSONArray("sessions") ?: JSONArray()
      val list = mutableListOf<ChatSession>()
      for (i in 0 until arr.length()) {
        list.add(sessionFromJson(arr.getJSONObject(i)))
      }
      val sorted = list.sortedByDescending { it.updatedAtMs }
      val cur =
        if (root.has("currentId") && !root.isNull("currentId")) {
          root.optString("currentId").takeIf { it.isNotBlank() }
        } else {
          null
        }
      Snapshot(sorted, cur)
    } catch (_: Exception) {
      Snapshot(emptyList(), null)
    }
  }

  fun save(sessions: List<ChatSession>, currentId: String?) {
    val arr = JSONArray()
    for (s in sessions.take(SESSION_CAP)) {
      arr.put(sessionToJson(s))
    }
    val root =
      JSONObject()
        .put("sessions", arr)
        .put("currentId", currentId)
    file.writeText(root.toString())
  }

  private fun sessionToJson(s: ChatSession): JSONObject {
    val turns = JSONArray()
    for (t in s.turns) {
      val tj =
        JSONObject()
          .put("id", t.id)
          .put("role", t.role.name)
          .put("text", t.text)
          .put("modelId", t.modelId)
      t.imageDataUrls?.takeIf { it.isNotEmpty() }?.let { urls ->
        val arr = JSONArray()
        urls.forEach { arr.put(it) }
        tj.put("imageDataUrls", arr)
      }
      turns.put(tj)
    }
    val o =
      JSONObject()
        .put("id", s.id)
        .put("title", s.title)
        .put("turns", turns)
        .put("createdAtMs", s.createdAtMs)
        .put("updatedAtMs", s.updatedAtMs)
    s.selectedModelId?.let { o.put("selectedModelId", it) }
    s.conversationId?.let { o.put("conversationId", it) }
    s.compact?.let { c ->
      o.put(
        "compact",
        JSONObject()
          .put("summary", c.summary)
          .put("throughTurnIndex", c.throughTurnIndex)
          .put("keepRecent", c.keepRecent)
          .put("model", c.model)
          .put("updatedAt", c.updatedAt),
      )
    }
    return o
  }

  private fun sessionFromJson(o: JSONObject): ChatSession {
    val turnsArr = o.optJSONArray("turns") ?: JSONArray()
    val turns = mutableListOf<ChatTurn>()
    for (i in 0 until turnsArr.length()) {
      val t = turnsArr.getJSONObject(i)
      val role =
        try {
          ChatTurn.Role.valueOf(t.optString("role", "User"))
        } catch (_: Exception) {
          ChatTurn.Role.User
        }
      val imgsArr = t.optJSONArray("imageDataUrls")
      val imgs =
        if (imgsArr != null && imgsArr.length() > 0) {
          (0 until imgsArr.length()).mapNotNull { j ->
            imgsArr.optString(j).takeIf { it.isNotBlank() }
          }
        } else {
          null
        }
      turns.add(
        ChatTurn(
          id = t.optString("id", UUID.randomUUID().toString()),
          role = role,
          text = t.optString("text", ""),
          modelId =
            if (t.has("modelId") && !t.isNull("modelId")) {
              t.optString("modelId").takeIf { it.isNotBlank() }
            } else {
              null
            },
          imageDataUrls = imgs,
        ),
      )
    }
    val compact =
      o.optJSONObject("compact")?.let { c ->
        ConversationCompactState(
          summary = c.optString("summary", ""),
          throughTurnIndex = c.optInt("throughTurnIndex", 0),
          keepRecent = c.optInt("keepRecent", 2),
          model = c.optString("model", ""),
          updatedAt =
            if (c.has("updatedAt") && !c.isNull("updatedAt")) {
              c.optString("updatedAt").takeIf { it.isNotBlank() }
            } else {
              null
            },
        )
      }
    return ChatSession(
      id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
      title = o.optString("title").ifBlank { "New chat" },
      turns = turns,
      selectedModelId =
        if (o.has("selectedModelId") && !o.isNull("selectedModelId")) {
          o.optString("selectedModelId").takeIf { it.isNotBlank() }
        } else {
          null
        },
      conversationId =
        if (o.has("conversationId") && !o.isNull("conversationId")) {
          o.optString("conversationId").takeIf { it.isNotBlank() }
        } else {
          null
        },
      compact = compact,
      createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis()),
      updatedAtMs = o.optLong("updatedAtMs", System.currentTimeMillis()),
    )
  }

  /** Export sessions snapshot as pretty JSON bytes (share / backup). */
  fun exportJson(sessions: List<ChatSession>, currentId: String?): ByteArray {
    val arr = JSONArray()
    for (s in sessions) arr.put(sessionToJson(s))
    val root =
      JSONObject()
        .put("sessions", arr)
        .put("currentId", currentId)
        .put("exportedAt", System.currentTimeMillis())
    return root.toString(2).toByteArray(Charsets.UTF_8)
  }

  /** Merge imported sessions by id; returns merged list sorted by updatedAt. */
  fun mergeFromJson(existing: List<ChatSession>, data: ByteArray): List<ChatSession> {
    val incoming = decodeSessions(data)
    val byId = existing.associateBy { it.id }.toMutableMap()
    for (s in incoming) byId[s.id] = s
    return byId.values.sortedByDescending { it.updatedAtMs }.take(SESSION_CAP)
  }

  /** Replace local list with file contents. */
  fun replaceFromJson(data: ByteArray): List<ChatSession> =
    decodeSessions(data).sortedByDescending { it.updatedAtMs }.take(SESSION_CAP)

  /** Preview without applying. */
  fun previewFromJson(data: ByteArray): ChatImportPreview {
    val list = decodeSessions(data)
    return ChatImportPreview(
      count = list.size,
      titleSample = list.firstOrNull()?.title,
    )
  }

  private fun decodeSessions(data: ByteArray): List<ChatSession> {
    val root = JSONObject(String(data, Charsets.UTF_8))
    val arr = root.optJSONArray("sessions") ?: JSONArray()
    val list = mutableListOf<ChatSession>()
    for (i in 0 until arr.length()) {
      list.add(sessionFromJson(arr.getJSONObject(i)))
    }
    return list
  }

  companion object {
    const val SESSION_CAP = 50
  }
}
