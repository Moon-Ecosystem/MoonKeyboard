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
 *
 * See tools/fetch-data.sh for where the inputs come from.
 */
fun main(args: Array<String>) {
  val opts = args.toList().chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
  val words = File(opts["words"] ?: usage())
  val sentences = opts["sentences"]?.let(::File)
  val out = File(opts["out"] ?: usage())

  val builder = LexiconBuilder()
  readAospWordList(words, builder)
  println("words: ${builder.size}")
  if (sentences != null) addPairs(sentences, builder)

  val lexicon = builder.build()
  out.parentFile?.mkdirs()
  out.outputStream().use(lexicon::write)
  println("nodes: ${lexicon.nodeCount}, wrote ${out.length() / 1024} KiB to $out")
}

private fun usage(): Nothing {
  System.err.println("usage: --words <aosp.combined.gz> [--sentences <tatoeba.tsv>] --out <file>")
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
 * [PER_WORD] most common successors seen at least [MIN_COUNT] times.
 */
private fun addPairs(file: File, builder: LexiconBuilder) {
  val pairs = LongCounter()
  val totals = HashMap<Int, Int>()
  var sentences = 0
  for (line in lines(file)) {
    val text = line.substringAfterLast('\t')
    var prev: Int? = Lexicon.SENTENCE_START
    for (m in TOKEN.findAll(text)) {
      val token = m.value.replace('’', '\'')
      when {
        token in ".!?" -> { prev = Lexicon.SENTENCE_START; continue }
        !token[0].isLetterOrDigit() -> { prev = null; continue }
      }
      val id = lookup(builder, token)?.takeUnless { isName(builder, it) }
      val p = prev
      if (p != null) {
        totals[p] = (totals[p] ?: 0) + 1
        if (id != null) pairs.increment(key(p, id))
      }
      prev = id
    }
    sentences++
  }
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

private fun key(prev: Int, next: Int): Long = (prev.toLong() shl 32) or (next.toLong() and 0xFFFFFFFFL)

private const val MIN_COUNT = 2
private const val PER_WORD = 40

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
