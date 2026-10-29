package com.moonkeyboard

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonkeyboard.engine.history.LearnedPhrase
import com.moonkeyboard.engine.suggest.WordContext
import com.moonkeyboard.ime.ClipKind
import com.moonkeyboard.ime.ClipboardStore
import com.moonkeyboard.ime.Haptics
import com.moonkeyboard.ime.Icons
import com.moonkeyboard.ime.ImageExpiry
import com.moonkeyboard.ime.InputController
import com.moonkeyboard.ime.KeySize
import com.moonkeyboard.ime.KeyboardUi
import com.moonkeyboard.ime.Language
import com.moonkeyboard.ime.OneHanded
import com.moonkeyboard.ime.Prefs
import com.moonkeyboard.ime.ThemeMode
import com.moonkeyboard.ime.ToolbarItem
import com.moonkeyboard.ime.label
import com.moonkeyboard.ime.icon
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex

enum class Screen { HOME, LOOK, TYPING, GLIDE, EMOJI, CLIPBOARD, LEARNED, TEST_PAD }

/** A screen's scrolling body under its top bar. */
@Composable
private fun Page(title: Int, onBack: () -> Unit, content: @Composable () -> Unit) {
  val c = LocalAppColors.current
  Column(Modifier.fillMaxSize().background(c.background)) {
    TopBar(title, onBack)
    Column(
      Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
  }
}

// Welcome

@Composable
fun WelcomeScreen(enabled: Boolean, onEnable: () -> Unit, onSelect: () -> Unit, onTestPad: () -> Unit) {
  val c = LocalAppColors.current
  Column(
    Modifier.fillMaxSize().background(c.background).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 32.dp),
  ) {
    MoonLogo(72)
    Text(stringResource(R.string.app_name), Modifier.padding(top = 18.dp), color = c.text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.welcome_pitch), Modifier.padding(top = 10.dp), color = c.muted, fontSize = 16.sp, lineHeight = 24.sp)
    Spacer(Modifier.height(36.dp))
    Step(1, R.string.setup_step_enable, R.string.setup_step_enable_body, R.string.setup_open_settings, done = enabled, current = !enabled, onEnable)
    Spacer(Modifier.height(12.dp))
    Step(2, R.string.setup_step_select, R.string.setup_step_select_body, R.string.setup_choose, done = false, current = enabled, onSelect)
    Spacer(Modifier.weight(1f).heightIn(min = 32.dp))
    PrivacyNote(R.string.welcome_privacy)
    Box(Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onTestPad), contentAlignment = Alignment.Center) {
      Text(stringResource(R.string.try_without_switching), color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
  }
}

/** A setup step: ticked when done, highlighted with its button while it's the one to do. */
@Composable
private fun Step(number: Int, title: Int, body: Int, action: Int, done: Boolean, current: Boolean, onAction: () -> Unit) {
  val c = LocalAppColors.current
  Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
      .then(if (current) Modifier.border(1.dp, c.focus, RoundedCornerShape(18.dp)) else Modifier).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        Modifier.size(32.dp).clip(CircleShape).background(if (done) c.successBg else if (current) c.accent else c.selected),
        contentAlignment = Alignment.Center,
      ) {
        if (done) Icon(AppIcons.Check, null, Modifier.size(18.dp), tint = c.success)
        else Text("$number", color = if (current) c.onAccent else c.muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
      }
      Column(Modifier.padding(start = 14.dp)) {
        Text(stringResource(title), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(if (done) stringResource(R.string.setup_done) else stringResource(body), Modifier.padding(top = 2.dp), color = c.muted, fontSize = 13.sp, lineHeight = 18.sp)
      }
    }
    if (current) PrimaryButton(action, onClick = onAction)
  }
}

// Home

@Composable
fun HomeScreen(open: (Screen) -> Unit) {
  val c = LocalAppColors.current
  val engine by Language.ready.collectAsState()
  val words = remember(engine) { engine?.history?.learnedWords()?.size ?: 0 }
  val phrases = remember(engine) { engine?.history?.learnedPhrases()?.size ?: 0 }
  Column(
    Modifier.fillMaxSize().background(c.background).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      MoonLogo(40)
      Text(stringResource(R.string.app_name), Modifier.weight(1f).padding(start = 12.dp), color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
      Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(c.successBg).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c.success))
        Text(stringResource(R.string.active), Modifier.padding(start = 6.dp), color = c.success, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
      }
    }

    TryField()

    val look = listOf(
      stringResource(when (Prefs.theme.value) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark }),
      stringResource(if (Prefs.borders.value) R.string.with_borders else R.string.borderless),
    ).joinToString(", ")
    val typing = listOf(
      stringResource(if (Prefs.hinglish.value) R.string.english_hinglish else R.string.english_only),
      stringResource(if (Prefs.autocorrect.value) R.string.autocorrect_on else R.string.autocorrect_off),
      stringResource(if (Prefs.haptics.value) R.string.vibration else R.string.no_vibration),
    ).joinToString(", ")
    val glide = stringResource(
      when {
        !Prefs.glideTyping.value -> R.string.off
        Prefs.glideTrail.value -> R.string.glide_on_trail
        else -> R.string.on
      },
    )
    val clips = ClipboardStore.clips.size
    val clipDetail = stringResource(R.string.clipboard_storage, clips, Formatter.formatShortFileSize(LocalContext.current, ClipboardStore.storageBytes()))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Tile(Icons.Theme, R.string.look, look) { open(Screen.LOOK) }
      Tile(AppIcons.Typing, R.string.typing, typing) { open(Screen.TYPING) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Tile(AppIcons.Glide, R.string.glide_typing, glide) { open(Screen.GLIDE) }
      Tile(Icons.Clipboard, R.string.clipboard, clipDetail) { open(Screen.CLIPBOARD) }
    }

    Card {
      NavRow(AppIcons.Learned, R.string.learned_words, stringResource(R.string.learned_counts, words, phrases)) { open(Screen.LEARNED) }
      Divider()
      NavRow(Icons.Emoji, R.string.emoji, stringResource(if (Prefs.emojiSuggestions.value) R.string.emoji_suggestions_on else R.string.emoji_suggestions_off)) { open(Screen.EMOJI) }
      Divider()
      NavRow(AppIcons.TestPad, R.string.test_pad, stringResource(R.string.test_pad_detail)) { open(Screen.TEST_PAD) }
    }

    PrivacyNote(R.string.home_privacy)
  }
}

/** A text field to try the keyboard in; the system shows whichever keyboard is selected. */
@Composable
private fun TryField() {
  val c = LocalAppColors.current
  var text by rememberSaveable { mutableStateOf("") }
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(stringResource(R.string.setup_try).uppercase(), Modifier.padding(horizontal = 4.dp), color = c.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
    BasicTextField(
      text,
      { text = it },
      Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.focus, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp),
      textStyle = TextStyle(color = c.text, fontSize = 15.sp),
      cursorBrush = SolidColor(c.accent),
      singleLine = true,
      decorationBox = { inner ->
        Box(contentAlignment = Alignment.CenterStart) {
          if (text.isEmpty()) Text(stringResource(R.string.try_placeholder), color = c.muted, fontSize = 15.sp)
          inner()
        }
      },
    )
  }
}

// Look

@Composable
fun LookScreen(onBack: () -> Unit) {
  val c = LocalAppColors.current
  Column(Modifier.fillMaxSize().background(c.background)) {
    TopBar(R.string.look, onBack)
    Column(
      Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      SectionLabel(R.string.theme)
      Segmented(Prefs.theme, ThemeMode.entries, listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark), Modifier.fillMaxWidth())
      Spacer(Modifier.height(4.dp))
      Card {
        SwitchRow(R.string.key_borders, Prefs.borders)
        Divider()
        SwitchRow(R.string.number_row, Prefs.numberRow)
        Divider()
        LabelRow(R.string.key_height) { Segmented(Prefs.keySize, KeySize.entries, listOf(R.string.key_short, R.string.key_normal, R.string.key_tall), compact = true) }
        Divider()
        LabelRow(R.string.one_handed_short) { Segmented(Prefs.oneHanded, OneHanded.entries, listOf(R.string.off, R.string.left, R.string.right), compact = true) }
        Divider()
        SwitchRow(R.string.floating, Prefs.floating)
        Divider()
        SwitchRow(R.string.split_landscape, Prefs.splitLandscape, stringResource(R.string.split_landscape_detail))
      }
      Spacer(Modifier.height(4.dp))
      SectionLabel(R.string.top_bar)
      TopBarEditor()
      Text(stringResource(R.string.top_bar_note, ToolbarItem.MAX), Modifier.padding(horizontal = 4.dp), color = c.muted, fontSize = 12.sp)
      Text(stringResource(R.string.look_preview_note), Modifier.padding(horizontal = 4.dp), color = c.muted, fontSize = 12.sp)
    }
    PreviewKeyboard()
  }
}

private val BarRowHeight = 56.dp

/**
 * The top bar's buttons in its order, moved by dragging their handles, then the ones not on it.
 * Rows move as the dragged one passes half of its neighbour, so the saved order is always what shows.
 */
@Composable
private fun TopBarEditor() {
  val c = LocalAppColors.current
  val view = LocalView.current
  val chosen = Prefs.toolbar()
  // A row and the divider under it
  val pitch = with(LocalDensity.current) { (BarRowHeight + 1.dp).toPx() }
  var dragging by remember { mutableStateOf<ToolbarItem?>(null) }
  var dragY by remember { mutableFloatStateOf(0f) }
  val moveUp = stringResource(R.string.move_up)
  val moveDown = stringResource(R.string.move_down)

  fun move(item: ToolbarItem, by: Int): Boolean {
    val list = Prefs.toolbar().toMutableList()
    val from = list.indexOf(item)
    if (from < 0 || from + by !in list.indices) return false
    list.add(from + by, list.removeAt(from))
    Prefs.setToolbar(list)
    Haptics.tap(view)
    return true
  }

  Card {
    GroupLabel(R.string.on_the_bar)
    chosen.forEachIndexed { i, item ->
      // Keyed so a row keeps its drag as the list reorders under it
      key(item) {
        if (i > 0) Divider()
        val lifted = item == dragging
        BarRow(
          item,
          Modifier.zIndex(if (lifted) 1f else 0f)
            .graphicsLayer { translationY = if (lifted) dragY else 0f }
            .background(if (lifted) c.selected else Color.Transparent)
            .semantics {
              customActions = listOf(
                CustomAccessibilityAction(moveUp) { move(item, -1) },
                CustomAccessibilityAction(moveDown) { move(item, 1) },
              )
            },
          AppIcons.Remove, R.string.remove_from_bar, enabled = true, onAction = { Prefs.setToolbar(chosen - item) },
        ) {
          Box(
            Modifier.size(44.dp).pointerInput(item) {
              detectDragGestures(
                onDragStart = { dragging = item; dragY = 0f },
                onDragEnd = { dragging = null; dragY = 0f },
                onDragCancel = { dragging = null; dragY = 0f },
              ) { change, amount ->
                change.consume()
                val list = Prefs.toolbar()
                val at = list.indexOf(item)
                // Not past either end of the list
                dragY = (dragY + amount.y).coerceIn(-at * pitch, (list.lastIndex - at) * pitch)
                if (dragY > pitch / 2 && move(item, 1)) dragY -= pitch
                if (dragY < -pitch / 2 && move(item, -1)) dragY += pitch
              }
            },
            contentAlignment = Alignment.Center,
          ) {
            Icon(AppIcons.DragHandle, stringResource(R.string.drag_to_reorder), Modifier.size(22.dp), tint = c.muted)
          }
        }
      }
    }
    val rest = ToolbarItem.entries - chosen.toSet()
    if (rest.isNotEmpty()) {
      Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
      GroupLabel(R.string.more_for_bar)
      rest.forEachIndexed { i, item ->
        if (i > 0) Divider()
        // A full bar takes nothing more until something comes off it
        BarRow(item, Modifier, AppIcons.Add, R.string.add_to_bar, enabled = chosen.size < ToolbarItem.MAX, onAction = { Prefs.setToolbar(chosen + item) })
      }
    }
  }
}

@Composable
private fun GroupLabel(text: Int) {
  Text(
    stringResource(text).uppercase(), Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    color = LocalAppColors.current.faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
  )
}

/** A toolbar button's row: its icon and name, then add or remove, then (on the bar) the drag handle. */
@Composable
private fun BarRow(
  item: ToolbarItem,
  modifier: Modifier,
  actionIcon: androidx.compose.ui.graphics.vector.ImageVector,
  actionLabel: Int,
  enabled: Boolean,
  onAction: () -> Unit,
  handle: @Composable () -> Unit = {},
) {
  val c = LocalAppColors.current
  Row(modifier.fillMaxWidth().height(BarRowHeight).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(item.icon, null, Modifier.size(20.dp), tint = c.muted)
    Spacer(Modifier.width(14.dp))
    Text(stringResource(item.label), Modifier.weight(1f), color = c.text, fontSize = 15.sp)
    Box(
      Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onAction),
      contentAlignment = Alignment.Center,
    ) {
      Icon(actionIcon, stringResource(actionLabel), Modifier.size(22.dp), tint = if (enabled) c.accent else c.faint)
    }
    handle()
  }
}

/** The real keyboard, drawn by the same code as in other apps, so every change shows as it is made. */
@Composable
private fun PreviewKeyboard() {
  val view = LocalView.current
  val host = remember { TestPadHost(view) }
  val controller = remember { InputController(host).also { it.onStartInput(host.editorInfo) } }
  androidx.compose.runtime.DisposableEffect(controller) { onDispose { controller.close() } }
  // A change to the top bar opens it here, to show the result
  var barChanged by remember { mutableStateOf(false) }
  androidx.compose.runtime.LaunchedEffect(Prefs.toolbarItems.value) {
    if (barChanged) controller.toolbarOpen = true
    barChanged = true
  }
  Box(Modifier.fillMaxWidth().height(1.dp).background(LocalAppColors.current.divider))
  KeyboardUi(controller)
}

// Typing, glide, emoji

@Composable
fun TypingScreen(onBack: () -> Unit) = Page(R.string.typing, onBack) {
  Card {
    SwitchRow(R.string.hinglish, Prefs.hinglish, stringResource(R.string.hinglish_detail))
    Divider()
    SwitchRow(R.string.autocorrect, Prefs.autocorrect, stringResource(R.string.autocorrect_detail))
    Divider()
    SwitchRow(R.string.auto_caps, Prefs.autoCaps, stringResource(R.string.auto_caps_detail))
    Divider()
    SwitchRow(R.string.block_offensive, Prefs.blockOffensive, stringResource(R.string.block_offensive_detail))
  }
  Card {
    SwitchRow(R.string.haptics, Prefs.haptics)
    if (Prefs.haptics.value) {
      val view = LocalView.current
      // Buzzes once at the new strength, so the choice can be felt
      SliderRow(R.string.vibration_strength, Prefs.hapticStrength, 1..Haptics.MAX_STRENGTH, "${Prefs.hapticStrength.value}") { Haptics.tap(view) }
    }
    Divider()
    SwitchRow(R.string.key_sound, Prefs.sound)
  }
}

@Composable
fun GlideScreen(onBack: () -> Unit) = Page(R.string.glide_typing, onBack) {
  Card {
    SwitchRow(R.string.glide_typing, Prefs.glideTyping, stringResource(R.string.glide_detail))
    Divider()
    SwitchRow(R.string.glide_trail, Prefs.glideTrail)
  }
}

@Composable
fun EmojiScreen(onBack: () -> Unit) = Page(R.string.emoji, onBack) {
  Card {
    SwitchRow(R.string.emoji_suggestions, Prefs.emojiSuggestions, stringResource(R.string.emoji_suggestions_detail))
  }
  val c = LocalAppColors.current
  Text(stringResource(R.string.emoji_search_tip), Modifier.padding(horizontal = 4.dp), color = c.muted, fontSize = 13.sp, lineHeight = 19.sp)
  DangerButton(stringResource(R.string.clear_recent_emoji), enabled = Prefs.emojiRecents.value.isNotEmpty()) { Prefs.emojiRecents.value = "" }
}

// Clipboard

@Composable
fun ClipboardScreen(onBack: () -> Unit) = Page(R.string.clipboard, onBack) {
  val c = LocalAppColors.current
  val context = LocalContext.current
  val usage = ClipboardStore.usage()
  val total = usage.values.sumOf { it.second }
  val count = usage.values.sumOf { it.first }
  val kinds = listOf(
    Triple(ClipKind.IMAGE, c.accent, R.plurals.clip_images),
    Triple(ClipKind.GIF, Color(0xFFF2B880), R.plurals.clip_gifs),
    Triple(ClipKind.TEXT, c.success, R.plurals.clip_texts),
  )

  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Row(verticalAlignment = Alignment.Bottom) {
      Text(Formatter.formatShortFileSize(context, total), color = c.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
      Text(pluralStringResource(R.plurals.used_by_items, count, count), Modifier.padding(start = 8.dp, bottom = 5.dp), color = c.muted, fontSize = 13.sp)
    }
    Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.divider), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
      for ((kind, color, _) in kinds) {
        val bytes = usage.getValue(kind).second
        if (bytes > 0) Box(Modifier.weight(bytes.toFloat()).height(10.dp).background(color))
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      for ((kind, color, label) in kinds) {
        val n = usage.getValue(kind).first
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.size(8.dp).clip(CircleShape).background(color))
          Text(pluralStringResource(label, n, n), Modifier.padding(start = 6.dp), color = c.muted, fontSize = 12.sp)
        }
      }
    }
  }

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    SectionLabel(R.string.image_expiry)
    Segmented(Prefs.imageExpiry, ImageExpiry.entries, listOf(R.string.expiry_week, R.string.expiry_month, R.string.expiry_never), Modifier.fillMaxWidth())
    Text(stringResource(R.string.expiry_note), Modifier.padding(horizontal = 4.dp), color = c.muted, fontSize = 12.sp, lineHeight = 17.sp)
  }

  val pinned = ClipboardStore.clips.filter { it.pinned }
  if (pinned.isNotEmpty()) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      SectionLabel(R.string.pinned)
      Card {
        pinned.forEachIndexed { i, clip ->
          if (i > 0) Divider()
          Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Pin, null, Modifier.size(16.dp), tint = c.accent)
            Text(
              if (clip.kind == ClipKind.TEXT) clip.content.take(120).replace(Regex("\\s+"), " ") else stringResource(if (clip.kind == ClipKind.GIF) R.string.copied_gif else R.string.copied_image),
              Modifier.weight(1f).padding(start = 12.dp),
              color = c.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { ClipboardStore.setPinned(clip.id, false) }, contentAlignment = Alignment.Center) {
              Icon(AppIcons.Close, stringResource(R.string.unpin), Modifier.size(18.dp), tint = c.muted)
            }
          }
        }
      }
    }
  }

  Card { SwitchRow(R.string.offer_copied, Prefs.offerCopied, stringResource(R.string.offer_copied_detail)) }

  var confirm by remember { mutableStateOf(false) }
  DangerButton(
    stringResource(if (confirm) R.string.tap_again_to_clear else R.string.clear_unpinned),
    enabled = ClipboardStore.clips.any { !it.pinned },
  ) {
    if (confirm) ClipboardStore.clearUnpinned()
    confirm = !confirm
  }
}

// Learned words

private sealed interface Learned {
  data class Word(val word: String, val count: Int) : Learned
  data class Phrase(val phrase: LearnedPhrase) : Learned
  data class Blocked(val word: String) : Learned
}

@Composable
fun LearnedScreen(onBack: () -> Unit) {
  val c = LocalAppColors.current
  val engine by Language.ready.collectAsState()
  val history = engine?.history
  // Bumped after each change, so the lists are read again
  var version by remember { mutableIntStateOf(0) }
  var query by rememberSaveable { mutableStateOf("") }
  val words = remember(history, version) { history?.learnedWords().orEmpty() }
  val phrases = remember(history, version) { history?.learnedPhrases().orEmpty() }
  val blocked = remember(history, version) { history?.blockedWords().orEmpty() }
  val shown = remember(words, phrases, blocked, query) {
    val q = query.trim().lowercase()
    fun hit(s: String) = q.isEmpty() || q in s.lowercase()
    blocked.filter(::hit).map { Learned.Blocked(it) } +
      phrases.filter { hit(phraseText(it)) }.map { Learned.Phrase(it) } +
      words.filter { hit(it.first) }.map { Learned.Word(it.first, it.second) }
  }
  val changed = {
    Language.historyChanged()
    version++
  }

  Column(Modifier.fillMaxSize().background(c.background)) {
    TopBar(R.string.learned_words, onBack)
    Column(Modifier.weight(1f).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat("%,d".format(words.size), pluralStringResource(R.plurals.words_label, words.size), Modifier.weight(1f))
        Stat("%,d".format(phrases.size), pluralStringResource(R.plurals.phrases_label, phrases.size), Modifier.weight(1f))
      }
      Text(stringResource(R.string.learned_words_explain), Modifier.padding(horizontal = 4.dp), color = c.muted, fontSize = 13.sp, lineHeight = 19.sp)
      Row(
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(c.surface).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Search, null, Modifier.size(18.dp), tint = c.muted)
        Box(Modifier.weight(1f).padding(start = 10.dp), contentAlignment = Alignment.CenterStart) {
          if (query.isEmpty()) Text(stringResource(R.string.search_learned), color = c.muted, fontSize = 15.sp)
          BasicTextField(query, { query = it }, Modifier.fillMaxWidth(), textStyle = TextStyle(color = c.text, fontSize = 15.sp), cursorBrush = SolidColor(c.accent), singleLine = true)
        }
      }
      LazyColumn(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface)) {
        if (history == null) item { Text(stringResource(R.string.loading), Modifier.padding(16.dp), color = c.muted, fontSize = 14.sp) }
        else if (shown.isEmpty()) item { Text(stringResource(R.string.nothing_learned), Modifier.padding(16.dp), color = c.muted, fontSize = 14.sp) }
        items(shown, key = { it.toString() }) { item ->
          when (item) {
            is Learned.Word -> LearnedRow(item.word, pluralStringResource(R.plurals.typed_times, item.count, item.count), stringResource(R.string.forget_item, item.word)) {
              history?.forget(item.word)
              changed()
            }
            is Learned.Phrase -> {
              val text = phraseText(item.phrase)
              LearnedRow(text, pluralStringResource(R.plurals.phrase_used, item.phrase.count, item.phrase.count), stringResource(R.string.forget_item, text)) {
                history?.forgetPhrase(item.phrase.prev2, item.phrase.prev, item.phrase.word)
                changed()
              }
            }
            is Learned.Blocked -> Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text(item.word, color = c.muted, fontSize = 15.sp, textDecoration = TextDecoration.LineThrough)
                Text(stringResource(R.string.never_suggested), Modifier.padding(top = 2.dp), color = c.muted, fontSize = 12.sp)
              }
              Box(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(c.divider).clickable { history?.unblock(item.word); changed() }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.allow), color = c.text, fontSize = 13.sp)
              }
            }
          }
        }
      }
      var confirm by remember { mutableStateOf(false) }
      DangerButton(stringResource(if (confirm) R.string.tap_again_to_delete else R.string.delete_learned_words), enabled = words.isNotEmpty() || blocked.isNotEmpty()) {
        if (confirm) {
          history?.clear()
          changed()
        }
        confirm = !confirm
      }
    }
  }
}

/** A phrase as typed, without the sentence-start marker the model keeps. */
private fun phraseText(p: LearnedPhrase) = listOf(p.prev2, p.prev, p.word).filter { it != WordContext.START_TOKEN }.joinToString(" ")

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
  val c = LocalAppColors.current
  Column(modifier.clip(RoundedCornerShape(18.dp)).background(c.surface).padding(16.dp)) {
    Text(value, color = c.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    Text(label, Modifier.padding(top = 2.dp), color = c.muted, fontSize = 13.sp)
  }
}

@Composable
private fun LearnedRow(title: String, detail: String, forgetLabel: String, onForget: () -> Unit) {
  val c = LocalAppColors.current
  Column {
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(title, color = c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, Modifier.padding(top = 2.dp), color = c.muted, fontSize = 12.sp)
      }
      Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onForget), contentAlignment = Alignment.Center) {
        Icon(Icons.Trash, forgetLabel, Modifier.size(18.dp), tint = c.muted)
      }
    }
    Divider()
  }
}
