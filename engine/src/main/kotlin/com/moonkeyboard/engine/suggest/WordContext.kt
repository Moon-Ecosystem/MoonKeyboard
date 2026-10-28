package com.moonkeyboard.engine.suggest

/** What comes before the word being typed: the previous word, or the start of a sentence. */
data class WordContext(val prev: String?, val sentenceStart: Boolean) {
  companion object {
    val START = WordContext(null, true)

    /** [textBefore] is the text before the current word (excluding the word itself). */
    fun from(textBefore: CharSequence): WordContext {
      var end = textBefore.length
      while (end > 0 && textBefore[end - 1] == ' ') end--
      if (end == 0) return START
      val last = textBefore[end - 1]
      if (last in SENTENCE_END) return START
      if (!isWordChar(last)) return WordContext(null, false)
      var start = end
      while (start > 0 && isWordChar(textBefore[start - 1])) start--
      return WordContext(textBefore.substring(start, end).trim('\''), false)
    }

    private const val SENTENCE_END = ".?!\n"
  }
}

/** Letters, plus apostrophes inside a word ("don't"). */
fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\''
