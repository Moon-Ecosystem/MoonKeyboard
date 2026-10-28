package com.moonkeyboard.tools

import com.moonkeyboard.engine.dict.Lexicon
import com.moonkeyboard.engine.dict.LexiconBuilder
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.ln

/**
 * Builds the English + Hinglish language pack:
 *   --words              AOSP LatinIME `.combined(.gz)` word list (words, frequencies, fixed corrections)
 *   --sentences          Tatoeba sentences TSV (id, lang, text) for word-pair counts
 *   --hinglish           L3Cube-HingCorpus lines (one tweet per line, .gz ok): Hinglish words and pairs
 *   --hinglish-lines     how many of its lines feed the pair counts (default 3M; memory grows with it)
 *   --out                lexicon file to write
 *   --eval-out           where to write held-out English sentences for accuracy tests (never counted)
 *   --hinglish-eval-out  the same for Hinglish
 *
 * See tools/fetch-data.sh for where the inputs come from.
 */
fun main(args: Array<String>) {
  val opts = args.toList().chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
  val words = File(opts["words"] ?: usage())
  val sentences = opts["sentences"]?.let(::File)
  val hinglish = opts["hinglish"]?.let(::File)
  val out = File(opts["out"] ?: usage())

  val builder = LexiconBuilder()
  readAospWordList(words, builder)
  println("words: ${builder.size}")
  val ngrams = Ngrams(builder)
  if (sentences != null) writeEval(opts["eval-out"], addTatoeba(sentences, builder, ngrams))
  if (hinglish != null) {
    addHinglishWords(hinglish, builder)
    writeEval(opts["hinglish-eval-out"], addHinglishPairs(hinglish, builder, ngrams, opts["hinglish-lines"]?.toInt() ?: 3_000_000))
  }
  ngrams.finish()

  val lexicon = builder.build()
  out.parentFile?.mkdirs()
  out.outputStream().use(lexicon::write)
  println("nodes: ${lexicon.nodeCount}, wrote ${out.length() / 1024} KiB to $out")
}

private fun usage(): Nothing {
  System.err.println("usage: --words <aosp.combined.gz> [--sentences <tatoeba.tsv>] [--hinglish <hingcorpus.txt.gz>] --out <file> [--eval-out <file>] [--hinglish-eval-out <file>]")
  kotlin.system.exitProcess(2)
}

private fun writeEval(path: String?, heldOut: List<String>) {
  val file = File(path ?: return)
  file.parentFile?.mkdirs()
  file.writeText(heldOut.joinToString("\n", postfix = "\n"))
  println("held-out test sentences: ${heldOut.size} → $file")
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
        if (f == 0) flags = flags or Lexicon.NO_SUGGEST
        // Kept as real words (and in the word-pair model); the app decides whether to suggest them
        if (a["possibly_offensive"] == "true") flags = flags or Lexicon.OFFENSIVE
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
 * words ([PER_CONTEXT] successors seen at least [MIN_TRIPLE] times). Both corpora feed one count,
 * so a Hinglish sentence's English words ("kal meeting hai") link the two languages.
 */
private class Ngrams(private val builder: LexiconBuilder) {
  private val pairs = LongCounter()
  private val totals = HashMap<Int, Int>()
  private val triples = LongCounter()
  private val contextTotals = LongCounter()
  private var sentences = 0

  fun count(text: String) {
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

  fun finish() {
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
  }
}

private fun addTatoeba(file: File, builder: LexiconBuilder, ngrams: Ngrams): List<String> {
  val heldOut = ArrayList<String>()
  for (line in lines(file)) {
    val text = line.substringAfterLast('\t')
    // Every HOLD_OUT-th sentence is test data: kept out of the counts so tests can't have seen it
    if ((line.substringBefore('\t').toIntOrNull() ?: 0) % HOLD_OUT == 0) {
      if (heldOut.size < MAX_HELD_OUT) testSentence(builder, text)?.let { heldOut += it }
      continue
    }
    ngrams.count(text)
  }
  return heldOut
}

private fun words(text: String): List<String> =
  TOKEN.findAll(text).map { it.value.replace('’', '\'') }.filter { it[0].isLetterOrDigit() }.toList()

/** Words only Hindi uses; a tweet with two of them is Hinglish, one without any is taken as English. */
private val ANCHORS = setOf(
  "hai", "hain", "nahi", "nhi", "kya", "ka", "ki", "ke", "ko", "se", "bhi", "tha", "thi", "ye", "yeh",
  "wo", "woh", "aur", "mein", "mai", "kar", "karo", "raha", "rahe", "rha", "rhe", "hu", "hoon", "tum",
  "aap", "mujhe", "kuch", "kyu", "kyun", "toh", "bhai", "yaar", "sab", "koi", "ek", "hota", "hoga", "gaya",
)

private fun isHinglish(words: List<String>) = words.count { it.lowercase() in ANCHORS } >= 2

/** Lower-case Latin letters, no letter three times in a row ("sooo" is emphasis, not a word). */
private val CANDIDATE = Regex("[a-z]{2,20}")
private val STRETCHED = Regex("(.)\\1\\1")

/**
 * Adds the words of Hinglish tweets that English doesn't have, with frequencies on the AOSP
 * scale. A word must turn up in Hinglish tweets [MIN_HINGLISH] times, at least twice as often as in
 * English ones (that keeps out English typos and other languages), and mostly in lower case
 * (capitalised mid-sentence means a name).
 */
private fun addHinglishWords(file: File, builder: LexiconBuilder) {
  // [in Hinglish tweets, elsewhere, capitalised mid-sentence]
  val counts = HashMap<String, IntArray>()
  val english = IntArray(builder.size)
  var englishTokens = 0L
  var hinglishTokens = 0L
  var n = 0
  for (line in lines(file)) {
    val words = words(line)
    val hi = isHinglish(words)
    words.forEachIndexed { i, w ->
      if (hi) hinglishTokens++
      val id = lookup(builder, w)
      if (id != null) {
        if (!hi) { english[id]++; englishTokens++ }
        return@forEachIndexed
      }
      val lower = w.lowercase()
      if (!CANDIDATE.matches(lower) || STRETCHED.containsMatchIn(lower)) return@forEachIndexed
      val c = counts.getOrPut(lower) { IntArray(3) }
      c[if (hi) 0 else 1]++
      if (i > 0 && w[0].isUpperCase()) c[2]++
    }
    // Drop one-offs now and then so the map of every misspelling ever tweeted fits in memory
    if (++n % 1_000_000 == 0) counts.values.removeIf { it[0] + it[1] <= 1 }
  }
  // AOSP frequencies aren't raw probabilities; line ours up with them on common English words
  val offset = english.indices.filter { english[it] >= 500 && builder.isWord(it) && !isName(builder, it) }
    .map { builder.frequency(it) - scale(english[it].toLong(), englishTokens) }.sorted()
    .let { it[it.size / 2] }
  val kept = counts.filter { (_, c) -> c[0] >= MIN_HINGLISH && c[0] >= 2 * c[1] && c[2] * 2 < c[0] + c[1] }
  val candidates = kept.mapValues { (_, c) -> (scale(c[0].toLong(), hinglishTokens) + offset + HINGLISH_BIAS).toInt().coerceIn(1, 254) }
  // A word one edit from a far more common Hinglish word is a typo of it ("hoom" for "hoon"); as a
  // word it would never be autocorrected. Spelling variants are close in frequency ("nhi", "nahi")
  // and stay, and so does anything common enough to be no slip ("nayi" beside "nahi"). English
  // words aren't compared: "ko" is no typo of "to".
  val (added, typos) = candidates.entries.sortedByDescending { it.value }.partition { (word, f) ->
    kept.getValue(word)[0] >= NEVER_A_TYPO || oneEditAway(word).none { (candidates[it] ?: 0) - f >= TYPO_GAP }
  }
  for ((word, f) in added) builder.addWord(word, f, Lexicon.HINGLISH or (if (isOffensive(word)) Lexicon.OFFENSIVE else 0))
  println("hinglish tweets: $n lines, frequency offset %.1f, words added: ${added.size}, typos dropped: ${typos.size}".format(offset))
  println("  most common: " + added.take(40).joinToString(" ") { it.key })
  println("  typos: " + typos.take(40).joinToString(" ") { it.key })
  println("  offensive: " + added.map { it.key }.filter(::isOffensive).joinToString(" "))
}

/** Every string one deletion, swap, substitution or insertion of a–z away from [w]. */
private fun oneEditAway(w: String): Sequence<String> = sequence {
  for (i in w.indices) {
    yield(w.removeRange(i, i + 1))
    if (i + 1 < w.length) yield(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2))
    for (c in 'a'..'z') if (c != w[i]) yield(w.substring(0, i) + c + w.substring(i + 1))
  }
  for (i in 0..w.length) for (c in 'a'..'z') yield(w.substring(0, i) + c + w.substring(i))
}

/** Count [c] of [total] tokens on the AOSP 0–255 log scale (255 = probability 1, steps of 1.15). */
private fun scale(c: Long, total: Long): Double = 255 + ln(c.toDouble() / total) / ln(1.15)

/** Pairs from the first [limit] lines that are Hinglish; every HOLD_OUT-th of them is test data instead. */
private fun addHinglishPairs(file: File, builder: LexiconBuilder, ngrams: Ngrams, limit: Int): List<String> {
  val heldOut = ArrayList<String>()
  var hinglish = 0
  for (line in lines(file).take(limit)) {
    if (!isHinglish(words(line))) continue
    if (++hinglish % HOLD_OUT == 0) {
      if (heldOut.size < MAX_HELD_OUT) testSentence(builder, line)?.let { heldOut += it }
      continue
    }
    ngrams.count(line)
  }
  return heldOut
}

// Hindi abuse as typed in Latin letters: whole words, and stems that only start abusive words
private val OFFENSIVE_WORDS = setOf("bc", "mc", "bkl", "mkc", "tmkc", "bsdk", "bsdke", "mkb", "chut", "gand", "lodu", "loda", "chodu")
private val OFFENSIVE_STEMS = listOf(
  "chutiy", "chutya", "chutia", "choot", "madarch", "maderch", "behench", "behanch", "bhench", "benchod", "bhanchod",
  "bhosd", "bhosad", "lavd", "lawd", "lauda", "laude", "lund", "gaand", "gandu", "randi", "randw", "harami",
  "haramkhor", "jhaat", "jhant", "bhadw", "bhadv", "chinal", "chudai", "chudne", "chudwa", "chodna", "chodne",
  "hijda", "hijde", "hijra", "kutiya", "kamina", "kamine",
)

private fun isOffensive(word: String) = word in OFFENSIVE_WORDS || OFFENSIVE_STEMS.any(word::startsWith)

/**
 * A held-out sentence as space-separated words, or null if it isn't a fair test: every word must
 * be in the dictionary (a keyboard can't be blamed for words it has never heard of), no digits,
 * no names, nothing offensive, 3–12 words.
 */
private fun testSentence(builder: LexiconBuilder, text: String): String? {
  val words = words(text)
  if (words.size !in 3..12) return null
  for (w in words) {
    if (w.any { it.isDigit() }) return null
    val id = lookup(builder, w) ?: return null
    if (isName(builder, id) || !builder.isWord(id) || builder.flags(id) and Lexicon.OFFENSIVE != 0) return null
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
private const val MIN_TRIPLE = 4
private const val PER_CONTEXT = 6
private const val HOLD_OUT = 50
private const val MAX_HELD_OUT = 3000
private const val MIN_HINGLISH = 40
/** Added to Hinglish frequencies on the AOSP scale; below 0 favours English when both fit. */
private const val HINGLISH_BIAS = 0.0
/** 150× more common, on the AOSP scale (ln 150 / ln 1.15); 20× dropped real words ("kis" next to "kisi"). */
private const val TYPO_GAP = 36
/** Times seen in Hinglish tweets beyond which a word is never taken for a typo. */
private const val NEVER_A_TYPO = 1000

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
