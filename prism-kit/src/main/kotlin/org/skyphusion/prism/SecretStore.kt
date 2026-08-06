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

  /** UI prefs (non-secret; same store as iOS Keychain prefs keys). */
  const val SELECTED_CHAT_MODEL = "org.skyphusion.prism.pref.chat-model"
  const val SELECTED_IMAGE_MODEL = "org.skyphusion.prism.pref.image-model"
  const val SELECTED_VIDEO_MODEL = "org.skyphusion.prism.pref.video-model"
  const val SELECTED_SPEECH_MODEL = "org.skyphusion.prism.pref.speech-model"
  const val SELECTED_STT_MODEL = "org.skyphusion.prism.pref.stt-model"
  const val SELECTED_MUSIC_MODEL = "org.skyphusion.prism.pref.music-model"
  const val USE_STREAM = "org.skyphusion.prism.pref.use-stream"
  const val HIDE_UNSPENDABLE = "org.skyphusion.prism.pref.hide-unspendable"

  /** Playground Worker base URL. */
  const val PLAYGROUND_BASE_URL = "org.skyphusion.prism.playground.base-url"
  /** Playground session cookie value (`__Host-prism_session`). */
  const val PLAYGROUND_SESSION_COOKIE = "org.skyphusion.prism.playground.session-cookie"
  const val PLAYGROUND_SESSION_USERNAME = "org.skyphusion.prism.playground.session-username"
  /** `controlPlane` (default) or `playground`. */
  const val BACKEND_MODE = "org.skyphusion.prism.backend-mode"
  /** When true, Settings shows backend switch + URL tinkering. */
  const val SHOW_DEVELOPER = "org.skyphusion.prism.pref.show-developer"
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
