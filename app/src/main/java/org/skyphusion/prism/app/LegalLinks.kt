package org.skyphusion.prism.app

/**
 * Public legal and source URLs for AGPL compliance + Play Console review.
 * Mirrors iOS LegalLinks.
 */
object LegalLinks {
  const val PRIVACY_POLICY = "https://skyphusion.org/privacy.html"
  const val WEBSITE = "https://skyphusion.org"
  const val PLAYGROUND = "https://play.skyphusion.org"
  const val STATUS = "https://status.skyphusion.org"
  const val SUPPORT_EMAIL = "mailto:support@skyphusion.org"

  /** Complete corresponding source for this AGPL client. */
  const val SOURCE_CODE = "https://github.com/skyphusion-labs/prism-android"
  /** License text on the public repo (canonical when network is available). */
  const val LICENSE_ONLINE =
    "https://github.com/skyphusion-labs/prism-android/blob/main/LICENSE"
  const val NOTICE_ONLINE =
    "https://github.com/skyphusion-labs/prism-android/blob/main/NOTICE"

  /** Related AGPL components (inference + commercial plane). */
  const val PRISM_WORKER_SOURCE = "https://github.com/skyphusion-labs/prism"
  const val CONTROL_PLANE_SOURCE =
    "https://github.com/skyphusion-labs/prism-control-plane"

  const val LICENSE_SHORT_NAME = "AGPL-3.0-only"
  const val COPYRIGHT_LINE =
    "Copyright SkyPhusion Labs. Prism is free software under the GNU Affero " +
      "General Public License v3.0 only."
}
