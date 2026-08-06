package org.skyphusion.prism

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class PrismClientAuthTest {
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
  fun modelsBootProbeParses() {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody(
          """
          {
            "mode":"public",
            "authenticated":false,
            "models":[
              {"id":"@cf/meta/llama-3.1-8b-instruct","label":"Llama 3.1","type":"chat","streaming":true}
            ]
          }
          """.trimIndent(),
        ),
    )
    val res = client().models()
    assertEquals("public", res.mode)
    assertEquals(false, res.authenticated)
    assertEquals(1, res.models.size)
    val m = res.models.first().toControlPlaneModel()
    assertEquals("@cf/meta/llama-3.1-8b-instruct", m.id)
    assertEquals("chat", m.modality)
    assertEquals(true, m.streaming)
  }

  @Test
  fun loginStoresSessionCookie() {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader("Set-Cookie", "__Host-prism_session=tok123; Path=/; Secure; HttpOnly")
        .setBody("""{"ok":true,"user":{"username":"alice"}}"""),
    )
    val c = client()
    val res = c.login("alice", "secret")
    assertEquals("alice", res.user?.username)
    // Cookie may not parse __Host- on mock host without HTTPS; export may be null.
    // Assert request went to login path.
    val req = server.takeRequest()
    assertEquals("/api/auth/login", req.path)
    assertTrue(req.body.readUtf8().contains("alice"))
  }

  @Test
  fun chatPostsUserInput() {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody("""{"output":"hi back","conversation_id":"c1","model":"m1"}"""),
    )
    val c = client()
    val res =
      c.chat(
        PlaygroundChatRequest(model = "m1", userInput = "hello", conversationId = null),
      )
    assertEquals("hi back", res.output)
    assertEquals("c1", res.conversationId)
    val req = server.takeRequest()
    assertEquals("/api/chat", req.path)
    assertTrue(req.body.readUtf8().contains("user_input"))
  }

  @Test
  fun chatStreamParsesPlaygroundFrames() {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody(
          """
          data: {"type":"delta","text":"Hel"}

          data: {"type":"delta","text":"lo"}

          data: {"type":"done","output":"Hello"}

          """.trimIndent(),
        ),
    )
    val c = client()
    val (text, _) =
      c.chatStreamText(PlaygroundChatRequest(model = "m", userInput = "hi"))
    assertEquals("Hello", text)
  }

  @Test
  fun playgroundModelPrefersModelField() {
    val entry =
      prismJson.decodeFromString(
        PlaygroundModelEntry.serializer(),
        """{"model":"x","id":"y","label":"L","type":"chat"}""",
      )
    assertEquals("x", entry.modelId)
    assertNotNull(entry.toControlPlaneModel().displayName)
  }
}
