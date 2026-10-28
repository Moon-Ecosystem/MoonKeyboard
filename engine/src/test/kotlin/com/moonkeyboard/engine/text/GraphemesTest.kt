package com.moonkeyboard.engine.text

import org.junit.Assert.assertEquals
import org.junit.Test

class GraphemesTest {
  @Test
  fun plainLetter() = assertEquals(1, lastGraphemeLength("ab"))

  @Test
  fun empty() = assertEquals(0, lastGraphemeLength(""))

  @Test
  fun letterWithCombiningAccent() = assertEquals(2, lastGraphemeLength("café"))

  @Test
  fun emojiWithSkinTone() = assertEquals(4, lastGraphemeLength("ok 👍🏽"))

  @Test
  fun zwjFamilyEmoji() = assertEquals(8, lastGraphemeLength("👩‍👧‍👦"))

  @Test
  fun stepsOverWholeCharactersAndStopsAtTheEnds() {
    val s = "a👍🏽b"
    assertEquals(1, graphemeStep(s, 0, forward = true))
    assertEquals(5, graphemeStep(s, 1, forward = true))
    assertEquals(1, graphemeStep(s, 5, forward = false))
    assertEquals(6, graphemeStep(s, 6, forward = true))
    assertEquals(0, graphemeStep(s, 0, forward = false))
  }
}
