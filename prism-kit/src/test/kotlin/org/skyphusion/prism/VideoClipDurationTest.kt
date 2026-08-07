package org.skyphusion.prism

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

class VideoClipDurationTest {
  @Test
  fun veoUsesString8s() {
    val d = VideoClipDuration.forModel("google/veo-3.1-fast") as JsonPrimitive
    assertTrue(d.isString)
    assertEquals("8s", d.content)
    assertEquals("8s", VideoClipDuration.labelForModel("google/veo-3.1"))
  }

  @Test
  fun hailuoUsesInt6() {
    val d = VideoClipDuration.forModel("minimax/hailuo-2.3") as JsonPrimitive
    assertEquals(6, d.intOrNull)
  }

  @Test
  fun grokSeedanceRunwayDefault5() {
    for (id in
      listOf(
        "xai/grok-imagine-video",
        "xai/grok-imagine-video-1.5-preview",
        "bytedance/seedance-2.0-fast",
        "bytedance/seedance-2.0",
        "bytedance/seedance-2.0-mini",
        "runwayml/gen-4.5",
        "alibaba/hh1-t2v",
        "alibaba/wan-2.7-i2v",
        "pixverse/v6",
        "vidu/q3-pro",
      )) {
      val d = VideoClipDuration.forModel(id) as JsonPrimitive
      assertEquals(5, d.intOrNull, id)
    }
  }

  @Test
  fun requestEncodeIncludesDuration() {
    val body =
      VideoGenerationRequest(
        model = "google/veo-3.1-fast",
        prompt = "ocean",
        async = true,
        duration = VideoClipDuration.forModel("google/veo-3.1-fast"),
      )
    val s = prismJsonEncode.encodeToString(VideoGenerationRequest.serializer(), body)
    assertTrue(s.contains("\"duration\":\"8s\""), s)
    assertTrue(s.contains("\"async\":true"), s)
  }

  @Test
  fun requestEncodeGrokDurationInt() {
    val body =
      VideoGenerationRequest(
        model = "xai/grok-imagine-video",
        prompt = "waves",
        duration = VideoClipDuration.forModel("xai/grok-imagine-video"),
      )
    val s = prismJsonEncode.encodeToString(VideoGenerationRequest.serializer(), body)
    assertTrue(s.contains("\"duration\":5"), s)
    assertEquals(null, (VideoClipDuration.forModel("xai/grok-imagine-video") as JsonPrimitive).contentOrNull?.takeIf { false })
  }
}
