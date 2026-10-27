package com.moonkeyboard.engine.trace

import com.moonkeyboard.engine.layout.KeyBounds
import com.moonkeyboard.engine.layout.KeyType
import com.moonkeyboard.engine.layout.KeyboardGeometry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One sample of a finger: position in keyboard pixels, time in ms since the finger went down. */
@Serializable
data class TracePoint(val x: Float, val y: Float, val t: Long)

/** Everything one finger did from down to up, and what the keyboard typed for it. */
@Serializable
data class TouchTrace(val points: List<TracePoint>, val typed: String? = null) {
  val durationMs: Long get() = if (points.isEmpty()) 0 else points.last().t - points.first().t

  val lengthPx: Float
    get() = points.zipWithNext { a, b -> kotlin.math.hypot(b.x - a.x, b.y - a.y) }.sum()

  /** Keys the finger crossed, in order, without repeats. The raw input a glide decoder starts from. */
  fun keysAlong(geometry: KeyboardGeometry): List<String> =
    points.mapNotNull { geometry.keyAt(it.x, it.y)?.let(::keyName) }
      .fold(mutableListOf()) { acc, k -> if (acc.lastOrNull() != k) acc += k; acc }
}

@Serializable
data class TraceKey(val name: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * A replayable recording: the key positions at the time plus the traces. Exported from the in-app
 * test pad and meant to become fixtures for decoder tests.
 */
@Serializable
data class TraceRecording(
  val layout: String,
  val width: Float,
  val height: Float,
  val keys: List<TraceKey>,
  val traces: List<TouchTrace>,
) {
  fun toJson(): String = json.encodeToString(serializer(), this)

  companion object {
    private val json = Json { prettyPrint = false }

    fun of(layout: String, geometry: KeyboardGeometry, traces: List<TouchTrace>) = TraceRecording(
      layout, geometry.width, geometry.height,
      geometry.keys.map { TraceKey(keyName(it), it.left, it.top, it.right, it.bottom) },
      traces,
    )

    fun fromJson(text: String): TraceRecording = json.decodeFromString(serializer(), text)
  }
}

/** The label for character keys, the type for function keys (shift, space…). */
fun keyName(bounds: KeyBounds): String =
  if (bounds.key.type == KeyType.CHAR) bounds.key.label else bounds.key.type.name.lowercase()
