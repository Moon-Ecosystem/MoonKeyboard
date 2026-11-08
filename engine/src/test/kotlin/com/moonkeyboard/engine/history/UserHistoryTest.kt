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
  fun emailsAreOfferedMostUsedFirstAndNarrowByPrefix() {
    val h = UserHistory()
    h.recordEmail("Rishabh123@Gmail.com")
    repeat(2) { h.recordEmail("rishabh@work.example") }
    h.recordEmail("not an email")
    h.recordEmail("moon@x.org")
    val back = UserHistory.parse(h.serialize())
    assertEquals(listOf("rishabh@work.example", "moon@x.org", "rishabh123@gmail.com"), back.emailsStartingWith(""))
    assertEquals(listOf("rishabh@work.example", "rishabh123@gmail.com"), back.emailsStartingWith("RI"))
    assertEquals(listOf("rishabh123@gmail.com"), back.emailsStartingWith("rishabh1"))
    assertEquals("the full address typed isn't offered again", emptyList<String>(), back.emailsStartingWith("moon@x.org"))
    back.removeEmail("moon@x.org")
    assertEquals(2, back.emails().size)
  }

  @Test
  fun shortcutsSurviveSavingAndClearing() {
    val h = UserHistory()
    h.setShortcut("OMW", " on my way ")
    h.setShortcut("two words", "rejected")
    h.setShortcut("addr", "12 Moon Street")
    val back = UserHistory.parse(h.serialize())
    assertEquals(listOf("addr" to "12 Moon Street", "omw" to "on my way"), back.shortcuts())
    assertEquals("on my way", back.shortcut("Omw"))
    back.clear()
    assertEquals("clearing learned words keeps shortcuts", 2, back.shortcuts().size)
    back.removeShortcut("OMW")
    assertEquals(null, back.shortcut("omw"))
  }

  @Test
  fun listsPhrasesUsedMoreThanOnce() {
    val h = typed("see you soon", "see you soon", "see me")
    assertEquals(listOf(LearnedPhrase("see", "you", "soon", 2), LearnedPhrase("<s>", "see", "you", 2)), h.learnedPhrases().sortedBy { it.word })
    assertEquals(3, h.count("see"))
  }

  @Test
  fun learnedWordsListsTheLatestFirst() {
    // "see" was typed last of all repeated words, "me" after that, so "me" tops the list
    val h = typed("see you soon", "see you soon", "see me")
    assertEquals(listOf("me" to 1, "see" to 3, "soon" to 2, "you" to 2), h.learnedWords())
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
