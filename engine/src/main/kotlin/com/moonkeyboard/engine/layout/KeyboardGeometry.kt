package com.moonkeyboard.engine.layout

data class KeyBounds(val key: Key, val left: Float, val top: Float, val right: Float, val bottom: Float) {
  val width get() = right - left
  val height get() = bottom - top
  val centerX get() = (left + right) / 2
  val centerY get() = (top + bottom) / 2

  fun distanceSq(x: Float, y: Float): Float {
    val dx = maxOf(left - x, 0f, x - right)
    val dy = maxOf(top - y, 0f, y - bottom)
    return dx * dx + dy * dy
  }
}

/**
 * Pixel positions of every key. The widest row fills the width; narrower rows are centred, so a
 * 9-key row gets the half-key indent of a real QWERTY. Shared by drawing, hit-testing and (later)
 * the glide decoder.
 */
class KeyboardGeometry(val keys: List<KeyBounds>, val width: Float, val height: Float) {

  /** The key under the point, or the nearest one when the point falls in a gap. */
  fun keyAt(x: Float, y: Float): KeyBounds? = keys.minByOrNull { it.distanceSq(x, y) }

  companion object {
    fun compute(
      layout: Layout,
      width: Float,
      keyHeight: Float,
      hGap: Float,
      vGap: Float,
      sidePadding: Float,
      topPadding: Float = 0f,
      /** A gap down the middle for thumbs: keys right of centre move over by it, space stretches across. */
      splitGap: Float = 0f,
    ): KeyboardGeometry {
      if (splitGap > 0f) {
        val narrow = compute(layout, width - splitGap, keyHeight, hGap, vGap, sidePadding, topPadding)
        val mid = (width - splitGap) / 2
        val keys = narrow.keys.map {
          when {
            // A key straddling the middle ("g", "v") stays with the left half
            it.centerX > mid + 1f -> it.copy(left = it.left + splitGap, right = it.right + splitGap)
            it.key.type == KeyType.SPACE && it.right > mid -> it.copy(right = it.right + splitGap)
            else -> it
          }
        }
        return KeyboardGeometry(keys, width, narrow.height)
      }
      val units = layout.rows.maxOf { row -> row.keys.sumOf { it.width.toDouble() } }.toFloat()
      val unit = (width - 2 * sidePadding - (units - 1) * hGap) / units
      // A key spanning n units also spans the n-1 gaps it replaces, so wide keys line up with the grid
      fun keyWidth(k: Key) = k.width * unit + (k.width - 1) * hGap

      val keys = ArrayList<KeyBounds>()
      var top = topPadding
      for (row in layout.rows) {
        val rowWidth = row.keys.sumOf { keyWidth(it).toDouble() }.toFloat() + (row.keys.size - 1) * hGap
        var x = (width - rowWidth) / 2
        val h = keyHeight * row.height
        for (k in row.keys) {
          val w = keyWidth(k)
          keys += KeyBounds(k, x, top, x + w, top + h)
          x += w + hGap
        }
        top += h + vGap
      }
      return KeyboardGeometry(keys, width, top - vGap)
    }
  }
}
