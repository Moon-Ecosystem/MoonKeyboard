package com.moonkeyboard.engine.suggest

/**
 * What comes before the word being typed: the previous word (or the start of a sentence), and the
 * word before that (or whether the previous word itself started the sentence).
 */
data class WordContext(
  val prev: String?,
  val sentenceStart: Boolean,
  val prevPrev: String? = null,
  val prevIsFirst: Boolean = false,
  /** The words before, lower case, [START_TOKEN] at sentence boundaries: the neural model's context. */
  val recent: List<String> = emptyList(),
) {
  /** [recent], or what [prev] and [prevPrev] tell when it wasn't filled in. */
  val tokens: List<String> get() = when {
    recent.isNotEmpty() -> recent
    sentenceStart || prev == null -> listOf(START_TOKEN)
    else -> listOfNotNull(if (prevIsFirst) START_TOKEN else prevPrev?.lowercase(), prev.lowercase())
  }

  /** The previous word as the user model keys it: lower case, [START] at a sentence start. */
  val userKey: String? get() = if (sentenceStart) START_TOKEN else prev?.lowercase()

  /** The word before that, keyed the same way; null when unknown. */
  val userKey2: String? get() = when {
    prev == null -> null
    prevIsFirst -> START_TOKEN
    else -> prevPrev?.lowercase()
  }

  /** The context for the word after [word] has been typed in this one. */
  fun then(word: String) = WordContext(word, false, prevPrev = prev, prevIsFirst = sentenceStart, recent = tokens + word.lowercase())

  companion object {
    val START = WordContext(null, true)
    const val START_TOKEN = "<s>"

    /** [textBefore] is the text before the current word (excluding the word itself). */
    fun from(textBefore: CharSequence): WordContext {
      val (prev, start, boundary) = lastWord(textBefore, textBefore.length) ?: return START
      val recent = tokensOf(textBefore)
      if (prev == null) return WordContext(null, boundary, recent = recent)
      // Look one word further back
      val before = lastWord(textBefore, start)
      return when {
        before == null -> WordContext(prev, false, prevIsFirst = true, recent = recent)
        before.first == null -> WordContext(prev, false, prevIsFirst = before.third, recent = recent)
        else -> WordContext(prev, false, prevPrev = before.first, recent = recent)
      }
    }

    /**
     * Words as the neural model was trained on them: lower case, sentence ends and line breaks as
     * [START_TOKEN], other punctuation dropped. shortcut: a text cut off mid-sentence still starts
     * with [START_TOKEN]; the model keeps only the last words, so it rarely reaches that far.
     */
    fun tokensOf(text: CharSequence): List<String> {
      val out = arrayListOf(START_TOKEN)
      for (m in TOKEN.findAll(text)) {
        val t = m.value
        if (t[0] in SENTENCE_END) {
          if (out.last() != START_TOKEN) out += START_TOKEN
        } else {
          out += t.replace('’', '\'').lowercase()
        }
      }
      return out
    }

    private val TOKEN = Regex("[\\p{L}\\p{N}]+(?:['’]\\p{L}+)*|[.?!\\n]")

    /**
     * The word ending at [end] (skipping spaces), its start index, and whether a sentence boundary
     * sits there instead. Null when nothing precedes it at all.
     */
    private fun lastWord(text: CharSequence, end: Int): Triple<String?, Int, Boolean>? {
      var e = end
      while (e > 0 && text[e - 1] == ' ') e--
      if (e == 0) return null
      val last = text[e - 1]
      if (last in SENTENCE_END) return Triple(null, e, true)
      if (!isWordChar(last)) return Triple(null, e, false)
      var s = e
      while (s > 0 && isWordChar(text[s - 1])) s--
      return Triple(text.substring(s, e).trim('\''), s, false)
    }

    private const val SENTENCE_END = ".?!\n"
  }
}

/** Letters, plus apostrophes inside a word ("don't"). */
fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\''
