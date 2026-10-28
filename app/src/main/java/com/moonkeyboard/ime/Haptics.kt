package com.moonkeyboard.ime

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The key-press buzz. Strength 0 leaves it to the system's own keyboard tap; 1–10 is our own,
 * longer and (where the motor allows) stronger at each step.
 */
object Haptics {
  const val MAX_STRENGTH = 10

  fun tap(view: View) {
    if (!Prefs.haptics.value) return
    val strength = Prefs.hapticStrength.value
    if (strength == 0) {
      view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
      return
    }
    val vibrator = vibrator(view.context)
    val ms = strength * MS_PER_STEP
    if (Build.VERSION.SDK_INT >= 26) {
      val amplitude = if (vibrator.hasAmplitudeControl()) 255 * strength / MAX_STRENGTH else VibrationEffect.DEFAULT_AMPLITUDE
      vibrator.vibrate(VibrationEffect.createOneShot(ms, amplitude))
    } else {
      @Suppress("DEPRECATION")
      vibrator.vibrate(ms)
    }
  }

  private fun vibrator(context: Context): Vibrator =
    if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java).defaultVibrator
    else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

  private const val MS_PER_STEP = 3L
}
