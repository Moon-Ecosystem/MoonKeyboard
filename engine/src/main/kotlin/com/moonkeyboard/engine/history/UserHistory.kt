package com.moonkeyboard.engine.history

/**
 * What this user types: word counts, word-pair counts and words they asked us to forget. Kept
 * until they delete it. Thread-safe: the decoder reads it off the main thread.
 */
class UserHistory {
  private val words = HashMap<String, Int>()
  private val pairs = HashMap<String, HashMap<String, Int>>()
  private val blocked = HashSet<String>()

  /** Bumped on every change so callers know when to save. */
  @Volatile
  var version = 0L
    private set

  @Synchronized
  fun record(prev: String?, word: String, weight: Int = 1) {
    if (word.isBlank()) return
    blocked.remove(word)
    words[word] = (words[word] ?: 0) + weight
    if (prev != null) {
      val next = pairs.getOrPut(prev) { HashMap() }
      next[word] = (next[word] ?: 0) + weight
    }
    version++
  }

  @Synchronized
  fun count(word: String): Int = words[word] ?: 0

  @Synchronized
  fun pairCount(prev: String, word: String): Int = pairs[prev]?.get(word) ?: 0

  @Synchronized
  fun isBlocked(word: String): Boolean = word in blocked

  /** Removes the word everywhere and stops it being suggested again until typed again. */
  @Synchronized
  fun forget(word: String) {
    words.remove(word)
    pairs.remove(word)
    pairs.values.forEach { it.remove(word) }
    blocked += word
    version++
  }

  @Synchronized
  fun clear() {
    words.clear()
    pairs.clear()
    blocked.clear()
    version++
  }

  /** Words typed at least [minCount] times, for matching against touches. */
  @Synchronized
  fun wordsAtLeast(minCount: Int): List<Pair<String, Int>> = words.filter { it.value >= minCount }.toList()

  @Synchronized
  fun successors(prev: String): List<Pair<String, Int>> = pairs[prev]?.toList().orEmpty()

  /** Tab-separated lines: `w word count`, `p prev word count`, `x word` (blocked). */
  @Synchronized
  fun serialize(): String = buildString {
    words.forEach { (w, c) -> append("w\t").append(w).append('\t').append(c).append('\n') }
    pairs.forEach { (p, next) -> next.forEach { (w, c) -> append("p\t").append(p).append('\t').append(w).append('\t').append(c).append('\n') } }
    blocked.forEach { append("x\t").append(it).append('\n') }
  }

  companion object {
    fun parse(text: String): UserHistory = UserHistory().apply {
      for (line in text.lineSequence()) {
        val f = line.split('\t')
        when {
          f[0] == "w" && f.size == 3 -> f[2].toIntOrNull()?.let { words[f[1]] = it }
          f[0] == "p" && f.size == 4 -> f[3].toIntOrNull()?.let { pairs.getOrPut(f[1]) { HashMap() }[f[2]] = it }
          f[0] == "x" && f.size == 2 -> blocked += f[1]
        }
      }
    }
  }
}
