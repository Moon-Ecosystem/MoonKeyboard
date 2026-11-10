package com.moonkeyboard.ime

import android.content.res.Configuration
import android.os.SystemClock
import android.media.AudioManager
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.key
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
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
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import com.moonkeyboard.R
import com.moonkeyboard.engine.decode.KeyMap
import com.moonkeyboard.engine.history.UserHistory
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
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.foundation.layout.fillMaxSize
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
import com.moonkeyboard.engine.layout.Layouts

// The design's key: labels are drawn at their sizes for a key this tall, and scale with the real one
private val KeyHeight = 48.dp
private val HGap = 6.dp
// Roomy rows: with the larger labels a tighter gap looked cramped
private val VGap = 15.dp
// Portrait, as AOSP's keyboard: the letters' rows and gaps fill a height set by the screen's width
// (55% of it, our 4 rows of 48 dp on a 432 dp wide phone), so a number row squeezes the rows instead
// of adding one, and display or font size settings don't inflate the keys. Capped by the screen's
// height (a wide unfolded screen) and by a largest key (tablets).
private const val PORTRAIT_WIDTH_SHARE = 0.55f
private const val MAX_HEIGHT_SHARE = 0.3f
private val MaxKeyHeight = 60.dp
// A fifth row (numbers) halves the gaps between rows, so the keys keep most of their height
private const val FIVE_ROW_GAP = 0.5f
private val SidePadding = 4.dp
internal val StripHeight = 48.dp
private val TopPadding = 6.dp
// Some devices under-report the navigation-bar inset for the IME nav strip (gesture nav + a
// dismiss/switcher icon drawn above it); the extra breathing room keeps the bottom row clear
private val BottomPadding = 16.dp
private val KeyRadius = 8.dp
// A long-press hint is centred this far in from the key's top-right corner; the letter drops a little to clear it
private val HintInset = 9.dp
private val HintLabelDrop = 2.dp
private const val ONE_HANDED_SHARE = 0.84f
// The emoji panel's extra height over the keys, as a share of the screen's
private const val EMOJI_EXTRA_SHARE = 0.05f
// Landscape: everything smaller, and rows closer, so the app keeps about half the screen
private const val LANDSCAPE_SCALE = 0.8f
private val LandscapeVGap = 8.dp
private const val SPLIT_SHARE = 0.28f

private val ShiftIcon = icon("M12 4l8 8h-5v8H9v-8H4z")
// The × sits a little left of the box's middle, so it looks centred in the whole shape, point included
private val BackspaceIcon = icon("M9 5h11a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H9l-6-7zM11 9l6 6M17 9l-6 6")
private val EnterIcon = icon("M20 5v7a2 2 0 0 1-2 2H5M9 10l-4 4 4 4")
// The handle starts on the lens (radius 7 meets the diagonal at ~16), not inside it
private val SearchIcon = icon("M16 16l4.5 4.5M4 11a7 7 0 1 0 14 0a7 7 0 1 0-14 0")
private val GoIcon = icon("M4 12h16M14 6l6 6-6 6")
private val SendIcon = icon("M4 12l17-8-7 18-3-7z")
private val DoneIcon = icon("M5 12l5 5 10-11")
private val PrevIcon = icon("M20 12H4M10 6l-6 6 6 6")
private val SmileIcon = icon("M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0M8 14s1.5 2 4 2 4-2 4-2M9 9h.01M15 9h.01")

private fun enterIcon(action: Int): Path = when (action) {
  android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH -> SearchIcon
  android.view.inputmethod.EditorInfo.IME_ACTION_SEND -> SendIcon
  android.view.inputmethod.EditorInfo.IME_ACTION_GO -> GoIcon
  android.view.inputmethod.EditorInfo.IME_ACTION_DONE -> DoneIcon
  android.view.inputmethod.EditorInfo.IME_ACTION_NEXT -> GoIcon
  android.view.inputmethod.EditorInfo.IME_ACTION_PREVIOUS -> PrevIcon
  else -> EnterIcon
}

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
  val heightScale = Prefs.keyboardHeight.value / 100f
  val vGap = if (landscape) LandscapeVGap else VGap
  val split = landscape && !floating && oneHanded == OneHanded.OFF && Prefs.splitLandscape.value
  val density = LocalDensity.current
  val view = LocalView.current
  val audio = remember { view.context.getSystemService(AudioManager::class.java) }
  val scope = rememberCoroutineScope()
  val fontResolver = LocalFontFamilyResolver.current
  val layoutDirection = LocalLayoutDirection.current
  val screen = view.resources.displayMetrics
  val raw = controller.layout
  // Landscape rows are roomy, so the space bar takes some of that width
  val layout = remember(raw, landscape) { if (landscape) Layouts.wideSpace(raw) else raw }

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
    val docked = !landscape && !floating
    val (geometry, letterKey) = remember(layout, letters, widthPx, heightScale, vGap, split, density, docked, screen.widthPixels, screen.heightPixels) {
      with(density) {
        // The letters' rows and gaps share one height, however many rows there are. Landscape and the
        // floating card keep the design's 4 rows of keys, scaled through their density as before
        val area = heightScale * if (docked) {
          minOf(screen.widthPixels * PORTRAIT_WIDTH_SHARE, screen.heightPixels * MAX_HEIGHT_SHARE)
        } else 4 * KeyHeight.toPx() + 3 * vGap.toPx()
        val gapRatio = vGap.toPx() / KeyHeight.toPx() * (if (letters.rows.size > 4) FIVE_ROW_GAP else 1f)
        var k = minOf(
          area / (letters.rows.sumOf { it.height.toDouble() }.toFloat() + (letters.rows.size - 1) * gapRatio),
          MaxKeyHeight.toPx() * heightScale,
        )
        val letterGap = k * gapRatio
        val letterKey = k
        // Every layer is as tall as the letters: a layer with fewer rows (symbols next to letters
        // with a number row) spreads them out instead of shrinking the keyboard
        fun keysHeight(l: Layout) = l.rows.sumOf { (k * it.height).toDouble() }.toFloat()
        val target = keysHeight(letters) + (letters.rows.size - 1) * letterGap
        // A number pad's keys grow taller instead, so they stay big targets
        if (controller.layerId in InputController.PADS) k = (target - (layout.rows.size - 1) * letterGap) / layout.rows.sumOf { it.height.toDouble() }.toFloat()
        val gap = if (layout.rows.size > 1) maxOf(letterGap, (target - keysHeight(layout)) / (layout.rows.size - 1)) else letterGap
        KeyboardGeometry.compute(
          layout, widthPx, k, HGap.toPx(), gap, SidePadding.toPx(), topPadding = TopPadding.toPx(),
          splitGap = if (split && controller.layerId !in InputController.PADS) widthPx * SPLIT_SHARE else 0f,
        ) to letterKey
      }
    }
    // Key labels are sized for the design's key and scale with the letter keys' real height (as AOSP
    // sizes them by key height), never with the system font size: a large font made the keys look bloated
    val measurer = remember(letterKey, density, fontResolver, layoutDirection) {
      val labelScale = letterKey / with(density) { KeyHeight.toPx() }
      TextMeasurer(fontResolver, Density(density.density, labelScale), layoutDirection, cacheSize = 64)
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
    // The recent-emoji row sits over the strip; the panels count it too, so opening one doesn't change the height
    val emojiRow = Prefs.emojiRow.value && controller.emojiQuery == null
    val rows = StripHeight + if (emojiRow) EmojiRowHeight else 0.dp
    val panelModifier = Modifier.fillMaxWidth().height(rows + height)

    when (controller.panel) {
      Panel.CLIPBOARD -> ClipboardPanel(controller, colors, panelModifier)
      // Taller than the keys, so emoji can be big: the app above moves up to make room, by a share of
      // the screen so it stays in proportion (not in landscape or floating, with little room to spare)
      Panel.EMOJI -> {
        val extra = if (docked) with(density) { (screen.heightPixels * EMOJI_EXTRA_SHARE * heightScale).toDp() } else 0.dp
        // The same gap at the bottom as the keys, so its bottom bar clears the navigation strip's icons
        EmojiPanel(controller, colors, Modifier.fillMaxWidth().height(rows + height + extra).padding(bottom = BottomPadding))
      }
      Panel.EDIT -> EditPanel(controller, colors, panelModifier)
      Panel.OPTIONS -> OptionsPanel(controller, colors, panelModifier)
      Panel.KEYS -> Column {
        if (emojiRow) EmojiRow(controller)
        // Emoji search stands in for the strip, taller: the app above moves up to make room
        if (controller.emojiQuery != null) EmojiSearch(controller, colors)
        // A hairline along the strip's bottom edge, so the bar reads as separate from the keys
        else Box(Modifier.drawBehind {
          val y = size.height - 0.5.dp.toPx()
          // Edge to edge: an inset line lined up with neither the strip's buttons nor the keys
          drawLine(colors.divider, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        }) { Strip(controller, colors) }
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
              val popup = touches.popup
              if (popup != null) drawPopup(popup, controller, colors, measurer)
              else if (Prefs.keyPreview.value) {
                for (bounds in touches.pressed.values) if (bounds.key.type == KeyType.CHAR) drawKeyPreview(bounds, controller, colors, measurer)
              }
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
  ToolbarItem.INCOGNITO -> Icons.Incognito
}

internal val ToolbarItem.label get() = when (this) {
  ToolbarItem.EMOJI -> R.string.emoji
  ToolbarItem.THEME -> R.string.theme
  ToolbarItem.FLOATING -> R.string.floating_short
  ToolbarItem.ONE_HANDED -> R.string.one_handed_short
  ToolbarItem.TEXT_EDIT -> R.string.text_editing
  ToolbarItem.SPLIT -> R.string.split_short
  ToolbarItem.INCOGNITO -> R.string.incognito
}

/** A setting that is on, shown highlighted. */
internal fun ToolbarItem.isOn() = when (this) {
  // Highlighted while its long press has the recent-emoji row showing
  ToolbarItem.EMOJI -> Prefs.emojiRow.value
  ToolbarItem.FLOATING -> Prefs.floating.value
  ToolbarItem.ONE_HANDED -> !Prefs.floating.value && Prefs.oneHanded.value != OneHanded.OFF
  ToolbarItem.SPLIT -> Prefs.splitLandscape.value
  ToolbarItem.INCOGNITO -> Prefs.incognito.value
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
    ToolbarItem.INCOGNITO -> Prefs.incognito.value = !Prefs.incognito.value
  }
}

// Flips what's on screen; cycling through "follow system" looked like a dead tap when that matched
internal fun flipTheme(colors: KeyboardColors) {
  Prefs.theme.value = if (colors.isDark) ThemeMode.LIGHT else ThemeMode.DARK
}

@Composable
internal fun keyboardColors(): KeyboardColors {
  val dark = when (Prefs.theme.value) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
  }
  val wallpaper = Prefs.accent.value == Accent.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
  val colors = if (wallpaper) {
    // Read again when the configuration changes, which is how a new wallpaper's palette arrives
    LocalConfiguration.current
    KeyboardColors.dynamic(LocalContext.current, dark)
  } else {
    (if (dark) KeyboardColors.Dark else KeyboardColors.Light).withAccent(Prefs.accent.value)
  }
  return if (dark && Prefs.pitchBlack.value) colors.pitchBlack(moonKeys = !wallpaper) else colors
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

internal val EmojiRowHeight = 40.dp
private const val EMOJI_ROW_COUNT = 10
// Until there are recent ones of the user's own
private val COMMON_EMOJI = listOf("😂", "❤️", "👍", "😭", "🙏", "😊", "🔥", "😍", "🥺", "✨")

/**
 * The recent emoji, one tap each, above the strip. Their order is read once per field: re-sorting as
 * each one is used would move them under the finger.
 */
@Composable
private fun EmojiRow(controller: InputController) {
  val shown = remember(controller.fieldsOpened) {
    val recents = Emoji.recents
    (recents + COMMON_EMOJI.filter { it !in recents }).take(EMOJI_ROW_COUNT)
  }
  Row(Modifier.fillMaxWidth().height(EmojiRowHeight).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    for (emoji in shown) {
      Box(
        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).clickable {
          controller.insert(emoji)
          Emoji.used(emoji)
        },
        contentAlignment = Alignment.Center,
      ) {
        Text(emoji, fontSize = 22.sp)
      }
    }
  }
}

/** Suggestions, or the toolbar when it is open or there is nothing to suggest. */
@Composable
private fun Strip(controller: InputController, colors: KeyboardColors) {
  // Read here, not in KeyboardUi, so a new suggestion recomposes only the strip
  val fresh = if (Prefs.offerCopied.value) ClipboardStore.freshClip() else null
  val chips = controller.inlineChips
  when {
    // Autofill chips from the system (a password manager, etc.) take the strip over the predictions;
    // tapping one fills the field — the autofill service drew it and owns the touch
    chips.isNotEmpty() && !controller.toolbarOpen -> InlineChipStrip(controller, colors, chips)
    // A new copy takes the predictions' place (never a word being typed), if this field can take it
    fresh != null && !controller.toolbarOpen && !controller.clipSetAside && controller.suggestions.isPrediction &&
      (if (fresh.kind == ClipKind.TEXT) controller.copiedTextOffer(fresh.content) != null else controller.acceptsContent(fresh.mime)) -> FreshClipStrip(controller, colors, fresh)
    // Keep showing even once backspace reaches the previous word and picks it up for spelling suggestions;
    // any key other than backspace clears it, so it's never in the way of real typing
    controller.undo != null && !controller.toolbarOpen -> UndoStrip(controller, colors, controller.undo!!)
    controller.toolbarOpen || controller.suggestions.items.isEmpty() -> Toolbar(controller, colors)
    else -> SuggestionStrip(controller, colors)
  }
}

/** Where the chip row and each chip are, so every chip can be told how much of it is in view. */
private class ChipClip {
  var row: LayoutCoordinates? = null
  val chips = HashMap<android.view.View, LayoutCoordinates>()
}

/**
 * An autofill chip draws on its own surface, which the row's clip doesn't reach: a chip scrolled
 * partly out of the row would paint over the icons beside it. Its setClipBounds crops that
 * surface, so it gets the part of it that is inside the row (none when fully in view).
 */
private fun clipChip(view: android.view.View, chip: LayoutCoordinates, row: LayoutCoordinates, clip: ChipClip) {
  if (!chip.isAttached || !row.isAttached) return
  val inRow = row.localBoundingBoxOf(chip, clipBounds = false)
  val left = (maxOf(0f, inRow.left) - inRow.left).toInt()
  val right = (minOf(row.size.width.toFloat(), inRow.right) - inRow.left).toInt()
  // A chip wholly out of the row keeps its surface but crops it to a pixel off the chip: an empty
  // clip reads as no clip at all, and hiding the view tears the surface down, which then showed
  // for a frame unclipped when it came back
  val hidden = right <= left
  val bounds = when {
    hidden -> OFF_CHIP
    left <= 0 && right >= chip.size.width -> null
    else -> android.graphics.Rect(left, 0, right, chip.size.height)
  }
  if (bounds != view.clipBounds) view.clipBounds = bounds
  // Hidden only until its first clip (see setInlineSuggestions), never again after
  if (view.visibility != android.view.View.VISIBLE) view.visibility = android.view.View.VISIBLE
}

private val OFF_CHIP = android.graphics.Rect(-2, -2, -1, -1)

@Composable
private fun InlineChipStrip(controller: InputController, colors: KeyboardColors, chips: List<android.view.View>) {
  Row(Modifier.fillMaxWidth().height(StripHeight).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    // Same left/right leaders as the suggestion strip, so nothing moves when autofill chips come and go
    ToolButton(Icons.Next, R.string.open_toolbar, colors) { controller.toolbarOpen = true }
    val clip = remember { ChipClip() }
    // A plain scrolling Row, not a LazyRow: a chip the lazy list dropped once scrolled away left
    // its surface stranded at the window's top left corner
    BoxWithConstraints(
      Modifier.weight(1f).fillMaxHeight().clipToBounds().onGloballyPositioned { row ->
        clip.row = row
        for ((view, coords) in clip.chips) clipChip(view, coords, row, clip)
      },
      contentAlignment = Alignment.CenterStart,
    ) {
      Row(
        // At least as wide as the row, so one chip sits centred; wider ones scroll
        // No stretch at the ends: it's drawn as an effect the chips' clip bounds can't follow
        Modifier.horizontalScroll(rememberScrollState(), overscrollEffect = null).widthIn(min = maxWidth).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(InputController.CHIP_GAP_DP.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
      ) {
      for (view in chips) key(view) {
        DisposableEffect(view) { onDispose { clip.chips.remove(view) } }
        // Fade in and slide in slightly from the right
        val enter = remember(view) { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
          visibleState = enter,
          enter = fadeIn(tween(200)) + slideInHorizontally(tween(220)) { it / 3 },
        ) {
          AndroidView(
            factory = { view },
            modifier = Modifier.onGloballyPositioned { coords ->
              clip.chips[view] = coords
              clip.row?.let { clipChip(view, coords, it, clip) }
            },
          )
        }
      }
      }
    }
    ToolButton(Icons.Clipboard, R.string.clipboard, colors) { controller.panel = Panel.CLIPBOARD }
  }
}

@Composable
private fun Toolbar(controller: InputController, colors: KeyboardColors) {
  Row(Modifier.fillMaxWidth().height(StripHeight).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    // Opened from the suggestions: back to them. Shown for want of any: a mark of where you are, so
    // the strip's left end is never bare
    if (controller.toolbarOpen) ToolButton(Icons.Back, R.string.close_toolbar, colors) { controller.toolbarOpen = false }
    // The app's cat, its themed-icon silhouette; the cat fills about half of that square
    else Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_launcher_cat_mono), null, Modifier.size(42.dp), tint = colors.muted) }
    // The user's picks; everything is also in the options panel, which is always here
    for (item in Prefs.toolbar().filter { it.isEnabled() }) {
      // Holding emoji shows or hides the recent-emoji row (also in Look)
      val longPress = if (item == ToolbarItem.EMOJI) ({ Prefs.emojiRow.value = !Prefs.emojiRow.value }) else null
      ToolButton(item.icon, item.label, colors, active = item.isOn(), onLongClick = longPress) { item.act(controller, colors) }
    }
    ToolButton(Icons.Settings, R.string.keyboard_options, colors) { controller.panel = Panel.OPTIONS }
    // Last, where the suggestion strip has it too, so it doesn't move when the toolbar opens
    ToolButton(Icons.Clipboard, R.string.clipboard, colors) { controller.panel = Panel.CLIPBOARD }
  }
}

/** One suggestion: squeezed to fit its [textWidth] px, cut in the middle only past the limit. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestionSlot(controller: InputController, colors: KeyboardColors, measurer: TextMeasurer, i: Int, word: String?, bold: Boolean, textWidth: Int, modifier: Modifier) {
  // Long-pressed: the slot asks whether to forget the word, the rest of the strip stays
  if (word != null && word == controller.forgetAsk) {
    Row(modifier.fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
      ForgetButton(Icons.Trash, stringResource(R.string.forget_word), colors, controller::forgetSuggestion)
      ForgetButton(Icons.Close, stringResource(android.R.string.cancel), colors, controller::cancelForget)
    }
    return
  }
  val clickable = if (word == null) Modifier else {
    Modifier.combinedClickable(onClick = { controller.pickSuggestion(i) }, onLongClick = { controller.askForget(i) }, onLongClickLabel = stringResource(R.string.forget_word))
  }
  Box(modifier.fillMaxHeight().clip(RoundedCornerShape(22.dp)).then(clickable), contentAlignment = Alignment.Center) {
    if (word != null) {
      val style = suggestionStyle(colors, i, bold)
      val scale = remember(word, style, textWidth) { fitScale(measurer, word, style, textWidth) }
      Text(
        word,
        Modifier.padding(horizontal = SlotPadding),
        style = style.copy(textGeometricTransform = TextGeometricTransform(scaleX = maxOf(scale, MIN_TEXT_SCALE))),
        maxLines = 1,
        softWrap = false,
        // Similar suggestions (implementation, implementations) differ at the end, so the middle goes;
        // addresses differ before the "@" and share their domain, so the end goes
        overflow = when {
          scale >= MIN_TEXT_SCALE -> TextOverflow.Clip
          '@' in word -> TextOverflow.Ellipsis
          else -> TextOverflow.MiddleEllipsis
        },
      )
    }
  }
}

@Composable
private fun ForgetButton(icon: ImageVector, label: String, colors: KeyboardColors, onClick: () -> Unit) {
  Box(Modifier.size(36.dp).clip(CircleShape).background(colors.key).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
    Icon(icon, label, Modifier.size(18.dp), tint = colors.text)
  }
}

// As in AOSP's strip: the middle slot is 40% of the strip and the sides 30% each, whatever the words
private const val CENTER_SHARE = 0.4f
// A word too wide for its slot is first squeezed, down to this much of its width, and only then cut in the middle
private const val MIN_TEXT_SCALE = 0.7f
private val SlotPadding = 6.dp

private fun suggestionStyle(colors: KeyboardColors, i: Int, bold: Boolean) = TextStyle(
  color = if (i == 1) colors.text else colors.muted,
  fontSize = 19.sp,
  fontWeight = if (i == 1 && bold) FontWeight.SemiBold else FontWeight.Normal,
)

/** How much [word] has to be squeezed to fit [width] px: 1 when it fits as it is. */
private fun fitScale(measurer: TextMeasurer, word: String, style: TextStyle, width: Int): Float {
  val natural = measurer.measure(word, style, maxLines = 1, softWrap = false).size.width
  return if (natural <= width) 1f else width.toFloat() / natural
}

/** Three slots side by side, best in the middle. The middle goes bold only when space will autocorrect to it. */
@Composable
private fun SuggestionStrip(controller: InputController, colors: KeyboardColors) {
  val suggestions = controller.suggestions
  val measurer = rememberTextMeasurer()
  Row(Modifier.fillMaxWidth().height(StripHeight).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    ToolButton(Icons.Next, R.string.open_toolbar, colors) { controller.toolbarOpen = true }
    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
      val words = List(3) { suggestions.items.getOrNull(it)?.takeIf(String::isNotEmpty) }
      val bold = suggestions.autocorrect != null
      val density = LocalDensity.current
      val pad = with(density) { (SlotPadding * 2).roundToPx() }
      val fullWidth = constraints.maxWidth
      val slots = fullWidth - 2 * with(density) { 1.dp.roundToPx() }
      val center = words[1]
      // Several saved addresses: each would be too long for its slot, and the whole-strip rule below
      // would show only one, so the addresses alone share the strip equally
      val emails = words.indices.filter { words[it]?.let(UserHistory::isEmail) == true }
      // As in AOSP: a middle word that would have to be squeezed past the limit gets the whole strip
      val centerOnly = emails.size < 2 && center != null && center != controller.forgetAsk &&
        fitScale(measurer, center, suggestionStyle(colors, 1, bold), (slots * CENTER_SHARE).toInt() - pad) < MIN_TEXT_SCALE
      Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        if (emails.size >= 2) {
          val share = 1f / emails.size
          emails.forEachIndexed { n, i ->
            if (n > 0) Box(Modifier.width(1.dp).height(20.dp).background(colors.divider))
            SuggestionSlot(controller, colors, measurer, i, words[i], bold, (slots * share).toInt() - pad, Modifier.weight(share))
          }
        } else if (centerOnly) {
          SuggestionSlot(controller, colors, measurer, 1, center, bold, fullWidth - pad, Modifier.weight(1f))
        } else {
          for (i in 0 until 3) {
            if (i > 0) Box(Modifier.width(1.dp).height(20.dp).background(colors.divider))
            val share = if (i == 1) CENTER_SHARE else (1 - CENTER_SHARE) / 2
            SuggestionSlot(controller, colors, measurer, i, words[i], bold, (slots * share).toInt() - pad, Modifier.weight(share))
          }
        }
      }
    }
    // Emoji for the word being typed ("pizza" → 🍕) take the clipboard button's place
    val emoji = remember(suggestions) {
      // Not for a glide still under way: its guess changes every few frames
      // Nor inside a word, where an emoji would land in the middle of it
      if (suggestions.isPrediction || suggestions === controller.glidePreview || controller.midWord != null || !Prefs.emojiSuggestions.value) emptyList()
      else Emoji.forWord(suggestions.typed).ifEmpty { suggestions.autocorrect?.let(Emoji::forWord).orEmpty() }
    }
    if (emoji.isEmpty()) {
      ToolButton(Icons.Clipboard, R.string.clipboard, colors) { controller.panel = Panel.CLIPBOARD }
    } else {
      // The same line as between the words, before each emoji, so each reads as its own choice
      for (e in emoji) {
        Box(Modifier.width(1.dp).height(20.dp).background(colors.divider))
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { controller.pickEmoji(e) }, contentAlignment = Alignment.Center) {
          Text(e, fontSize = 24.sp)
        }
      }
    }
  }
}

/** A 44 dp round icon button for the strip, toolbar and panels. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun ToolButton(icon: ImageVector, label: Int, colors: KeyboardColors, active: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
  val view = LocalView.current
  Box(
    Modifier.size(44.dp).clip(CircleShape).background(if (active) colors.special else Color.Transparent).combinedClickable(
      onLongClick = onLongClick?.let { action -> { Haptics.tap(view); action() } },
    ) {
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
    key.type == KeyType.ENTER -> colors.enter
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
    KeyType.ENTER -> drawIcon(enterIcon(controller.enterAction), center, 22.dp.toPx(), colors.onEnter, strokeWidth = 2f)
    KeyType.EMOJI -> drawIcon(SmileIcon, center, 22.dp.toPx(), colors.text, strokeWidth = 2f)
    KeyType.SPACE -> drawLabel(measurer, key.label, center, TextStyle(color = colors.muted, fontSize = 15.sp))
    KeyType.LAYER -> drawLabel(measurer, key.label, center, TextStyle(color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
    KeyType.CHAR -> {
      // Pads write a key's letters beside it (2 ABC), with the digits in one column, as a dial pad does
      if (controller.layerId in InputController.PADS && (key.hint != null || controller.layerId == InputController.PHONE && key.label[0].isDigit())) {
        val main = measurer.measure(key.label, TextStyle(color = colors.text, fontSize = 27.sp))
        val x = bounds.left + bounds.width * 0.36f - main.size.width / 2
        drawText(main, topLeft = Offset(x, bounds.centerY - main.size.height / 2))
        key.hint?.let { hint ->
          val side = measurer.measure(hint, TextStyle(color = colors.muted, fontSize = 15.sp))
          drawText(side, topLeft = Offset(x + main.size.width + 10.dp.toPx(), bounds.centerY - side.size.height / 2))
        }
        return
      }
      // A key with a hint draws its letter a little smaller and lower, so it keeps clear of the corner
      val hinted = key.hint != null
      val labelCenter = if (hinted) center + Offset(0f, HintLabelDrop.toPx()) else center
      drawLabel(measurer, controller.display(key.label), labelCenter, TextStyle(color = colors.text, fontSize = if (functional) 25.sp else if (hinted) 25.sp else 27.sp))
      // The corner spot every hint is centred in, whatever its glyph's shape ("_" sits low, "^" high)
      val corner = Offset(bounds.right - HintInset.toPx(), bounds.top + HintInset.toPx())
      // Hint that holding this key opens emoji search
      if (key.longPress == "emoji") {
        drawIcon(SmileIcon, corner, 11.dp.toPx(), colors.hint, strokeWidth = 2.2f)
      }
      key.hint?.let { hint ->
        drawLabel(measurer, hint, corner, TextStyle(color = colors.hint, fontSize = 11.sp, fontWeight = FontWeight.Medium))
      }
    }
  }
}

private fun DrawScope.drawPopup(popup: Popup, controller: InputController, colors: KeyboardColors, measurer: TextMeasurer) {
  val pad = 4.dp.toPx()
  val topLeft = Offset(popup.left - pad, popup.top - pad)
  val size = Size(popup.cellWidth * popup.columns + 2 * pad, popup.cellHeight * popup.rows + 2 * pad)
  drawRoundRect(Color.Black.copy(alpha = 0.18f), topLeft + Offset(0f, 3.dp.toPx()), size, CornerRadius(12.dp.toPx()))
  drawRoundRect(colors.popup, topLeft, size, CornerRadius(12.dp.toPx()))
  popup.items.forEachIndexed { i, item ->
    val left = popup.left + (i % popup.columns) * popup.cellWidth
    val top = popup.top + (i / popup.columns) * popup.cellHeight
    val selected = i == popup.selected
    if (selected) {
      drawRoundRect(colors.accent, Offset(left, top), Size(popup.cellWidth, popup.cellHeight), CornerRadius(KeyRadius.toPx()))
    }
    val center = Offset(left + popup.cellWidth / 2, top + popup.cellHeight / 2)
    val color = if (selected) colors.onAccent else colors.text
    if (popup.languages != null) {
      // Language names stay as written, smaller, and shrink further rather than run into the next one
      val style = TextStyle(color = color, fontSize = 17.sp)
      val fit = popup.cellWidth * 0.9f / measurer.measure(item, style).size.width
      drawLabel(measurer, item, center, if (fit < 1f) style.copy(fontSize = 17.sp * fit) else style)
    } else {
      drawLabel(measurer, controller.display(item), center, TextStyle(color = color, fontSize = 24.sp))
    }
  }
}

/** The pressed key's letter, enlarged in a bubble above it: the finger hides the key itself. */
private fun DrawScope.drawKeyPreview(bounds: KeyBounds, controller: InputController, colors: KeyboardColors, measurer: TextMeasurer) {
  val width = bounds.width.coerceAtMost(bounds.height * 1.4f)
  val topLeft = Offset(bounds.centerX - width / 2, bounds.top - bounds.height - 8.dp.toPx())
  val size = Size(width, bounds.height)
  drawRoundRect(Color.Black.copy(alpha = 0.18f), topLeft + Offset(0f, 3.dp.toPx()), size, CornerRadius(12.dp.toPx()))
  drawRoundRect(colors.popup, topLeft, size, CornerRadius(12.dp.toPx()))
  drawLabel(measurer, controller.display(bounds.key.label), topLeft + Offset(width / 2, bounds.height / 2), TextStyle(color = colors.text, fontSize = 26.sp))
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
