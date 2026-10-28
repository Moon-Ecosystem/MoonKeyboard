package com.moonkeyboard.engine.suggest

import com.moonkeyboard.engine.decode.KeyMap
import com.moonkeyboard.engine.decode.resample
import com.moonkeyboard.engine.trace.TracePoint
import com.moonkeyboard.engine.dict.Lexicon
import com.moonkeyboard.engine.eval.TypingSimulator
import com.moonkeyboard.engine.history.UserHistory
import com.moonkeyboard.engine.layout.KeyboardGeometry
import com.moonkeyboard.engine.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Runs against the real English + Hinglish pack built by :tools (skipped if it hasn't been built). */
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
  fun predictsFromTwoWordsOfContext() {
    val ctx = WordContext.START.then("I").then("want")
    assertTrue(engine.predict(ctx, capitalize = false).items.contains("to"))
  }

  @Test
  fun learnsTheUsersPhrases() {
    val history = UserHistory()
    val e = SuggestionEngine(lexicon, history)
    val afterThe = WordContext.START.then("See").then("you").then("at").then("the")
    assertTrue("not predicted before learning", "gym" !in e.predict(afterThe, capitalize = false).items)
    repeat(3) {
      var c = WordContext.START
      for (w in "see you at the gym".split(' ')) {
        history.record(c.userKey, w, prev2 = c.userKey2)
        c = c.then(w)
      }
    }
    assertEquals("best prediction after three uses", "gym", e.predict(afterThe, capitalize = false).items[1])
    // And once its first letter is typed
    val g = "g"
    assertTrue(e.suggest(g, g.map(keys::touchFor), keys, afterThe, allowAutocorrect = false).items.contains("gym"))
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
   * Accuracy floors on held-out sentences (never seen by the pair model) typed by a simulated
   * typist. The full report and tuning live in tools/Evaluate.kt; this guards against regressions.
   */
  @Test
  fun tapAccuracyOnHeldOutSentences() = tapAccuracy(EVAL, TAP_FLOOR)

  @Test
  fun glideAccuracyOnHeldOutSentences() = glideAccuracy(EVAL, GLIDE_FLOOR)

  @Test
  fun hinglishTapAccuracy() = tapAccuracy(EVAL_HI, HINGLISH_TAP_FLOOR, HINGLISH_BROKE_CEILING)

  @Test
  fun hinglishGlideAccuracy() = glideAccuracy(EVAL_HI, HINGLISH_GLIDE_FLOOR)

  @Test
  fun hinglishWordsAreKnown() {
    for (word in listOf("nahi", "kya", "hai", "bhai")) assertNull(word, suggest(word, prev = null).autocorrect)
    assertTrue(suggest("kuc", prev = "aur").items.contains("kuch"))
  }

  @Test
  fun predictsHinglish() {
    val ctx = WordContext.START.then("kya").then("kar")
    assertTrue(engine.predict(ctx, capitalize = false).items.toString(), engine.predict(ctx, capitalize = false).items.contains("rahe"))
  }

  @Test
  fun hinglishCanBeTurnedOff() {
    val english = Lexicon.read(file.readBytes()).also { it.hinglish = false }
    val e = SuggestionEngine(english, UserHistory())
    assertTrue("nahi is just a typo with Hinglish off", suggest("nahi", prev = null, engine = e).autocorrect != null)
    assertTrue(e.predict(WordContext.START.then("kya"), capitalize = false).items.none { english.findLenient(it).let { id -> id >= 0 && !english.isSuggestable(id) } })
    // English words that are also Hindi stay
    assertNull(suggest("main", prev = "the", engine = e).autocorrect)
  }

  private fun tapAccuracy(sentences: List<String>, floor: Double, brokeCeiling: Double = 0.005) {
    val sim = TypingSimulator(geometry, seed = 1)
    var words = 0
    var committed = 0
    var broke = 0
    for (sentence in sentences) {
      var context = WordContext.START
      for (target in sentence.split(' ')) {
        val (typed, touches) = sim.tap(target, 0.35f)
        if (typed.isEmpty()) continue
        val result = engine.suggest(typed, touches, sim.keys, context, allowAutocorrect = true).let { it.autocorrect ?: typed }
        words++
        if (result.equals(target, ignoreCase = true)) committed++
        else if (typed.equals(target, ignoreCase = true)) broke++
        context = context.then(target)
      }
    }
    println("tap: committed %.1f%%, broke %.1f%% of %d words".format(100.0 * committed / words, 100.0 * broke / words, words))
    assertTrue("committed accuracy", committed >= words * floor)
    assertTrue("autocorrect must almost never break a right word", broke <= words * brokeCeiling)
  }

  @Test
  fun glideFindsCleanPaths() {
    val sim = TypingSimulator(geometry)
    for (word in listOf("moon", "hello", "keyboard", "the", "don't")) {
      val s = engine.glide(sim.glide(word, 0f, 0f), keys, WordContext("the", false), false, false)
      assertEquals(word, s.typed)
      assertEquals("no case-only duplicates in ${s.items}", s.items.size, s.items.distinctBy { it.lowercase() }.size)
    }
  }

  /** Turning back before reaching "a" (n → almost a → m → e) used to type "be". */
  @Test
  fun glideForgivesTurningBeforeTheKey() {
    fun c(ch: Char) = keys.center(ch)!!
    val (ax, ay) = c('a')
    val turn = (ax + 1.5f * keys.keyWidth) to ay
    val path = resample(listOf(c('n'), turn, c('m'), c('e')), 60).mapIndexed { i, (x, y) -> TracePoint(x, y, i * 8L) }
    assertEquals("name", engine.glide(path, keys, WordContext("my", false), false, false).typed)
  }

  /** Wobbly, corner-cutting swipes of held-out sentences. */
  private fun glideAccuracy(sentences: List<String>, floor: Double) {
    val sim = TypingSimulator(geometry, seed = 2)
    var words = 0
    var right = 0
    for (sentence in sentences) {
      var context = WordContext.START
      for (target in sentence.split(' ')) {
        if (target.count { it.isLetter() } >= 2) {
          words++
          if (engine.glide(sim.glide(target, 0.3f, 0.6f, 0.35f), sim.keys, context, false, false).typed.equals(target, ignoreCase = true)) right++
        }
        context = context.then(target)
      }
    }
    println("glide: right %.1f%% of %d words".format(100.0 * right / words, words))
    assertTrue("glide accuracy", right >= words * floor)
  }

  private companion object {
    val file = File("../app/src/main/assets/dict/en.dict")
    val lexicon: Lexicon by lazy {
      assumeTrue("English pack not built", file.exists())
      Lexicon.read(file.readBytes())
    }
    val geometry = KeyboardGeometry.compute(Layouts.load("qwerty"), 390f, 46f, 6f, 10f, 4f)
    val keys = KeyMap.from(geometry)

    /** Held-out sentences written by :tools; the first 150 keep the test quick. */
    val EVAL: List<String> by lazy { eval("en") }
    val EVAL_HI: List<String> by lazy { eval("hi") }

    private fun eval(name: String): List<String> {
      val stream = EnglishTest::class.java.getResourceAsStream("/eval/$name.txt")
      assumeTrue("held-out sentences not built", stream != null)
      return stream!!.bufferedReader().readLines().filter { it.isNotBlank() }.take(150)
    }

    // A few points under what tools/Evaluate.kt measures, so noise in a change doesn't flake the build
    const val TAP_FLOOR = 0.95
    const val GLIDE_FLOOR = 0.72
    const val HINGLISH_TAP_FLOOR = 0.92
    const val HINGLISH_GLIDE_FLOOR = 0.65
    /** Tweets' looser spelling: variants correct into each other more often than English words do. */
    const val HINGLISH_BROKE_CEILING = 0.012
  }
}
