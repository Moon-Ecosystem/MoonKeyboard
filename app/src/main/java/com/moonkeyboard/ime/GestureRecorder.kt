package com.moonkeyboard.ime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.input.pointer.PointerId
import com.moonkeyboard.engine.layout.KeyboardGeometry
import com.moonkeyboard.engine.trace.TouchTrace
import com.moonkeyboard.engine.trace.TracePoint
import com.moonkeyboard.engine.trace.TraceRecording

/**
 * Debug-only: records every finger's full path so the test pad can draw it, show stats and export
 * it as JSON for glide-decoder fixtures. Not attached in the real keyboard.
 */
class GestureRecorder(private val maxTraces: Int = 50, private val maxLog: Int = 40) {
  val traces = mutableStateListOf<TouchTrace>()
  val live = mutableStateMapOf<PointerId, SnapshotStateList<TracePoint>>()
  val log = mutableStateListOf<String>()
  var showPaths by mutableStateOf(true)
  var layerId by mutableStateOf("")
    private set
  var geometry by mutableStateOf<KeyboardGeometry?>(null)
    private set

  private val downAt = HashMap<PointerId, Long>()

  fun attach(layerId: String, geometry: KeyboardGeometry) {
    this.layerId = layerId
    this.geometry = geometry
  }

  fun down(id: PointerId, x: Float, y: Float, timeMs: Long, key: String?) {
    downAt[id] = timeMs
    live[id] = mutableStateListOf(TracePoint(x, y, 0))
    log("down  ${key ?: "-"}  (${x.toInt()}, ${y.toInt()})")
  }

  fun move(id: PointerId, x: Float, y: Float, timeMs: Long) {
    val start = downAt[id] ?: return
    live[id]?.add(TracePoint(x, y, timeMs - start))
  }

  fun up(id: PointerId, x: Float, y: Float, timeMs: Long, typed: String?) {
    val start = downAt.remove(id) ?: return
    val trace = TouchTrace(live.remove(id).orEmpty().toList() + TracePoint(x, y, timeMs - start), typed)
    traces += trace
    if (traces.size > maxTraces) traces.removeAt(0)
    log("up    ${typed ?: "-"}  ${trace.points.size} pts  ${trace.durationMs} ms  ${trace.lengthPx.toInt()} px")
  }

  fun clear() {
    traces.clear()
    log.clear()
  }

  fun exportJson(): String? = geometry?.let { TraceRecording.of(layerId, it, traces.toList()).toJson() }

  private fun log(line: String) {
    log += line
    if (log.size > maxLog) log.removeAt(0)
  }
}
