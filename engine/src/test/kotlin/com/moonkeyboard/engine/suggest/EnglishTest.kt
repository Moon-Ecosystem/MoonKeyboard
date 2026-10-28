package com.moonkeyboard.engine.suggest

import com.moonkeyboard.engine.decode.KeyMap
import com.moonkeyboard.engine.decode.Touch
import com.moonkeyboard.engine.dict.Lexicon
import com.moonkeyboard.engine.history.UserHistory
import com.moonkeyboard.engine.layout.KeyboardGeometry
import com.moonkeyboard.engine.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random

/** Runs against the real English pack built by :tools (skipped if it hasn't been built). */
class EnglishTest {
  private val engine: SuggestionEngine by lazy { SuggestionEngine(lexicon, UserHistory()) }

  private fun touches(word: String) = word.map(keys::touchFor)

  private fun suggest(typed: String, prev: String? = "the", engine: SuggestionEngine = this.engine) =
    engine.suggest(typed, touches(typed), keys, WordContext(prev, prev == null), allowAutocorrect = true)

  @Test
  fun correctsCommonTypos() {
    assertEquals("the", suggest("teh", prev = "at").autocorrect)
    assertEquals("don't", suggest("dont", prev = "I").autocorrect)
    assertEquals("I'm", suggest("im", prev = null).autocorrect)
    assertEquals("I", suggest("i", prev = null).autocorrect)
    assertEquals("hello", suggest("hrllo", prev = null).autocorrect)
  }

  @Test
  fun leavesRealWordsAlone() {
    for (word in listOf("form", "cafe", "from", "lead")) assertNull(word, suggest(word).autocorrect)
  }

  @Test
  fun followsCapitals() {
    assertEquals("The", suggest("Teh", prev = null).autocorrect)
  }

  @Test
  fun completesWords() {
    assertTrue(suggest("tomorr").items.contains("tomorrow"))
  }

  @Test
  fun predictsNextWord() {
    assertTrue(engine.predict(WordContext("thank", false), capitalize = false).items.contains("you"))
  }

  @Test
  fun learnedWordsAreSuggestedAndNotCorrected() {
    val history = UserHistory()
    val e = SuggestionEngine(lexicon, history)
    assertTrue("before learning it gets corrected", suggest("moonkey", engine = e).autocorrect != null)
    history.record(null, "moonkey", weight = 2)
    assertNull(suggest("moonkey", engine = e).autocorrect)
    assertTrue(suggest("moonke", engine = e).items.contains("moonkey"))
  }

  /**
   * Noisy-typing benchmark: real sentences tapped with Gaussian finger error, scored on the word
   * that space would commit. Prints numbers so tuning changes can be compared.
   */
  @Test
  fun noisyTypingBenchmark() {
    val random = Random(42)
    for (sigma in listOf(0.25f, 0.4f, 0.55f)) {
      var words = 0
      var literalRight = 0
      var committedRight = 0
      var top3 = 0
      var nanos = 0L
      for (sentence in SENTENCES) {
        var context = WordContext.START
        for (target in sentence.split(' ')) {
          val noisy = target.map { c ->
            val (x, y) = keys.center(c.lowercaseChar())!!
            Touch(x + (random.nextGaussian() * sigma * keys.keyWidth).toFloat(), y + (random.nextGaussian() * sigma * keys.keyWidth).toFloat(), c)
          }
          // What a keyboard without correction would type: the key each touch landed on
          val typed = noisy.joinToString("") { t -> letterKeys.minBy { it.distanceSq(t.x, t.y) }.key.label }
            .let { if (target[0].isUpperCase()) it.replaceFirstChar(Char::uppercaseChar) else it }
          val touchesTyped = noisy.mapIndexed { i, t -> t.copy(char = typed[i]) }
          val start = System.nanoTime()
          val s = engine.suggest(typed, touchesTyped, keys, context, allowAutocorrect = true)
          nanos += System.nanoTime() - start
          val committed = s.autocorrect ?: typed
          words++
          if (typed.equals(target, ignoreCase = true)) literalRight++
          if (committed.equals(target, ignoreCase = true)) committedRight++
          if (s.items.any { it.equals(target, ignoreCase = true) }) top3++
          context = WordContext(target, false)
        }
      }
      println(
        "noise %.2f keys: literal %.1f%%, committed %.1f%%, in strip %.1f%%, %.2f ms/word"
          .format(sigma, 100.0 * literalRight / words, 100.0 * committedRight / words, 100.0 * top3 / words, nanos / 1e6 / words)
      )
      assertTrue("correction must not lose to raw taps at noise $sigma", committedRight >= literalRight)
    }
  }

  private companion object {
    val file = File("../app/src/main/assets/dict/en.dict")
    val lexicon: Lexicon by lazy {
      assumeTrue("English pack not built", file.exists())
      Lexicon.read(file.readBytes())
    }
    val geometry = KeyboardGeometry.compute(Layouts.load("qwerty"), 390f, 46f, 6f, 10f, 4f)
    val keys = KeyMap.from(geometry)
    // Simulated typists only miss onto other letters; a stray shift would just be another key press
    val letterKeys = geometry.keys.filter { it.key.label.length == 1 && it.key.label[0].isLetter() }

    val SENTENCES = listOf(
      "I will be there in five minutes",
      "Are you coming to the party tonight",
      "Thank you so much for your help",
      "Can we meet tomorrow morning",
      "I am running a little late",
      "What time does the movie start",
      "Let me know when you get home",
      "Do you want to grab lunch",
      "The meeting has been moved to Friday",
      "I think we should leave early",
      "Please send me the address",
      "Happy birthday to you",
      "She said she would call back later",
      "We need to buy milk and bread",
      "How was your day",
      "I really like this song",
      "Sorry I missed your call",
      "The weather is beautiful today",
      "Have you finished the report",
      "Good night and sleep well",
    )
  }
}
