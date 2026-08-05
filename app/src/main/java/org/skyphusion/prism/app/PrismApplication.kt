package org.skyphusion.prism.app

import android.app.Application
import org.skyphusion.prism.SecretStore

class PrismApplication : Application() {
  lateinit var secrets: SecretStore
    private set

  override fun onCreate() {
    super.onCreate()
    secrets = EncryptedPrefsSecretStore(this)
  }
}
