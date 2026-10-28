package com.moonkeyboard.engine.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class LexiconTest {
  private fun sample(): Lexicon {
    val b = LexiconBuilder()
    val the = b.addWord("the", 222)
    val thank = b.addWord("thank", 160)
    val you = b.addWord("you", 200)
    b.addWord("I", 196)
    val im = b.addWord("im", 0, Lexicon.NOT_A_WORD)
    val iam = b.addWord("I'm", 170)
    b.addWord("damn", 0, Lexicon.NO_SUGGEST)
    b.whitelist(im, iam)
    b.bigram(thank, you, -0.2f)
    b.bigram(Lexicon.SENTENCE_START, the, -2f)
    b.trigram(Lexicon.SENTENCE_START, thank, you, -0.1f)
    return b.build()
  }

  private fun roundTrip(l: Lexicon) = Lexicon.read(ByteArrayOutputStream().also(l::write).toByteArray())

  @Test
  fun survivesWriteAndRead() {
    val l = roundTrip(sample())
    assertEquals(7, l.wordCount)
    for (id in 0 until l.wordCount) assertEquals(id, l.find(l.word(id)))
  }

  @Test
  fun lookupsAndFlags() {
    val l = roundTrip(sample())
    assertEquals(-1, l.find("th"))
    assertEquals("I", l.word(l.findLenient("i")))
    assertEquals("the", l.word(l.findLenient("The")))
    assertFalse(l.isWord(l.find("im")))
    assertEquals("I'm", l.word(l.whitelistTarget(l.find("im"))))
    assertTrue(l.isWord(l.find("damn")))
    assertFalse(l.isSuggestable(l.find("damn")))
  }

  @Test
  fun pairModel() {
    val l = roundTrip(sample())
    assertEquals(-0.2f, l.bigramLogProb(l.find("thank"), l.find("you"))!!, 1f / 16)
    assertNull(l.bigramLogProb(l.find("you"), l.find("thank")))
    assertEquals(listOf(l.find("the")), l.successors(Lexicon.SENTENCE_START).map { it.first })
  }

  @Test
  fun twoWordModel() {
    val l = roundTrip(sample())
    val thank = l.find("thank")
    val you = l.find("you")
    assertEquals(-0.1f, l.trigramLogProb(Lexicon.SENTENCE_START, thank, you)!!, 1f / 16)
    assertNull(l.trigramLogProb(you, thank, you))
    assertEquals(listOf(you), l.trigramSuccessors(Lexicon.SENTENCE_START, thank).map { it.first })
    assertFalse(l.hasTrigrams(thank, you))
  }
}
