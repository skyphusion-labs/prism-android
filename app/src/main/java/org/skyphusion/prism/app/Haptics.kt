package org.skyphusion.prism.app

import android.view.HapticFeedbackConstants
import android.view.View

/** Lightweight haptic feedback (iOS Haptics parity). */
object Haptics {
  fun success(view: View?) {
    view ?: return
    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
  }

  fun error(view: View?) {
    view ?: return
    view.performHapticFeedback(HapticFeedbackConstants.REJECT)
  }

  fun light(view: View?) {
    view ?: return
    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
  }

  fun warning(view: View?) {
    view ?: return
    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
  }
}
