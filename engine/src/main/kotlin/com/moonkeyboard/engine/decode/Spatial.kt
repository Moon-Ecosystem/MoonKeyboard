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
  fun center(c: Char): Pair<Float, Float>? = centers[c]

  /** A touch in the middle of [c]'s key, or an exact touch if the layout has no such key. */
  fun touchFor(c: Char): Touch =
    centers[baseChar(c)]?.let { (x, y) -> Touch(x, y, c) } ?: Touch.exact(c)

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
  return Normalizer.normalize(l.toString(), Normalizer.Form.NFD)[0]
}

/**
 * The cost of reading each touch as each letter: squared distance from the key centre in key
 * widths, scaled so a tap at the edge of the right key costs about as much as a near miss of the
 * neighbour.
 */
class Spatial(val touches: List<Touch>, keys: KeyMap?, private val config: DecoderConfig) {
  val size: Int get() = touches.size

  private val table: Array<FloatArray> = Array(touches.size) { i ->
    val t = touches[i]
    FloatArray(SLOTS) { slot ->
      val c = slotChar(slot)
      val center = keys?.center(c)
      if (!t.hasPosition || center == null || keys == null) {
        if (baseChar(t.char) == c) 0f else config.maxSubstitution
      } else {
        val dx = (t.x - center.first) / keys.keyWidth
        val dy = (t.y - center.second) / keys.keyWidth
        minOf((dx * dx + dy * dy) * config.spatialScale, config.maxSubstitution)
      }
    }
  }

  /** Cost of reading touch [i] as the word character [c]. */
  fun cost(i: Int, c: Char): Float {
    val base = baseChar(c)
    val typed = touches[i].char
    val slot = slotOf(base)
    var cost = if (slot >= 0) table[i][slot] else if (base == baseChar(typed)) 0f else config.maxSubstitution
    // Same key, different accent ("cafe" typed, "café" in the dictionary, or the reverse)
    if (c.lowercaseChar() != typed.lowercaseChar() && base == baseChar(typed)) cost += config.accent
    return cost
  }

  private companion object {
    const val SLOTS = 27
    fun slotOf(c: Char) = when (c) {
      in 'a'..'z' -> c - 'a'
      '\'' -> 26
      else -> -1
    }
    fun slotChar(slot: Int) = if (slot < 26) 'a' + slot else '\''
  }
}

/** Every decoder knob in one place, so the benchmark can tune them. */
data class DecoderConfig(
  val spatialScale: Float = 2f,
  val maxSubstitution: Float = 6f,
  val insert: Float = 2.4f,
  val delete: Float = 2.4f,
  val transpose: Float = 1.2f,
  /** Inserting an apostrophe the user skipped ("dont" → "don't") is nearly free. */
  val apostropheInsert: Float = 0.3f,
  val accent: Float = 0.25f,
  val completion: Float = 1.0f,
  val completionsPerPrefix: Int = 6,
  val baseLimit: Float = 1.5f,
  val limitPerTouch: Float = 0.9f,
  val maxLimit: Float = 7f,
) {
  fun limit(touches: Int) = minOf(baseLimit + limitPerTouch * touches, maxLimit)
}
