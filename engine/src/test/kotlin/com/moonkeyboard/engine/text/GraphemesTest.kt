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
}
