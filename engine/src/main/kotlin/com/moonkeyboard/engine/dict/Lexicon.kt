package com.moonkeyboard.engine.dict

import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.math.ln

/**
 * A language pack's words and word-pair model, laid out as flat arrays so it loads with a few bulk
 * copies and costs no per-word objects.
 *
 * Trie nodes are numbered breadth-first, so the children of node `i` are exactly the nodes
 * `firstChild[i] until firstChild[i + 1]`. Node 0 is the root.
 */
class Lexicon internal constructor(
  internal val nodeChar: CharArray,
  internal val firstChild: IntArray,
  internal val nodeWord: IntArray,
  /** 1 + the highest frequency of a suggestable word at or below the node; 0 if there is none. */
  internal val subtreeBest: ShortArray,
  private val wordText: String,
  private val wordStart: IntArray,
  private val freq: ByteArray,
  private val flags: ByteArray,
  private val whitelistFrom: IntArray,
  private val whitelistTo: IntArray,
  /** Successors of word `w` (and of [SENTENCE_START] at index wordCount), sorted by id. */
  private val bigramStart: IntArray,
  private val bigramNext: IntArray,
  private val bigramCost: ByteArray,
  /** Two-word contexts as sorted `(w1 << 32) | w2` keys; w1 may be [SENTENCE_START] (stored as wordCount). */
  private val trigramKeys: LongArray,
  /** Successors of context `k` are entries `trigramStart[k] until trigramStart[k + 1]`, sorted by id. */
  private val trigramStart: IntArray,
  private val trigramNext: IntArray,
  private val trigramCost: ByteArray,
) {
  val wordCount: Int get() = freq.size
  val nodeCount: Int get() = nodeChar.size

  fun word(id: Int): String = wordText.substring(wordStart[id], wordStart[id + 1])

  /** 0–255 on a log scale: 255 is probability 1, each step down divides by 1.15 (the AOSP scale). */
  fun frequency(id: Int): Int = freq[id].toInt() and 0xFF

  /** False for entries that only exist as a correction source, like "im" (→ "I'm"). */
  fun isWord(id: Int): Boolean = flags[id].toInt() and NOT_A_WORD == 0

  /** Whether words AOSP marks as possibly offensive are kept out of suggestions, glide results and predictions. */
  @Volatile
  var blockOffensive = true

  fun isSuggestable(id: Int): Boolean {
    val f = flags[id].toInt()
    return f and (NOT_A_WORD or NO_SUGGEST) == 0 && !(blockOffensive && f and OFFENSIVE != 0)
  }

  fun unigramLogProb(id: Int): Float = (frequency(id) - 255) * LN_1_15

  /** Exact lookup; -1 if absent. */
  fun find(word: String): Int {
    var node = 0
    for (c in word) {
      node = child(node, c)
      if (node < 0) return -1
    }
    return nodeWord[node]
  }

  /** Exact, then lower case, then capitalised: "hello" finds "Hello" only if "hello" is absent. */
  fun findLenient(word: String): Int {
    if (word.isEmpty()) return -1
    find(word).let { if (it >= 0) return it }
    val lower = word.lowercase()
    find(lower).let { if (it >= 0) return it }
    return find(lower.replaceFirstChar { it.uppercaseChar() })
  }

  /** A fixed correction for this word ("ill" → "I'll"), or -1. */
  fun whitelistTarget(id: Int): Int {
    val i = whitelistFrom.binarySearch(id)
    return if (i >= 0) whitelistTo[i] else -1
  }

  fun hasBigrams(prev: Int): Boolean {
    val p = bigramIndex(prev)
    return bigramStart[p + 1] > bigramStart[p]
  }

  /** ln P(next | prev) from the pair model, or null when the pair was never seen. */
  fun bigramLogProb(prev: Int, next: Int): Float? {
    val p = bigramIndex(prev)
    val i = bigramNext.binarySearch(next, bigramStart[p], bigramStart[p + 1])
    return if (i >= 0) bigramLogProbAt(i) else null
  }

  /** Every recorded successor of [prev] as (word id, ln P). */
  fun successors(prev: Int): List<Pair<Int, Float>> {
    val p = bigramIndex(prev)
    return (bigramStart[p] until bigramStart[p + 1]).map { bigramNext[it] to bigramLogProbAt(it) }
  }

  fun hasTrigrams(w1: Int, w2: Int): Boolean = trigramSlot(w1, w2) >= 0

  /** ln P(next | w1 w2) from the two-word model, or null when that triple was never seen. */
  fun trigramLogProb(w1: Int, w2: Int, next: Int): Float? {
    val k = trigramSlot(w1, w2)
    if (k < 0) return null
    val i = trigramNext.binarySearch(next, trigramStart[k], trigramStart[k + 1])
    return if (i >= 0) -(trigramCost[i].toInt() and 0xFF) / COST_SCALE else null
  }

  fun trigramSuccessors(w1: Int, w2: Int): List<Pair<Int, Float>> {
    val k = trigramSlot(w1, w2)
    if (k < 0) return emptyList()
    return (trigramStart[k] until trigramStart[k + 1]).map { trigramNext[it] to -(trigramCost[it].toInt() and 0xFF) / COST_SCALE }
  }

  private fun trigramSlot(w1: Int, w2: Int): Int {
    if (w2 < 0) return -1
    val key = (bigramIndex(w1).toLong() shl 32) or w2.toLong()
    return trigramKeys.binarySearch(key).let { if (it >= 0) it else -1 }
  }

  internal fun child(node: Int, c: Char): Int {
    var lo = firstChild[node]
    var hi = firstChild[node + 1] - 1
    while (lo <= hi) {
      val mid = (lo + hi) ushr 1
      val m = nodeChar[mid]
      when {
        m < c -> lo = mid + 1
        m > c -> hi = mid - 1
        else -> return mid
      }
    }
    return -1
  }

  private fun bigramIndex(prev: Int) = if (prev == SENTENCE_START) wordCount else prev

  private fun bigramLogProbAt(i: Int): Float = -(bigramCost[i].toInt() and 0xFF) / COST_SCALE

  fun write(out: OutputStream) {
    val d = DataOutputStream(out.buffered())
    d.writeInt(MAGIC)
    d.writeInt(VERSION)
    d.writeInt(nodeCount)
    nodeChar.forEach { d.writeChar(it.code) }
    firstChild.forEach(d::writeInt)
    nodeWord.forEach(d::writeInt)
    subtreeBest.forEach { d.writeShort(it.toInt()) }
    val text = wordText.toByteArray(Charsets.UTF_8)
    d.writeInt(wordCount)
    d.writeInt(text.size)
    d.write(text)
    wordStart.forEach(d::writeInt)
    d.write(freq)
    d.write(flags)
    d.writeInt(whitelistFrom.size)
    whitelistFrom.forEach(d::writeInt)
    whitelistTo.forEach(d::writeInt)
    d.writeInt(bigramNext.size)
    bigramStart.forEach(d::writeInt)
    bigramNext.forEach(d::writeInt)
    d.write(bigramCost)
    d.writeInt(trigramKeys.size)
    d.writeInt(trigramNext.size)
    trigramKeys.forEach(d::writeLong)
    trigramStart.forEach(d::writeInt)
    trigramNext.forEach(d::writeInt)
    d.write(trigramCost)
    d.flush()
  }

  companion object {
    const val SENTENCE_START = -2
    const val NOT_A_WORD = 1
    const val NO_SUGGEST = 2
    /** Possibly offensive: suggested only when the user turns blocking off. */
    const val OFFENSIVE = 4

    /** Pair costs are stored as -ln P × 16 in one byte, so ln P down to -16. */
    internal const val COST_SCALE = 16f
    internal val LN_1_15 = ln(1.15f)
    private const val MAGIC = 0x4D4B4C58 // "MKLX"
    private const val VERSION = 2

    fun read(bytes: ByteArray): Lexicon {
      val b = ByteBuffer.wrap(bytes)
      require(b.int == MAGIC) { "Not a Moon Keyboard lexicon" }
      require(b.int == VERSION) { "Unsupported lexicon version" }
      val n = b.int
      val nodeChar = CharArray(n).also { b.asCharBuffer().get(it); b.skip(n * 2) }
      val firstChild = b.ints(n + 1)
      val nodeWord = b.ints(n)
      val subtreeBest = ShortArray(n).also { b.asShortBuffer().get(it); b.skip(n * 2) }
      val w = b.int
      val text = ByteArray(b.int).also { b.get(it) }.toString(Charsets.UTF_8)
      val wordStart = b.ints(w + 1)
      val freq = ByteArray(w).also { b.get(it) }
      val flags = ByteArray(w).also { b.get(it) }
      val wl = b.int
      val whitelistFrom = b.ints(wl)
      val whitelistTo = b.ints(wl)
      val pairs = b.int
      val bigramStart = b.ints(w + 2)
      val bigramNext = b.ints(pairs)
      val bigramCost = ByteArray(pairs).also { b.get(it) }
      val contexts = b.int
      val triples = b.int
      val trigramKeys = LongArray(contexts).also { b.asLongBuffer().get(it); b.skip(contexts * 8) }
      val trigramStart = b.ints(contexts + 1)
      val trigramNext = b.ints(triples)
      val trigramCost = ByteArray(triples).also { b.get(it) }
      return Lexicon(
        nodeChar, firstChild, nodeWord, subtreeBest, text, wordStart, freq, flags,
        whitelistFrom, whitelistTo, bigramStart, bigramNext, bigramCost,
        trigramKeys, trigramStart, trigramNext, trigramCost,
      )
    }

    private fun ByteBuffer.ints(count: Int) = IntArray(count).also { asIntBuffer().get(it); skip(count * 4) }

    private fun ByteBuffer.skip(bytes: Int) {
      position(position() + bytes)
    }
  }
}
