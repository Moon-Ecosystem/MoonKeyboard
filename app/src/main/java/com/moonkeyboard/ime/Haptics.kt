package com.moonkeyboard.ime

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View

/**
 * The key-press buzz, 10–100 ms at the motor's usual strength (AOSP's keyboard used the same
 * range); shorter or quieter buzzes don't spin many motors up enough to be felt. Our own vibration
 * rather than the system's keyboard tap, which does nothing while touch vibration is off in the
 * phone's settings, even with vibration turned on here.
 */
object Haptics {
  const val MAX_STRENGTH = 10

  fun tap(view: View) {
    if (!Prefs.haptics.value) return
    val strength = Prefs.hapticStrength.value
    val vibrator = vibrator(view.context)
    val ms = strength * MS_PER_STEP
    val effect = if (Build.VERSION.SDK_INT >= 26) VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE) else null
    if (Build.VERSION.SDK_INT >= 33) {
      // Android 13 files a short buzz with no stated use under touch feedback, which is muted while
      // touch vibration is off system-wide; the user turned vibration on here, so it goes as media
      vibrator.vibrate(effect!!, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
    } else if (effect != null) {
      vibrator.vibrate(effect)
    } else {
      @Suppress("DEPRECATION")
      vibrator.vibrate(ms)
    }
  }

  private fun vibrator(context: Context): Vibrator =
    if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java).defaultVibrator
    else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

  private const val MS_PER_STEP = 10L
}
