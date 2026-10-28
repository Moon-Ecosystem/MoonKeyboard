package com.moonkeyboard.engine.decode

import com.moonkeyboard.engine.dict.Lexicon
import java.util.PriorityQueue

/**
 * Finds dictionary words that the touches could have meant: a Levenshtein search over the trie
 * where substituting a letter costs its distance from the touch, plus insertions, deletions and
 * swapped letters. Words whose start matches all touches are offered as completions.
 */
class TapDecoder(private val lexicon: Lexicon, val config: DecoderConfig = DecoderConfig()) {

  /** [cost] is spatial/edit cost only; the language model is applied by the caller. */
  data class Hit(val wordId: Int, val cost: Float, val completion: Boolean)

  fun search(spatial: Spatial, emit: (Hit) -> Unit) {
    val n = spatial.size
    if (n == 0) return
    val limit = config.limit(n)
    val rows = Array(MAX_DEPTH + 1) { FloatArray(n + 1) }
    for (j in 0..n) rows[0][j] = j * config.delete
    dfs(0, 0, '\u0000', rows, spatial, limit, emit)
  }

  private fun dfs(node: Int, depth: Int, prevChar: Char, rows: Array<FloatArray>, sp: Spatial, limit: Float, emit: (Hit) -> Unit) {
    if (depth >= MAX_DEPTH) return
    val n = sp.size
    val prev = rows[depth]
    val prevPrev = if (depth >= 1) rows[depth - 1] else null
    val out = rows[depth + 1]
    for (child in lexicon.firstChild[node] until lexicon.firstChild[node + 1]) {
      val c = lexicon.nodeChar[child]
      if (step(prev, prevPrev, prevChar, c, out, sp) > limit) continue
      val end = out[n]
      if (end <= limit) {
        val w = lexicon.nodeWord[child]
        if (w >= 0) emit(Hit(w, end, false))
        // Only where this node consumed the last touch, or every deeper node would repeat them
        if (end < prev[n]) completions(child, end + config.completion, emit)
      }
      dfs(child, depth + 1, c, rows, sp, limit, emit)
    }
  }

  /** Cost of aligning [word] with the touches, and of the best prefix of it as a completion. */
  fun align(word: String, sp: Spatial): Pair<Float, Float> {
    val n = sp.size
    var prev = FloatArray(n + 1) { it * config.delete }
    var prevPrev: FloatArray? = null
    var out = FloatArray(n + 1)
    var bestPrefix = Float.MAX_VALUE
    var prevChar = '\u0000'
    word.forEachIndexed { i, c ->
      step(prev, prevPrev, prevChar, c, out, sp)
      if (i < word.lastIndex && out[n] < prev[n]) bestPrefix = minOf(bestPrefix, out[n] + config.completion)
      val recycled = prevPrev ?: FloatArray(n + 1)
      prevPrev = prev
      prev = out
      out = recycled
      prevChar = c
    }
    return prev[n] to bestPrefix
  }

  /** One trie level: [out] = alignment costs after adding [c]. Returns the row minimum for pruning. */
  private fun step(prev: FloatArray, prevPrev: FloatArray?, prevChar: Char, c: Char, out: FloatArray, sp: Spatial): Float {
    val n = sp.size
    val ins = if (c == '\'') config.apostropheInsert else config.insert
    out[0] = prev[0] + ins
    var min = out[0]
    for (j in 1..n) {
      var v = prev[j - 1] + sp.cost(j - 1, c)
      val insert = prev[j] + ins
      if (insert < v) v = insert
      val delete = out[j - 1] + config.delete
      if (delete < v) v = delete
      if (prevPrev != null && j >= 2) {
        val swap = prevPrev[j - 2] + config.transpose + sp.cost(j - 1, prevChar) + sp.cost(j - 2, c)
        if (swap < v) v = swap
      }
      out[j] = v
      if (v < min) min = v
    }
    return min
  }

  /** The most frequent suggestable words strictly below [node], best first by subtree frequency. */
  private fun completions(node: Int, cost: Float, emit: (Hit) -> Unit) {
    // Entries are node ids (explore) or ~wordId (emit), ordered by frequency
    val queue = PriorityQueue<Long>(compareByDescending { it ushr 32 })
    fun push(priority: Int, payload: Int) = queue.add((priority.toLong() shl 32) or (payload.toLong() and 0xFFFFFFFFL))
    for (c in lexicon.firstChild[node] until lexicon.firstChild[node + 1]) {
      val best = lexicon.subtreeBest[c].toInt()
      if (best > 0) push(best, c)
    }
    var found = 0
    while (found < config.completionsPerPrefix) {
      val entry = queue.poll() ?: return
      val payload = entry.toInt()
      if (payload < 0) {
        emit(Hit(payload.inv(), cost, true))
        found++
        continue
      }
      val w = lexicon.nodeWord[payload]
      if (w >= 0 && lexicon.isSuggestable(w)) push(lexicon.frequency(w) + 1, w.inv())
      for (c in lexicon.firstChild[payload] until lexicon.firstChild[payload + 1]) {
        val best = lexicon.subtreeBest[c].toInt()
        if (best > 0) push(best, c)
      }
    }
  }

  private companion object {
    const val MAX_DEPTH = 48
  }
}
