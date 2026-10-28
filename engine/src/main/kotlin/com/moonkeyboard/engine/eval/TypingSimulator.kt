package com.moonkeyboard.engine.eval

import com.moonkeyboard.engine.decode.KeyMap
import com.moonkeyboard.engine.decode.Touch
import com.moonkeyboard.engine.decode.baseChar
import com.moonkeyboard.engine.decode.resample
import com.moonkeyboard.engine.layout.KeyType
import com.moonkeyboard.engine.layout.KeyboardGeometry
import com.moonkeyboard.engine.trace.TracePoint
import java.util.Random

/**
 * Simulated typists for measuring accuracy: taps with Gaussian finger error, and swipes that wobble
 * and cut corners the way a thumb does. The same seed gives the same "typist", so two decoder
 * settings can be compared on identical input.
 */
class TypingSimulator(geometry: KeyboardGeometry, seed: Long = 42) {
  val keys = KeyMap.from(geometry)
  private val letterKeys = geometry.keys.filter { it.key.type == KeyType.CHAR && it.key.label.singleOrNull()?.isLetter() == true }
  private val random = Random(seed)

  /**
   * Taps for [word] with error [sigma] (key widths): what lands on screen, and the touches. People
   * skip the apostrophe ("dont") and let the keyboard add it, so it is never tapped here.
   */
  fun tap(word: String, sigma: Float): Pair<String, List<Touch>> {
    val typed = StringBuilder()
    val touches = ArrayList<Touch>()
    for (c in word) {
      val (cx, cy) = keys.center(baseChar(c)) ?: continue
      val x = cx + gaussian(sigma)
      val y = cy + gaussian(sigma)
      val hit = letterKeys.minBy { it.distanceSq(x, y) }.key.label[0]
      val ch = if (c.isUpperCase()) hit.uppercaseChar() else hit
      typed.append(ch)
      touches += Touch(x, y, ch)
    }
    return typed.toString() to touches
  }

  /**
   * A swipe through [word]'s keys: each corner pushed off by [sigma] key widths, interior corners
   * cut short by up to [undershoot] of the way back (people turn before reaching the key, at most
   * two key widths early), then smoothed over [smoothing] key widths so corners are rounded off.
   */
  fun glide(word: String, sigma: Float, smoothing: Float, undershoot: Float = 0f): List<TracePoint> {
    val letters = word.map(::baseChar).filter { keys.center(it) != null }
    val noisy = letters.filterIndexed { i, c -> i == 0 || c != letters[i - 1] }.map { c ->
      val (x, y) = keys.center(c)!!
      (x + gaussian(sigma)) to (y + gaussian(sigma))
    }
    val corners = noisy.mapIndexed { i, p ->
      if (i == 0 || i == noisy.lastIndex || undershoot == 0f) return@mapIndexed p
      val prev = noisy[i - 1]
      val dx = p.first - prev.first
      val dy = p.second - prev.second
      val length = kotlin.math.hypot(dx, dy)
      if (length == 0f) return@mapIndexed p
      val back = minOf(random.nextFloat() * undershoot * length, 2f * keys.keyWidth)
      (p.first - dx / length * back) to (p.second - dy / length * back)
    }
    val dense = resample(corners, DENSE)
    val length = dense.zipWithNext { a, b -> kotlin.math.hypot(b.first - a.first, b.second - a.second) }.sum()
    val window = if (length == 0f) 0 else (smoothing * keys.keyWidth / (length / (DENSE - 1))).toInt()
    // Moving average; the window shrinks towards the ends so the swipe still starts and ends on its keys
    val smooth = dense.indices.map { i ->
      val half = minOf(window, i, dense.lastIndex - i)
      var sx = 0f
      var sy = 0f
      for (k in i - half..i + half) {
        sx += dense[k].first
        sy += dense[k].second
      }
      (sx / (2 * half + 1)) to (sy / (2 * half + 1))
    }
    return resample(smooth, SAMPLES).mapIndexed { i, (x, y) -> TracePoint(x, y, i * 8L) }
  }

  private fun gaussian(sigma: Float) = (random.nextGaussian() * sigma * keys.keyWidth).toFloat()

  private companion object {
    const val DENSE = 200
    const val SAMPLES = 60
  }
}
