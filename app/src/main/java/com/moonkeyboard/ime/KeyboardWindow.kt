package com.moonkeyboard.ime

import android.content.res.Configuration
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonkeyboard.R

private val HandleHeight = 28.dp
private val GripWidth = 48.dp
private val DockZone = 160.dp
private val MinWidth = 220.dp
// The card width at which keys are full size
private val FullSizeWidth = 400.dp

/**
 * Everything the keyboard's window shows: the keyboard docked at the bottom, or floating as a card
 * dragged by its top bar and resized from its corner. [onBounds] gets where the keyboard is, so the
 * rest of the window lets touches through to the app.
 */
@Composable
fun KeyboardWindow(controller: InputController, onBounds: (Rect) -> Unit) {
  val bounds = Modifier.onGloballyPositioned { c ->
    val b = c.boundsInWindow()
    onBounds(Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()))
  }
  if (Prefs.floating.value) {
    FloatingKeyboard(controller, bounds)
  } else {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
      Box(bounds) { KeyboardUi(controller) }
    }
  }
}

@Composable
private fun FloatingKeyboard(controller: InputController, bounds: Modifier) {
  val colors = keyboardColors()
  val density = LocalDensity.current
  val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  val pref = if (landscape) Prefs.floatLandscape else Prefs.floatPortrait
  BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
    val areaW = maxWidth.value
    val areaH = maxHeight.value
    var spot by remember(pref, areaW, areaH) {
      val saved = pref.value
      val width = if (saved != FloatSpot.UNSET) saved.width.coerceIn(MinWidth.value, areaW) else areaW * (if (landscape) 0.5f else 0.78f)
      val start = if (saved != FloatSpot.UNSET) saved else FloatSpot((areaW - width) / 2, areaH * (if (landscape) 0.2f else 0.45f), width)
      mutableStateOf(start.copy(x = start.x.coerceIn(0f, areaW - width), width = width))
    }
    var cardHeight by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    fun overDock() = spot.y + HandleHeight.value > areaH - DockZone.value
    val docking = dragging && overDock()
    // Kept on screen when it grows (the emoji panel), except while being dragged to the dock
    val y = if (dragging) spot.y else spot.y.coerceIn(0f, maxOf(0f, areaH - cardHeight))

    // Runs from the drag handler, which outlives this composition: it reads state, never local vals
    fun settle() {
      dragging = false
      if (overDock()) Prefs.floating.value = false
      spot = spot.copy(y = spot.y.coerceIn(0f, maxOf(0f, areaH - cardHeight)))
      pref.value = spot
    }

    if (dragging) DockTarget(colors, docking, Modifier.align(Alignment.BottomCenter))
    Column(
      Modifier.offset(spot.x.dp, y.dp).width(spot.width.dp).then(bounds)
        .shadow(12.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(colors.background)
        .onSizeChanged { cardHeight = with(density) { it.height.toDp().value } }
    ) {
      val move = stringResource(R.string.move_keyboard)
      // The resize grip sits in the bar too: in a corner over the keys it took enter's taps
      Row(Modifier.fillMaxWidth().height(HandleHeight).background(if (dragging) colors.special else colors.background)) {
        Spacer(Modifier.width(GripWidth))
        Box(
          Modifier.weight(1f).fillMaxHeight()
            .semantics { contentDescription = move }
            .pointerInput(areaW, areaH) {
              detectDragGestures(onDragStart = { dragging = true }, onDragEnd = ::settle, onDragCancel = ::settle) { change, amount ->
                change.consume()
                spot = spot.copy(
                  x = (spot.x + amount.x / density.density).coerceIn(0f, areaW - spot.width),
                  // Lets the card slide down off the screen into the dock target
                  y = (spot.y + amount.y / density.density).coerceIn(0f, areaH - HandleHeight.value),
                )
              }
            },
          contentAlignment = Alignment.Center,
        ) {
          Box(Modifier.size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(if (dragging) colors.accent else colors.muted))
        }
        val resize = stringResource(R.string.resize_keyboard)
        Canvas(
          Modifier.width(GripWidth).fillMaxHeight().semantics { contentDescription = resize }
            .pointerInput(areaW) {
              detectDragGestures(onDragEnd = { pref.value = spot }) { change, amount ->
                change.consume()
                spot = spot.copy(width = (spot.width + amount.x / density.density).coerceIn(MinWidth.value, areaW - spot.x))
              }
            }
        ) {
          val s = 1.8.dp.toPx()
          val e = Offset(size.width - 10.dp.toPx(), size.height - 6.dp.toPx())
          // Two diagonal strokes, the usual resize grip
          for (len in listOf(12.dp.toPx(), 6.dp.toPx())) {
            drawLine(colors.muted, Offset(e.x, e.y - len), Offset(e.x - len, e.y), s, StrokeCap.Round)
          }
        }
      }
      // A smaller density shrinks everything in the keyboard alike: keys, labels, icons, panels
      val scale = (spot.width / FullSizeWidth.value).coerceIn(0.6f, 1f)
      CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
        KeyboardUi(controller, floating = true)
      }
    }
  }
}

/** The dashed area at the bottom that docks the keyboard when it's dropped there. */
@Composable
private fun DockTarget(colors: KeyboardColors, active: Boolean, modifier: Modifier) {
  Box(modifier.fillMaxWidth().height(DockZone).padding(12.dp), contentAlignment = Alignment.BottomCenter) {
    Canvas(Modifier.fillMaxSize()) {
      val r = CornerRadius(20.dp.toPx())
      drawRoundRect(colors.accent.copy(alpha = if (active) 0.22f else 0.08f), cornerRadius = r)
      drawRoundRect(colors.accent, cornerRadius = r, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()))))
    }
    Text(
      stringResource(R.string.dock_hint),
      Modifier.padding(bottom = 16.dp).clip(RoundedCornerShape(18.dp)).background(colors.accent).padding(horizontal = 14.dp, vertical = 8.dp),
      color = colors.onAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
    )
  }
}
