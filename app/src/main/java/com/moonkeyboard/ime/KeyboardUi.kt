package com.moonkeyboard.ime

import android.content.res.Configuration
import android.os.SystemClock
import android.media.AudioManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.moonkeyboard.R
import com.moonkeyboard.engine.decode.KeyMap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonkeyboard.engine.input.ShiftState
import com.moonkeyboard.engine.layout.KeyBounds
import com.moonkeyboard.engine.layout.KeyType
import com.moonkeyboard.engine.layout.KeyboardGeometry
import com.moonkeyboard.engine.layout.Layout

private val KeyHeight = 46.dp
private val HGap = 6.dp
// Roomy rows: with the larger labels a tighter gap looked cramped
private val VGap = 15.dp
private val SidePadding = 4.dp
internal val StripHeight = 48.dp
private val TopPadding = 6.dp
private val BottomPadding = 8.dp
private val KeyRadius = 8.dp
private const val ONE_HANDED_SHARE = 0.84f
private val EmojiExtraHeight = 96.dp
// Landscape: everything smaller, and rows closer, so the app keeps about half the screen
private const val LANDSCAPE_SCALE = 0.8f
private val LandscapeVGap = 8.dp
private const val SPLIT_SHARE = 0.28f

private val ShiftIcon = icon("M12 4l8 8h-5v8H9v-8H4z")
// The × sits a little left of the box's middle, so it looks centred in the whole shape, point included
private val BackspaceIcon = icon("M9 5h11a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H9l-6-7zM11 9l6 6M17 9l-6 6")
private val EnterIcon = icon("M20 5v7a2 2 0 0 1-2 2H5M9 10l-4 4 4 4")
private val SmileIcon = icon("M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0M8 14s1.5 2 4 2 4-2 4-2M9 9h.01M15 9h.01")

private fun icon(d: String): Path = PathParser().parsePathString(d).toPath()

/**
 * The suggestion strip over one Canvas holding every key: a key press redraws once instead of
 * recomposing ~40 keys. Popups and debug paths live on an overlay without pointer input, so they
 * can spill over the strip without stealing its taps.
 *
 * [floating]: drawn inside the floating card, which sizes it (and scales it through the density).
 */
@Composable
fun KeyboardUi(controller: InputController, recorder: GestureRecorder? = null, floating: Boolean = false) {
  val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  if (!landscape) return KeyboardContent(controller, recorder, floating, landscape = false)
  // Landscape shrinks everything alike (keys, labels, strip), as the floating card does
  val d = LocalDensity.current
  CompositionLocalProvider(LocalDensity provides Density(d.density * LANDSCAPE_SCALE, d.fontScale)) {
    KeyboardContent(controller, recorder, floating, landscape = true)
  }
}

@Composable
private fun KeyboardContent(controller: InputController, recorder: GestureRecorder?, floating: Boolean, landscape: Boolean) {
  val colors = keyboardColors()
  val borders = Prefs.borders.value
  val oneHanded = if (floating) OneHanded.OFF else Prefs.oneHanded.value
  val keyHeight = KeyHeight * Prefs.keySize.value.scale
  val vGap = if (landscape) LandscapeVGap else VGap
  val split = landscape && !floating && oneHanded == OneHanded.OFF && Prefs.splitLandscape.value
  val density = LocalDensity.current
  val view = LocalView.current
  val audio = remember { view.context.getSystemService(AudioManager::class.java) }
  val scope = rememberCoroutineScope()
  val measurer = rememberTextMeasurer(cacheSize = 64)
  val layout = controller.layout

  BoxWithConstraints(
    Modifier.fillMaxWidth().background(colors.background).then(if (floating) Modifier else Modifier.windowInsetsPadding(WindowInsets.navigationBars))
      // Watches every touch on the keyboard without taking it, so the controller can tell its own
      // edits' echoes from the cursor moving in the app
      .pointerInput(controller) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            controller.onKeyboardTouch(fingerDown = event.changes.any { it.pressed })
          }
        }
      }
  ) {
    val fullWidth = constraints.maxWidth.toFloat()
    val widthPx = if (oneHanded == OneHanded.OFF) fullWidth else (fullWidth * ONE_HANDED_SHARE).roundToInt().toFloat()
    val letters = controller.layoutFor(InputController.LETTERS)
    val geometry = remember(layout, letters, widthPx, keyHeight, vGap, split, density) {
      with(density) {
        // Every layer is as tall as the letters: a layer with fewer rows (symbols next to letters
        // with a number row) spreads them out instead of shrinking the keyboard
        val k = keyHeight.toPx()
        fun keysHeight(l: Layout) = l.rows.sumOf { (k * it.height).toDouble() }.toFloat()
        val target = keysHeight(letters) + (letters.rows.size - 1) * vGap.toPx()
        val gap = if (layout.rows.size > 1) maxOf(vGap.toPx(), (target - keysHeight(layout)) / (layout.rows.size - 1)) else vGap.toPx()
        KeyboardGeometry.compute(
          layout, widthPx, k, HGap.toPx(), gap, SidePadding.toPx(), topPadding = TopPadding.toPx(),
          splitGap = if (split) widthPx * SPLIT_SHARE else 0f,
        )
      }
    }
    val touches = remember(geometry) {
      with(density) {
        val keyWidth = geometry.keys.minOf { it.width }
        TouchTracker(controller, geometry, scope, popupGap = 8.dp.toPx(), sidePadding = SidePadding.toPx(), glideThreshold = keyWidth * 0.8f, recorder) { type ->
          Haptics.tap(view)
          if (Prefs.sound.value) audio.playSoundEffect(soundFor(type), -1f)
        }
      }
    }
    if (controller.layerId == InputController.LETTERS) {
      val keyMap = remember(geometry) { KeyMap.from(geometry) }
      SideEffect { controller.keyMap = keyMap }
    }
    if (recorder != null) SideEffect { recorder.attach(controller.layerId, geometry) }
    val height = with(density) { geometry.height.toDp() } + BottomPadding
    val panelModifier = Modifier.fillMaxWidth().height(StripHeight + height)

    when (controller.panel) {
      Panel.CLIPBOARD -> ClipboardPanel(controller, colors, panelModifier)
      // Taller than the keys, so emoji can be big: the app above moves up to make room
      // (Not in landscape or floating, where the app has little room to spare)
      Panel.EMOJI -> EmojiPanel(controller, colors, Modifier.fillMaxWidth().height(StripHeight + height + if (landscape || floating) 0.dp else EmojiExtraHeight))
      Panel.EDIT -> EditPanel(controller, colors, panelModifier)
      Panel.OPTIONS -> OptionsPanel(controller, colors, panelModifier)
      Panel.KEYS -> Column {
        Strip(controller, colors)
        Row {
          if (oneHanded == OneHanded.RIGHT) OneHandedControls(colors, Modifier.weight(1f).height(height))
          Box(Modifier.width(with(density) { widthPx.toDp() }).height(height)) {
            Spacer(
              Modifier.matchParentSize().pointerInput(touches) { touches.track(this) }.drawWithCache {
                // Every key at rest, recorded once; rebuilt only when what it reads (shift, size) changes
                val keys = obtainGraphicsLayer().apply {
                  record { for (bounds in geometry.keys) drawKey(bounds, false, controller, colors, measurer, borders) }
                }
                onDrawBehind {
                  drawLayer(keys)
                  for (bounds in touches.pressed.values) drawKey(bounds, true, controller, colors, measurer, borders)
                }
              }
            )
            // Its own offscreen layer, where each piece replaces what's under it: fading pieces
            // blended over each other showed their overlaps as a string of dots
            if (Prefs.glideTrail.value) {
              Canvas(Modifier.matchParentSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                drawGlideTrail(touches, colors)
              }
            }
            // Not clipped, so popups can rise over the strip
            Canvas(Modifier.matchParentSize()) {
              if (recorder != null && recorder.showPaths) drawTouchPaths(recorder, colors)
              touches.popup?.let { drawPopup(it, controller, colors, measurer) }
            }
          }
          if (oneHanded == OneHanded.LEFT) OneHandedControls(colors, Modifier.weight(1f).height(height))
        }
      }
    }
  }
}

internal val ToolbarItem.icon get() = when (this) {
  ToolbarItem.EMOJI -> Icons.Emoji
  ToolbarItem.THEME -> Icons.Theme
  ToolbarItem.FLOATING -> Icons.Floating
  ToolbarItem.ONE_HANDED -> Icons.OneHanded
  ToolbarItem.TEXT_EDIT -> Icons.TextEdit
  ToolbarItem.SPLIT -> Icons.Split
}

internal val ToolbarItem.label get() = when (this) {
  ToolbarItem.EMOJI -> R.string.emoji
  ToolbarItem.THEME -> R.string.theme
  ToolbarItem.FLOATING -> R.string.floating_short
  ToolbarItem.ONE_HANDED -> R.string.one_handed_short
  ToolbarItem.TEXT_EDIT -> R.string.text_editing
  ToolbarItem.SPLIT -> R.string.split_short
}

/** A setting that is on, shown highlighted. */
internal fun ToolbarItem.isOn() = when (this) {
  ToolbarItem.FLOATING -> Prefs.floating.value
  ToolbarItem.ONE_HANDED -> !Prefs.floating.value && Prefs.oneHanded.value != OneHanded.OFF
  ToolbarItem.SPLIT -> Prefs.splitLandscape.value
  else -> false
}

// One-handed doesn't apply to a floating keyboard, which is already small
internal fun ToolbarItem.isEnabled() = this != ToolbarItem.ONE_HANDED || !Prefs.floating.value

/** The same action from the toolbar or the options panel; a change of layout goes back to the keys. */
internal fun ToolbarItem.act(controller: InputController, colors: KeyboardColors) {
  when (this) {
    ToolbarItem.EMOJI -> controller.panel = Panel.EMOJI
    ToolbarItem.THEME -> flipTheme(colors)
    ToolbarItem.FLOATING -> {
      Prefs.floating.value = !Prefs.floating.value
      controller.panel = Panel.KEYS
    }
    ToolbarItem.ONE_HANDED -> {
      Prefs.oneHanded.value = if (Prefs.oneHanded.value == OneHanded.OFF) Prefs.oneHandedSide.value else OneHanded.OFF
      controller.panel = Panel.KEYS
    }
    ToolbarItem.TEXT_EDIT -> controller.panel = Panel.EDIT
    ToolbarItem.SPLIT -> Prefs.splitLandscape.value = !Prefs.splitLandscape.value
  }
}

// Flips what's on screen; cycling through "follow system" looked like a dead tap when that matched
internal fun flipTheme(colors: KeyboardColors) {
  Prefs.theme.value = if (colors == KeyboardColors.Dark) ThemeMode.LIGHT else ThemeMode.DARK
}

@Composable
internal fun keyboardColors(): KeyboardColors {
  val dark = when (Prefs.theme.value) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
  }
  return if (dark) KeyboardColors.Dark else KeyboardColors.Light
}

private fun soundFor(type: KeyType) = when (type) {
  KeyType.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
  KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
  KeyType.ENTER -> AudioManager.FX_KEYPRESS_RETURN
  else -> AudioManager.FX_KEYPRESS_STANDARD
}

/** The space beside a one-handed keyboard: back to full width, or over to the other side. */
@Composable
private fun OneHandedControls(colors: KeyboardColors, modifier: Modifier) {
  Column(modifier, verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
    ToolButton(Icons.Expand, R.string.full_width, colors) { Prefs.oneHanded.value = OneHanded.OFF }
    ToolButton(Icons.SwapSide, R.string.other_side, colors) {
      val side = if (Prefs.oneHanded.value == OneHanded.LEFT) OneHanded.RIGHT else OneHanded.LEFT
      Prefs.oneHanded.value = side
      Prefs.oneHandedSide.value = side
    }
  }
}

/** Suggestions, or the toolbar when it is open or there is nothing to suggest. */
@Composable
private fun Strip(controller: InputController, colors: KeyboardColors) {
  // Read here, not in KeyboardUi, so a new suggestion recomposes only the strip
  val fresh = if (Prefs.offerCopied.value) ClipboardStore.freshClip() else null
  when {
    controller.emojiQuery != null -> EmojiSearchStrip(controller, colors)
    // A new copy takes the predictions' place (never a word being typed), if this field can take it
    fresh != null && !controller.toolbarOpen && controller.suggestions.isPrediction &&
      (fresh.kind == ClipKind.TEXT || controller.acceptsContent(fresh.mime)) -> FreshClipStrip(controller, colors, fresh)
    controller.toolbarOpen || controller.suggestions.items.isEmpty() -> Toolbar(controller, colors)
    else -> SuggestionStrip(controller, colors)
  }
}

@Composable
private fun Toolbar(controller: InputController, colors: KeyboardColors) {
  Row(Modifier.fillMaxWidth().height(StripHeight).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    if (controller.toolbarOpen) ToolButton(Icons.Back, R.string.close_toolbar, colors) { controller.toolbarOpen = false }
    // The user's picks; everything is also in the options panel, which is always here
    for (item in Prefs.toolbar().filter { it.isEnabled() }) {
      ToolButton(item.icon, item.label, colors, active = item.isOn()) { item.act(controller, colors) }
    }
    ToolButton(Icons.Settings, R.string.keyboard_options, colors) { controller.panel = Panel.OPTIONS }
    // Last, where the suggestion strip has it too, so it doesn't move when the toolbar opens
    ToolButton(Icons.Clipboard, R.string.clipboard, colors) { controller.panel = Panel.CLIPBOARD }
  }
}

/** Three slots: the best word in the middle, bold when space will autocorrect to it. Long-press forgets a word. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestionStrip(controller: InputController, colors: KeyboardColors) {
  val suggestions = controller.suggestions
  val onPick = controller::pickSuggestion
  val onForget = controller::forgetSuggestion
  Row(Modifier.fillMaxWidth().height(StripHeight).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    ToolButton(Icons.Next, R.string.open_toolbar, colors) { controller.toolbarOpen = true }
    for (i in 0 until 3) {
      if (i > 0) Box(Modifier.width(1.dp).height(20.dp).background(colors.divider))
      val word = suggestions.items.getOrNull(i)
      val clickable = if (word == null) Modifier else {
        Modifier.combinedClickable(onClick = { onPick(i) }, onLongClick = { onForget(i) }, onLongClickLabel = stringResource(R.string.forget_word))
      }
      Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(22.dp)).then(clickable), contentAlignment = Alignment.Center) {
        if (word != null) {
          Text(
            word,
            Modifier.padding(horizontal = 6.dp),
            color = if (i == 1) colors.text else colors.muted,
            fontSize = 19.sp,
            fontWeight = if (i == 1 && suggestions.autocorrect != null) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
    // Emoji for the word being typed ("pizza" → 🍕) take the clipboard button's place
    val emoji = remember(suggestions) {
      if (suggestions.isPrediction || !Prefs.emojiSuggestions.value) emptyList()
      else Emoji.forWord(suggestions.typed).ifEmpty { suggestions.autocorrect?.let(Emoji::forWord).orEmpty() }
    }
    if (emoji.isEmpty()) {
      ToolButton(Icons.Clipboard, R.string.clipboard, colors) { controller.panel = Panel.CLIPBOARD }
    } else {
      for (e in emoji) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { controller.pickEmoji(e) }, contentAlignment = Alignment.Center) {
          Text(e, fontSize = 24.sp)
        }
      }
    }
  }
}

/** A 44 dp round icon button for the strip, toolbar and panels. */
@Composable
internal fun ToolButton(icon: ImageVector, label: Int, colors: KeyboardColors, active: Boolean = false, onClick: () -> Unit) {
  val view = LocalView.current
  Box(
    Modifier.size(44.dp).clip(CircleShape).background(if (active) colors.special else Color.Transparent).clickable {
      Haptics.tap(view)
      onClick()
    },
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, stringResource(label), Modifier.size(20.dp), tint = if (active) colors.text else colors.muted)
  }
}

private fun DrawScope.drawKey(
  bounds: KeyBounds,
  isPressed: Boolean,
  controller: InputController,
  colors: KeyboardColors,
  measurer: TextMeasurer,
  borders: Boolean,
) {
  val key = bounds.key
  val functional = key.special || key.type == KeyType.SHIFT || key.type == KeyType.BACKSPACE || key.type == KeyType.LAYER
  val fill = when {
    key.type == KeyType.ENTER -> colors.accent
    isPressed -> colors.pressed
    functional -> colors.special
    else -> colors.key
  }
  val topLeft = Offset(bounds.left, bounds.top)
  val size = Size(bounds.width, bounds.height)
  val radius = CornerRadius(KeyRadius.toPx())
  // Borderless keeps the space bar, enter and the bottom-row layer switch (?123/ABC) filled so they stay easy to find
  val bottomRow = bounds.bottom >= this@drawKey.size.height - BottomPadding.toPx() - 1f
  if (borders || isPressed || key.type == KeyType.SPACE || key.type == KeyType.ENTER || key.type == KeyType.LAYER && bottomRow) {
    if (!isPressed && (borders || key.type == KeyType.SPACE)) drawRoundRect(colors.shadow, topLeft + Offset(0f, 1.dp.toPx()), size, radius)
    drawRoundRect(fill, topLeft, size, radius)
  }

  val center = Offset(bounds.centerX, bounds.centerY)
  when (key.type) {
    KeyType.SHIFT -> {
      val on = controller.shift != ShiftState.OFF
      drawIcon(ShiftIcon, center, 22.dp.toPx(), if (controller.shift == ShiftState.LOCKED) colors.accent else colors.text, filled = on)
      if (controller.shift == ShiftState.LOCKED) {
        drawLine(colors.accent, center + Offset(-7.dp.toPx(), 14.dp.toPx()), center + Offset(7.dp.toPx(), 14.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
      }
    }
    KeyType.BACKSPACE -> drawIcon(BackspaceIcon, center, 24.dp.toPx(), colors.text)
    KeyType.ENTER -> drawIcon(EnterIcon, center, 22.dp.toPx(), colors.onAccent, strokeWidth = 2f)
    KeyType.SPACE -> drawLabel(measurer, key.label, center, TextStyle(color = colors.muted, fontSize = 15.sp))
    KeyType.LAYER -> drawLabel(measurer, key.label, center, TextStyle(color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
    KeyType.CHAR -> {
      drawLabel(measurer, controller.display(key.label), center, TextStyle(color = colors.text, fontSize = if (functional) 25.sp else 27.sp))
      // Hint that holding this key opens emoji search
      if (key.longPress == "emoji_search") {
        drawIcon(SmileIcon, Offset(bounds.right - 9.dp.toPx(), bounds.top + 9.dp.toPx()), 11.dp.toPx(), colors.hint, strokeWidth = 2.2f)
      }
      key.hint?.let { hint ->
        val layout = measurer.measure(hint, TextStyle(color = colors.hint, fontSize = 11.sp, fontWeight = FontWeight.Medium))
        drawText(layout, topLeft = Offset(bounds.right - 5.dp.toPx() - layout.size.width, bounds.top + 2.dp.toPx()))
      }
    }
  }
}

private fun DrawScope.drawPopup(popup: Popup, controller: InputController, colors: KeyboardColors, measurer: TextMeasurer) {
  val pad = 4.dp.toPx()
  val topLeft = Offset(popup.left - pad, popup.top - pad)
  val size = Size(popup.cellWidth * popup.items.size + 2 * pad, popup.cellHeight + 2 * pad)
  drawRoundRect(Color.Black.copy(alpha = 0.18f), topLeft + Offset(0f, 3.dp.toPx()), size, CornerRadius(12.dp.toPx()))
  drawRoundRect(colors.popup, topLeft, size, CornerRadius(12.dp.toPx()))
  popup.items.forEachIndexed { i, item ->
    val left = popup.left + i * popup.cellWidth
    val selected = i == popup.selected
    if (selected) {
      drawRoundRect(colors.accent, Offset(left, popup.top), Size(popup.cellWidth, popup.cellHeight), CornerRadius(KeyRadius.toPx()))
    }
    val center = Offset(left + popup.cellWidth / 2, popup.top + popup.cellHeight / 2)
    drawLabel(measurer, controller.display(item), center, TextStyle(color = if (selected) colors.onAccent else colors.text, fontSize = 24.sp))
  }
}

/**
 * The glide trail in the accent colour (a pale halo looked washed out). Each piece fades and thins
 * with its age, so the line tapers behind the finger and melts away after it lifts.
 */
private fun DrawScope.drawGlideTrail(touches: TouchTracker, colors: KeyboardColors) {
  touches.trailTick // Read so each frame of the fade redraws
  val trail = touches.trail
  if (trail.size < 2) return
  val now = SystemClock.uptimeMillis()
  val thick = TrailStartWidth.toPx()
  val thin = TrailEndWidth.toPx()
  for (i in 1 until trail.size) {
    val a = trail[i - 1]
    val b = trail[i]
    val age = (now - b.t).toFloat()
    val life = age / (TRAIL_SOLID_MS + TRAIL_FADE_MS)
    if (life >= 1f) continue
    val alpha = if (age < TRAIL_SOLID_MS) 1f else 1f - (age - TRAIL_SOLID_MS) / TRAIL_FADE_MS
    drawLine(colors.accent.copy(alpha = alpha), Offset(a.x, a.y), Offset(b.x, b.y), thick + (thin - thick) * life, StrokeCap.Round, blendMode = BlendMode.Src)
  }
}

private val TrailStartWidth = 10.dp
private val TrailEndWidth = 2.dp

/** Debug overlay: fingers currently down, or else the last finished trace, with every raw sample dotted. */
private fun DrawScope.drawTouchPaths(recorder: GestureRecorder, colors: KeyboardColors) {
  val paths = recorder.live.values.ifEmpty { listOfNotNull(recorder.traces.lastOrNull()?.points) }
  for (points in paths) {
    val path = Path()
    points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
    drawPath(path, colors.accent.copy(alpha = 0.7f), style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPoints(points.map { Offset(it.x, it.y) }, PointMode.Points, colors.text, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
    points.firstOrNull()?.let { drawCircle(colors.accent, radius = 5.dp.toPx(), center = Offset(it.x, it.y)) }
  }
}

private fun DrawScope.drawLabel(measurer: TextMeasurer, text: String, center: Offset, style: TextStyle) {
  val layout = measurer.measure(text, style)
  drawText(layout, topLeft = center - Offset(layout.size.width / 2f, layout.size.height / 2f))
}

/** Draws a 24×24 viewport stroke icon (the same paths as the design) centred at [center]. */
private fun DrawScope.drawIcon(path: Path, center: Offset, size: Float, color: Color, filled: Boolean = false, strokeWidth: Float = 1.8f) {
  val scale = size / 24f
  withTransform({
    translate(center.x - size / 2, center.y - size / 2)
    scale(scale, scale, pivot = Offset.Zero)
  }) {
    if (filled) drawPath(path, color, style = Fill)
    drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
  }
}
