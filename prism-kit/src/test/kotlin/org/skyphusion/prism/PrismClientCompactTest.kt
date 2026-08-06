package org.skyphusion.prism

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class PrismClientCompactTest {
  private val server = MockWebServer()

  @AfterTest
  fun tearDown() {
    server.shutdown()
  }

  private fun client(): PrismClient {
    server.start()
    return PrismClient.create(
      baseUrl = server.url("/").toString().trimEnd('/'),
      client = OkHttpClient(),
    )
  }

  @Test
  fun compactConversationPostsBodyAndParses() {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody(
          """
          {
            "conversation_id": "conv_1",
            "compact": {
              "summary": "User asked about cats. Assistant recommended whiskers.",
              "through_turn_index": 2,
              "keep_recent": 2,
              "model": "@cf/meta/llama-3.2-3b-instruct",
              "updated_at": "2026-08-05T12:00:00.000Z"
            },
            "turns_summarized": 3,
            "turns_kept_raw": 2
          }
          """.trimIndent(),
        ),
    )
    val c = client()
    val res =
      c.compactConversation(
        id = "conv_1",
        keepRecent = 2,
        model = "@cf/meta/llama-3.2-3b-instruct",
      )
    assertEquals("conv_1", res.conversationId)
    assertEquals(3, res.turnsSummarized)
    assertEquals(2, res.turnsKeptRaw)
    val compact = assertNotNull(res.compact)
    assertEquals(2, compact.throughTurnIndex)
    assertEquals(2, compact.keepRecent)
    assertTrue(compact.summary.contains("cats"))
    assertTrue(compact.systemBlock.contains("[Compacted earlier conversation]"))

    val req = server.takeRequest()
    assertEquals("POST", req.method)
    assertEquals("/api/conversations/conv_1/compact", req.path)
    val body = req.body.readUtf8()
    assertTrue(body.contains("\"keep_recent\":2"))
    assertTrue(body.contains("llama-3.2-3b-instruct"))
  }

  @Test
  fun clearConversationCompactDelete() {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody(
          """
          {
            "conversation_id": "conv_1",
            "compact": null,
            "cleared": true
          }
          """.trimIndent(),
        ),
    )
    val c = client()
    val res = c.clearConversationCompact("conv_1")
    assertEquals("conv_1", res.conversationId)
    assertEquals(true, res.cleared)
    assertNull(res.compact)

    val req = server.takeRequest()
    assertEquals("DELETE", req.method)
    assertEquals("/api/conversations/conv_1/compact", req.path)
  }

  @Test
  fun compactMissingStateThrows() {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody("""{"conversation_id":"c1","compact":null}"""),
    )
    val c = client()
    assertFailsWith<PrismError.Server> {
      c.compactConversation("c1")
    }
  }

  @Test
  fun compactEncodesPathSegment() {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody(
          """
          {
            "conversation_id": "a b",
            "compact": {
              "summary": "s",
              "through_turn_index": 0,
              "keep_recent": 2,
              "model": "m"
            }
          }
          """.trimIndent(),
        ),
    )
    val c = client()
    c.compactConversation("a b")
    val req = server.takeRequest()
    assertEquals("/api/conversations/a%20b/compact", req.path)
  }
}
