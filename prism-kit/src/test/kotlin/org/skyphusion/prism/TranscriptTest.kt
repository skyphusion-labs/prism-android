package org.skyphusion.prism

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Minimal stand-in for `ChatTurn` (app module); only the id/text pair matters here. */
private data class Turn(val id: String, val text: String)

private fun chatA() = mutableListOf(Turn("a-user", "question"), Turn("a-assistant", ""))

private fun chatB() = listOf(Turn("b-user", "other question"), Turn("b-assistant", "B reply"))

/**
 * prism-android#37.
 *
 * The first three tests EXECUTE the pre-fix behaviour rather than describing it, so the
 * assertions after them are not vacuous: they show the captured-index write corrupting a
 * different conversation, throwing against a cleared one, and the inverted absent/empty guard
 * that turned the error path into a second crash. If those three ever stop failing in the way
 * they assert, the defect being fixed here was not the defect that existed.
 */
class TranscriptTest {
  // ---- Controls: what a captured list position does. ----

  @Test
  fun capturedIndexWritesIntoTheReplacementConversation() {
    val turns = chatA()
    val capturedIndex = turns.lastIndex // held across the streaming request
    turns.clear()
    turns.addAll(chatB()) // user opened chat B mid-stream
    val cur = turns[capturedIndex]
    turns[capturedIndex] = cur.copy(text = cur.text + " <delta from chat A>")
    // No exception, no error, and chat B's reply now carries chat A's answer.
    assertEquals("B reply <delta from chat A>", turns[1].text)
  }

  @Test
  fun capturedIndexThrowsAgainstAClearedTranscript() {
    val turns = chatA()
    val capturedIndex = turns.lastIndex
    turns.clear() // user tapped Clear chat mid-stream
    assertFailsWith<IndexOutOfBoundsException> { turns[capturedIndex] }
    assertFailsWith<IndexOutOfBoundsException> { turns.removeAt(capturedIndex) }
  }

  @Test
  fun theOldGuardCannotTellAbsentFromEmpty() {
    val turns = chatA()
    val capturedIndex = turns.lastIndex
    turns.clear()
    // `turns.getOrNull(i)?.text.isNullOrEmpty()` is TRUE for a turn that no longer exists,
    // so the error path read "gone" as "empty, delete it" and called removeAt on it.
    assertTrue(turns.getOrNull(capturedIndex)?.text.isNullOrEmpty())
    // Identity keeps the two states apart: absent is null, empty is a turn with empty text.
    assertNull(Transcript.findById(turns, "a-assistant", Turn::id))
    val live = chatA()
    assertEquals("", Transcript.findById(live, "a-assistant", Turn::id)?.text)
  }

  // ---- Positive control: the primitive really does mutate. ----

  @Test
  fun updateByIdWritesTheTurnItNames() {
    val turns = chatA()
    val hit = Transcript.updateById(turns, "a-assistant", Turn::id) { it.copy(text = it.text + "hi") }
    assertTrue(hit)
    assertEquals("hi", turns[1].text)
    assertEquals("question", turns[0].text) // and nothing else
  }

  // ---- The fix. ----

  @Test
  fun updateByIdCannotReachTheReplacementConversation() {
    val turns = chatA()
    turns.clear()
    turns.addAll(chatB())
    val hit = Transcript.updateById(turns, "a-assistant", Turn::id) { it.copy(text = it.text + "!") }
    assertFalse(hit)
    assertEquals(listOf("other question", "B reply"), turns.map { it.text })
  }

  @Test
  fun updateByIdIsANoOpOnAClearedTranscript() {
    val turns = chatA()
    turns.clear()
    assertFalse(Transcript.updateById(turns, "a-assistant", Turn::id) { it.copy(text = "x") })
    assertTrue(turns.isEmpty())
  }

  @Test
  fun updateByIdFollowsItsTurnAcrossAPositionShift() {
    val turns = chatA()
    turns.add(0, Turn("system", "preamble")) // compaction inserted ahead of it
    assertTrue(Transcript.updateById(turns, "a-assistant", Turn::id) { it.copy(text = "reply") })
    assertEquals("reply", turns[2].text)
    assertEquals(3, turns.size)
  }

  @Test
  fun removeByIdOnAnAbsentTurnDoesNotThrow() {
    val turns = chatA()
    turns.clear()
    assertFalse(Transcript.removeById(turns, "a-assistant", Turn::id))
    assertTrue(turns.isEmpty())
  }

  @Test
  fun removeByIdRemovesOnlyItsOwnTurn() {
    val turns = chatA()
    assertTrue(Transcript.removeById(turns, "a-assistant", Turn::id))
    assertEquals(listOf("a-user"), turns.map { it.id })
    assertFalse(Transcript.removeById(turns, "a-assistant", Turn::id)) // idempotent
  }

  @Test
  fun indexOfIdReportsMinusOneRatherThanAPlausiblePosition() {
    assertEquals(-1, Transcript.indexOfId(chatB(), "a-assistant", Turn::id))
    assertEquals(1, Transcript.indexOfId(chatA(), "a-assistant", Turn::id))
  }
}
