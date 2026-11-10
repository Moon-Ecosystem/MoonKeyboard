package com.moonkeyboard.engine.history

/** A learned three-word phrase: [word] typed after [prev2] [prev], [count] times. */
data class LearnedPhrase(val prev2: String, val prev: String, val word: String, val count: Int)

/**
 * What this user types: word counts, what follows each word and each pair of words, and words
 * they asked us to forget. Kept until they delete it. Thread-safe: the decoder reads it off the
 * main thread. Context keys are whatever the caller passes (WordContext.userKey: lower case, "<s>"
 * for a sentence start).
 */
class UserHistory {
  // Insertion-ordered so the Learned screen can show what was typed most recently first
  private val words = LinkedHashMap<String, Int>()
  private val pairs = HashMap<String, HashMap<String, Int>>()
  private val triples = HashMap<String, HashMap<String, Int>>()
  private val blocked = HashSet<String>()

  /** The user's text shortcuts ("omw" → "on my way"), keyed by the shortcut in lower case. */
  private val shortcuts = LinkedHashMap<String, String>()

  /** Email addresses typed in email fields (or added by hand), keyed in lower case, with how often each was used. */
  private val emails = HashMap<String, Int>()
  private var total = 0

  /** Bumped on every change so callers know when to save. */
  @Volatile
  var version = 0L
    private set

  @Synchronized
  fun record(prev: String?, word: String, weight: Int = 1, prev2: String? = null) {
    if (word.isBlank()) return
    blocked.remove(word)
    // Remove then re-insert so the latest word always sits at the end of the ordered map
    val count = (words.remove(word) ?: 0) + weight
    words[word] = count
    total += weight
    if (prev != null) {
      pairs.getOrPut(prev) { HashMap() }.merge(word, weight, Int::plus)
      if (prev2 != null) triples.getOrPut(tripleKey(prev2, prev)) { HashMap() }.merge(word, weight, Int::plus)
    }
    version++
  }

  @Synchronized
  fun count(word: String): Int = words[word] ?: 0

  /** All words typed, counting repeats. */
  @Synchronized
  fun totalCount(): Int = total

  @Synchronized
  fun pairCount(prev: String, word: String): Int = pairs[prev]?.get(word) ?: 0

  /** How many words this user has typed after [prev]. */
  @Synchronized
  fun pairTotal(prev: String): Int = pairs[prev]?.values?.sum() ?: 0

  /** How many different words this user has typed after [prev]. */
  @Synchronized
  fun pairTypes(prev: String): Int = pairs[prev]?.size ?: 0

  @Synchronized
  fun tripleTypes(prev2: String, prev: String): Int = triples[tripleKey(prev2, prev)]?.size ?: 0

  @Synchronized
  fun tripleCount(prev2: String, prev: String, word: String): Int = triples[tripleKey(prev2, prev)]?.get(word) ?: 0

  @Synchronized
  fun tripleTotal(prev2: String, prev: String): Int = triples[tripleKey(prev2, prev)]?.values?.sum() ?: 0

  @Synchronized
  fun isBlocked(word: String): Boolean = word in blocked

  /** Removes the word everywhere and stops it being suggested again until typed again. */
  @Synchronized
  fun forget(word: String) {
    total -= words.remove(word) ?: 0
    for (map in listOf(pairs, triples)) {
      map.values.forEach { it.remove(word) }
      map.values.removeAll { it.isEmpty() }
    }
    blocked += word
    version++
  }

  /** Stops predicting [word] after [prev2] [prev] (and after [prev] alone); the words themselves stay learned. */
  @Synchronized
  fun forgetPhrase(prev2: String, prev: String, word: String) {
    val key = tripleKey(prev2, prev)
    triples[key]?.let { next -> next.remove(word); if (next.isEmpty()) triples.remove(key) }
    pairs[prev]?.let { next -> next.remove(word); if (next.isEmpty()) pairs.remove(prev) }
    version++
  }

  /** Lets a forgotten word be suggested again. */
  @Synchronized
  fun unblock(word: String) {
    if (blocked.remove(word)) version++
  }

  /** Every learned word with how often it was typed, most recently typed first. */
  @Synchronized
  fun learnedWords(): List<Pair<String, Int>> = words.toList().asReversed()

  /** Three-word phrases used at least [minCount] times, most used first. */
  @Synchronized
  fun learnedPhrases(minCount: Int = 2): List<LearnedPhrase> = triples.flatMap { (key, next) ->
    val (prev2, prev) = key.split(SEP)
    next.filter { it.value >= minCount }.map { (word, count) -> LearnedPhrase(prev2, prev, word, count) }
  }.sortedByDescending { it.count }

  @Synchronized
  fun blockedWords(): List<String> = blocked.sorted()

  /** What [typed] expands to, if it's one of the user's shortcuts (any capitalisation). */
  @Synchronized
  fun shortcut(typed: String): String? = shortcuts[typed.lowercase()]

  /** Adds or replaces a shortcut; one word in, any text out. */
  @Synchronized
  fun setShortcut(shortcut: String, expansion: String) {
    val key = shortcut.trim().lowercase()
    val text = expansion.trim()
    if (key.isEmpty() || key.any(Char::isWhitespace) || text.isEmpty() || '\t' in text || '\n' in text) return
    shortcuts.remove(key)
    shortcuts[key] = text
    version++
  }

  @Synchronized
  fun removeShortcut(shortcut: String) {
    if (shortcuts.remove(shortcut.lowercase()) != null) version++
  }

  /** Every shortcut with its text, newest first. */
  @Synchronized
  fun shortcuts(): List<Pair<String, String>> = shortcuts.toList().asReversed()

  /** Counts one more use of [address], saving it first if new; anything that isn't an email address is ignored. */
  @Synchronized
  fun recordEmail(address: String) {
    val a = address.trim().lowercase()
    if (!isEmail(a)) return
    emails.merge(a, 1, Int::plus)
    version++
  }

  @Synchronized
  fun removeEmail(address: String) {
    if (emails.remove(address.lowercase()) != null) version++
  }

  /** Saved addresses, most used first. */
  @Synchronized
  fun emails(): List<Pair<String, Int>> = emails.toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

  /** The most used addresses that start with [prefix] (all of them for an empty one), the exact match left out. */
  @Synchronized
  fun emailsStartingWith(prefix: String, limit: Int = 3): List<String> {
    val p = prefix.lowercase()
    return emails().map { it.first }.filter { it.startsWith(p) && it != p }.take(limit)
  }

  /** Forgets everything learned from typing, saved emails included; shortcuts the user made themselves stay. */
  @Synchronized
  fun clear() {
    emails.clear()
    words.clear()
    pairs.clear()
    triples.clear()
    blocked.clear()
    total = 0
    version++
  }

  /** Words typed at least [minCount] times, for matching against touches. */
  @Synchronized
  fun wordsAtLeast(minCount: Int): List<Pair<String, Int>> = words.filter { it.value >= minCount }.toList()

  @Synchronized
  fun successors(prev: String): List<Pair<String, Int>> = pairs[prev]?.toList().orEmpty()

  @Synchronized
  fun successors(prev2: String, prev: String): List<Pair<String, Int>> = triples[tripleKey(prev2, prev)]?.toList().orEmpty()

  /** Tab-separated lines: `w word count`, `p prev word count`, `t prev2 prev word count`, `x word` (blocked), `s shortcut text`, `e address count`. */
  @Synchronized
  fun serialize(): String = buildString {
    words.forEach { (w, c) -> append("w\t").append(w).append('\t').append(c).append('\n') }
    pairs.forEach { (p, next) -> next.forEach { (w, c) -> append("p\t").append(p).append('\t').append(w).append('\t').append(c).append('\n') } }
    triples.forEach { (k, next) ->
      val (p2, p) = k.split(SEP)
      next.forEach { (w, c) -> append("t\t").append(p2).append('\t').append(p).append('\t').append(w).append('\t').append(c).append('\n') }
    }
    blocked.forEach { append("x\t").append(it).append('\n') }
    shortcuts.forEach { (k, v) -> append("s\t").append(k).append('\t').append(v).append('\n') }
    emails.forEach { (a, c) -> append("e\t").append(a).append('\t').append(c).append('\n') }
  }

  companion object {
    private const val SEP = '\u0001'
    private val EMAIL = Regex("""[^\s@]+@[^\s@]+\.[^\s@]+""")

    /** Shaped like an email address: something@domain.tld, no spaces. */
    fun isEmail(text: String) = EMAIL.matches(text)
    private fun tripleKey(prev2: String, prev: String) = "$prev2$SEP$prev"

    fun parse(text: String): UserHistory = UserHistory().apply {
      for (line in text.lineSequence()) {
        val f = line.split('\t')
        when {
          f[0] == "w" && f.size == 3 -> f[2].toIntOrNull()?.let { words[f[1]] = it; total += it }
          f[0] == "p" && f.size == 4 -> f[3].toIntOrNull()?.let { pairs.getOrPut(f[1]) { HashMap() }[f[2]] = it }
          f[0] == "t" && f.size == 5 -> f[4].toIntOrNull()?.let { triples.getOrPut(tripleKey(f[1], f[2])) { HashMap() }[f[3]] = it }
          f[0] == "x" && f.size == 2 -> blocked += f[1]
          f[0] == "s" && f.size == 3 -> shortcuts[f[1]] = f[2]
          f[0] == "e" && f.size == 3 -> f[2].toIntOrNull()?.let { emails[f[1]] = it }
        }
      }
    }
  }
}
