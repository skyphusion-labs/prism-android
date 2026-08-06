package org.skyphusion.prism

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaCodecTest {
  @Test
  fun imageResponseBase64AndUrl() {
    val b64 =
      prismJson.decodeFromString(
        ImageGenerationResponse.serializer(),
        """{"created":1,"data":[{"b64_json":"abc123"}]}""",
      )
    assertEquals("abc123", b64.firstBase64)
    assertNull(b64.firstDisplayUrl)

    val url =
      prismJson.decodeFromString(
        ImageGenerationResponse.serializer(),
        """{"data":[{"url":"https://example.com/x.png"}]}""",
      )
    assertEquals("https://example.com/x.png", url.firstDisplayUrl)
    assertNull(url.firstBase64)

    val legacy =
      prismJson.decodeFromString(
        ImageGenerationResponse.serializer(),
        """{"data":[{"b64_json":"https://cdn.example/a.png"}]}""",
      )
    assertEquals("https://cdn.example/a.png", legacy.firstDisplayUrl)
    assertNull(legacy.firstBase64)
  }

  @Test
  fun modelCapabilitiesAndPriceSnippet() {
    val m =
      prismJson.decodeFromString(
        ControlPlaneModel.serializer(),
        """
        {
          "id":"xai/grok-imagine-image",
          "modality":"image",
          "spendable":true,
          "capabilities":["text-to-image","image-input"],
          "unit_price":{"micro_usd_per_unit":20000,"unit":"request"}
        }
        """.trimIndent(),
      )
    assertTrue(m.acceptsImageInput())
    assertEquals("$0.02/request", m.priceSnippet())
  }

  @Test
  fun userFacingErrorMapsQuotaAnd7003() {
    assertTrue(prismUserFacingError(PrismError.HttpStatus(402, "quota_exhausted: low")).contains("balance"))
    assertTrue(prismUserFacingError(PrismError.Server("7003: User Input Error")).contains("Veo"))
  }

  @Test
  fun speechResponseDecodesAudio() {
    val raw = java.util.Base64.getEncoder().encodeToString("hello-audio".toByteArray())
    val res =
      prismJson.decodeFromString(
        SpeechGenerationResponse.serializer(),
        """{"model":"@cf/deepgram/aura-2-en","audio_base64":"$raw","format":"mp3"}""",
      )
    assertEquals("@cf/deepgram/aura-2-en", res.model)
    assertEquals("mp3", res.format)
    assertEquals("hello-audio", res.audioBytes()?.decodeToString())
  }

  @Test
  fun transcriptionResponse() {
    val res =
      prismJson.decodeFromString(
        TranscriptionResponse.serializer(),
        """{"model":"whisper","text":"hello world"}""",
      )
    assertEquals("hello world", res.text)
  }

  @Test
  fun usageDualPoolLines() {
    val u =
      prismJson.decodeFromString(
        UsageSummary.serializer(),
        """
        {
          "spendable_remaining_micro_usd": 1500000,
          "remaining_micro_usd": 500000,
          "allowance_remaining_micro_usd": 1000000,
          "period": "2026-08",
          "overage": false
        }
        """.trimIndent(),
      )
    val lines = u.dualPoolLines()
    assertTrue(lines.any { it.startsWith("Spendable:") })
    assertTrue(lines.any { it.startsWith("Prepaid remaining:") })
    assertTrue(lines.any { it.startsWith("Monthly remaining:") })
    assertTrue(lines.any { it.startsWith("Period:") })
  }

  @Test
  fun musicResponseUrlAndBase64() {
    val url =
      prismJson.decodeFromString(
        MusicGenerationResponse.serializer(),
        """{"audio":"https://cdn.example/m.mp3"}""",
      )
    assertEquals("https://cdn.example/m.mp3", url.audioUrl)
    assertNull(url.audioBytes())

    val raw = java.util.Base64.getEncoder().encodeToString("notes".toByteArray())
    val b64 =
      prismJson.decodeFromString(
        MusicGenerationResponse.serializer(),
        """{"audio":"$raw"}""",
      )
    assertNull(b64.audioUrl)
    assertEquals("notes", b64.audioBytes()?.decodeToString())
  }
}
