package org.skyphusion.prism.app

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Device credential / biometrics gate (iOS BiometricLock / Face ID).
 * Prefer BIOMETRIC_STRONG | DEVICE_CREDENTIAL so PIN works when fingerprint is off.
 */
object BiometricGate {
  fun isAvailable(context: Context): Boolean {
    val bm = BiometricManager.from(context)
    val code =
      bm.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
          BiometricManager.Authenticators.DEVICE_CREDENTIAL,
      )
    return code == BiometricManager.BIOMETRIC_SUCCESS ||
      code == BiometricManager.BIOMETRIC_STATUS_UNKNOWN
  }

  fun label(context: Context): String {
    val bm = BiometricManager.from(context)
    val strong =
      bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    return when (strong) {
      BiometricManager.BIOMETRIC_SUCCESS -> "biometrics"
      else -> "screen lock"
    }
  }

  fun authenticate(
    activity: FragmentActivity,
    reason: String = "Unlock Prism",
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
  ) {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt =
      BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
          override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onSuccess()
          }

          override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
              errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
              errorCode == BiometricPrompt.ERROR_CANCELED
            ) {
              // Stay locked; user can retry.
              return
            }
            onError(errString.toString())
          }

          override fun onAuthenticationFailed() {
            // Wrong finger; prompt stays open.
          }
        },
      )
    val info =
      BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock Prism")
        .setSubtitle(reason)
        .setAllowedAuthenticators(
          BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
        .build()
    prompt.authenticate(info)
  }
}
