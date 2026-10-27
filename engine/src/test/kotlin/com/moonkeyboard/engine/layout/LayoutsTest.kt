package com.moonkeyboard.engine.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutsTest {
  private val ids = listOf("qwerty", "symbols", "symbols2")

  @Test
  fun everyLayoutLoadsAndLayerKeysPointToRealLayouts() {
    for (id in ids) {
      val layout = Layouts.load(id)
      for (key in layout.rows.flatMap { it.keys }.filter { it.type == KeyType.LAYER }) {
        assertTrue("$id: ${key.layer}", key.layer in ids)
      }
    }
  }

  @Test
  fun everyRowFillsTheWidthOrIsCentred() {
    for (id in ids) {
      val g = geometry(Layouts.load(id))
      for (row in g.keys.groupBy { it.top }.values) {
        val leftMargin = row.first().left
        val rightMargin = g.width - row.last().right
        assertEquals("$id row at ${row.first().top} is centred", leftMargin, rightMargin, 0.01f)
        assertTrue("$id row fits", leftMargin >= 4f - 0.01f)
      }
    }
  }

  @Test
  fun hitTestFindsKeyUnderFingerAndNearestKeyInGaps() {
    val g = geometry(Layouts.load("qwerty"))
    val e = g.keys.first { it.key.label == "e" }
    assertEquals("e", g.keyAt(e.centerX, e.centerY)?.key?.label)
    // Just right of "e", inside the gap before "r"
    assertEquals("e", g.keyAt(e.right + 1f, e.centerY)?.key?.label)
    // The shift key is 1.5 units wide, so it reaches past the "a" column
    val shift = g.keys.first { it.key.type == KeyType.SHIFT }
    val a = g.keys.first { it.key.label == "a" }
    assertTrue(shift.right > a.centerX)
  }

  private fun geometry(layout: Layout) =
    KeyboardGeometry.compute(layout, width = 390f, keyHeight = 46f, hGap = 6f, vGap = 10f, sidePadding = 4f)
}
