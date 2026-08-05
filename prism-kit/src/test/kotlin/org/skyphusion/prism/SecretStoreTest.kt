package org.skyphusion.prism

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecretStoreTest {
  @Test
  fun memoryRoundTrip() {
    val store = MemorySecretStore()
    assertNull(store.get(SecretStoreKeys.CONTROL_PLANE_DEVICE_KEY))
    store.set(SecretStoreKeys.CONTROL_PLANE_DEVICE_KEY, "pcp_test")
    assertEquals("pcp_test", store.get(SecretStoreKeys.CONTROL_PLANE_DEVICE_KEY))
    store.set(SecretStoreKeys.CONTROL_PLANE_DEVICE_KEY, null)
    assertNull(store.get(SecretStoreKeys.CONTROL_PLANE_DEVICE_KEY))
  }
}
