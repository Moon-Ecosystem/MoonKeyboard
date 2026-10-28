package com.moonkeyboard.engine.text

import java.text.BreakIterator

/**
 * Length in chars of the last user-perceived character, so one backspace removes a whole emoji
 * (with skin tone or ZWJ sequence) or a letter with its combining accent.
 */
fun lastGraphemeLength(text: CharSequence): Int {
  if (text.isEmpty()) return 0
  val s = text.toString()
  val it = BreakIterator.getCharacterInstance().apply { setText(s) }
  val start = it.preceding(s.length)
  return s.length - if (start == BreakIterator.DONE) 0 else start
}

/** The index one user-perceived character after (or before) [index] in [text], staying inside it. */
fun graphemeStep(text: CharSequence, index: Int, forward: Boolean): Int {
  val s = text.toString()
  val i = index.coerceIn(0, s.length)
  val it = BreakIterator.getCharacterInstance().apply { setText(s) }
  val next = when {
    forward && i < s.length -> it.following(i)
    !forward && i > 0 -> it.preceding(i)
    else -> BreakIterator.DONE
  }
  return if (next == BreakIterator.DONE) i else next
}
