package com.moonkeyboard.ime

import androidx.compose.ui.graphics.Color

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
) {
  companion object {
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
    )
  }
}
