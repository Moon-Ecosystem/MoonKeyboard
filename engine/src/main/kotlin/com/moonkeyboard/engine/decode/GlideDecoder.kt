package com.moonkeyboard.engine.decode

import com.moonkeyboard.engine.dict.Lexicon
import com.moonkeyboard.engine.trace.TracePoint
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

/**
 * Glide typing: which words could this finger path spell? A trie walk keeps only words whose
 * letters' keys the path passes near, in order, starting near where the finger went down and
 * ending near where it lifted. Each survivor is scored by how far the path strays from the word's
 * ideal path through its key centres (the location channel of SHARK2), both resampled to the same
 * number of evenly spaced points.
 *
 * People turn back before reaching a key ("name": n → almost a → m → e), so a letter the path
 * never comes near can still match at a sharp turn if its key lies ahead of the turn, in the
 * direction the finger was moving. The word's ideal path then bends where the finger actually
 * turned, and the shortfall costs extra. Turns that no letter of a word explains cost extra too,
 * which is what stops a short word like "be" from swallowing that zig-zag.
 */
class GlideDecoder(private val lexicon: Lexicon, val config: GlideConfig = GlideConfig()) {

  /** [cost] is the mean distance between the path and the word's ideal path, in key widths, plus penalties. */
  data class Hit(val wordId: Int, val cost: Float)

  /** A sharp change of direction at path index [index], with the unit direction the finger arrived in. */
  private class Turn(val index: Int, val x: Float, val y: Float, val dx: Float, val dy: Float)

  fun search(path: List<TracePoint>, keys: KeyMap, emit: (Hit) -> Unit) {
    if (path.size < 2) return
    val pts = resample(path.map { it.x to it.y }, config.samples)
    val kw = keys.keyWidth
    val near = Array(SLOTS) { slot ->
      val c = keys.center('a' + slot) ?: return@Array IntArray(0)
      pts.indices.filter { dist(pts[it], c) <= config.nearRadius * kw }.toIntArray()
    }
    val turns = turns(pts)
    var emitted = 0
    // The current trie path's letters and where its ideal path bends for each, reused across nodes
    val letters = CharArray(MAX_DEPTH)
    val anchorX = FloatArray(MAX_DEPTH)
    val anchorY = FloatArray(MAX_DEPTH)
    fun walk(node: Int, index: Int, depth: Int, count: Int, shortfall: Float) {
      if (depth >= MAX_DEPTH || emitted >= config.maxCandidates) return
      for (child in lexicon.firstChild[node] until lexicon.firstChild[node + 1]) {
        val base = baseChar(lexicon.nodeChar[child])
        // Apostrophes have no key on the letters layer: they ride along for free
        if (base == '\'') {
          if (depth > 0) walk(child, index, depth + 1, count, shortfall)
          continue
        }
        if (base !in 'a'..'z') continue
        val center = keys.center(base) ?: continue
        var j: Int
        var missed = 0f
        var ax = center.first
        var ay = center.second
        if (depth == 0) {
          if (dist(pts[0], center) > config.endRadius * kw) continue
          j = 0
        } else {
          // Earliest point near this key: leaves the most path for the letters still to come
          j = firstAtLeast(near[base - 'a'], index) ?: -1
          if (j < 0) {
            val turn = turns.firstOrNull { it.index >= index && reachAhead(it, center, kw) >= 0f } ?: continue
            missed = reachAhead(turn, center, kw)
            if (shortfall + missed > config.maxShortfall) continue
            j = turn.index
            ax = turn.x
            ay = turn.y
          }
        }
        letters[count] = base
        anchorX[count] = ax
        anchorY[count] = ay
        val total = shortfall + missed
        val w = lexicon.nodeWord[child]
        if (w >= 0 && count >= 1 && dist(pts.last(), center) <= config.endRadius * kw) {
          val cost = templateCost(anchorX, anchorY, letters, count + 1, pts, kw) +
            config.shortfallCost * total +
            config.unexplainedTurnCost * unexplainedTurns(turns, anchorX, anchorY, count + 1, kw)
          emit(Hit(w, cost))
          emitted++
        }
        walk(child, j, depth + 1, count + 1, total)
      }
    }
    walk(0, 0, 0, 0, 0f)
  }

  /**
   * How far past [turn] (in key widths) the finger would have had to keep going to reach [center],
   * or -1 if the key isn't ahead of the turn, within reach and close to the line of travel.
   */
  private fun reachAhead(turn: Turn, center: Pair<Float, Float>, kw: Float): Float {
    val vx = center.first - turn.x
    val vy = center.second - turn.y
    val along = vx * turn.dx + vy * turn.dy
    if (along < 0f || along > config.undershootReach * kw) return -1f
    val across = abs(vx * turn.dy - vy * turn.dx)
    return if (across <= config.nearRadius * kw) along / kw else -1f
  }

  /** Sharp turns: local maxima of the angle between the incoming and outgoing direction. */
  private fun turns(pts: List<Pair<Float, Float>>): List<Turn> {
    val k = 2
    val angle = FloatArray(pts.size)
    val dirX = FloatArray(pts.size)
    val dirY = FloatArray(pts.size)
    for (i in k until pts.size - k) {
      val ax = pts[i].first - pts[i - k].first
      val ay = pts[i].second - pts[i - k].second
      val bx = pts[i + k].first - pts[i].first
      val by = pts[i + k].second - pts[i].second
      val la = hypot(ax, ay)
      val lb = hypot(bx, by)
      if (la == 0f || lb == 0f) continue
      angle[i] = acos(((ax * bx + ay * by) / (la * lb)).coerceIn(-1f, 1f))
      dirX[i] = ax / la
      dirY[i] = ay / la
    }
    val minAngle = Math.toRadians(config.turnDegrees.toDouble()).toFloat()
    return (k until pts.size - k)
      .filter { i -> angle[i] >= minAngle && angle[i] >= angle[i - 1] && angle[i] > angle[i + 1] }
      .map { i -> Turn(i, pts[i].first, pts[i].second, dirX[i], dirY[i]) }
  }

  /** Turns with none of the word's letters at them: a word that can't explain a zig-zag isn't it. */
  private fun unexplainedTurns(turns: List<Turn>, ax: FloatArray, ay: FloatArray, n: Int, kw: Float): Int =
    turns.count { t -> (0 until n).none { hypot(ax[it] - t.x, ay[it] - t.y) <= config.explainRadius * kw } }

  /** Cost of a word the trie doesn't hold (the user's own words), or null if the path can't spell it. */
  fun cost(word: String, path: List<TracePoint>, keys: KeyMap): Float? {
    val letters = word.map(::baseChar).filter { it in 'a'..'z' }
    if (letters.size < 2 || path.size < 2) return null
    val pts = resample(path.map { it.x to it.y }, config.samples)
    val first = keys.center(letters.first()) ?: return null
    val last = keys.center(letters.last()) ?: return null
    val ends = config.endRadius * keys.keyWidth
    if (dist(pts.first(), first) > ends || dist(pts.last(), last) > ends) return null
    val centers = letters.map { keys.center(it)!! }
    return templateCost(FloatArray(centers.size) { centers[it].first }, FloatArray(centers.size) { centers[it].second }, letters.toCharArray(), letters.size, pts, keys.keyWidth)
  }

  /** Mean distance between the path and the polyline through the word's anchors, in key widths. */
  private fun templateCost(ax: FloatArray, ay: FloatArray, letters: CharArray, n: Int, pts: List<Pair<Float, Float>>, kw: Float): Float {
    // "moon" has one corner at o, not two
    val corners = (0 until n).filter { i -> i == 0 || letters[i] != letters[i - 1] }.map { ax[it] to ay[it] }
    val template = resample(corners, pts.size)
    var sum = 0f
    for (i in pts.indices) sum += dist(pts[i], template[i])
    return sum / pts.size / kw
  }

  private fun firstAtLeast(indices: IntArray, from: Int): Int? {
    var lo = 0
    var hi = indices.size
    while (lo < hi) {
      val mid = (lo + hi) ushr 1
      if (indices[mid] < from) lo = mid + 1 else hi = mid
    }
    return indices.getOrNull(lo)
  }

  private companion object {
    const val SLOTS = 26
    const val MAX_DEPTH = 24
  }
}

data class GlideConfig(
  val samples: Int = 48,
  /** A key counts as passed when the path comes this close to its centre, in key widths. */
  val nearRadius: Float = 1.3f,
  /** The first and last letters must be this close to where the path starts and ends. */
  val endRadius: Float = 0.9f,
  val maxCandidates: Int = 4000,
  /** A change of direction sharper than this many degrees is a turn. */
  val turnDegrees: Float = 35f,
  /** How far past a turn (key widths) a key may lie and still count as the one the finger was heading for. */
  val undershootReach: Float = 3.5f,
  /** Cost per key width the finger fell short. */
  val shortfallCost: Float = 0.12f,
  /** Total shortfall allowed in one word, in key widths. */
  val maxShortfall: Float = 4f,
  /** A turn is explained by a letter whose anchor is this close (key widths). */
  val explainRadius: Float = 1.2f,
  /** Cost for each turn none of the word's letters sits at. */
  val unexplainedTurnCost: Float = 0.25f,
)

/** [count] points evenly spaced along the polyline [points] (all equal if it has no length). */
fun resample(points: List<Pair<Float, Float>>, count: Int): List<Pair<Float, Float>> {
  if (points.size == 1) return List(count) { points[0] }
  val lengths = FloatArray(points.size)
  for (i in 1 until points.size) lengths[i] = lengths[i - 1] + dist(points[i - 1], points[i])
  val total = lengths.last()
  if (total == 0f) return List(count) { points[0] }
  var seg = 1
  return List(count) { i ->
    val target = total * i / (count - 1)
    while (seg < points.lastIndex && lengths[seg] < target) seg++
    val a = points[seg - 1]
    val b = points[seg]
    val span = lengths[seg] - lengths[seg - 1]
    val t = if (span == 0f) 0f else ((target - lengths[seg - 1]) / span).coerceIn(0f, 1f)
    (a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t)
  }
}

private fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>) = hypot(a.first - b.first, a.second - b.second)
