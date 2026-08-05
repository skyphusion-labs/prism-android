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
}
