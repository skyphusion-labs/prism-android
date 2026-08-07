package org.skyphusion.prism

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

class VideoClipDurationTest {
  @Test
  fun veoDefaultString8s() {
    val d = VideoClipDuration.forModel("google/veo-3.1-fast") as JsonPrimitive
    assertTrue(d.isString)
    assertEquals("8s", d.content)
    assertEquals("8s", VideoClipDuration.labelForModel("google/veo-3.1"))
  }

  @Test
  fun veoUserPicks6() {
    val d = VideoClipDuration.wire("google/veo-3.1", 6) as JsonPrimitive
    assertTrue(d.isString)
    assertEquals("6s", d.content)
  }

  @Test
  fun hailuoDefaultInt6() {
    val d = VideoClipDuration.forModel("minimax/hailuo-2.3") as JsonPrimitive
    assertEquals(6, d.intOrNull)
  }

  @Test
  fun hailuoAllows10() {
    val d = VideoClipDuration.wire("minimax/hailuo-2.3-fast", 10) as JsonPrimitive
    assertEquals(10, d.intOrNull)
  }

  @Test
  fun grokRange1to15UserMax() {
    val lim = VideoClipDuration.limits("xai/grok-imagine-video")
    assertEquals(1, lim.min)
    assertEquals(15, lim.max)
    assertEquals(5, lim.defaultSeconds)
    assertEquals(15, lim.clamp(99))
    assertEquals(1, lim.clamp(0))
    val d = VideoClipDuration.wire("xai/grok-imagine-video", 12) as JsonPrimitive
    assertEquals(12, d.intOrNull)
  }

  @Test
  fun seedanceClampsTo4to12() {
    val lim = VideoClipDuration.limits("bytedance/seedance-2.0-mini")
    assertEquals(4, lim.clamp(2))
    assertEquals(12, lim.clamp(20))
    assertEquals(5, lim.defaultSeconds)
  }

  @Test
  fun catalogDefaults5ExceptVeoHailuo() {
    for (id in
      listOf(
        "xai/grok-imagine-video",
        "xai/grok-imagine-video-1.5-preview",
        "bytedance/seedance-2.0-fast",
        "bytedance/seedance-2.0",
        "bytedance/seedance-2.0-mini",
        "runwayml/gen-4.5",
        "alibaba/hh1-t2v",
        "alibaba/hh1-i2v",
        "alibaba/hh1.1-t2v",
        "alibaba/hh1.1-i2v",
        "alibaba/wan-2.7-i2v",
        "pixverse/v6",
        "pixverse/v5.6",
        "vidu/q3-pro",
        "vidu/q3-turbo",
      )) {
      val d = VideoClipDuration.forModel(id) as JsonPrimitive
      assertEquals(5, d.intOrNull, id)
    }
  }

  @Test
  fun veoSnapsToAllowed() {
    val lim = VideoClipDuration.limits("google/veo-3.1")
    assertEquals(4, lim.clamp(5))
    assertEquals(6, lim.clamp(7))
    assertEquals(8, lim.clamp(8))
    assertEquals(listOf(4, 6, 8), lim.pickerSeconds)
  }

  @Test
  fun requestEncodeUserDuration() {
    val body =
      VideoGenerationRequest(
        model = "xai/grok-imagine-video",
        prompt = "waves",
        duration = VideoClipDuration.wire("xai/grok-imagine-video", 15),
      )
    val s = prismJsonEncode.encodeToString(VideoGenerationRequest.serializer(), body)
    assertTrue(s.contains("\"duration\":15"), s)
  }

  @Test
  fun requestEncodeVeoDurationString() {
    val body =
      VideoGenerationRequest(
        model = "google/veo-3.1-fast",
        prompt = "ocean",
        async = true,
        duration = VideoClipDuration.wire("google/veo-3.1-fast", 8),
      )
    val s = prismJsonEncode.encodeToString(VideoGenerationRequest.serializer(), body)
    assertTrue(s.contains("\"duration\":\"8s\""), s)
    assertTrue(s.contains("\"async\":true"), s)
  }
}
