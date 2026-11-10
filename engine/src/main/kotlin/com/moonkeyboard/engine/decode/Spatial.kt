package com.moonkeyboard.engine.decode

import com.moonkeyboard.engine.layout.KeyType
import com.moonkeyboard.engine.layout.KeyboardGeometry
import java.text.Normalizer

/**
 * One typed character with where the finger landed. Characters restored from existing text have no
 * position ([x] is NaN) and only match themselves.
 */
data class Touch(val x: Float, val y: Float, val char: Char) {
  val hasPosition: Boolean get() = !x.isNaN()

  companion object {
    fun exact(char: Char) = Touch(Float.NaN, Float.NaN, char)
  }
}

/** Centres of the letter keys, the only geometry the tap decoder needs. */
class KeyMap(private val centers: Map<Char, Pair<Float, Float>>, val keyWidth: Float) {
  /** The letters with keys of their own, lower case: a–z, plus ä, ö, ü on a German keyboard. */
  val letters: CharArray = centers.keys.sorted().toCharArray()

  fun center(c: Char): Pair<Float, Float>? = centers[c]

  /**
   * The key [c] is typed with: its own when the layout has one (ä on a German keyboard), otherwise
   * its base letter's (é is a long press on e).
   */
  fun keyOf(c: Char): Char {
    val l = c.lowercaseChar()
    return if (l in centers) l else baseChar(c)
  }

  /** A touch in the middle of [c]'s key, or an exact touch if the layout has no such key. */
  fun touchFor(c: Char): Touch = centers[keyOf(c)]?.let { (x, y) -> Touch(x, y, c) } ?: Touch.exact(c)

  companion object {
    fun from(geometry: KeyboardGeometry): KeyMap {
      val letters = geometry.keys.filter { it.key.type == KeyType.CHAR && it.key.label.length == 1 && it.key.label[0].isLetter() }
      val width = letters.map { it.width }.average().toFloat()
      return KeyMap(letters.associate { it.key.label[0].lowercaseChar() to (it.centerX to it.centerY) }, width)
    }
  }
}

/** The letter a character is typed with: lower case, accents removed ("É" → 'e'). */
fun baseChar(c: Char): Char {
  val l = c.lowercaseChar()
  if (l in 'a'..'z' || l == '\'') return l
  // ß doesn't decompose, but it's typed and glided on the s key
  if (l == 'ß') return 's'
  return Normalizer.normalize(l.toString(), Normalizer.Form.NFD)[0]
}

/**
 * The cost of reading each touch as each letter: squared distance from the key centre in key
 * widths, scaled so a tap at the edge of the right key costs about as much as a near miss of the
 * neighbour.
 */
class Spatial(val touches: List<Touch>, private val keys: KeyMap?, private val config: DecoderConfig) {
  val size: Int get() = touches.size

  /** One slot per key the layout has (a–z when there's no layout), plus the apostrophe. */
  private val slotKeys: CharArray = (keys?.letters ?: DEFAULT_LETTERS) + '\''

  private fun keyOf(c: Char) = keys?.keyOf(c) ?: baseChar(c)

  // Every character's slot, worked out once: cost() runs for every trie node and touch
  private val slotTable = IntArray(TABLE_CHARS) { slotKeys.indexOf(keyOf(it.toChar())) }

  private fun slotOf(c: Char) = if (c.code < TABLE_CHARS) slotTable[c.code] else slotKeys.indexOf(keyOf(c))

  private val table: Array<FloatArray> = Array(touches.size) { i ->
    val t = touches[i]
    FloatArray(slotKeys.size) { slot ->
      val c = slotKeys[slot]
      val center = keys?.center(c)
      if (!t.hasPosition || center == null || keys == null) {
        if (keyOf(t.char) == c) 0f else config.maxSubstitution
      } else {
        val dx = (t.x - center.first) / keys.keyWidth
        val dy = (t.y - center.second) / keys.keyWidth
        minOf((dx * dx + dy * dy) * config.spatialScale, config.maxSubstitution)
      }
    }
  }

  /** Cost of reading touch [i] as the word character [c]. */
  fun cost(i: Int, c: Char): Float {
    val typed = touches[i].char
    val slot = slotOf(c)
    val sameKey = slot >= 0 && slot == slotOf(typed)
    val base = baseChar(c)
    val typedBase = baseChar(typed)
    var cost = if (slot >= 0) table[i][slot] else if (base == typedBase) 0f else config.maxSubstitution
    // Classic spelling confusions: cost no more than a near miss, even when the keys are far apart
    if (base != typedBase && confuses(typedBase, base)) cost = minOf(cost, config.softSubstitution)
    // The same letter on a key of its own ("schon" typed for "schön" where ö has a key): a skipped
    // umlaut, however far apart the keys are
    if (base == typedBase && !sameKey) cost = minOf(cost, config.softSubstitution)
    // Same key, different accent ("cafe" typed, "café" in the dictionary, or the reverse)
    if (c.lowercaseChar() != typed.lowercaseChar() && sameKey) cost += config.accent
    return cost
  }

  private companion object {
    val DEFAULT_LETTERS = ('a'..'z').toList().toCharArray()

    /** Characters whose slot is looked up in a table: Latin, Latin-1 and Latin Extended-A. */
    const val TABLE_CHARS = 0x180

    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')
    private val SOFT_PAIRS = setOf('s' to 'c', 'c' to 's', 'z' to 's', 's' to 'z')

    /** Classic spelling confusions from the Wikipedia misspellings list: vowel swaps and silent-letter mixups. */
    fun confuses(a: Char, b: Char): Boolean = (a in VOWELS && b in VOWELS) || (a to b) in SOFT_PAIRS
  }
}

/** Every decoder knob in one place, so the benchmark can tune them. */
data class DecoderConfig(
  val spatialScale: Float = 1.2f,
  val maxSubstitution: Float = 6f,
  /** Cap on classic spelling confusions (vowel swaps, s↔c, z↔s): what their cost shrinks to even when the keys sit far apart. */
  val softSubstitution: Float = 2.5f,
  val insert: Float = 3f,
  val delete: Float = 3f,
  /** A missing or extra vowel: the Wikipedia misspellings skip and double vowels far more than any other letter. */
  val vowelInsert: Float = 2.25f,
  val vowelDelete: Float = 2.25f,
  val transpose: Float = 1.8f,
  /** Inserting an apostrophe the user skipped ("dont" → "don't") is nearly free. */
  val apostropheInsert: Float = 0.3f,
  val accent: Float = 0.25f,
  /** A letter typed as its two-letter spelling ("ss" for ß, "ae" for ä): about an accent's worth, not a typo's. */
  val digraph: Float = 0.5f,
  /** The language's two-letter spellings (lower case), from its pack; none for English. */
  val digraphs: Map<Char, String> = emptyMap(),
  val completion: Float = 1.0f,
  val completionsPerPrefix: Int = 6,
  val baseLimit: Float = 1.5f,
  val limitPerTouch: Float = 0.9f,
  val maxLimit: Float = 7f,
) {
  fun limit(touches: Int) = minOf(baseLimit + limitPerTouch * touches, maxLimit)
}
