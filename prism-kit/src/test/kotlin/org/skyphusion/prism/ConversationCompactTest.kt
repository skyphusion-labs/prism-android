package org.skyphusion.prism

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationCompactTest {
  @Test
  fun normalizeSummaryTruncates() {
    val long = "x".repeat(ConversationCompact.SUMMARY_MAX_CHARS + 50)
    val n = ConversationCompact.normalizeSummary(long)
    assertTrue(n.endsWith("[summary truncated]"))
    assertTrue(n.length <= ConversationCompact.SUMMARY_MAX_CHARS + 30)
  }

  @Test
  fun splitPairsKeepsRecent() {
    val pairs =
      (0 until 5).map { i ->
        ConversationCompact.Pair(user = "u$i", assistant = "a$i", throughTurnIndex = i)
      }
    val split = ConversationCompact.splitPairs(pairs, keepRecent = 2)
    assertEquals(3, split.summarize.size)
    assertEquals(2, split.keep.size)
    assertEquals("u3", split.keep[0].user)
    assertEquals("u4", split.keep[1].user)
  }

  @Test
  fun formatPairsForSummary() {
    val s =
      ConversationCompact.formatPairsForSummary(
        listOf(ConversationCompact.Pair("hi", "hello", 0)),
      )
    assertTrue(s.contains("User:\nhi"))
    assertTrue(s.contains("Assistant:\nhello"))
  }

  @Test
  fun buildSystemBlock() {
    val block = ConversationCompact.buildSystemBlock("decided on X")
    assertTrue(block.contains("[Compacted earlier conversation]"))
    assertTrue(block.contains("decided on X"))
    assertTrue(block.contains("[End compacted context]"))
    assertEquals("", ConversationCompact.buildSystemBlock("  "))
  }

  @Test
  fun minTurnsConstant() {
    assertEquals(3, ConversationCompact.MIN_TURNS_TO_COMPACT)
  }
}
