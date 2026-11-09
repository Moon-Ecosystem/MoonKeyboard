package com.moonkeyboard.ime

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/** Palette from the design canvas. */
data class KeyboardColors(
  val background: Color,
  val key: Color,
  val special: Color,
  val pressed: Color,
  val text: Color,
  val muted: Color,
  val hint: Color,
  val accent: Color,
  val onAccent: Color,
  val shadow: Color,
  val popup: Color,
  val divider: Color,
  val isDark: Boolean = false,
  /**
   * In the dark themes a bright enter key stood out alone, so it gets a tonal fill instead: this much
   * accent over the background, with the accent as its icon. 0 keeps the solid accent (light theme).
   */
  val enterTone: Float = 0f,
) {
  val enter get() = if (enterTone > 0f) accent.copy(alpha = enterTone).compositeOver(background) else accent
  val onEnter get() = if (enterTone > 0f) accent else onAccent

  fun withAccent(a: Accent): KeyboardColors {
    val accent = Color(if (isDark) a.dark else a.light)
    return copy(accent = accent, onAccent = contentColorFor(accent))
  }

  /**
   * Black behind everything. The built-in dark keys go to near-blacks with a faint Moon-blue lean, so they
   * sit with the accent instead of looking stock grey, and text is softened a touch (pure white glows on
   * OLED). The wallpaper's keys keep their hue, darkened to the same depth: made for a dark grey
   * background, on black they read too bright.
   */
  fun pitchBlack(moonKeys: Boolean): KeyboardColors {
    val black = copy(background = Color.Black, shadow = Color.Black, enterTone = PITCH_ENTER_TONE)
    return if (!moonKeys) black.copy(
      key = key.dim(0.5f),
      special = special.dim(0.5f),
      pressed = pressed.dim(0.6f),
      popup = special.dim(0.5f),
      divider = divider.dim(0.5f),
    ) else black.copy(
      key = Color(0xFF16181F),
      special = Color(0xFF1C1F28),
      pressed = Color(0xFF2A2E3A),
      popup = Color(0xFF1C1F28),
      divider = Color(0xFF1A1D25),
      text = Color(0xFFE6E9F2),
      muted = Color(0xFF8F95A6),
      hint = Color(0xFF8F95A6),
    )
  }

  companion object {
    /** Black or white, whichever reads better on [background] (WCAG contrast). */
    fun contentColorFor(background: Color): Color {
      val l = background.luminance()
      return if ((l + 0.05f) / 0.05f >= 1.05f / (l + 0.05f)) Color.Black else Color.White
    }

    /** Material You: the system's palette from the wallpaper (Android 12+), in the same roles as ours. */
    @RequiresApi(Build.VERSION_CODES.S)
    fun dynamic(context: Context, dark: Boolean): KeyboardColors {
      fun c(id: Int) = Color(context.getColor(id))
      return if (dark) KeyboardColors(
        background = c(android.R.color.system_neutral1_900),
        key = c(android.R.color.system_neutral2_800),
        special = c(android.R.color.system_neutral2_700),
        pressed = c(android.R.color.system_neutral2_600),
        text = c(android.R.color.system_neutral1_50),
        muted = c(android.R.color.system_neutral2_300),
        hint = c(android.R.color.system_neutral2_300),
        accent = c(android.R.color.system_accent1_200),
        onAccent = c(android.R.color.system_accent1_900),
        shadow = c(android.R.color.system_neutral1_1000),
        popup = c(android.R.color.system_neutral2_700),
        divider = c(android.R.color.system_neutral2_700),
        isDark = true,
        enterTone = DARK_ENTER_TONE,
      ) else KeyboardColors(
        background = c(android.R.color.system_neutral2_100),
        key = c(android.R.color.system_neutral1_10),
        special = c(android.R.color.system_accent2_200),
        pressed = c(android.R.color.system_accent2_300),
        text = c(android.R.color.system_neutral1_900),
        muted = c(android.R.color.system_neutral2_700),
        hint = c(android.R.color.system_neutral2_600),
        accent = c(android.R.color.system_accent1_600),
        onAccent = c(android.R.color.system_accent1_0),
        shadow = c(android.R.color.system_neutral2_300),
        popup = c(android.R.color.system_neutral1_10),
        divider = c(android.R.color.system_neutral2_200),
      )
    }

    // Light: a brighter ground, lighter function keys and a softer key shadow; enter stays solid accent
    val Light = KeyboardColors(
      background = Color(0xFFEDEFF4),
      key = Color(0xFFFFFFFF),
      special = Color(0xFFD8DCE5),
      pressed = Color(0xFFC2C7D2),
      text = Color(0xFF14171E),
      muted = Color(0xFF555B6A),
      hint = Color(0xFF626878),
      accent = Color(0xFF3D4BC4),
      onAccent = Color(0xFFFFFFFF),
      shadow = Color(0xFFC7CCD7),
      popup = Color(0xFFFFFFFF),
      divider = Color(0xFFD3D7E0),
    )
    // Dark: letter and function keys closer together so the keys read as one surface, a quieter divider
    val Dark = KeyboardColors(
      background = Color(0xFF111319),
      key = Color(0xFF262A33),
      special = Color(0xFF323743),
      pressed = Color(0xFF434957),
      text = Color(0xFFE9EBF2),
      muted = Color(0xFFA3A9B8),
      hint = Color(0xFF9AA0AF),
      accent = Color(0xFFB4BDFF),
      onAccent = Color(0xFF12141C),
      shadow = Color(0xFF08090D),
      popup = Color(0xFF2F3440),
      divider = Color(0xFF2A2E38),
      isDark = true,
      enterTone = DARK_ENTER_TONE,
    )

    private const val DARK_ENTER_TONE = 0.3f
    private const val PITCH_ENTER_TONE = 0.24f
  }
}

/** The same hue, [f] times as bright. */
private fun Color.dim(f: Float) = Color(red * f, green * f, blue * f, alpha)
