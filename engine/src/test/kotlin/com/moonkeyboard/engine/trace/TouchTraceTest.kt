package com.moonkeyboard.engine.trace

import com.moonkeyboard.engine.layout.KeyboardGeometry
import com.moonkeyboard.engine.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchTraceTest {
  private val geometry =
    KeyboardGeometry.compute(Layouts.load("qwerty"), width = 390f, keyHeight = 46f, hGap = 6f, vGap = 10f, sidePadding = 4f)

  private fun center(label: String) = geometry.keys.first { it.key.label == label }.let { it.centerX to it.centerY }

  @Test
  fun keysAlongASwipeFromMToOToN() {
    val (mx, my) = center("m")
    val (ox, oy) = center("o")
    val (nx, ny) = center("n")
    // Straight segments sampled every 10% between key centres, like a quick "moon" swipe
    val points = listOf(mx to my, ox to oy, nx to ny).zipWithNext().flatMap { (a, b) ->
      (0 until 10).map { i -> TracePoint(a.first + (b.first - a.first) * i / 10, a.second + (b.second - a.second) * i / 10, 0) }
    } + TracePoint(nx, ny, 0)
    val keys = TouchTrace(points).keysAlong(geometry)
    assertEquals("m", keys.first())
    assertTrue("o" in keys)
    assertEquals("n", keys.last())
    assertTrue("no repeats: $keys", keys.zipWithNext().none { (a, b) -> a == b })
  }

  @Test
  fun lengthAndDuration() {
    val t = TouchTrace(listOf(TracePoint(0f, 0f, 100), TracePoint(3f, 4f, 150), TracePoint(3f, 14f, 220)))
    assertEquals(15f, t.lengthPx, 0.001f)
    assertEquals(120L, t.durationMs)
  }

  @Test
  fun recordingRoundTripsThroughJson() {
    val rec = TraceRecording.of("qwerty", geometry, listOf(TouchTrace(listOf(TracePoint(1f, 2f, 3)), typed = "e")))
    assertEquals(rec, TraceRecording.fromJson(rec.toJson()))
  }
}
