package com.moonkeyboard.engine.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserHistoryTest {
  private fun typed(vararg sentence: String) = UserHistory().apply {
    for (s in sentence) {
      var prev2: String? = null
      var prev: String? = "<s>"
      for (w in s.split(' ')) {
        record(prev, w, prev2 = prev2)
        prev2 = prev
        prev = w
      }
    }
  }

  @Test
  fun listsPhrasesUsedMoreThanOnce() {
    val h = typed("see you soon", "see you soon", "see me")
    assertEquals(listOf(LearnedPhrase("see", "you", "soon", 2), LearnedPhrase("<s>", "see", "you", 2)), h.learnedPhrases().sortedBy { it.word })
    assertEquals("see" to 3, h.learnedWords().first())
  }

  @Test
  fun forgettingAPhraseKeepsItsWords() {
    val h = typed("see you soon", "see you soon")
    h.forgetPhrase("see", "you", "soon")
    assertTrue(h.learnedPhrases().none { it.word == "soon" })
    assertEquals(0, h.pairCount("you", "soon"))
    assertEquals(2, h.count("soon"))
  }

  @Test
  fun allowingABlockedWordAgain() {
    val h = typed("teh cat")
    h.forget("teh")
    assertEquals(listOf("teh"), h.blockedWords())
    h.unblock("teh")
    assertFalse(h.isBlocked("teh"))
  }
}
