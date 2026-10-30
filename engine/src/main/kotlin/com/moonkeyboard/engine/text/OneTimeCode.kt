package com.moonkeyboard.engine.text

/**
 * The one-time code in a copied message ("G-482913 is your verification code"), or null when there
 * is no clear one. A code is 4–8 digits standing on its own; "482 913" and "482-913" count as one.
 * With several candidates ("482913 is your OTP for Rs 5000"), the one nearest a word like "code" or
 * "OTP" wins; with no such word, none does.
 */
fun oneTimeCode(text: String): String? {
  val candidates = CANDIDATE.findAll(text).filter { m -> m.value.count(Char::isDigit) in 4..8 }.toList()
  val codes = candidates.map { m -> m.value.filter(Char::isDigit) }.distinct()
  if (codes.size <= 1) return codes.singleOrNull()
  val keywords = KEYWORD.findAll(text).map { it.range }.toList()
  if (keywords.isEmpty()) return null
  fun distance(a: IntRange, b: IntRange) = maxOf(0, maxOf(a.first, b.first) - minOf(a.last, b.last))
  return candidates.minBy { m -> keywords.minOf { distance(it, m.range) } }.value.filter(Char::isDigit)
}

// Digits not glued to letters, dates, times or amounts ("30/10", "10:45", "1,250.00"); "G-482913" still counts
private val CANDIDATE = Regex("""(?<![\w/:.,])(?:\d{3,4}[ -]\d{3,4}|\d+)(?![\w/:]|[.,]\d)""")
private val KEYWORD = Regex("""code|otp|pin|passcode|password|verif|one[ -]time|token""", RegexOption.IGNORE_CASE)
