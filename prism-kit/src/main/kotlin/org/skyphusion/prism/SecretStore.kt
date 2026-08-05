package org.skyphusion.prism

/**
 * Small secret store for device keys.
 *
 * Android app uses EncryptedSharedPreferences; tests and JVM use [MemorySecretStore].
 * Mirrors iOS PrismKit SecretStore / KeychainStore.
 */
interface SecretStore {
  fun get(key: String): String?

  fun set(key: String, value: String?)
}

/** Well-known keys for Prism clients. */
object SecretStoreKeys {
  /** Control-plane device key (`pcp_…`). */
  const val CONTROL_PLANE_DEVICE_KEY = "org.skyphusion.prism.control-plane.device-key"
  const val CONTROL_PLANE_BASE_URL = "org.skyphusion.prism.control-plane.base-url"
  const val DEVICE_LABEL = "org.skyphusion.prism.device-label"
}

/** In-memory store (unit tests + non-Android runtimes). Thread-safe. */
class MemorySecretStore : SecretStore {
  private val lock = Any()
  private val bag = mutableMapOf<String, String>()

  override fun get(key: String): String? = synchronized(lock) { bag[key] }

  override fun set(key: String, value: String?) {
    synchronized(lock) {
      if (value == null) bag.remove(key) else bag[key] = value
    }
  }
}
