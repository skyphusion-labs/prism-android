package org.skyphusion.prism

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class ControlPlaneClientTest {
  private val server = MockWebServer()

  @AfterTest
  fun tearDown() {
    server.shutdown()
  }

  private fun client(key: String? = "pcp_0123456789abcdef_abcdefghijklmnopqrstuvwxyz0123456"): ControlPlaneClient {
    server.start()
    val http =
      HttpJson(
        baseUrl = server.url("/").toString().trimEnd('/'),
        client = OkHttpClient(),
      )
    return ControlPlaneClient(http, clientKey = key)
  }

  @Test
  fun health() {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody("""{"ok":true,"service":"prism-control-plane"}"""),
    )
    val c = client(key = null)
    val h = c.health()
    assertEquals(true, h.ok)
    assertEquals("prism-control-plane", h.service)
    val req = server.takeRequest()
    assertEquals("GET", req.method)
    assertEquals("/health", req.path)
  }

  @Test
  fun enrollStoresKey() {
    server.enqueue(
      MockResponse()
        .setResponseCode(201)
        .setBody(
          """
          {
            "client_id": "cli_1",
            "key": "pcp_deadbeefdeadbeef_abcdefghijklmnopqrstuvwxyz0123456",
            "account": { "id": "acc_1", "plan": "dev" }
          }
          """.trimIndent(),
        ),
    )
    val c = client(key = null)
    val en = c.enroll(enrollmentToken = "enr_test", label = "Pixel")
    assertEquals("cli_1", en.clientId)
    assertEquals("pcp_deadbeefdeadbeef_abcdefghijklmnopqrstuvwxyz0123456", c.clientKey)
    val req = server.takeRequest()
    assertEquals("POST", req.method)
    assertEquals("/v1/clients", req.path)
    assertTrue(req.body.readUtf8().contains("android"))
  }

  @Test
  fun listModelsSendsBearer() {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody(
          """
          {"object":"list","data":[{"id":"@cf/meta/llama","display_name":"Llama","modality":"chat","spendable":true,"streaming":true}]}
          """.trimIndent(),
        ),
    )
    val c = client()
    val models = c.listModels()
    assertEquals(1, models.data.size)
    assertEquals("@cf/meta/llama", models.data[0].id)
    val req = server.takeRequest()
    assertEquals("Bearer pcp_0123456789abcdef_abcdefghijklmnopqrstuvwxyz0123456", req.getHeader("Authorization"))
    assertEquals("/v1/models", req.path)
  }

  @Test
  fun chatCompletionsParsesBodyAndMeters() {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader("prism-request-id", "req_1")
        .addHeader("prism-usage-micro-usd", "99")
        .addHeader("prism-metered", "true")
        .setBody(
          """
          {
            "id": "chatcmpl-x",
            "choices": [{"index":0,"message":{"role":"assistant","content":"pong"},"finish_reason":"stop"}],
            "usage": {"prompt_tokens": 10, "completion_tokens": 2}
          }
          """.trimIndent(),
        ),
    )
    val c = client()
    val result = c.chat(model = "@cf/meta/llama", user = "hi")
    assertEquals("pong", result)
    val req = server.takeRequest()
    assertEquals("POST", req.method)
    assertEquals("/v1/chat/completions", req.path)
    val body = req.body.readUtf8()
    assertTrue(body.contains("\"stream\":false"))
    assertTrue(body.contains("\"content\":\"hi\""))
  }

  @Test
  fun chatCompletionsReturnsMetersOnResult() {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader("prism-usage-micro-usd", "12")
        .addHeader("prism-metered", "true")
        .setBody(
          """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""",
        ),
    )
    val c = client()
    val r =
      c.chatCompletions(
        ControlPlaneChatRequest(
          model = "m",
          messages = listOf(ControlPlaneChatMessage("user", "x")),
        ),
      )
    assertEquals("ok", r.response.firstContent)
    assertEquals(12L, r.meters.usageMicroUsd)
    assertEquals(true, r.meters.metered)
  }

  @Test
  fun unauthenticatedWithoutKey() {
    val c = client(key = null)
    assertFailsWith<PrismError.Unauthenticated> { c.listModels() }
  }

  @Test
  fun clientRevoked() {
    server.enqueue(
      MockResponse()
        .setResponseCode(401)
        .setBody("""{"error":{"code":"client_revoked","message":"revoked"}}"""),
    )
    val c = client()
    assertFailsWith<PrismError.ClientRevoked> { c.listModels() }
  }

  @Test
  fun chatStreamCollectsDeltas() =
    runBlocking {
      val sse =
        """
        data: {"choices":[{"delta":{"content":"Hel"}}]}

        data: {"choices":[{"delta":{"content":"lo"}}]}

        data: [DONE]

        """.trimIndent() + "\n"
      server.enqueue(
        MockResponse()
          .setResponseCode(200)
          .addHeader("Content-Type", "text/event-stream")
          .setBody(sse),
      )
      val c = client()
      val events =
        c.chatCompletionsStream(
          ControlPlaneChatRequest(
            model = "m",
            messages = listOf(ControlPlaneChatMessage("user", "hi")),
            stream = true,
          ),
        ).toList()
      val text =
        events.filterIsInstance<ChatStreamEvent.Delta>().joinToString("") { it.text }
      assertEquals("Hello", text)
      val req = server.takeRequest()
      assertTrue(req.body.readUtf8().contains("\"stream\":true"))
    }

  @Test
  fun generateImageAndVideo() {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody("""{"created":1,"data":[{"url":"https://example.com/i.png"}]}"""),
    )
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody("""{"model":"xai/grok-imagine-video","video":"https://example.com/v.mp4"}"""),
    )
    val c = client()
    val img = c.generateImage(model = "xai/grok-imagine-image", prompt = "cube")
    assertEquals("https://example.com/i.png", img.firstDisplayUrl)
    val imgReq = server.takeRequest()
    assertEquals("/v1/images/generations", imgReq.path)
    assertTrue(imgReq.body.readUtf8().contains("cube"))

    // Explicit sync for unit test fixture that returns a ready URL.
    val vid =
      c.generateVideo(model = "xai/grok-imagine-video", prompt = "waves", async = false)
    assertEquals("https://example.com/v.mp4", vid.video)
    val vidReq = server.takeRequest()
    assertEquals("/v1/videos/generations", vidReq.path)
  }

  @Test
  fun meAndUsage() {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody(
          """
          {
            "client": {"id":"cli_1","platform":"android"},
            "account": {"id":"acc_1"},
            "usage": {"spendable_remaining_micro_usd": 500000, "period": "2026-08"}
          }
          """.trimIndent(),
        ),
    )
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody("""{"spendable_remaining_micro_usd": 400000, "period": "2026-08"}"""),
    )
    val c = client()
    val me = c.me()
    assertEquals("cli_1", me.client?.id)
    assertEquals(500000L, me.usage?.spendableRemainingMicroUsd)
    val usage = c.usage()
    assertEquals(400000L, usage.spendableRemainingMicroUsd)
  }
}
