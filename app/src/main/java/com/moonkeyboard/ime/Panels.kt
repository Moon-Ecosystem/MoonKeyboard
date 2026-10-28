package com.moonkeyboard.ime

import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.moonkeyboard.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The strip's row when a panel is open: back to the keys, a title, and the panel's own buttons. */
@Composable
private fun PanelHeader(title: Int, controller: InputController, colors: KeyboardColors, actions: @Composable RowScope.() -> Unit = {}) {
  Row(Modifier.fillMaxWidth().height(StripHeight).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    ToolButton(Icons.Back, R.string.back_to_keyboard, colors) { controller.panel = Panel.KEYS }
    Text(stringResource(title), Modifier.weight(1f).padding(start = 4.dp), color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    actions()
  }
}

// Clipboard

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClipboardPanel(controller: InputController, colors: KeyboardColors, modifier: Modifier) {
  val context = LocalContext.current
  var confirmClear by remember { mutableStateOf(false) }
  // The tile showing its pin/delete buttons after a long press
  var selected by remember { mutableStateOf<Long?>(null) }
  // Pinned first; the sort is stable, so each group stays newest first
  val clips = ClipboardStore.clips.sortedByDescending { it.pinned }

  Column(modifier) {
    PanelHeader(R.string.clipboard, controller, colors) {
      if (confirmClear) {
        Text(
          stringResource(R.string.clear_unpinned),
          Modifier.clip(RoundedCornerShape(22.dp)).background(colors.special).clickable {
            ClipboardStore.clearUnpinned()
            confirmClear = false
          }.padding(horizontal = 14.dp, vertical = 10.dp),
          color = colors.text,
          fontSize = 14.sp,
        )
      } else if (clips.any { !it.pinned }) {
        ToolButton(Icons.Trash, R.string.clear_unpinned, colors) { confirmClear = true }
      }
    }
    if (clips.isEmpty()) {
      Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.clipboard_empty), color = colors.muted, fontSize = 14.sp)
      }
      return@Column
    }
    // Staggered: each tile as tall as its text or image needs, between a minimum and a maximum
    LazyVerticalStaggeredGrid(
      StaggeredGridCells.Fixed(2),
      Modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
      verticalItemSpacing = 8.dp,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(clips, key = { it.id }) { clip ->
        val pastable = clip.kind == ClipKind.TEXT || controller.acceptsContent(clip.mime)
        Box(
          Modifier.heightIn(min = TileMinHeight, max = TileMaxHeight).clip(RoundedCornerShape(10.dp)).background(colors.key).alpha(if (pastable) 1f else 0.4f)
            .combinedClickable(
              onClick = {
                when {
                  selected != null -> selected = null
                  pastable -> if (pasteClip(context, controller, clip)) controller.panel = Panel.KEYS
                }
              },
              onLongClick = { selected = clip.id },
            ),
        ) {
          if (clip.kind == ClipKind.TEXT) {
            Text(
              // Only what fits is laid out: a huge clip in full made the panel slow to open
              clip.content.take(TILE_CHARS),
              Modifier.padding(start = 10.dp, end = 22.dp, top = 8.dp, bottom = 8.dp),
              color = colors.text,
              fontSize = 13.sp,
              lineHeight = 17.sp,
              maxLines = TILE_LINES,
              overflow = TextOverflow.Ellipsis,
            )
          } else {
            ClipImage(clip, keepShape = true)
            if (clip.kind == ClipKind.GIF) {
              Text(
                "GIF",
                Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 6.dp, vertical = 2.dp),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
              )
            }
          }
          if (clip.pinned) Icon(Icons.Pin, stringResource(R.string.pinned), Modifier.align(Alignment.TopEnd).padding(6.dp).size(14.dp), tint = colors.accent)
          if (selected == clip.id) {
            Row(Modifier.matchParentSize().background(colors.popup.copy(alpha = 0.94f)), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
              ToolButton(Icons.Pin, if (clip.pinned) R.string.unpin else R.string.pin, colors, active = clip.pinned) {
                ClipboardStore.setPinned(clip.id, !clip.pinned)
                selected = null
              }
              ToolButton(Icons.Trash, R.string.delete, colors) {
                ClipboardStore.delete(clip.id)
                selected = null
              }
            }
          }
        }
      }
    }
  }
}

private const val TILE_CHARS = 400
private const val TILE_LINES = 8
private val TileMinHeight = 64.dp
private val TileMaxHeight = 180.dp

/** Puts [clip] into the app's field: text as typed text, an image or GIF through commitContent. */
fun pasteClip(context: Context, controller: InputController, clip: Clip): Boolean {
  if (clip.kind == ClipKind.TEXT) {
    controller.insert(clip.content)
    return true
  }
  if (!controller.acceptsContent(clip.mime)) return false
  val uri = FileProvider.getUriForFile(context, "${context.packageName}.clips", ClipboardStore.file(clip))
  // Before Android 7.1 the receiving app can't be granted access through commitContent
  if (Build.VERSION.SDK_INT < 25) context.grantUriPermission(controller.targetPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
  return controller.commitContent(uri, clip.mime, clip.kind.name.lowercase())
}

/**
 * The clip just copied, offered once in place of the predictions: a line of its text (masked in
 * password fields) or a thumbnail of its image.
 */
@Composable
fun FreshClipStrip(controller: InputController, colors: KeyboardColors, clip: Clip) {
  val context = LocalContext.current
  Row(Modifier.fillMaxWidth().height(StripHeight).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    ToolButton(Icons.Next, R.string.open_toolbar, colors) { controller.toolbarOpen = true }
    // A pill as wide as its content, centred; long text stops at about two thirds of the strip
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
    Row(
      Modifier.widthIn(max = 240.dp).height(36.dp).clip(RoundedCornerShape(18.dp)).background(colors.key)
        .clickable {
          if (pasteClip(context, controller, clip)) ClipboardStore.fresh = null
        }
        .padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(Icons.Clipboard, stringResource(R.string.paste_copied), Modifier.size(16.dp), tint = colors.accent)
      Spacer(Modifier.width(8.dp))
      if (clip.kind == ClipKind.TEXT) {
        // One line: newlines and runs of spaces collapse; a copied password is never shown
        val preview = if (controller.isSecretField) "••••••••" else clip.content.take(STRIP_CHARS).replace(Regex("\\s+"), " ").trim()
        Text(preview, color = colors.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      } else {
        Box(Modifier.size(width = 44.dp, height = 28.dp).clip(RoundedCornerShape(6.dp))) { ClipImage(clip) }
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (clip.kind == ClipKind.GIF) R.string.copied_gif else R.string.copied_image), color = colors.text, fontSize = 15.sp)
      }
    }
    }
    ToolButton(Icons.Clipboard, R.string.clipboard, colors) { controller.panel = Panel.CLIPBOARD }
  }
}

private const val STRIP_CHARS = 120

/** A clip's image filling its box, or with [keepShape] as tall as its proportions need (the tile caps it). */
@Composable
private fun ClipImage(clip: Clip, keepShape: Boolean = false) {
  val maxPx = with(LocalDensity.current) { 200.dp.roundToPx() }
  val bitmap by produceState<ImageBitmap?>(null, clip.content) {
    value = withContext(Dispatchers.IO) { runCatching { ClipboardStore.thumbnail(clip, maxPx) }.getOrNull() }
  }
  // shortcut: a GIF shows its first frame, not the animation; AnimatedImageDrawable (API 28+) can play it later
  val image = bitmap
  when {
    !keepShape -> image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    image == null -> Spacer(Modifier.fillMaxWidth().height(96.dp))
    else -> Image(image, null, Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height), contentScale = ContentScale.Crop)
  }
}

// Emoji

/**
 * A search bar, then one scrolling list (recents first, then every category under its
 * name), and a bottom row of grey category icons between ABC and backspace.
 */
@Composable
fun EmojiPanel(controller: InputController, colors: KeyboardColors, modifier: Modifier) {
  val groups = Emoji.groups.collectAsState().value.orEmpty()
  // Read once: re-sorting recents under the finger while picking would move what you aim at
  val recents = remember { Emoji.recents.map { EmojiEntry(listOf(it), emptyList()) } }
  val grid = rememberLazyGridState()
  val scope = rememberCoroutineScope()
  // Skin tones of a long-pressed emoji, shown over the grid
  var tones by remember { mutableStateOf<List<String>?>(null) }
  val pick = { emoji: String ->
    controller.insert(emoji)
    Emoji.used(emoji)
    tones = null
  }
  // Where each section's header sits in the grid, for jumping to it and highlighting the one in view
  val sections = remember(groups, recents) {
    val all = listOf(Section(R.string.recent_emoji, null, recents)) + groups.map { Section(null, it.name, it.entries) }
    var index = 0
    all.filter { it.entries.isNotEmpty() }.map { s -> s.also { it.start = index; index += 1 + s.entries.size } }
  }
  val current by remember(sections) {
    derivedStateOf { sections.indexOfLast { it.start <= grid.firstVisibleItemIndex }.coerceAtLeast(0) }
  }

  Column(modifier) {
    // Search, then the categories beside it
    Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 8.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(
        Modifier.size(width = 52.dp, height = 32.dp).clip(RoundedCornerShape(16.dp)).background(colors.key).clickable(onClick = controller::startEmojiSearch),
        contentAlignment = Alignment.Center,
      ) {
        Icon(Icons.Search, stringResource(R.string.search_emoji), Modifier.size(18.dp), tint = colors.muted)
      }
      sections.forEachIndexed { i, s ->
        val icon = if (s.title != null) Icons.Recent else Icons.EmojiGroups.getOrElse(groups.indexOfFirst { it.name == s.name }) { Icons.Emoji }
        Box(
          Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).clickable {
            tones = null
            scope.launch { grid.scrollToItem(s.start) }
          },
          contentAlignment = Alignment.Center,
        ) {
          Icon(icon, s.title?.let { stringResource(it) } ?: s.name, Modifier.size(18.dp), tint = if (i == current) colors.accent else colors.muted)
        }
      }
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
      LazyVerticalGrid(GridCells.Adaptive(52.dp), Modifier.fillMaxSize(), state = grid, contentPadding = PaddingValues(horizontal = 6.dp)) {
        for (s in sections) {
          item(key = "header:${s.start}", span = { GridItemSpan(maxLineSpan) }) {
            Text(
              s.title?.let { stringResource(it) } ?: s.name.orEmpty(),
              Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp),
              color = colors.muted,
              fontSize = 12.sp,
              fontWeight = FontWeight.Medium,
            )
          }
          items(s.entries, key = { "${s.start}:${it.emoji}" }) { entry -> EmojiCell(entry, { pick(it) }, { tones = entry.variants }) }
        }
      }
      tones?.let { list -> TonePicker(list, colors, Modifier.align(Alignment.TopCenter), pick) }
    }
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
      PanelKey(colors, Modifier.width(52.dp), onClick = { controller.panel = Panel.KEYS }) { Text("ABC", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
      Spacer(Modifier.weight(1f))
      PanelKey(colors, Modifier.width(52.dp), onClick = controller::deleteBackward) { Icon(Icons.Backspace, stringResource(R.string.delete), Modifier.size(20.dp), tint = colors.text) }
    }
  }
}

private class Section(val title: Int?, val name: String?, val entries: List<EmojiEntry>) {
  var start = 0
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmojiCell(entry: EmojiEntry, onPick: (String) -> Unit, onTones: () -> Unit, size: Dp = Dp.Unspecified) {
  Box(
    Modifier.then(if (size == Dp.Unspecified) Modifier.aspectRatio(1f) else Modifier.size(size)).clip(RoundedCornerShape(8.dp)).combinedClickable(
      onClick = { onPick(entry.emoji) },
      onLongClick = if (entry.variants.size > 1) onTones else null,
    ),
    contentAlignment = Alignment.Center,
  ) {
    Text(entry.emoji, fontSize = 30.sp)
  }
}

@Composable
private fun TonePicker(tones: List<String>, colors: KeyboardColors, modifier: Modifier, onPick: (String) -> Unit) {
  Row(
    modifier.padding(8.dp).clip(RoundedCornerShape(12.dp)).background(colors.popup).padding(4.dp),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    for (emoji in tones) {
      Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).clickable { onPick(emoji) }, contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = 30.sp)
      }
    }
  }
}

/** Emoji search, in the strip over the letter keys: back, the query, and the matches scrolling beside it. */
@Composable
fun EmojiSearchStrip(controller: InputController, colors: KeyboardColors) {
  val query = controller.emojiQuery.orEmpty()
  val results = remember(query) { Emoji.search(query) }
  var tones by remember { mutableStateOf<List<String>?>(null) }
  val pick = { emoji: String ->
    controller.insert(emoji)
    Emoji.used(emoji)
    tones = null
  }
  Row(Modifier.fillMaxWidth().height(StripHeight).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    ToolButton(Icons.Back, R.string.back_to_emoji, colors, onClick = controller::endEmojiSearch)
    Row(
      Modifier.widthIn(min = 96.dp, max = 160.dp).height(36.dp).clip(RoundedCornerShape(18.dp)).background(colors.key).padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(Icons.Search, null, Modifier.size(16.dp), tint = colors.muted)
      Spacer(Modifier.width(6.dp))
      if (query.isNotEmpty()) Text(query, Modifier.weight(1f, fill = false), color = colors.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      // The keys type here, not in the app, so show where the text goes
      val blink by rememberInfiniteTransition(label = "cursor").animateFloat(
        1f, 0f, infiniteRepeatable(keyframes { durationMillis = 1000; 1f at 499; 0f at 500 }), label = "cursor",
      )
      Box(Modifier.padding(horizontal = 1.dp).size(width = 2.dp, height = 18.dp).alpha(blink).background(colors.accent))
      if (query.isEmpty()) Text(stringResource(R.string.search_emoji), color = colors.muted, fontSize = 14.sp, maxLines = 1)
    }
    // A long-pressed match swaps the row for its skin tones
    val shown = tones?.map { EmojiEntry(listOf(it), emptyList()) } ?: results
    LazyRow(Modifier.weight(1f).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      items(shown, key = { it.emoji }) { entry -> EmojiCell(entry, pick, { tones = entry.variants }, size = 44.dp) }
    }
  }
}

// Options

/** The keyboard's quick settings, so the toolbar keeps only what is used all the time. */
@Composable
fun OptionsPanel(controller: InputController, colors: KeyboardColors, modifier: Modifier) {
  val context = LocalContext.current
  val floating = Prefs.floating.value
  val keys = { controller.panel = Panel.KEYS }
  Column(modifier) {
    PanelHeader(R.string.keyboard_options, controller, colors)
    Column(Modifier.weight(1f).padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      EditRow {
        OptionTile(colors, Icons.TextEdit, R.string.text_editing) { controller.panel = Panel.EDIT }
        // One-handed doesn't apply to a floating keyboard, which is already small
        OptionTile(colors, Icons.OneHanded, R.string.one_handed_short, active = !floating && Prefs.oneHanded.value != OneHanded.OFF, enabled = !floating) {
          Prefs.oneHanded.value = if (Prefs.oneHanded.value == OneHanded.OFF) Prefs.oneHandedSide.value else OneHanded.OFF
          keys()
        }
        OptionTile(colors, Icons.Floating, R.string.floating_short, active = floating) {
          Prefs.floating.value = !floating
          keys()
        }
      }
      EditRow {
        OptionTile(colors, Icons.Split, R.string.split_short, active = Prefs.splitLandscape.value) { Prefs.splitLandscape.value = !Prefs.splitLandscape.value }
        OptionTile(colors, Icons.Theme, R.string.theme) { flipTheme(colors) }
        OptionTile(colors, Icons.Open, R.string.settings) {
          context.startActivity(Intent(context, com.moonkeyboard.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
      }
    }
  }
}

@Composable
private fun RowScope.OptionTile(colors: KeyboardColors, icon: ImageVector, label: Int, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
  PanelKey(colors, Modifier.weight(1f).alpha(if (enabled) 1f else 0.4f), special = false, active = active, onClick = { if (enabled) onClick() }) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
      val tint = if (active) colors.onAccent else colors.text
      Icon(icon, null, Modifier.size(22.dp), tint = tint)
      Text(stringResource(label), color = tint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

// Text editing

@Composable
fun EditPanel(controller: InputController, colors: KeyboardColors, modifier: Modifier) {
  var selecting by remember { mutableStateOf(false) }
  val move = { code: Int -> controller.moveCursor(code, selecting) }

  Column(modifier) {
    PanelHeader(R.string.text_editing, controller, colors)
    Column(Modifier.weight(1f).padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      EditRow {
        EditKey(colors, label = R.string.line_start) { move(KeyEvent.KEYCODE_MOVE_HOME) }
        EditKey(colors, Icons.Up, R.string.up) { move(KeyEvent.KEYCODE_DPAD_UP) }
        EditKey(colors, label = R.string.line_end) { move(KeyEvent.KEYCODE_MOVE_END) }
        EditKey(colors, label = R.string.select_all, special = true) { controller.contextAction(android.R.id.selectAll) }
        EditKey(colors, label = R.string.copy, special = true) { controller.contextAction(android.R.id.copy) }
      }
      EditRow {
        EditKey(colors, Icons.Left, R.string.left) { move(KeyEvent.KEYCODE_DPAD_LEFT) }
        EditKey(colors, label = R.string.select, active = selecting) { selecting = !selecting }
        EditKey(colors, Icons.Right, R.string.right) { move(KeyEvent.KEYCODE_DPAD_RIGHT) }
        EditKey(colors, label = R.string.cut, special = true) { controller.contextAction(android.R.id.cut) }
        EditKey(colors, label = R.string.paste, special = true) { controller.contextAction(android.R.id.paste) }
      }
      EditRow {
        Spacer(Modifier.weight(1f))
        EditKey(colors, Icons.Down, R.string.down) { move(KeyEvent.KEYCODE_DPAD_DOWN) }
        Spacer(Modifier.weight(1f))
        EditKey(colors, Icons.Backspace, R.string.delete, special = true, onClick = controller::deleteBackward)
        EditKey(colors, label = R.string.abc, special = true) { controller.panel = Panel.KEYS }
      }
    }
  }
}

@Composable
private fun ColumnScope.EditRow(content: @Composable RowScope.() -> Unit) {
  Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun RowScope.EditKey(
  colors: KeyboardColors,
  icon: ImageVector? = null,
  label: Int,
  special: Boolean = false,
  active: Boolean = false,
  onClick: () -> Unit,
) {
  PanelKey(colors, Modifier.weight(1f).fillMaxHeight(), special, active, onClick) {
    if (icon != null) {
      Icon(icon, stringResource(label), Modifier.size(22.dp), tint = if (active) colors.onAccent else colors.text)
    } else {
      Text(stringResource(label), color = if (active) colors.onAccent else colors.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

/** A key-shaped button for the panels, with the keyboard's haptics. */
@Composable
private fun PanelKey(
  colors: KeyboardColors,
  modifier: Modifier,
  special: Boolean = true,
  active: Boolean = false,
  onClick: () -> Unit,
  content: @Composable () -> Unit,
) {
  val view = LocalView.current
  Box(
    modifier.fillMaxHeight().clip(RoundedCornerShape(8.dp))
      .background(if (active) colors.accent else if (special) colors.special else colors.key)
      .clickable {
        if (Prefs.haptics.value) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onClick()
      },
    contentAlignment = Alignment.Center,
  ) { content() }
}
