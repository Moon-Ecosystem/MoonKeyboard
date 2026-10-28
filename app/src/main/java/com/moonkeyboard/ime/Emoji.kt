package com.moonkeyboard.ime

import android.content.Context
import android.graphics.Paint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** An emoji, its skin-tone variants after it (if any), and the lower-case words search matches. */
class EmojiEntry(val variants: List<String>, val words: List<String>) {
  val emoji: String get() = variants[0]
}

class EmojiGroup(val name: String, val entries: List<EmojiEntry>)

/** The emoji list from assets/emoji.txt (built by tools/BuildEmoji.kt), minus what this phone's font can't draw. */
object Emoji {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val loaded = MutableStateFlow<List<EmojiGroup>?>(null)
  private var started = false

  val groups: StateFlow<List<EmojiGroup>?> = loaded

  val recents: List<String> get() = Prefs.emojiRecents.value.split(' ').filter { it.isNotEmpty() }

  @Synchronized
  fun load(context: Context) {
    if (started) return
    started = true
    val assets = context.applicationContext.assets
    scope.launch {
      val paint = Paint()
      val groups = ArrayList<EmojiGroup>()
      var name = ""
      var entries = ArrayList<EmojiEntry>()
      fun flush() {
        if (entries.isNotEmpty()) groups += EmojiGroup(name, entries)
        entries = ArrayList()
      }
      assets.open("emoji.txt").bufferedReader().useLines { lines ->
        for (line in lines) {
          if (line.startsWith("=")) {
            flush()
            name = line.drop(1)
          } else if (line.isNotEmpty()) {
            // Older Android versions lack the newest emoji; drawing them would show empty boxes
            val variants = line.substringBefore('\t').split(' ').filter(paint::hasGlyph)
            if (variants.isNotEmpty()) entries += EmojiEntry(variants, line.substringAfter('\t', "").split('|'))
          }
        }
      }
      flush()
      // Whole keywords only ("pizza", "laugh"), so the strip offers emoji for words that clearly mean one
      val byWord = HashMap<String, MutableList<String>>()
      for (e in groups.flatMap { it.entries }) for (w in e.words) byWord.getOrPut(w) { ArrayList() } += e.emoji
      // A word tagging dozens of emoji ("face", "hand") says little about which one is meant
      keywordIndex = byWord.filterValues { it.size <= MAX_PER_KEYWORD }
      loaded.value = groups
    }
  }

  @Volatile private var keywordIndex: Map<String, List<String>> = emptyMap()

  /** Emoji tagged with exactly [word], for the suggestion strip. */
  fun forWord(word: String): List<String> =
    if (word.length < MIN_STRIP_WORD) emptyList() else keywordIndex[word.lowercase()].orEmpty().take(MAX_STRIP)

  /**
   * Emoji whose name or keywords match [query]: a word starting with it first ("joy" finds 😂),
   * then words merely containing it. Every word of a multi-word query must match.
   */
  fun search(query: String): List<EmojiEntry> {
    val terms = query.lowercase().split(' ').filter { it.isNotEmpty() }
    if (terms.isEmpty()) return emptyList()
    val all = loaded.value.orEmpty().flatMap { it.entries }
    fun rank(e: EmojiEntry): Int? {
      var worst = 0
      for (t in terms) {
        val words = e.words.flatMap { it.split(' ', ':', '-') }
        worst = maxOf(worst, when {
          words.any { it == t } -> 0
          words.any { it.startsWith(t) } -> 1
          e.words.any { t in it } -> 2
          else -> return null
        })
      }
      return worst
    }
    return all.mapNotNull { e -> rank(e)?.let { e to it } }.sortedBy { it.second }.map { it.first }.take(MAX_RESULTS)
  }

  fun used(emoji: String) {
    Prefs.emojiRecents.value = (listOf(emoji) + recents.filter { it != emoji }).take(MAX_RECENTS).joinToString(" ")
  }

  private const val MAX_RECENTS = 27
  private const val MAX_RESULTS = 40
  private const val MAX_STRIP = 2
  private const val MIN_STRIP_WORD = 3
  private const val MAX_PER_KEYWORD = 8
}
