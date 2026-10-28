package com.moonkeyboard.ime

import android.content.ClipDescription
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableString
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.moonkeyboard.engine.decode.KeyMap
import com.moonkeyboard.engine.decode.Touch
import com.moonkeyboard.engine.input.ShiftController
import com.moonkeyboard.engine.input.ShiftState
import com.moonkeyboard.engine.layout.Key
import com.moonkeyboard.engine.layout.KeyType
import com.moonkeyboard.engine.layout.Layout
import com.moonkeyboard.engine.layout.Layouts
import com.moonkeyboard.engine.suggest.Suggestions
import com.moonkeyboard.engine.suggest.WordContext
import com.moonkeyboard.engine.suggest.isWordChar
import com.moonkeyboard.engine.text.graphemeStep
import com.moonkeyboard.engine.text.lastGraphemeLength
import com.moonkeyboard.engine.trace.TracePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Where key presses go: the focused app's field when running as the keyboard, or the in-app test pad. */
interface KeyboardHost {
  val inputConnection: InputConnection?
  val editorInfo: EditorInfo
  fun sendDownUpKeyEvents(keyCode: Int)
}

fun InputMethodService.asKeyboardHost() = object : KeyboardHost {
  override val inputConnection: InputConnection? get() = currentInputConnection
  override val editorInfo: EditorInfo get() = currentInputEditorInfo
  override fun sendDownUpKeyEvents(keyCode: Int) = this@asKeyboardHost.sendDownUpKeyEvents(keyCode)
}

enum class Panel { KEYS, CLIPBOARD, EMOJI, EDIT }

/**
 * Shows [text] as the word being typed, without the underline apps add by default: they only add
 * it to plain text, so styled (Spannable) text goes in as is.
 */
private fun InputConnection.compose(text: CharSequence) = setComposingText(SpannableString(text), 1)

/**
 * Turns key presses into edits on the host's text field. The word being typed is kept as
 * composing text with the touch positions behind each letter, so it can be corrected on space.
 * Its state drives the keyboard UI.
 */
class InputController(private val host: KeyboardHost) {
  private val layouts = HashMap<String, Layout>()
  private val shiftController = ShiftController()
  private val scope = MainScope()

  @OptIn(ExperimentalCoroutinesApi::class)
  private val decodeDispatcher = Dispatchers.Default.limitedParallelism(1)

  var layerId by mutableStateOf(LETTERS)
    private set
  var shift by mutableStateOf(ShiftState.OFF)
    private set
  var suggestions by mutableStateOf(Suggestions.EMPTY)
    private set
  /** What fills the keyboard: the keys, or one of the toolbar's panels. */
  var panel by mutableStateOf(Panel.KEYS)
  /** The toolbar is showing in place of the suggestions. */
  var toolbarOpen by mutableStateOf(false)
  /** Emoji search is open: the keys type into this query instead of the app. */
  var emojiQuery by mutableStateOf<String?>(null)
    private set

  fun onLongPress(action: String) {
    when (action) {
      "emoji_search" -> startEmojiSearch()
    }
  }

  /** Where leaving search goes back to: the emoji panel, or the keys when opened from a long press. */
  private var searchReturn = Panel.EMOJI

  fun startEmojiSearch() {
    finishWord()
    searchReturn = panel
    emojiQuery = ""
    panel = Panel.KEYS
    layerId = LETTERS
    // The query is lower case, so the keys shouldn't show capitals from auto-caps
    shiftController.reset()
    syncShift()
  }

  /** Back from search to wherever it was opened from. */
  fun endEmojiSearch() {
    emojiQuery = null
    panel = searchReturn
    afterSeparator()
  }

  /** Letter-key centres from the UI; restored words get synthetic touches from them. */
  var keyMap: KeyMap? = null

  private val composing = StringBuilder()
  private val touches = ArrayList<Touch>()
  private var field = FieldFlags.NONE
  /** The selection as the app last reported it (or we last set it): the fixed end and the moving end. */
  private var selAnchor = -1
  private var selCursor = -1
  private var decodeJob: Job? = null

  /** Last committed word was a suggestion followed by our own space. */
  private var autoSpaced = false
  /** The previous key was space, so a second one may become ". ". */
  private var lastWasSpace = false
  /** An autocorrection the next backspace undoes. */
  private var revert: Revert? = null
  /** The composing word came from a glide: it is already decoded, so never autocorrect it again. */
  private var glided = false
  /**
   * A swipe left its word without a space after it. The next letter or swipe adds the space first;
   * punctuation like "." attaches to the word and keeps the space owed; space, backspace, enter
   * or moving the cursor drop it.
   */
  private var phantomSpace = false
  private var wordContext: WordContext? = null

  private data class Revert(val typed: String, val corrected: String, val separator: String)

  val layout: Layout get() = layoutFor(layerId)

  fun layoutFor(id: String): Layout {
    val numbers = id == LETTERS && Prefs.numberRow.value
    return layouts.getOrPut(if (numbers) "$id+numbers" else id) {
      Layouts.load(id).let { if (numbers) Layouts.withNumberRow(it) else it }
    }
  }

  init {
    // The dictionary may still be loading when the first field opens
    scope.launch {
      Language.ready.filterNotNull().first()
      if (composing.isEmpty()) refreshPredictions() else requestSuggestions()
    }
  }

  fun onStartInput(info: EditorInfo) {
    layerId = when (info.inputType and InputType.TYPE_MASK_CLASS) {
      InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> SYMBOLS
      else -> LETTERS
    }
    field = FieldFlags.from(info).let { if (Prefs.autocorrect.value) it else it.copy(autocorrect = false) }
    Language.engine?.lexicon?.blockOffensive = Prefs.blockOffensive.value
    selAnchor = info.initialSelStart
    selCursor = info.initialSelEnd
    phantomSpace = false
    panel = Panel.KEYS
    toolbarOpen = false
    emojiQuery = null
    shiftController.reset()
    clearWord()
    autoSpaced = false
    lastWasSpace = false
    revert = null
    afterSeparator()
  }

  fun onFinishInput() {
    if (composing.isNotEmpty()) host.inputConnection?.finishComposingText()
    clearWord()
    suggestions = Suggestions.EMPTY
  }

  /** The editor's selection moved; if the user moved it away from the word, stop composing it. */
  fun onSelectionUpdate(selStart: Int, selEnd: Int, composingEnd: Int) {
    // Keep our own direction when this is the echo of a selection we set
    if (minOf(selAnchor, selCursor) != selStart || maxOf(selAnchor, selCursor) != selEnd) {
      selAnchor = selStart
      selCursor = selEnd
    }
    // Updates without a composing span can be stale echoes of our own commits
    if (composing.isEmpty() || composingEnd < 0) return
    if (selStart != selEnd || composingEnd != selEnd) {
      host.inputConnection?.finishComposingText()
      clearWord()
      revert = null
      autoSpaced = false
      lastWasSpace = false
      phantomSpace = false
      afterSeparator()
    }
  }

  fun close() = scope.cancel()

  /** [x], [y]: where the finger went down, in key-area pixels (NaN when unknown). */
  fun onKey(key: Key, nowMs: Long, x: Float = Float.NaN, y: Float = Float.NaN) {
    emojiQuery?.let { query ->
      when (key.type) {
        KeyType.CHAR -> emojiQuery = query + key.label.lowercase(Locale.ROOT)
        KeyType.SPACE -> emojiQuery = "$query "
        KeyType.BACKSPACE -> emojiQuery = query.dropLast(1)
        KeyType.ENTER -> endEmojiSearch()
        KeyType.LAYER -> layerId = key.layer!!
        KeyType.SHIFT -> Unit
      }
      return
    }
    when (key.type) {
      KeyType.CHAR -> typeText(display(key.label), x, y)
      KeyType.SPACE -> space()
      KeyType.BACKSPACE -> backspace()
      KeyType.ENTER -> enter()
      KeyType.SHIFT -> {
        shiftController.onShiftTap(nowMs)
        syncShift()
      }
      KeyType.LAYER -> layerId = key.layer!!
    }
  }

  fun commitPopup(text: String, x: Float, y: Float) {
    emojiQuery?.let { emojiQuery = it + text.lowercase(Locale.ROOT); return }
    typeText(display(text), x, y)
  }

  /** Text from a panel (emoji, a clipboard item): ends the word in progress, then goes in as is. */
  fun insert(text: String) = typeText(text, Float.NaN, Float.NaN)

  fun deleteBackward() = backspace()

  /** Commits the word in progress as typed, before the cursor moves or a panel acts on the text. */
  fun finishWord() {
    if (composing.isEmpty()) return
    val ic = host.inputConnection ?: return
    ic.beginBatchEdit()
    commitWord(autocorrect = false)
    ic.endBatchEdit()
    afterSeparator()
  }

  /** An arrow, Home or End key; with [select] it extends the selection instead of moving the cursor. */
  fun moveCursor(keyCode: Int, select: Boolean) {
    phantomSpace = false
    finishWord()
    val ic = host.inputConnection ?: return
    if ((keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) && selCursor >= 0) {
      stepCursor(ic, forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT, select)
      return
    }
    val meta = if (select) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
    val now = SystemClock.uptimeMillis()
    ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
    ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
  }

  /**
   * Moves the cursor (or the moving end of the selection) one character by setting the selection.
   * An arrow key event would do the same mid-text, but at either end of the text the app moves
   * focus to the next field instead.
   */
  private fun stepCursor(ic: InputConnection, forward: Boolean, select: Boolean) {
    val lo = minOf(selAnchor, selCursor)
    val hi = maxOf(selAnchor, selCursor)
    val target = if (!select && lo != hi) {
      // An arrow on a selection lands on its edge, like a hardware keyboard
      if (forward) hi else lo
    } else {
      // The text around the selection, enough to find the next whole character either side
      val before = ic.getTextBeforeCursor(GRAPHEME_LOOKBEHIND, 0) ?: ""
      val selected = if (lo != hi) ic.getSelectedText(0) ?: return else ""
      val after = ic.getTextAfterCursor(GRAPHEME_LOOKBEHIND, 0) ?: ""
      val start = lo - before.length
      start + graphemeStep("$before$selected$after", selCursor - start, forward)
    }
    selCursor = target
    if (!select) selAnchor = target
    ic.setSelection(selAnchor, selCursor)
  }

  /** Select all, cut, copy or paste (android.R.id.*), done by the app itself. */
  fun contextAction(id: Int) {
    finishWord()
    host.inputConnection?.performContextMenuAction(id)
  }

  /** A password or PIN field: never show clipboard text on screen here. */
  val isSecretField: Boolean get() = this.field.secret

  /** The app whose field is focused. */
  val targetPackage: String? get() = host.editorInfo.packageName

  /** The field takes images of [mime] (apps list what they accept in their EditorInfo). */
  fun acceptsContent(mime: String): Boolean =
    EditorInfoCompat.getContentMimeTypes(host.editorInfo).any { ClipDescription.compareMimeTypes(mime, it) }

  fun commitContent(uri: Uri, mime: String, label: String): Boolean {
    val ic = host.inputConnection ?: return false
    finishWord()
    val info = InputContentInfoCompat(uri, ClipDescription(label, arrayOf(mime)), null)
    return InputConnectionCompat.commitContent(ic, host.editorInfo, info, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
  }

  /** Letters follow the shift state; everything else is shown as is. */
  fun display(text: String): String =
    if (shift != ShiftState.OFF) text.uppercase(Locale.ROOT) else text

  fun pickSuggestion(index: Int) {
    val word = suggestions.items.getOrNull(index) ?: return
    val ic = host.inputConnection ?: return
    revert = null
    // A picked word gets a real space after it
    phantomSpace = false
    ic.beginBatchEdit()
    if (composing.isNotEmpty()) {
      val context = contextBeforeWord(ic)
      ic.compose(word)
      ic.finishComposingText()
      // Picking exactly what you typed is a strong sign it's a real word
      learn(word, context, weight = if (word == suggestions.typed) LEARN_AS_WORD else 1)
      clearWord()
    } else {
      val context = WordContext.from(ic.getTextBeforeCursor(CONTEXT_CHARS, 0) ?: "")
      if (textBefore(ic, 1).let { it.isNotEmpty() && !it[0].isWhitespace() }) ic.commitText(" ", 1)
      ic.commitText(word, 1)
      learn(word, context)
    }
    ic.commitText(" ", 1)
    ic.endBatchEdit()
    autoSpaced = true
    lastWasSpace = true
    shiftController.onCharTyped()
    afterSeparator()
  }

  /** An emoji from the strip takes the place of the word it matched. */
  fun pickEmoji(emoji: String) {
    val ic = host.inputConnection ?: return
    revert = null
    if (composing.isNotEmpty()) {
      ic.beginBatchEdit()
      ic.compose(emoji)
      ic.finishComposingText()
      ic.endBatchEdit()
      clearWord()
    } else {
      ic.commitText(emoji, 1)
    }
    Emoji.used(emoji)
    autoSpaced = false
    lastWasSpace = false
    afterSeparator()
  }

  /** Long-press on a suggestion: never suggest it again (until the user types it themselves). */
  fun forgetSuggestion(index: Int) {
    val word = suggestions.items.getOrNull(index) ?: return
    val engine = Language.engine ?: return
    engine.history.forget(word)
    Language.historyChanged()
    if (composing.isNotEmpty()) requestSuggestions() else refreshPredictions()
  }

  /** Live preview in the strip while the finger is still moving; the latest path wins. */
  fun previewGlide(path: List<TracePoint>) {
    if (emojiQuery != null) return
    val engine = Language.engine ?: return
    val keys = keyMap ?: return
    val ic = host.inputConnection ?: return
    // A glide commits the word in progress first, so that word becomes the context
    val context = if (composing.isNotEmpty()) contextBeforeWord(ic).then(composing.toString()) else WordContext.from(ic.getTextBeforeCursor(CONTEXT_CHARS, 0) ?: "")
    val firstUpper = shift != ShiftState.OFF
    val allUpper = shift == ShiftState.LOCKED
    decodeJob?.cancel()
    decodeJob = scope.launch {
      suggestions = withContext(decodeDispatcher) { engine.glide(path, keys, context, firstUpper, allUpper) }
    }
  }

  /** The finger lifted: commit any word in progress, then compose the best glide word after a space. */
  fun onGlide(path: List<TracePoint>) {
    val engine = Language.engine ?: return
    emojiQuery?.let { query ->
      // A glided word goes into the search, as its own word
      val word = engine.glide(path, keyMap ?: return, WordContext.START, firstUpper = false, allUpper = false).typed.lowercase(Locale.ROOT)
      if (word.isNotEmpty()) emojiQuery = if (query.isEmpty() || query.endsWith(' ')) query + word else "$query $word"
      return
    }
    val keys = keyMap ?: return
    val ic = host.inputConnection ?: return
    decodeJob?.cancel()
    ClipboardStore.fresh = null
    revert = null
    lastWasSpace = false
    autoSpaced = false
    ic.beginBatchEdit()
    if (composing.isNotEmpty()) commitWord(autocorrect = true)
    val context = WordContext.from(ic.getTextBeforeCursor(CONTEXT_CHARS, 0) ?: "")
    val result = engine.glide(path, keys, context, firstUpper = shift != ShiftState.OFF, allUpper = shift == ShiftState.LOCKED)
    if (result.typed.isNotEmpty()) {
      val before = textBefore(ic, 1)
      if (before.isNotEmpty() && !before[0].isWhitespace() && before[0] !in "([{\"'") ic.commitText(" ", 1)
      composing.append(result.typed)
      result.typed.forEach { touches += keys.touchFor(it) }
      glided = true
      ic.compose(composing)
      // No space after the word yet: one is owed, and the next letter or swipe pays it
      phantomSpace = true
    }
    ic.endBatchEdit()
    suggestions = result
    shiftController.onCharTyped()
    syncShift()
  }

  private fun typeText(text: String, x: Float, y: Float) {
    val ic = host.inputConnection ?: return
    // Typing means the user moved on from what they copied
    ClipboardStore.fresh = null
    revert = null
    lastWasSpace = false
    val c = text.singleOrNull()
    if (phantomSpace) {
      phantomSpace = false
      if (c != null && c in FOLLOWED_BY_SPACE) {
        // "hello" + "." → "hello.", and the next letter still gets its space
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) commitWord(autocorrect = false)
        ic.commitText(text, 1)
        ic.endBatchEdit()
        phantomSpace = true
        shiftController.onCharTyped()
        afterSeparator()
        return
      }
      if (c != null && (c.isLetterOrDigit() || c in PRECEDED_BY_SPACE)) {
        // A new word after a swiped one: pay the owed space first ("hello" + "t" → "hello t")
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) commitWord(autocorrect = false)
        // Unless the user put the cursor somewhere that already has one
        if (textBefore(ic, 1).let { it.isNotEmpty() && !it[0].isWhitespace() }) ic.commitText(" ", 1)
        ic.endBatchEdit()
      }
    }
    if (field.suggest && c != null && (c.isLetter() || (c == '\'' && composing.isNotEmpty()))) {
      composing.append(c)
      touches += if (x.isNaN()) keyMap?.touchFor(c) ?: Touch.exact(c) else Touch(x, y, c)
      // Tapping more letters onto a glided word makes it an ordinary typed word again
      glided = false
      ic.compose(composing)
      autoSpaced = false
      shiftController.onCharTyped()
      syncShift()
      requestSuggestions()
      return
    }
    ic.beginBatchEdit()
    if (composing.isNotEmpty()) commitWord(autocorrect = c != null && c in AUTOCORRECT_TRIGGERS)
    if (autoSpaced && c != null && c in SPACE_SWAPPING && textBefore(ic, 1) == " ") {
      // "word " + "." → "word. ": punctuation goes before the space we added
      ic.deleteSurroundingText(1, 0)
      ic.commitText("$c ", 1)
    } else {
      ic.commitText(text, 1)
      autoSpaced = false
    }
    ic.endBatchEdit()
    shiftController.onCharTyped()
    afterSeparator()
  }

  private fun space() {
    val ic = host.inputConnection ?: return
    revert = null
    phantomSpace = false
    ic.beginBatchEdit()
    if (composing.isNotEmpty()) {
      val corrected = commitWord(autocorrect = true)
      ic.commitText(" ", 1)
      revert = corrected?.copy(separator = " ")
      lastWasSpace = true
    } else if (lastWasSpace && doubleSpaceApplies(ic)) {
      ic.deleteSurroundingText(1, 0)
      ic.commitText(". ", 1)
      lastWasSpace = false
    } else {
      ic.commitText(" ", 1)
      lastWasSpace = true
    }
    ic.endBatchEdit()
    autoSpaced = false
    afterSeparator()
  }

  private fun backspace() {
    val ic = host.inputConnection ?: return
    phantomSpace = false
    lastWasSpace = false
    autoSpaced = false
    val r = revert
    revert = null
    if (r != null) {
      val expected = r.corrected + r.separator
      if (textBefore(ic, expected.length) == expected) {
        val context = WordContext.from(ic.getTextBeforeCursor(CONTEXT_CHARS + expected.length, 0)?.dropLast(expected.length) ?: "")
        ic.beginBatchEdit()
        ic.deleteSurroundingText(expected.length, 0)
        ic.commitText(r.typed + r.separator, 1)
        ic.endBatchEdit()
        // Undoing a correction means the user meant it
        learn(r.typed, context, weight = LEARN_AS_WORD)
        afterSeparator()
        return
      }
    }
    if (composing.isNotEmpty() && glided) {
      // A glide put the whole word down at once, so one backspace takes it all back
      ic.commitText("", 1)
      clearWord()
      afterSeparator()
      return
    }
    if (composing.isNotEmpty()) {
      val n = lastGraphemeLength(composing)
      composing.setLength(composing.length - n)
      repeat(minOf(n, touches.size)) { touches.removeAt(touches.lastIndex) }
      if (composing.isEmpty()) {
        ic.commitText("", 1)
        clearWord()
        afterSeparator()
      } else {
        ic.compose(composing)
        requestSuggestions()
      }
      return
    }
    if (!ic.getSelectedText(0).isNullOrEmpty()) {
      ic.commitText("", 1)
    } else {
      val n = lastGraphemeLength(ic.getTextBeforeCursor(GRAPHEME_LOOKBEHIND, 0) ?: "")
      // Fields that don't expose their text still understand a raw delete key
      if (n > 0) ic.deleteSurroundingText(n, 0) else host.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }
    if (field.suggest) resumeWord(ic)
    if (composing.isEmpty()) afterSeparator() else requestSuggestions()
  }

  private fun enter() {
    val ic = host.inputConnection ?: return
    revert = null
    phantomSpace = false
    lastWasSpace = false
    autoSpaced = false
    if (composing.isNotEmpty()) {
      ic.beginBatchEdit()
      commitWord(autocorrect = true)
      ic.endBatchEdit()
    }
    val info = host.editorInfo
    val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
    val multiLine = info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
    val noAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
    if (!multiLine && !noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
      ic.performEditorAction(action)
    } else {
      ic.commitText("\n", 1)
    }
    afterSeparator()
  }

  /** Commits the composing word, autocorrected if asked. Returns the correction made, if any. */
  private fun commitWord(autocorrect: Boolean): Revert? {
    val ic = host.inputConnection ?: return null
    val typed = composing.toString()
    val context = contextBeforeWord(ic)
    val word = (if (autocorrect && field.autocorrect && !glided) resultFor(typed, context)?.autocorrect else null) ?: typed
    if (word != typed) ic.compose(word)
    ic.finishComposingText()
    learn(word, context)
    clearWord()
    return if (word != typed) Revert(typed, word, "") else null
  }

  /** After backspacing to the end of a word, pick it up again so it can be corrected. */
  private fun resumeWord(ic: InputConnection) {
    val after = ic.getTextAfterCursor(1, 0)
    if (!after.isNullOrEmpty() && isWordChar(after[0])) return
    val before = ic.getTextBeforeCursor(RESUME_CHARS, 0) ?: return
    var start = before.length
    while (start > 0 && isWordChar(before[start - 1])) start--
    // A word running into the lookbehind limit may be longer than we can see
    if (start == 0 && before.length == RESUME_CHARS) return
    val word = before.substring(start)
    if (word.isEmpty() || !word[0].isLetter()) return
    ic.beginBatchEdit()
    ic.deleteSurroundingText(word.length, 0)
    ic.compose(word)
    ic.endBatchEdit()
    composing.append(word)
    word.forEach { touches += keyMap?.touchFor(it) ?: Touch.exact(it) }
  }

  private fun requestSuggestions() {
    val engine = Language.engine ?: return
    val ic = host.inputConnection ?: return
    val typed = composing.toString()
    val typedTouches = touches.toList()
    val context = contextBeforeWord(ic)
    val keys = keyMap
    val allowAutocorrect = field.autocorrect
    decodeJob?.cancel()
    decodeJob = scope.launch {
      val result = withContext(decodeDispatcher) { engine.suggest(typed, typedTouches, keys, context, allowAutocorrect) }
      if (composing.contentEquals(typed)) suggestions = result
    }
  }

  /** The latest suggestions if they are for [typed]; otherwise decode now so space never waits on a stale result. */
  private fun resultFor(typed: String, context: WordContext): Suggestions? {
    val current = suggestions
    if (!current.isPrediction && current.typed == typed) return current
    return Language.engine?.suggest(typed, touches.toList(), keyMap, context, field.autocorrect)
  }

  private fun refreshPredictions() {
    decodeJob?.cancel()
    val engine = Language.engine
    val ic = host.inputConnection
    if (!field.suggest || engine == null || ic == null) {
      suggestions = Suggestions.EMPTY
      return
    }
    val context = WordContext.from(ic.getTextBeforeCursor(CONTEXT_CHARS, 0) ?: "")
    suggestions = engine.predict(context, capitalize = shift != ShiftState.OFF)
  }

  private fun afterSeparator() {
    if (field.autoCaps) {
      host.inputConnection?.let { ic ->
        shiftController.autoCapitalize(ic.getCursorCapsMode(host.editorInfo.inputType) != 0)
      }
    }
    syncShift()
    refreshPredictions()
  }

  private fun learn(word: String, context: WordContext, weight: Int = 1) {
    if (!field.learn) return
    val engine = Language.engine ?: return
    // "The" at the start of a sentence is the word "the"; "I" and names keep their capital
    val lower = word.lowercase()
    val normalized = if (context.sentenceStart && word != lower && word.drop(1) == lower.drop(1) && engine.lexicon.find(word) < 0) lower else word
    engine.history.record(context.userKey, normalized, weight, prev2 = context.userKey2)
    Language.historyChanged()
  }

  /** The words before the composing word. They can't change mid-word, so one IPC per word, not per letter. */
  private fun contextBeforeWord(ic: InputConnection): WordContext {
    wordContext?.let { return it }
    val before = ic.getTextBeforeCursor(CONTEXT_CHARS + composing.length, 0) ?: ""
    return WordContext.from(before.dropLast(minOf(composing.length, before.length))).also { wordContext = it }
  }

  private fun doubleSpaceApplies(ic: InputConnection): Boolean {
    val before = textBefore(ic, 2)
    return before.length == 2 && before[1] == ' ' && (before[0].isLetterOrDigit() || before[0] in "\"')")
  }

  private fun textBefore(ic: InputConnection, n: Int): String = ic.getTextBeforeCursor(n, 0)?.toString() ?: ""

  private fun clearWord() {
    decodeJob?.cancel()
    composing.clear()
    touches.clear()
    glided = false
    wordContext = null
  }

  private fun syncShift() {
    shift = shiftController.state
  }

  /** What the focused field allows, from its input type. */
  private data class FieldFlags(val suggest: Boolean, val autocorrect: Boolean, val learn: Boolean, val autoCaps: Boolean, val secret: Boolean = false) {
    companion object {
      val NONE = FieldFlags(suggest = false, autocorrect = false, learn = false, autoCaps = false)

      fun from(info: EditorInfo): FieldFlags {
        val type = info.inputType
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) {
          val pin = type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER &&
            type and InputType.TYPE_MASK_VARIATION == InputType.TYPE_NUMBER_VARIATION_PASSWORD
          return NONE.copy(secret = pin)
        }
        val variation = type and InputType.TYPE_MASK_VARIATION
        val password = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
          variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
          variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        val suggest = !password && type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0
        val literal = variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
          variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
          variation == InputType.TYPE_TEXT_VARIATION_URI ||
          variation == InputType.TYPE_TEXT_VARIATION_FILTER ||
          variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        val learn = suggest && info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
        val caps = type and (InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0
        return FieldFlags(suggest, autocorrect = suggest && !literal, learn = learn, autoCaps = caps, secret = password)
      }
    }
  }

  companion object {
    const val LETTERS = "qwerty"
    const val SYMBOLS = "symbols"
    // Longest grapheme we expect (ZWJ emoji sequences run to ~11 chars), with room to spare
    private const val GRAPHEME_LOOKBEHIND = 32
    private const val CONTEXT_CHARS = 64
    private const val RESUME_CHARS = 48
    private const val LEARN_AS_WORD = 2
    private const val AUTOCORRECT_TRIGGERS = ".,?!:;)\""
    private const val SPACE_SWAPPING = ".,?!:;)"
    // After a swiped word: what attaches to it, and what starts something new after a space
    private const val FOLLOWED_BY_SPACE = ".,?!:;)]}"
    private const val PRECEDED_BY_SPACE = "([{\"&"
  }
}
