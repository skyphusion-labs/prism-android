package org.skyphusion.prism.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.skyphusion.prism.SecretStore

/**
 * Android Keystore-backed secret store via EncryptedSharedPreferences.
 * Use for the control-plane device key (`pcp_…`); never log values.
 */
class EncryptedPrefsSecretStore(context: Context) : SecretStore {
  private val prefs =
    EncryptedSharedPreferences.create(
      PREFS_NAME,
      MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
      context.applicationContext,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

  override fun get(key: String): String? = prefs.getString(key, null)

  override fun set(key: String, value: String?) {
    prefs.edit().apply {
      if (value == null) remove(key) else putString(key, value)
      apply()
    }
  }

  companion object {
    private const val PREFS_NAME = "org.skyphusion.prism.secure"
  }
}
