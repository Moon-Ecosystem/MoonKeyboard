package com.moonkeyboard.engine.input

enum class ShiftState { OFF, ON, LOCKED }

/** Tap shift for one capital, double-tap for caps lock, tap again to release. */
class ShiftController(private val doubleTapMs: Long = 300) {
  var state = ShiftState.OFF
    private set

  private var lastTap: Long? = null

  fun onShiftTap(nowMs: Long) {
    val last = lastTap
    state = when {
      state == ShiftState.OFF -> ShiftState.ON
      state == ShiftState.ON && last != null && nowMs - last <= doubleTapMs -> ShiftState.LOCKED
      else -> ShiftState.OFF
    }
    lastTap = nowMs
  }

  /** A one-shot shift releases after the next character; caps lock stays. */
  fun onCharTyped() {
    if (state == ShiftState.ON) state = ShiftState.OFF
  }

  fun reset() {
    state = ShiftState.OFF
    lastTap = null
  }
}
