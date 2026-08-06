package org.skyphusion.prism

/**
 * Pure helpers for conversation compact (mirrors prism `src/conversation-context.ts`
 * and iOS `ConversationCompact`, v0.175.7).
 *
 * Server-side compact lives on the playground Worker
 * (`POST /api/conversations/:id/compact`). These helpers keep constants and
 * assembly rules aligned for clients and unit tests.
 */
object ConversationCompact {
  const val DEFAULT_KEEP_RECENT: Int = 2
  const val SUMMARY_MAX_CHARS: Int = 12_000
  /** Minimum completed chat pairs before compact is useful (keep_recent + 1). */
  const val MIN_TURNS_TO_COMPACT: Int = DEFAULT_KEEP_RECENT + 1

  const val SYSTEM_PROMPT: String =
    "You compress multi-turn chat history into a continuity brief for another " +
      "assistant that will continue the conversation. Preserve: decisions, facts, " +
      "names, constraints, open questions, and anything the user asked to remember. " +
      "Drop chit-chat and repeated boilerplate. Write in neutral third person or " +
      "tight bullet form. No preamble like 'Here is a summary'."

  /**
   * One completed user/assistant exchange with the linear transcript index of the
   * assistant turn (used as `through_turn_index` when building wire history).
   */
  data class Pair(
    val user: String,
    val assistant: String,
    val throughTurnIndex: Int,
  )

  data class Split(
    val summarize: List<Pair>,
    val keep: List<Pair>,
  )

  fun normalizeSummary(raw: String): String {
    var s = raw.trim()
    if (s.length > SUMMARY_MAX_CHARS) {
      s = s.take(SUMMARY_MAX_CHARS) + "\n[summary truncated]"
    }
    return s
  }

  fun formatPairsForSummary(pairs: List<Pair>): String =
    pairs.joinToString("\n\n---\n\n") { p ->
      val u = p.user.ifEmpty { "(empty)" }
      val a = p.assistant.ifEmpty { "(empty)" }
      "User:\n$u\n\nAssistant:\n$a"
    }

  fun splitPairs(pairs: List<Pair>, keepRecent: Int): Split {
    val k = keepRecent.coerceAtLeast(0)
    if (pairs.isEmpty()) return Split(emptyList(), emptyList())
    if (k == 0) return Split(pairs, emptyList())
    if (pairs.size <= k) return Split(emptyList(), pairs)
    val cut = pairs.size - k
    return Split(pairs.take(cut), pairs.drop(cut))
  }

  /** System block injected when compact is active (matches prism buildCompactSystemBlock). */
  fun buildSystemBlock(summary: String): String {
    val s = summary.trim()
    if (s.isEmpty()) return ""
    return buildString {
      appendLine("[Compacted earlier conversation]")
      appendLine(
        "The following is a summary of earlier turns in this thread. Treat it as " +
          "authoritative context. Recent turns (if any) follow as normal messages.",
      )
      appendLine()
      appendLine(s)
      appendLine()
      append("[End compacted context]")
    }
  }
}
