package com.moonkeyboard.ime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import com.moonkeyboard.engine.layout.KeyBounds
import com.moonkeyboard.engine.layout.KeyType
import com.moonkeyboard.engine.layout.KeyboardGeometry
import com.moonkeyboard.engine.trace.keyName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class Popup(val items: List<String>, val left: Float, val top: Float, val cellWidth: Float, val cellHeight: Float) {
  var selected by mutableIntStateOf(0)
}

/**
 * Follows every finger on its own so fast typists can roll from one key to the next. Characters
 * are typed when the finger lifts; shift and backspace act on touch.
 */
class TouchTracker(
  private val controller: InputController,
  private val geometry: KeyboardGeometry,
  private val scope: CoroutineScope,
  private val popupGap: Float,
  private val sidePadding: Float,
  private val recorder: GestureRecorder?,
  private val haptic: () -> Unit,
) {
  val pressed = mutableStateMapOf<PointerId, KeyBounds>()
  var popup by mutableStateOf<Popup?>(null)
    private set

  private var popupOwner: PointerId? = null
  private val holds = HashMap<PointerId, Job>()
  // Where each finger landed: the point the decoder scores, not where it lifted
  private val downAt = HashMap<PointerId, Offset>()

  @OptIn(ExperimentalComposeUiApi::class)
  suspend fun track(input: PointerInputScope) = input.awaitPointerEventScope {
    try {
      while (true) {
        val event = awaitPointerEvent()
        for (change in event.changes) {
          when {
            change.changedToDown() -> down(change.id, change.position, change.uptimeMillis)
            change.changedToUp() -> up(change.id, change.position, change.uptimeMillis)
            change.pressed -> {
              // Android batches touch samples per frame; the in-between ones matter for glide paths
              recorder?.let { r ->
                change.historical.forEach { r.move(change.id, it.position.x, it.position.y, it.uptimeMillis) }
                r.move(change.id, change.position.x, change.position.y, change.uptimeMillis)
              }
              move(change.id, change.position)
            }
          }
          change.consume()
        }
      }
    } finally {
      holds.values.forEach { it.cancel() }
      holds.clear()
      pressed.clear()
      downAt.clear()
      popup = null
    }
  }

  private fun down(id: PointerId, at: Offset, nowMs: Long) {
    val bounds = geometry.keyAt(at.x, at.y) ?: return
    val key = bounds.key
    recorder?.down(id, at.x, at.y, nowMs, keyName(bounds))
    pressed[id] = bounds
    downAt[id] = at
    haptic()
    when {
      key.type == KeyType.SHIFT -> controller.onKey(key, nowMs)
      key.type == KeyType.BACKSPACE -> {
        controller.onKey(key, nowMs)
        holds[id] = scope.launch {
          delay(BACKSPACE_REPEAT_DELAY_MS)
          while (isActive) {
            controller.onKey(key, nowMs)
            delay(BACKSPACE_REPEAT_MS)
          }
        }
      }
      key.popup.isNotEmpty() -> holds[id] = scope.launch {
        delay(LONG_PRESS_MS)
        popup = popupFor(bounds)
        popupOwner = id
      }
    }
  }

  private fun move(id: PointerId, at: Offset) {
    val p = popup ?: return
    if (popupOwner != id) return
    p.selected = ((at.x - p.left) / p.cellWidth).toInt().coerceIn(0, p.items.lastIndex)
  }

  private fun up(id: PointerId, at: Offset, nowMs: Long) {
    holds.remove(id)?.cancel()
    val bounds = pressed.remove(id) ?: return
    val start = downAt.remove(id) ?: at
    val p = popup
    if (p != null && popupOwner == id) {
      popup = null
      popupOwner = null
      val item = p.items[p.selected]
      recorder?.up(id, at.x, at.y, nowMs, controller.display(item))
      // A chosen accent was deliberate: score it as a dead-centre tap on its key
      controller.commitPopup(item, bounds.centerX, bounds.centerY)
    } else {
      val acts = bounds.key.type != KeyType.SHIFT && bounds.key.type != KeyType.BACKSPACE
      recorder?.up(id, at.x, at.y, nowMs, if (bounds.key.type == KeyType.CHAR) controller.display(bounds.key.label) else keyName(bounds))
      if (acts) controller.onKey(bounds.key, nowMs, start.x, start.y)
    }
  }

  private fun popupFor(bounds: KeyBounds): Popup {
    val cell = bounds.width.coerceAtMost(geometry.keys.minOf { it.width } * 1.2f)
    val total = cell * bounds.key.popup.size
    val left = (bounds.centerX - cell / 2).coerceIn(sidePadding, geometry.width - sidePadding - total)
    return Popup(bounds.key.popup, left, bounds.top - bounds.height - popupGap, cell, bounds.height)
  }

  private companion object {
    const val LONG_PRESS_MS = 300L
    const val BACKSPACE_REPEAT_DELAY_MS = 400L
    const val BACKSPACE_REPEAT_MS = 50L
  }
}
