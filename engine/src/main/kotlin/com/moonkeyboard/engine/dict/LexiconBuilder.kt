package com.moonkeyboard.engine.dict

import java.util.TreeMap
import kotlin.math.roundToInt

/** Assembles a [Lexicon]. Used by the build tool and by tests that need a small dictionary. */
class LexiconBuilder {
  private val words = ArrayList<String>()
  private val ids = HashMap<String, Int>()
  private val freqs = ArrayList<Int>()
  private val flags = ArrayList<Int>()
  private val whitelist = TreeMap<Int, Int>()
  private val bigrams = HashMap<Int, MutableMap<Int, Float>>()

  val size: Int get() = words.size

  fun id(word: String): Int? = ids[word]

  fun word(id: Int): String = words[id]

  /** Adds or updates a word; [frequency] is 0–255 on the AOSP log scale. Returns its id. */
  fun addWord(word: String, frequency: Int, flags: Int = 0): Int {
    require(word.isNotEmpty() && '\u0000' !in word) { "Bad word '$word'" }
    val f = frequency.coerceIn(0, 255)
    ids[word]?.let { id ->
      freqs[id] = maxOf(freqs[id], f)
      this.flags[id] = this.flags[id] and flags
      return id
    }
    words += word
    freqs += f
    this.flags += flags
    return (words.size - 1).also { ids[word] = it }
  }

  fun whitelist(from: Int, to: Int) {
    whitelist[from] = to
  }

  /** ln P(next | prev); [prev] may be [Lexicon.SENTENCE_START]. */
  fun bigram(prev: Int, next: Int, logProb: Float) {
    bigrams.getOrPut(prev) { HashMap() }[next] = logProb
  }

  fun build(): Lexicon {
    class Node {
      val children = TreeMap<Char, Node>()
      var word = -1
    }

    val root = Node()
    words.forEachIndexed { id, w ->
      var node = root
      for (c in w) node = node.children.getOrPut(c) { Node() }
      node.word = id
    }

    // Breadth-first numbering puts each node's children next to each other
    val order = ArrayList<Node>().apply { add(root) }
    val firstChildList = ArrayList<Int>()
    val charList = ArrayList<Char>().apply { add('\u0000') }
    var i = 0
    while (i < order.size) {
      firstChildList += order.size
      for ((c, child) in order[i].children) {
        order += child
        charList += c
      }
      i++
    }
    val n = order.size
    val firstChild = IntArray(n + 1) { if (it < n) firstChildList[it] else n }
    val nodeWord = IntArray(n) { order[it].word }
    val subtreeBest = ShortArray(n)
    for (node in n - 1 downTo 0) {
      val w = nodeWord[node]
      var best = if (w >= 0 && flags[w] and (Lexicon.NOT_A_WORD or Lexicon.NO_SUGGEST) == 0) freqs[w] + 1 else 0
      for (c in firstChild[node] until firstChild[node + 1]) best = maxOf(best, subtreeBest[c].toInt())
      subtreeBest[node] = best.toShort()
    }

    val text = StringBuilder()
    val wordStart = IntArray(words.size + 1)
    words.forEachIndexed { id, w ->
      wordStart[id] = text.length
      text.append(w)
    }
    wordStart[words.size] = text.length

    val bigramStart = IntArray(words.size + 2)
    val next = ArrayList<Int>()
    val cost = ArrayList<Byte>()
    for (p in 0..words.size) {
      bigramStart[p] = next.size
      val key = if (p == words.size) Lexicon.SENTENCE_START else p
      bigrams[key]?.toSortedMap()?.forEach { (id, lp) ->
        next += id
        cost += (-lp * Lexicon.COST_SCALE).roundToInt().coerceIn(0, 255).toByte()
      }
    }
    bigramStart[words.size + 1] = next.size

    return Lexicon(
      nodeChar = CharArray(n) { charList[it] },
      firstChild = firstChild,
      nodeWord = nodeWord,
      subtreeBest = subtreeBest,
      wordText = text.toString(),
      wordStart = wordStart,
      freq = ByteArray(words.size) { freqs[it].toByte() },
      flags = ByteArray(words.size) { flags[it].toByte() },
      whitelistFrom = whitelist.keys.toIntArray(),
      whitelistTo = whitelist.values.toIntArray(),
      bigramStart = bigramStart,
      bigramNext = next.toIntArray(),
      bigramCost = cost.toByteArray(),
    )
  }
}
