package org.skyphusion.prism

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SseParserTest {
  @Test
  fun openAiDeltasAndDone() {
    val raw =
      """
      data: {"choices":[{"delta":{"content":"Hel"}}]}

      data: {"choices":[{"delta":{"content":"lo"}}]}

      data: [DONE]

      """.trimIndent() + "\n"
    val events = SseParser.parseChatEvents(raw)
    assertTrue(events.size >= 2)
    assertIs<ChatStreamEvent.Delta>(events[0])
    assertEquals("Hel", (events[0] as ChatStreamEvent.Delta).text)
    assertIs<ChatStreamEvent.Delta>(events[1])
    assertEquals("lo", (events[1] as ChatStreamEvent.Delta).text)
  }

  @Test
  fun playgroundDeltasAndDone() {
    val raw =
      """
      data: {"type":"delta","text":"A"}

      data: {"type":"done","output":"A","conversation_id":"c1"}

      """.trimIndent() + "\n"
    val events = SseParser.parseChatEvents(raw)
    assertEquals(2, events.size)
    assertIs<ChatStreamEvent.Delta>(events[0])
    assertEquals("A", (events[0] as ChatStreamEvent.Delta).text)
    assertIs<ChatStreamEvent.Done>(events[1])
    assertEquals("A", (events[1] as ChatStreamEvent.Done).fullText)
  }

  @Test
  fun errorFrame() {
    val raw = "data: {\"type\":\"error\",\"message\":\"boom\"}\n\n"
    val events = SseParser.parseChatEvents(raw)
    assertEquals(1, events.size)
    assertIs<ChatStreamEvent.Error>(events[0])
    assertEquals("boom", (events[0] as ChatStreamEvent.Error).message)
  }
}
