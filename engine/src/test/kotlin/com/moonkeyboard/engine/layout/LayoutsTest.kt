package com.moonkeyboard.engine.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutsTest {
  private val pads = listOf("number", "phone", "phone_symbols", "datetime")
  private val ids = listOf("qwerty", "symbols", "symbols2") + pads

  @Test
  fun padsAreFullFourColumnGrids() {
    for (id in pads) {
      for (row in Layouts.load(id).rows) assertEquals("$id row", 4f, row.keys.sumOf { it.width.toDouble() }.toFloat(), 0.001f)
    }
  }

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

  @Test
  fun numberRowTakesTheDigitsOffTheLetters() {
    val layout = Layouts.withNumberRow(Layouts.load("qwerty"))
    assertEquals("1234567890", layout.rows[0].keys.joinToString("") { it.label })
    val e = layout.rows[1].keys.first { it.label == "e" }
    assertEquals(null, e.hint)
    assertTrue("accents stay", "é" in e.popup && "3" !in e.popup)
    val g = geometry(layout)
    val one = g.keys.first { it.key.label == "1" }
    val q = g.keys.first { it.key.label == "q" }
    assertTrue("shorter number row", one.height < q.height)
    assertEquals(one.bottom + 10f, q.top, 0.01f)
  }

  @Test
  fun symbolsGoOnTheLettersLongPressByPosition() {
    val plain = Layouts.withSymbols(Layouts.load("qwerty"), "₹")
    val key = { l: Layout, label: String -> l.rows.flatMap { it.keys }.first { it.label == label } }
    // Without the number row the top row keeps its digit, and the symbol comes second
    assertEquals(listOf("1", "%"), key(plain, "q").popup)
    assertEquals("1", key(plain, "q").hint)
    // Elsewhere the symbol is the hint and comes first, before the accents
    assertEquals("@", key(plain, "a").hint)
    assertEquals(listOf("@", "à"), key(plain, "a").popup.take(2))
    assertEquals("₹", key(plain, "d").hint)
    assertEquals("?", key(plain, "m").hint)
    // With the number row, the symbols take the digits' place
    val numbers = Layouts.withSymbols(Layouts.withNumberRow(Layouts.load("qwerty")), "$")
    assertEquals("%", key(numbers, "q").hint)
    assertEquals("1", numbers.rows[0].keys[0].label)
    assertEquals(null, numbers.rows[0].keys[0].hint)
  }

  @Test
  fun numberRowSpansAWiderLayout() {
    // Eleven keys across, as German's top rows are
    val wide = Layout(listOf(Row((1..11).map { Key(label = ('a' + it).toString()) })))
    val digits = Layouts.withNumberRow(wide).rows[0].keys
    assertEquals(11f, digits.sumOf { it.width.toDouble() }.toFloat(), 0.001f)
  }

  @Test
  fun emojiKeyGoesAfterTheCommaInSpacesRoom() {
    val plain = Layouts.load("qwerty").rows.last().keys
    val keys = Layouts.withEmojiKey(Layouts.load("qwerty")).rows.last().keys
    assertEquals(listOf(KeyType.LAYER, KeyType.CHAR, KeyType.EMOJI, KeyType.SPACE, KeyType.CHAR, KeyType.ENTER), keys.map { it.type })
    assertEquals(plain.sumOf { it.width.toDouble() }, keys.sumOf { it.width.toDouble() }, 0.001)
    assertEquals("comma no longer opens emoji", null, keys[1].longPress)
  }

  @Test
  fun splitMovesTheRightHalfOverAndStretchesSpace() {
    val layout = Layouts.load("qwerty")
    val whole = KeyboardGeometry.compute(layout, width = 600f, keyHeight = 34f, hGap = 6f, vGap = 6f, sidePadding = 4f)
    val split = KeyboardGeometry.compute(layout, width = 840f, keyHeight = 34f, hGap = 6f, vGap = 6f, sidePadding = 4f, splitGap = 240f)
    fun key(g: KeyboardGeometry, label: String) = g.keys.first { it.key.label == label }
    assertEquals(key(whole, "t").left, key(split, "t").left, 0.01f)
    assertEquals(key(whole, "y").left + 240f, key(split, "y").left, 0.01f)
    assertEquals("g stays left", key(whole, "g").left, key(split, "g").left, 0.01f)
    val space = split.keys.first { it.key.type == KeyType.SPACE }
    assertTrue("space spans the gap", space.left < 420f && space.right > 420f + 120f)
    assertEquals("y", split.keyAt(key(split, "y").centerX, key(split, "y").centerY)?.key?.label)
  }

  @Test
  fun wideSpaceGrowsTheSpaceBarAndKeepsTheRowWidth() {
    val plain = Layouts.load("qwerty").rows.last().keys
    val keys = Layouts.wideSpace(Layouts.load("qwerty")).rows.last().keys
    val before = plain.first { it.type == KeyType.SPACE }.width
    val after = keys.first { it.type == KeyType.SPACE }.width
    assertTrue("space is wider", after > before)
    assertEquals(plain.sumOf { it.width.toDouble() }, keys.sumOf { it.width.toDouble() }, 0.001)
  }

  private fun geometry(layout: Layout) =
    KeyboardGeometry.compute(layout, width = 390f, keyHeight = 46f, hGap = 6f, vGap = 10f, sidePadding = 4f)
}
