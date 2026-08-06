package org.skyphusion.prism

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelsCodecTest {
  @Test
  fun controlPlaneModelListDecode() {
    val json =
      """
      {
        "object": "list",
        "data": [
          {
            "id": "@cf/meta/llama-3.2-3b-instruct",
            "display_name": "Llama 3.2 3B",
            "modality": "chat",
            "billing": "workers-ai",
            "tier": "standard",
            "streaming": true,
            "max_output_tokens": 4096,
            "spendable": true,
            "price": { "input_micro_usd_per_mtok": 1 },
            "extra_future_field": true
          },
          {
            "id": "xai/grok-imagine-image",
            "display_name": "Grok Image",
            "modality": "image",
            "spendable": false
          }
        ]
      }
      """.trimIndent()
    val list = prismJson.decodeFromString(ControlPlaneModelList.serializer(), json)
    assertEquals(2, list.data.size)
    assertEquals("@cf/meta/llama-3.2-3b-instruct", list.data[0].id)
    assertEquals("Llama 3.2 3B", list.data[0].displayName)
    assertEquals(true, list.data[0].streaming)
    assertEquals(true, list.data[0].spendable)
    assertEquals(false, list.data[1].spendable)
  }

  @Test
  fun chatRequestEncode() {
    val body =
      ControlPlaneChatRequest(
        model = "m",
        messages = listOf(ControlPlaneChatMessage("user", "hi")),
        stream = false,
      )
    val s = prismJsonEncode.encodeToString(ControlPlaneChatRequest.serializer(), body)
    assertTrue(s.contains("\"model\":\"m\""))
    assertTrue(s.contains("\"content\":\"hi\""))
    assertTrue(s.contains("\"stream\":false"))
  }

  @Test
  fun multipartyVisionMessageEncode() {
    val msg =
      ControlPlaneChatMessage(
        role = "user",
        content = "what is this?",
        imageDataUrls = listOf("data:image/jpeg;base64,abc"),
      )
    val s = prismJsonEncode.encodeToString(ControlPlaneChatMessage.serializer(), msg)
    assertTrue(s.contains("image_url"), s)
    assertTrue(s.contains("data:image/jpeg;base64,abc"), s)
    assertTrue(s.contains("\"type\":\"text\""), s)
    assertTrue(s.contains("what is this?"), s)
    assertFalse(s.contains("imageDataUrls"), s)
  }

  @Test
  fun chatAttachmentFromDataUrl() {
    val att = ChatAttachment.image("data:image/png;base64,AAA")
    assertEquals("image", att.type)
    assertEquals("image/png", att.mime)
    assertEquals("AAA", att.data)
  }

  @Test
  fun chatResponseFirstContent() {
    val json =
      """
      {
        "id": "chatcmpl-1",
        "choices": [
          { "index": 0, "message": { "role": "assistant", "content": "pong" }, "finish_reason": "stop" }
        ],
        "usage": { "prompt_tokens": 3, "completion_tokens": 1 }
      }
      """.trimIndent()
    val res = prismJson.decodeFromString(ControlPlaneChatResponse.serializer(), json)
    assertEquals("pong", res.firstContent)
    assertEquals(3, res.usage?.promptTokens)
  }

  @Test
  fun usageBalanceDescription() {
    val u = UsageSummary(spendableRemainingMicroUsd = 1_500_000, period = "2026-08")
    assertTrue(u.balanceDescription().contains("1.5000"))
    assertTrue(u.balanceDescription().contains("2026-08"))
    assertEquals("usage unknown", UsageSummary().balanceDescription())
  }

  @Test
  fun usagePeriodDetailLines() {
    val u =
      UsageSummary(
        periodRequests = 12,
        periodUnmeteredRequests = 2,
        periodMicroUsd = 50_000,
        periodReconciledMicroUsd = 48_000,
        periodStart = "2026-08-01",
        periodEnd = "2026-09-01",
      )
    val lines = u.periodDetailLines()
    assertTrue(lines.any { it.contains("Requests this period: 12") })
    assertTrue(lines.any { it.contains("Unmetered") })
    assertTrue(lines.any { it.contains("Window:") })
  }

  @Test
  fun modelCapabilityTags() {
    val m =
      ControlPlaneModel(
        id = "vision-1",
        streaming = true,
        spendable = true,
        capabilities = listOf("image-input"),
        price =
          ControlPlaneTokenPrice(
            inputMicroUsdPerMTok = 1_000_000,
            outputMicroUsdPerMTok = 2_000_000,
          ),
      )
    assertTrue(m.supportsVision())
    val tags = m.capabilityTags()
    assertTrue(tags.contains("vision"))
    assertTrue(tags.contains("stream"))
    assertTrue(tags.contains("token"))
  }

  @Test
  fun meterHeadersParse() {
    val meters =
      PrismMeterHeaders.from(
        mapOf(
          "prism-request-id" to listOf("req_abc"),
          "prism-api-version" to listOf("1"),
          "prism-usage-micro-usd" to listOf("42"),
          "prism-metered" to listOf("true"),
        ),
      )
    assertEquals("req_abc", meters.requestId)
    assertEquals(42L, meters.usageMicroUsd)
    assertEquals(true, meters.metered)
    assertNull(meters.stream)
  }

  @Test
  fun conversationCompactStateDecode() {
    val json =
      """
      {
        "summary": "Prior context about widgets.",
        "through_turn_index": 4,
        "keep_recent": 2,
        "model": "@cf/meta/llama-3.2-3b-instruct",
        "updated_at": "2026-08-05T00:00:00.000Z",
        "future_field": true
      }
      """.trimIndent()
    val state = prismJson.decodeFromString(ConversationCompactState.serializer(), json)
    assertEquals(4, state.throughTurnIndex)
    assertEquals(2, state.keepRecent)
    assertTrue(state.systemBlock.contains("widgets"))
  }

  @Test
  fun conversationCompactRequestEncode() {
    val body = ConversationCompactRequest(keepRecent = 2, model = "m1")
    val s = prismJsonEncode.encodeToString(ConversationCompactRequest.serializer(), body)
    assertTrue(s.contains("\"keep_recent\":2"))
    assertTrue(s.contains("\"model\":\"m1\""))
  }

  @Test
  fun conversationCompactResponseDecode() {
    val json =
      """
      {
        "conversation_id": "c1",
        "compact": {
          "summary": "s",
          "through_turn_index": 1,
          "keep_recent": 2,
          "model": "m"
        },
        "turns_summarized": 3,
        "turns_kept_raw": 2
      }
      """.trimIndent()
    val res = prismJson.decodeFromString(ConversationCompactResponse.serializer(), json)
    assertEquals("c1", res.conversationId)
    assertEquals(3, res.turnsSummarized)
    assertEquals(2, res.turnsKeptRaw)
    assertEquals("s", res.compact?.summary)
  }
}
