package org.skyphusion.prism

import kotlin.test.Test
import kotlin.test.assertEquals

class PrismKitTest {
  @Test
  fun health() {
    assertEquals("ok:PrismKit", PrismKit.health())
  }

  @Test
  fun version() {
    assertEquals("0.7.0", PrismKit.VERSION)
  }
}
