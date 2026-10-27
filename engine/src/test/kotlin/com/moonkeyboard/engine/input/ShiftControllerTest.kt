package com.moonkeyboard.engine.input

import org.junit.Assert.assertEquals
import org.junit.Test

class ShiftControllerTest {
  @Test
  fun tapGivesOneCapital() {
    val s = ShiftController()
    s.onShiftTap(1_000)
    assertEquals(ShiftState.ON, s.state)
    s.onCharTyped()
    assertEquals(ShiftState.OFF, s.state)
  }

  @Test
  fun doubleTapLocksUntilTappedAgain() {
    val s = ShiftController()
    s.onShiftTap(1_000)
    s.onShiftTap(1_200)
    assertEquals(ShiftState.LOCKED, s.state)
    s.onCharTyped()
    assertEquals(ShiftState.LOCKED, s.state)
    s.onShiftTap(5_000)
    assertEquals(ShiftState.OFF, s.state)
  }

  @Test
  fun slowSecondTapTurnsShiftOff() {
    val s = ShiftController()
    s.onShiftTap(1_000)
    s.onShiftTap(2_000)
    assertEquals(ShiftState.OFF, s.state)
  }
}
