package com.moonkeyboard.engine.input

enum class ShiftState { OFF, ON, LOCKED }

/** Tap shift for one capital, double-tap for caps lock, tap again to release. */
class ShiftController(private val doubleTapMs: Long = 300) {
  var state = ShiftState.OFF
    private set

  private var lastTap: Long? = null

  /** True while shift is on only because of auto-capitalisation, not because the user tapped it. */
  private var auto = false

  fun onShiftTap(nowMs: Long) {
    val last = lastTap
    state = when {
      state == ShiftState.OFF -> ShiftState.ON
      state == ShiftState.ON && !auto && last != null && nowMs - last <= doubleTapMs -> ShiftState.LOCKED
      else -> ShiftState.OFF
    }
    auto = false
    lastTap = nowMs
  }

  /** A one-shot shift releases after the next character; caps lock stays. */
  fun onCharTyped() {
    if (state == ShiftState.ON) state = ShiftState.OFF
    auto = false
  }

  /** Turns shift on at the start of a sentence, and back off only if it was us who turned it on. */
  fun autoCapitalize(should: Boolean) {
    if (should && state == ShiftState.OFF) {
      state = ShiftState.ON
      auto = true
    } else if (!should && state == ShiftState.ON && auto) {
      state = ShiftState.OFF
      auto = false
    }
  }

  fun reset() {
    state = ShiftState.OFF
    lastTap = null
    auto = false
  }
}
