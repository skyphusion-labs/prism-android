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
}
