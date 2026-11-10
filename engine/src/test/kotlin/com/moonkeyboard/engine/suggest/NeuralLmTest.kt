package com.moonkeyboard.engine.suggest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class NeuralLmTest {
  private val vocab = listOf("<pad>", "<unk>", "<s>", "you", "the", "soon")
  private var calls = 0
  private var lastInput = LongArray(0)

  // Scores: specials high (must never be offered), then "soon" > "you" > "the"
  private val lm = NeuralLm(vocab) { ids ->
    calls++
    lastInput = ids
    floatArrayOf(9f, 9f, 9f, 2f, 1f, 3f)
  }

  @Test
  fun tokensFollowTheTraining() {
    assertEquals(listOf("<s>", "hi", "there", "<s>", "how's", "it"), WordContext.tokensOf("Hi there!! How’s it, "))
    val c = WordContext.from("See you. Talk to").then("You")
    assertEquals(listOf("<s>", "see", "you", "<s>", "talk", "to", "you"), c.tokens)
    // Built by hand, as the tests do: falls back to the two words it has
    assertEquals(listOf("<s>", "i"), WordContext.START.then("I").tokens)
    assertEquals(listOf("see", "you"), WordContext("you", false, prevPrev = "see").tokens)
  }

  @Test
  fun predictsCachesAndNormalises() {
    val p = lm.predict(listOf("<s>", "you", "zzz"))!!
    assertEquals(listOf(2L, 3L, 1L), lastInput.toList())
    assertEquals(listOf("soon", "you", "the"), p.top)
    assertEquals(lm.predict(listOf("<s>", "you", "zzz")), p)
    assertEquals(1, calls)
    val total = (0 until vocab.size).sumOf { exp(p.logProb(vocab[it])!!.toDouble()) }
    assertEquals(1.0, total, 1e-4)
    assertEquals(p.logProb("you"), p.logProb("You"))
    assertNull(p.logProb("zzz"))
  }

  @Test
  fun aFailingNetworkMeansNoPrediction() {
    assertNull(NeuralLm(vocab) { error("no model") }.predict(listOf("<s>")))
    assertTrue(calls == 0)
  }
}
