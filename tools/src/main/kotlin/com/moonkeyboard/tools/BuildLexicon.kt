package com.moonkeyboard.tools

import com.moonkeyboard.engine.dict.Lexicon
import com.moonkeyboard.engine.dict.LexiconBuilder
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.ln

/**
 * Builds an English language pack:
 *   --words      AOSP LatinIME `.combined(.gz)` word list (words, frequencies, fixed corrections)
 *   --sentences  Tatoeba sentences TSV (id, lang, text) for word-pair counts
 *   --out        lexicon file to write
 *   --eval-out   where to write held-out sentences for accuracy tests (never counted in the pairs)
 *
 * See tools/fetch-data.sh for where the inputs come from.
 */
fun main(args: Array<String>) {
  val opts = args.toList().chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
  val words = File(opts["words"] ?: usage())
  val sentences = opts["sentences"]?.let(::File)
  val out = File(opts["out"] ?: usage())
  val evalOut = opts["eval-out"]?.let(::File)

  val builder = LexiconBuilder()
  readAospWordList(words, builder)
  println("words: ${builder.size}")
  if (sentences != null) {
    val heldOut = addPairs(sentences, builder)
    evalOut?.let {
      it.parentFile?.mkdirs()
      it.writeText(heldOut.joinToString("\n", postfix = "\n"))
      println("held-out test sentences: ${heldOut.size} → $it")
    }
  }

  val lexicon = builder.build()
  out.parentFile?.mkdirs()
  out.outputStream().use(lexicon::write)
  println("nodes: ${lexicon.nodeCount}, wrote ${out.length() / 1024} KiB to $out")
}

private fun usage(): Nothing {
  System.err.println("usage: --words <aosp.combined.gz> [--sentences <tatoeba.tsv>] --out <file> [--eval-out <file>]")
  kotlin.system.exitProcess(2)
}

private fun lines(file: File): Sequence<String> {
  val stream = file.inputStream().let { if (file.name.endsWith(".gz")) GZIPInputStream(it) else it }
  return stream.bufferedReader().lineSequence()
}

/** ` word=x,f=120,...` lines, each optionally followed by `  shortcut=y,f=whitelist` lines. */
private fun readAospWordList(file: File, builder: LexiconBuilder) {
  val whitelist = ArrayList<Pair<Int, String>>()
  var current = -1
  for (line in lines(file)) {
    when {
      line.startsWith(" word=") -> {
        val a = attributes(line)
        val word = a["word"] ?: continue
        val f = a["f"]?.toIntOrNull() ?: continue
        var flags = 0
        if (a["not_a_word"] == "true") flags = flags or Lexicon.NOT_A_WORD
        if (f == 0 || a["possibly_offensive"] == "true") flags = flags or Lexicon.NO_SUGGEST
        current = builder.addWord(word, f, flags)
      }
      line.startsWith("  shortcut=") && current >= 0 -> {
        val a = attributes(line)
        if (a["f"] == "whitelist") a["shortcut"]?.let { whitelist += current to it }
      }
    }
  }
  var linked = 0
  for ((from, target) in whitelist) {
    val to = builder.id(target) ?: continue
    builder.whitelist(from, to)
    linked++
  }
  println("fixed corrections: $linked of ${whitelist.size}")
}

private fun attributes(line: String): Map<String, String> =
  line.trim().split(',').mapNotNull { part ->
    val eq = part.indexOf('=')
    if (eq > 0) part.substring(0, eq) to part.substring(eq + 1) else null
  }.toMap()

private val TOKEN = Regex("""[\p{L}\p{N}]+(?:['’][\p{L}]+)*|[.!?]|[,;:"()]""")

/**
 * Counts which known word follows which (and which words start sentences), keeping the
 * [PER_WORD] most common successors seen at least [MIN_COUNT] times; and the same for pairs of
 * words ([PER_CONTEXT] successors seen at least [MIN_TRIPLE] times).
 */
private fun addPairs(file: File, builder: LexiconBuilder): List<String> {
  val pairs = LongCounter()
  val totals = HashMap<Int, Int>()
  val triples = LongCounter()
  val contextTotals = LongCounter()
  val heldOut = ArrayList<String>()
  var sentences = 0
  for (line in lines(file)) {
    val text = line.substringAfterLast('\t')
    // Every HOLD_OUT-th sentence is test data: kept out of the counts so tests can't have seen it
    if ((line.substringBefore('\t').toIntOrNull() ?: 0) % HOLD_OUT == 0) {
      if (heldOut.size < MAX_HELD_OUT) testSentence(builder, text)?.let { heldOut += it }
      continue
    }
    var prev2: Int? = null
    var prev: Int? = Lexicon.SENTENCE_START
    for (m in TOKEN.findAll(text)) {
      val token = m.value.replace('’', '\'')
      when {
        token in ".!?" -> { prev2 = null; prev = Lexicon.SENTENCE_START; continue }
        !token[0].isLetterOrDigit() -> { prev2 = null; prev = null; continue }
      }
      val id = lookup(builder, token)?.takeUnless { isName(builder, it) }
      val p = prev
      if (p != null) {
        totals[p] = (totals[p] ?: 0) + 1
        if (id != null) pairs.increment(key(p, id))
        val p2 = prev2
        if (p2 != null) {
          contextTotals.increment(key(p2, p))
          if (id != null) triples.increment(key3(p2, p, id))
        }
      }
      prev2 = prev
      prev = id
    }
    sentences++
  }
  addTriples(builder, triples, contextTotals)
  val byPrev = HashMap<Int, MutableList<Pair<Int, Int>>>()
  pairs.forEach { k, count ->
    if (count >= MIN_COUNT) byPrev.getOrPut((k shr 32).toInt()) { ArrayList() } += k.toInt() to count
  }
  var kept = 0
  for ((prev, next) in byPrev) {
    val total = totals.getValue(prev).toDouble()
    for ((id, count) in next.sortedByDescending { it.second }.take(PER_WORD)) {
      builder.bigram(prev, id, ln(count / total).toFloat())
      kept++
    }
  }
  println("sentences: $sentences, distinct pairs: ${pairs.size}, kept: $kept")
  return heldOut
}

/**
 * A held-out sentence as space-separated words, or null if it isn't a fair test: every word must
 * be in the dictionary (a keyboard can't be blamed for words it has never heard of), no digits,
 * no names, 3–12 words.
 */
private fun testSentence(builder: LexiconBuilder, text: String): String? {
  val words = TOKEN.findAll(text).map { it.value.replace('’', '\'') }.filter { it[0].isLetterOrDigit() }.toList()
  if (words.size !in 3..12) return null
  for (w in words) {
    if (w.any { it.isDigit() }) return null
    val id = lookup(builder, w) ?: return null
    if (isName(builder, id) || !builder.isWord(id)) return null
  }
  return words.joinToString(" ")
}

/** Sentence-initial capitals fold to the dictionary's form: "The" → "the", but "I" stays "I". */
private fun lookup(builder: LexiconBuilder, token: String): Int? =
  builder.id(token) ?: builder.id(token.lowercase()) ?: builder.id(token.lowercase().replaceFirstChar(Char::uppercaseChar))

/**
 * Tatoeba's example sentences are full of "Tom" and "Mary"; left in, every prediction is a name.
 * Capitalised dictionary words other than the "I" family are treated as names and dropped.
 */
private fun isName(builder: LexiconBuilder, id: Int): Boolean {
  val word = builder.word(id)
  return word[0].isUpperCase() && word != "I" && !word.startsWith("I'")
}

private fun addTriples(builder: LexiconBuilder, triples: LongCounter, contextTotals: LongCounter) {
  val byContext = HashMap<Long, MutableList<Pair<Int, Int>>>()
  triples.forEach { k, count ->
    if (count >= MIN_TRIPLE) byContext.getOrPut(k ushr 21) { ArrayList() } += (k and MASK21).toInt() to count
  }
  var kept = 0
  for ((context, next) in byContext) {
    val w1 = unpack21((context ushr 21).toInt())
    val w2 = unpack21((context and MASK21).toInt())
    val total = contextTotals.get(key(w1, w2)).toDouble()
    for ((id, count) in next.sortedByDescending { it.second }.take(PER_CONTEXT)) {
      builder.trigram(w1, w2, id, ln(count / total).toFloat())
      kept++
    }
  }
  println("two-word contexts: ${byContext.size}, kept triples: $kept")
}

private fun key(prev: Int, next: Int): Long = (prev.toLong() shl 32) or (next.toLong() and 0xFFFFFFFFL)

// Three word ids in one Long, 21 bits each (fine for < 2M words); sentence start gets the top value
private const val MASK21 = (1L shl 21) - 1
private fun pack21(id: Int): Long = if (id == Lexicon.SENTENCE_START) MASK21 else id.toLong()
private fun unpack21(v: Int): Int = if (v.toLong() == MASK21) Lexicon.SENTENCE_START else v
private fun key3(a: Int, b: Int, c: Int): Long = (pack21(a) shl 42) or (pack21(b) shl 21) or pack21(c)

private const val MIN_COUNT = 2
private const val PER_WORD = 40
private const val MIN_TRIPLE = 3
private const val PER_CONTEXT = 10
private const val HOLD_OUT = 50
private const val MAX_HELD_OUT = 3000

/** Long → count with open addressing; a boxed HashMap would need ~10× the memory for millions of pairs. */
private class LongCounter {
  private var keys = LongArray(1 shl 20)
  private var counts = IntArray(1 shl 20)
  private var used = BooleanArray(1 shl 20)
  var size = 0
    private set

  fun increment(k: Long) {
    if (size * 2 >= keys.size) grow()
    val i = slot(k)
    if (!used[i]) {
      used[i] = true
      keys[i] = k
      size++
    }
    counts[i]++
  }

  fun get(k: Long): Int = slot(k).let { if (used[it]) counts[it] else 0 }

  fun forEach(action: (Long, Int) -> Unit) {
    for (i in keys.indices) if (used[i]) action(keys[i], counts[i])
  }

  private fun slot(k: Long): Int {
    val mask = keys.size - 1
    var i = (java.lang.Long.hashCode(k * -0x61c8864680b583ebL) and mask)
    while (used[i] && keys[i] != k) i = (i + 1) and mask
    return i
  }

  private fun grow() {
    val oldKeys = keys
    val oldCounts = counts
    val oldUsed = used
    keys = LongArray(oldKeys.size * 2)
    counts = IntArray(oldKeys.size * 2)
    used = BooleanArray(oldKeys.size * 2)
    for (i in oldKeys.indices) {
      if (!oldUsed[i]) continue
      val j = slot(oldKeys[i])
      used[j] = true
      keys[j] = oldKeys[i]
      counts[j] = oldCounts[i]
    }
  }
}
