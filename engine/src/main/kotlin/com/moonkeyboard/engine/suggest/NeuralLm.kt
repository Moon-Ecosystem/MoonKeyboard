package com.moonkeyboard.engine.suggest

import java.util.PriorityQueue
import kotlin.math.exp
import kotlin.math.ln

/**
 * A neural next-word model over a lower-case [vocab] (ids 0–2 are padding, unknown and the
 * sentence boundary). The network runs outside the engine, so the engine needs no runtime: [run]
 * takes the context's word ids and returns one score per vocabulary word for the word after them.
 * The app and the tools pass ONNX Runtime.
 */
class NeuralLm(private val vocab: List<String>, private val run: (LongArray) -> FloatArray) {
  private val ids = HashMap<String, Int>(vocab.size * 2).apply { vocab.forEachIndexed { i, w -> put(w, i) } }
  private var lastKey: List<String>? = null
  private var last: Prediction? = null

  /** How long the last network call took, for the test pad. */
  @Volatile
  var lastMillis = 0L
    private set

  inner class Prediction(private val logProbs: FloatArray) {
    /** ln P of [word] next (any capitalisation), or null if the model doesn't know it. */
    fun logProb(word: String): Float? = ids[word.lowercase()]?.let { logProbs[it] }

    /** The model's likeliest next words, best first, in its lower-case spelling. */
    val top: List<String> by lazy {
      val heap = PriorityQueue<Int>(TOP + 1, compareBy { logProbs[it] })
      for (i in FIRST_WORD until logProbs.size) {
        if (heap.size < TOP) heap.add(i)
        else if (logProbs[i] > logProbs[heap.peek()]) { heap.poll(); heap.add(i) }
      }
      heap.sortedByDescending { logProbs[it] }.map { vocab[it] }
    }
  }

  /**
   * The next word's distribution after [tokens] (lower-case words, [WordContext.START_TOKEN] at
   * sentence boundaries), or null if the network failed. The last context is cached: every key
   * press within a word asks again with the same one.
   */
  @Synchronized
  fun predict(tokens: List<String>): Prediction? {
    val key = tokens.takeLast(MAX_CONTEXT)
    if (key == lastKey) return last
    val input = LongArray(key.size) { (ids[key[it]] ?: UNKNOWN).toLong() }
    val start = System.nanoTime()
    val scores = try {
      run(input)
    } catch (e: Exception) {
      return null
    }
    lastMillis = (System.nanoTime() - start) / 1_000_000
    // Log-softmax, so scores mix with the dictionary's probabilities
    val max = scores.max()
    var sum = 0.0
    for (s in scores) sum += exp((s - max).toDouble())
    val norm = max + ln(sum).toFloat()
    for (i in scores.indices) scores[i] -= norm
    return Prediction(scores).also { lastKey = key; last = it }
  }

  private companion object {
    const val UNKNOWN = 1
    const val FIRST_WORD = 3

    /** Words of context; the network was trained on up to 64. */
    const val MAX_CONTEXT = 32
    const val TOP = 30
  }
}
