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
  /** Pitch black: a bright enter key stood out alone on the black, so it gets a dim accent fill and an accent icon. */
  val subtleEnter: Boolean = false,
) {
  val enter get() = if (subtleEnter) accent.copy(alpha = 0.22f).compositeOver(background) else accent
  val onEnter get() = if (subtleEnter) accent else onAccent

  fun withAccent(a: Accent): KeyboardColors {
    val accent = Color(if (isDark) a.dark else a.light)
    return copy(accent = accent, onAccent = contentColorFor(accent))
  }

  /** Black behind everything; the built-in dark keys go neutral grey too, the wallpaper's keep their tint. */
  fun pitchBlack(neutralKeys: Boolean): KeyboardColors {
    val black = copy(background = Color.Black, shadow = Color.Black, subtleEnter = true)
    return if (!neutralKeys) black else black.copy(
      key = Color(0xFF1A1A1A),
      special = Color(0xFF2A2A2A),
      pressed = Color(0xFF3A3A3A),
      popup = Color(0xFF2A2A2A),
      divider = Color(0xFF2A2A2A),
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

    val Light = KeyboardColors(
      background = Color(0xFFE8EAF0),
      key = Color(0xFFFFFFFF),
      special = Color(0xFFCDD2DD),
      pressed = Color(0xFFB9BFCD),
      text = Color(0xFF161922),
      muted = Color(0xFF525868),
      hint = Color(0xFF626878),
      accent = Color(0xFF3D4BC4),
      onAccent = Color(0xFFFFFFFF),
      shadow = Color(0xFFB5BAC8),
      popup = Color(0xFFFFFFFF),
      divider = Color(0xFFC4C9D4),
    )
    val Dark = KeyboardColors(
      background = Color(0xFF0F1117),
      key = Color(0xFF272B36),
      special = Color(0xFF3A3F4D),
      pressed = Color(0xFF4A5062),
      text = Color(0xFFEEF0F6),
      muted = Color(0xFFA9AFBF),
      hint = Color(0xFF9DA3B4),
      accent = Color(0xFFB4BDFF),
      onAccent = Color(0xFF12141C),
      shadow = Color(0xFF05060A),
      popup = Color(0xFF2F3442),
      divider = Color(0xFF343947),
      isDark = true,
    )
  }
}
