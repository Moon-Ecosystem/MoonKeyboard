package com.moonkeyboard.ime

import android.content.ClipDescription
import android.content.Context
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.SuggestionSpan
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.moonkeyboard.engine.decode.KeyMap
import com.moonkeyboard.engine.decode.Touch
import com.moonkeyboard.engine.history.UserHistory
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
import com.moonkeyboard.engine.text.oneTimeCode
import com.moonkeyboard.engine.trace.TracePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Where key presses go: the focused app's field when running as the keyboard, or the in-app test pad. */
interface KeyboardHost {
  val inputConnection: InputConnection?
  val editorInfo: EditorInfo
  val context: Context
  fun sendDownUpKeyEvents(keyCode: Int)
}

fun InputMethodService.asKeyboardHost() = object : KeyboardHost {
  override val inputConnection: InputConnection? get() = currentInputConnection
  override val editorInfo: EditorInfo get() = currentInputEditorInfo
  override val context: Context get() = this@asKeyboardHost
  override fun sendDownUpKeyEvents(keyCode: Int) = this@asKeyboardHost.sendDownUpKeyEvents(keyCode)
}

enum class Panel { KEYS, CLIPBOARD, EMOJI, EDIT, OPTIONS }

/**
 * Shows [text] as the word being typed, without the underline apps add by default. Views only add
 * it to plain text, so styled (Spannable) text goes in as is; Compose text fields add it unless the
 * keyboard sends a style of its own, so the word carries an invisible one (a see-through
 * background). It's marked as part of the word being typed, so it's dropped once the word is done.
 */
private fun InputConnection.compose(text: CharSequence) = setComposingText(
  SpannableString(text).apply {
    // An exclusive span can't be empty
    if (isNotEmpty()) setSpan(BackgroundColorSpan(Color.TRANSPARENT), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_COMPOSING)
  },
  1,
)

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
  /** Text the copied-item pill just pasted: the strip offers to undo it, then to redo it, until the user moves on. */
  var undo by mutableStateOf<EditUndo?>(null)
    private set

  /**
   * What the strip offers to take back: text the copied-item pill pasted ([inserted]), words
   * backspace deleted, or an autocorrect that put [text] in place of [original]. [undone] once the
   * offer has been taken, and the offer becomes redo.
   */
  data class EditUndo(val text: String, val inserted: Boolean, val undone: Boolean = false, val original: String? = null) {
    /** The text is in the field now, so the next tap deletes it. */
    val present get() = inserted != undone
  }

  /** What the backspaces since the last other key deleted, oldest first, for [undo]. */
  private val deletedRun = StringBuilder()
  private fun dropUndo() {
    undo = null
    deletedRun.clear()
  }

  /**
   * The cursor stopped inside this word: the strip offers fixes for all of it, and picking one
   * replaces it. Nothing is composed, so typing still goes where the cursor is.
   */
  var midWord by mutableStateOf<MidWord?>(null)
    private set

  /**
   * [start]: where the word begins in the text. [hostFixes]: spell or grammar replacements the
   * host app's own checker put on this word (or a phrase that includes it), surfaced in the strip
   * so the viewer never has to open the host's own popup.
   */
  data class MidWord(val start: Int, val word: String, val context: WordContext, val hostFixes: List<HostFix> = emptyList())

  /** A replacement a `SuggestionSpan` offers, covering absolute offsets [spanStart, spanEnd). */
  data class HostFix(val text: String, val spanStart: Int, val spanEnd: Int)

  /** The last live glide preview; while [suggestions] is still it, the finger hasn't lifted. */
  var glidePreview: Suggestions? = null
    private set

  /**
   * Views the system's autofill service (e.g. a password manager) wants shown in the strip. They
   * are inflated by the service into our strip height, and tapping one triggers autofill. Empty
   * unless the field invites it and an autofill service responds.
   */
  var inlineChips by mutableStateOf<List<View>>(emptyList())
    private set
  @RequiresApi(Build.VERSION_CODES.R)
  fun setInlineSuggestions(suggestions: List<InlineSuggestion>) {
    releaseInlineChips()
    if (suggestions.isEmpty()) return
    val ctx = host.context
    val executor = ContextCompat.getMainExecutor(ctx)
    val density = ctx.resources.displayMetrics.density
    // Each chip as wide as its own content, up to the request's maximum; ones that run past the
    // row scroll in, cut off at its ends by their clip bounds (see clipChip)
    // The request's spec still caps each chip at CHIP_MAX_DP
    val size = Size(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, (CHIP_HEIGHT_DP * density).toInt())
    val shown = suggestions
    // Inflation is asynchronous and callbacks fire out of order; slot each view by its original
    // index and only publish once every slot has settled, matching the AOSP sample IME
    val slots = arrayOfNulls<View>(shown.size)
    val remaining = java.util.concurrent.atomic.AtomicInteger(shown.size)
    fun arrived() {
      if (remaining.decrementAndGet() == 0) inlineChips = slots.filterNotNull()
    }
    for ((i, suggestion) in shown.withIndex()) {
      runCatching {
        suggestion.inflate(ctx, size, executor) { view ->
          // Hidden until the strip has worked out how much of it is in view (see clipChip)
          if (view != null) slots[i] = view.apply { visibility = View.INVISIBLE }
          arrived()
        }
      }.onFailure { arrived() }
    }
  }

  fun releaseInlineChips() {
    inlineChips = emptyList()
  }

  fun onLongPress(action: String) {
    when (action) {
      "emoji" -> {
        finishWord()
        panel = Panel.EMOJI
      }
    }
  }

  /** Search from the emoji panel: the letter keys type into the query. */
  fun startEmojiSearch() {
    finishWord()
    emojiQuery = ""
    panel = Panel.KEYS
    layerId = LETTERS
    // The query is lower case, so the keys shouldn't show capitals from auto-caps
    shiftController.reset()
    syncShift()
  }

  /** Back from search to the emoji panel. */
  fun endEmojiSearch() {
    emojiQuery = null
    panel = Panel.EMOJI
    afterSeparator()
  }

  fun clearEmojiQuery() {
    if (emojiQuery != null) emojiQuery = ""
  }

  /** Letter-key centres from the UI; restored words get synthetic touches from them. */
  var keyMap: KeyMap? = null

  private val composing = StringBuilder()
  private val touches = ArrayList<Touch>()
  // State, so the strip redraws when a restart swaps a text field for a number one
  private var field by mutableStateOf(FieldFlags.NONE)
  private var inputClass = -1
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
    val emoji = id == LETTERS && Prefs.emojiKey.value
    // The letters come from the active language's pack (QWERTZ for German); the other layers are shared
    val pack = if (id == LETTERS) Language.pack else null
    return layouts.getOrPut(id + (pack?.let { "@" + it.manifest.id } ?: "") + (if (numbers) "+numbers" else "") + (if (emoji) "+emoji" else "")) {
      (pack?.layout(pack.manifest.layout) ?: Layouts.load(id))
        .let { if (numbers) Layouts.withNumberRow(it) else it }.let { if (emoji) Layouts.withEmojiKey(it) else it }
    }
  }

  /** The languages a long press on space offers, switched-on ones in order, and the one to land on. */
  fun languageChoices(): Pair<List<PackFiles>, Int> {
    val on = LanguagePacks.all(host.context).filter { it.second }.map { it.first }
    val active = on.indexOfFirst { it.manifest.id == Language.pack?.manifest?.id }
    // A quick hold and lift goes to the next language
    return on to if (on.isEmpty()) 0 else (active + 1) % on.size
  }

  /** Types on in language [id], or with null opens the system's list of keyboards. */
  fun switchLanguage(id: String?) {
    finishWord()
    if (id == null) {
      host.context.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
      return
    }
    Language.switchTo(id)
    suggestions = Suggestions.EMPTY
  }

  init {
    // The dictionary may still be loading when the first field opens
    scope.launch {
      Language.ready.filterNotNull().first()
      if (composing.isEmpty()) refreshPredictions() else requestSuggestions()
    }
  }

  /**
   * A field gained focus. [restarting]: the same field restarted its input (Firefox does this when
   * you tap back into a search box); the open panel stays, but the strip is filled again, since
   * [onFinishInput] emptied it. Chrome restarts when focus moves between a page's boxes too, so a
   * restart into a different kind of field (text to number) is treated as a new field.
   */
  fun onStartInput(info: EditorInfo, restarting: Boolean = false) {
    forgetAsk = null
    dropUndo()
    midWord = null
    field = FieldFlags.from(info).let { if (Prefs.autocorrect.value) it else it.copy(autocorrect = false) }
      .copy(contentTypes = EditorInfoCompat.getContentMimeTypes(info).toList())
    val kind = info.inputType and InputType.TYPE_MASK_CLASS
    val sameKind = kind == inputClass
    inputClass = kind
    if (restarting && sameKind) {
      selAnchor = info.initialSelStart
      selCursor = info.initialSelEnd
      clearWord()
      cursorPlaced()
      return
    }
    layerId = when (kind) {
      InputType.TYPE_CLASS_NUMBER -> NUMBER
      InputType.TYPE_CLASS_PHONE -> PHONE
      InputType.TYPE_CLASS_DATETIME -> DATETIME
      else -> LETTERS
    }
    Language.engine?.lexicon?.let {
      it.blockOffensive = Prefs.blockOffensive.value
      it.hinglish = Prefs.hinglish.value
    }
    Language.applyNeural()
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
    cursorPlaced()
  }

  /**
   * Typing puts the copied item aside so the strip can suggest; it comes back once the cursor is put
   * somewhere new (a tap in the text, another field, the keyboard reopening) until it is pasted.
   */
  var clipSetAside by mutableStateOf(false)
    private set

  /** When a finger was last on the keyboard; selection changes soon after are echoes of our own edits. */
  private var lastTouchMs = 0L
  /** A finger still on the keyboard: a held backspace keeps editing with no new touch events. */
  private var fingerDown = false

  fun onKeyboardTouch(fingerDown: Boolean) {
    this.fingerDown = fingerDown
    lastTouchMs = SystemClock.uptimeMillis()
  }

  private fun isEcho() = fingerDown || SystemClock.uptimeMillis() - lastTouchMs < ECHO_MS

  /**
   * The cursor landed somewhere the keyboard didn't put it (focus, a tap in the text, the app
   * clearing the field): the word it ends is picked up so the strip offers fixes and completions
   * for it ("who|"), as after backspacing into a word; elsewhere the strip predicts what comes next.
   */
  private fun cursorPlaced(bySlide: Boolean = false) {
    midWord = null
    clipSetAside = false
    val ic = host.inputConnection
    if (ic != null && (bySlide || !isEcho()) && composing.isEmpty() && panel == Panel.KEYS && emojiQuery == null && field.suggest) resumeWord(ic)
    if (composing.isNotEmpty()) {
      // Picked up the word the cursor ends (an app filling the field, as WhatsApp's edit does): letters
      // typed now continue it, so an auto capital from the empty field it started as goes off
      shiftController.autoCapitalize(false)
      syncShift()
      return requestSuggestions()
    }
    // Auto-caps syncs either way; one of mid-word similar words or next-word predictions takes the strip,
    // not both — predictions in between would flash before the async mid-word decode replaces them
    syncCaps()
    val m = midWord
    if (m == null) refreshPredictions() else suggestMidWord(m)
  }

  private fun syncCaps() {
    if (field.autoCaps && Prefs.autoCaps.value) {
      host.inputConnection?.let { ic ->
        shiftController.autoCapitalize(ic.getCursorCapsMode(host.editorInfo.inputType) != 0)
      }
    }
    syncShift()
  }

  private fun suggestMidWord(m: MidWord) {
    val engine = Language.engine ?: return
    val keys = keyMap
    val wordTouches = m.word.map { keys?.touchFor(it) ?: Touch.exact(it) }
    val hostTexts = m.hostFixes.map { it.text }
    decodeJob?.cancel()
    decodeJob = scope.launch {
      // No autocorrect: space doesn't apply these, it types a space where the cursor is. No completions
      // either: the whole word is already there, longer words would read as other words, not fixes
      val result = withContext(decodeDispatcher) { engine.suggest(m.word, wordTouches, keys, m.context, allowAutocorrect = false, allowCompletions = false) }
      if (midWord != m) return@launch
      // The host's own fixes come first — the system spell checker (and whatever grammar correction
      // it has) already looked at this word; we trust its picks more than our edit-distance guesses.
      // A real word already in the field otherwise has the typed form in the middle with similar words
      // either side; a typed non-word has itself on the left and the closer alternatives in the middle
      // and right. In both cases the engine already laid out items as the strip wants them.
      val merged = mergeHostFixes(hostTexts, result.items)
      if (merged.isNotEmpty()) {
        suggestions = if (merged == result.items) result else result.copy(items = merged)
        return@launch
      }
      // Nothing close enough edit-distance-wise ("banananana" is five keystrokes away from any word):
      // fall back to similar words from the start of what was typed, so the strip still offers a way out
      val cut = (m.word.length / 2).coerceIn(2, SIMILAR_PREFIX)
      if (cut >= m.word.length) return@launch
      val prefix = m.word.take(cut)
      val similar = withContext(decodeDispatcher) {
        engine.suggest(prefix, wordTouches.take(cut), keys, m.context, allowAutocorrect = false)
      }.items.filter { it.isNotEmpty() && !it.equals(m.word, ignoreCase = true) && !it.equals(prefix, ignoreCase = true) }.take(3)
      if (midWord != m) return@launch
      val fallback = mergeHostFixes(hostTexts, similar)
      if (fallback.isNotEmpty()) suggestions = result.copy(items = fallback) else refreshPredictions()
    }
  }

  /** Host-reported fixes come first (deduped), then as many of [engineItems] as still fit in 3 slots. */
  private fun mergeHostFixes(hostTexts: List<String>, engineItems: List<String>): List<String> {
    if (hostTexts.isEmpty()) return engineItems
    val seen = HashSet<String>()
    val out = ArrayList<String>(3)
    for (t in hostTexts + engineItems) {
      if (out.size >= 3) break
      if (t.isEmpty()) continue
      if (seen.add(t.lowercase())) out += t
    }
    return out
  }

  /** The space-bar slide let go: the word the cursor stopped after is picked up, as a tap in the text would. */
  fun cursorSlideEnded() = cursorPlaced(bySlide = true)

  fun onFinishInput() {
    // Leaving an email field: keep the address typed there, for the next email field
    if (field.email && !Prefs.incognito.value) {
      host.inputConnection?.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()?.trim()?.let { address ->
        Language.engine?.shared?.let { h ->
          if (UserHistory.isEmail(address)) {
            h.recordEmail(address)
            Language.historyChanged()
          }
        }
      }
    }
    if (composing.isNotEmpty()) host.inputConnection?.finishComposingText()
    clearWord()
    suggestions = Suggestions.EMPTY
    releaseInlineChips()
  }

  /** The editor's selection moved; if the user moved it away from the word, stop composing it. */
  fun onSelectionUpdate(selStart: Int, selEnd: Int, composingEnd: Int) {
    // Keep our own direction when this is the echo of a selection we set
    if (minOf(selAnchor, selCursor) != selStart || maxOf(selAnchor, selCursor) != selEnd) {
      selAnchor = selStart
      selCursor = selEnd
    }
    if (composing.isEmpty()) {
      // Our own commits echo back here too; only a cursor the user (or app) moved is acted on
      if (selStart == selEnd && !isEcho()) {
        dropUndo()
        revert = null
        autoSpaced = false
        lastWasSpace = false
        phantomSpace = false
        cursorPlaced()
      }
      return
    }
    if (composingEnd < 0) return
    if (selStart != selEnd || composingEnd != selEnd) {
      host.inputConnection?.finishComposingText()
      clearWord()
      revert = null
      autoSpaced = false
      lastWasSpace = false
      phantomSpace = false
      // The cursor left the word being typed for a point (into its middle, or another word): pick up
      // the word it's in, as any tap would, rather than predicting the next word as after a space.
      // Forced past the echo check: the finger is often still down, and the editor may not report
      // the cursor again once the composing word is finished
      if (selStart == selEnd) cursorPlaced(bySlide = true) else afterSeparator()
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
        KeyType.SHIFT, KeyType.EMOJI -> Unit
      }
      return
    }
    if (key.type != KeyType.SHIFT && key.type != KeyType.LAYER) {
      forgetAsk = null
      midWord = null
      // Backspace adds to what it can undo; anything else ends that
      if (key.type != KeyType.BACKSPACE) dropUndo()
    }
    when (key.type) {
      KeyType.CHAR -> typeText(display(key.label), x, y)
      KeyType.SPACE -> space()
      KeyType.BACKSPACE -> deleted(backspace())
      KeyType.ENTER -> enter()
      KeyType.SHIFT -> {
        shiftController.onShiftTap(nowMs)
        syncShift()
      }
      KeyType.LAYER -> layerId = key.layer!!
      KeyType.EMOJI -> onLongPress("emoji")
    }
  }

  fun commitPopup(text: String, x: Float, y: Float) {
    emojiQuery?.let { emojiQuery = it + text.lowercase(Locale.ROOT); return }
    // A digit held out of a letter (r → 4) was picked on purpose: no position, so it only reads as itself
    if (text.singleOrNull()?.isDigit() == true) typeText(text, Float.NaN, Float.NaN) else typeText(display(text), x, y)
  }

  /** Text from a panel (emoji, a clipboard item): ends the word in progress, then goes in as is. */
  fun insert(text: String) = typeText(text, Float.NaN, Float.NaN)

  /** The copied-item pill's paste, which the strip then offers to undo. */
  fun pasteFresh(text: String) {
    insert(text)
    undo = EditUndo(text, inserted = true)
  }

  /** The strip's undo or redo: deletes [undo]'s text if it is in the field, else puts it back. */
  fun toggleUndo() {
    val u = undo ?: return
    val ic = host.inputConnection ?: return
    finishWord()
    deletedRun.clear()
    if (u.original != null) {
      // Autocorrect revert: swap between the correction and what the user typed
      val have = if (u.undone) u.original else u.text
      val want = if (u.undone) u.text else u.original
      if (textBefore(ic, have.length) != have) {
        undo = null
        return
      }
      val context = WordContext.from(ic.getTextBeforeCursor(CONTEXT_CHARS + have.length, 0)?.dropLast(have.length) ?: "")
      ic.beginBatchEdit()
      ic.deleteSurroundingText(have.length, 0)
      ic.commitText(want, 1)
      ic.endBatchEdit()
      // Undoing a correction means the user meant it, as with backspace
      if (!u.undone) learn(want.trimEnd { !isWordChar(it) }, context, weight = LEARN_AS_WORD)
      // The space-triggered revert is now spent; backspace shouldn't try the same thing
      revert = null
      undo = u.copy(undone = !u.undone)
      afterSeparator()
      return
    }
    if (u.present) {
      // Only while it's still just before the cursor; the app may have changed the text since
      if (textBefore(ic, u.text.length) != u.text) {
        undo = null
        return
      }
      ic.deleteSurroundingText(u.text.length, 0)
    } else {
      ic.commitText(u.text, 1)
    }
    undo = u.copy(undone = !u.undone)
    afterSeparator()
  }

  /**
   * A backspace took [removed] away (null: it wasn't a plain deletion, or the field didn't say
   * what went). Once the run has taken a whole finished word (crossed back to a space or the
   * field start), the strip offers to put back all this run deleted.
   */
  private fun deleted(removed: String?) {
    // Starting over from a paste's undo, or from words just put back
    if (undo?.let { it.inserted || it.undone } == true) dropUndo()
    val ic = host.inputConnection
    // Still held at the start of the field, with nothing left to delete: keep the offer
    if (removed.isNullOrEmpty() && ic != null && composing.isEmpty() && textBefore(ic, 1).isEmpty()) return
    if (removed.isNullOrEmpty() || ic == null || field.secret) return dropUndo()
    deletedRun.insert(0, removed)
    val before = textBefore(ic, 1)
    val atWordStart = composing.isEmpty() && (before.isEmpty() || before[0].isWhitespace())
    // Once offered, it stays and grows while the run goes on, rather than blinking at each word
    val offer = undo != null || atWordStart && deletedRun.any(Char::isLetterOrDigit)
    undo = if (offer) EditUndo(deletedRun.toString(), inserted = false) else null
  }

  /** Where a backspace slide started (the cursor) and the text before it; -1 when none is under way. */
  private var slideEnd = -1
  private var slideBefore = ""

  /** A backspace slide: selects the last [words] words before where it started (0 selects nothing). */
  fun selectWordsBack(words: Int) {
    val ic = host.inputConnection ?: return
    if (slideEnd < 0) {
      if (words == 0) return
      finishWord()
      // From the editor: the backspace that started the slide may not have echoed back yet
      val text = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
      if (text.selectionEnd < 0) return
      slideEnd = text.startOffset + text.selectionEnd
      slideBefore = ic.getTextBeforeCursor(SLIDE_LOOKBEHIND, 0)?.toString() ?: ""
    }
    var start = slideBefore.length
    repeat(words) {
      while (start > 0 && slideBefore[start - 1].isWhitespace()) start--
      while (start > 0 && !slideBefore[start - 1].isWhitespace()) start--
    }
    selAnchor = slideEnd - (slideBefore.length - start)
    selCursor = slideEnd
    ic.setSelection(selAnchor, selCursor)
  }

  /** The backspace slide lifted: delete what it selected, with the strip's undo offer as for backspace. */
  fun deleteSelectedWords() {
    if (slideEnd < 0) return
    val selected = selCursor != selAnchor
    slideEnd = -1
    slideBefore = ""
    if (selected) deleted(backspace())
  }

  /** The emoji and edit panels' delete key, which the strip's undo doesn't follow. */
  fun deleteBackward() {
    dropUndo()
    backspace()
  }

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
    midWord = null
    finishWord()
    val ic = host.inputConnection ?: return
    if ((keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) && selCursor >= 0) {
      stepCursor(ic, forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT, select)
      return
    }
    if ((keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) && selCursor >= 0) {
      // With no line break that way the app would move focus to another field, so go to the text's start or end.
      // shortcut: a soft-wrapped first or last paragraph jumps to its end rather than one visual line; cursor bounds would fix it
      val up = keyCode == KeyEvent.KEYCODE_DPAD_UP
      val side = (if (up) ic.getTextBeforeCursor(LINE_LOOKAROUND, 0) else ic.getTextAfterCursor(LINE_LOOKAROUND, 0)) ?: ""
      if ('\n' !in side) {
        // The text either side is measured from the selection's edges
        selCursor = if (up) maxOf(0, minOf(selAnchor, selCursor) - side.length) else maxOf(selAnchor, selCursor) + side.length
        if (!select) selAnchor = selCursor
        ic.setSelection(selAnchor, selCursor)
        return
      }
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

  /**
   * What the copied-item pill pastes for copied [text], or null to not offer it. A number field gets
   * just the one-time code from a message, or the copy itself when it holds no letters (an account
   * number); a message without a clear code would paste as a jumble of its digits.
   */
  fun copiedTextOffer(text: String): String? =
    if (!field.number) text else oneTimeCode(text) ?: text.trim().takeIf { t -> t.isNotEmpty() && t.none(Char::isLetter) }

  /** The app whose field is focused. */
  val targetPackage: String? get() = host.editorInfo.packageName

  /**
   * The field takes images of [mime] (apps list what they accept in their EditorInfo). Read from [field],
   * which Compose watches, so the strip offers a copied image as soon as a field that takes it opens.
   */
  fun acceptsContent(mime: String): Boolean = field.contentTypes.any { ClipDescription.compareMimeTypes(mime, it) }

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
    emailPrefix?.let { prefix ->
      // A saved address takes the place of the start typed, with no space after it
      emailPrefix = null
      ic.beginBatchEdit()
      if (composing.isNotEmpty()) ic.finishComposingText()
      clearWord()
      if (textBefore(ic, prefix.length).equals(prefix, ignoreCase = true)) ic.deleteSurroundingText(prefix.length, 0)
      ic.commitText(word, 1)
      ic.endBatchEdit()
      afterSeparator()
      return
    }
    midWord?.let { m ->
      // A host fix may replace a phrase ("is struggling" → "are struggling"), not just this one word;
      // its span range is kept so the whole phrase gives way. Otherwise the word under the cursor does.
      val fix = m.hostFixes.firstOrNull { it.text == word }
      val replaceStart = fix?.spanStart ?: m.start
      val replaceEnd = fix?.spanEnd ?: (m.start + m.word.length)
      midWord = null
      ic.beginBatchEdit()
      ic.setSelection(replaceStart, replaceEnd)
      ic.commitText(word, 1)
      ic.endBatchEdit()
      learn(word, m.context)
      afterSeparator()
      return
    }
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

  /** A long-pressed suggestion: its slot asks whether to forget it, until answered or the user types on. */
  var forgetAsk by mutableStateOf<String?>(null)
    private set

  fun askForget(index: Int) {
    val word = suggestions.items.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return
    forgetAsk = word
    // Unanswered, the slot goes back to the word
    forgetAskTimeout?.cancel()
    forgetAskTimeout = scope.launch {
      delay(FORGET_ASK_MS)
      forgetAsk = null
    }
  }
  private var forgetAskTimeout: Job? = null

  fun cancelForget() {
    forgetAsk = null
  }

  /** Never suggest the asked-about word again (until the user types it themselves). */
  fun forgetSuggestion() {
    val word = forgetAsk ?: return
    forgetAsk = null
    val engine = Language.engine ?: return
    // The strip capitalises at a sentence start ("Nba"); forget the word as it is stored ("nba"),
    // which a name like "Paris" already is
    val stored = listOf(word, word.lowercase()).firstOrNull { engine.lexicon.find(it) >= 0 || engine.history.count(it) > 0 } ?: word
    engine.history.forget(stored)
    Language.historyChanged()
    refreshStrip()
  }

  /** The strip's words again for where the cursor is, after the history changed under them. */
  private fun refreshStrip() {
    val m = midWord
    when {
      composing.isNotEmpty() -> requestSuggestions()
      m != null -> suggestMidWord(m)
      else -> refreshPredictions()
    }
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
      val preview = withContext(decodeDispatcher) { engine.glide(path, keys, context, firstUpper, allUpper) }
      glidePreview = preview
      suggestions = preview
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
    clipSetAside = true
    forgetAsk = null
    dropUndo()
    midWord = null
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
    clipSetAside = true
    forgetAsk = null
    dropUndo()
    midWord = null
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
    // A digit after letters stays in the word ("c4ossover", "mp3"), so the engine sees all of it
    if (field.suggest && c != null && (c.isLetter() || ((c == '\'' || c.isDigit()) && composing.isNotEmpty()))) {
      if (composing.isEmpty()) adoptDigitsBefore(ic)
      composing.append(c)
      // Only a number-row digit keeps where it was hit (near the letters below it); one from the symbols
      // layer sat over a letter's spot, and a long-pressed one was chosen on purpose
      val placed = !x.isNaN() && (!c.isDigit() || layerId == LETTERS)
      touches += if (placed) Touch(x, y, c) else keyMap?.touchFor(c) ?: Touch.exact(c)
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
    val corrected = if (composing.isNotEmpty()) commitWord(autocorrect = c != null && c in AUTOCORRECT_TRIGGERS) else null
    if (autoSpaced && c != null && c in SPACE_SWAPPING && textBefore(ic, 1) == " ") {
      // "word " + "." → "word. ": punctuation goes before the space we added
      ic.deleteSurroundingText(1, 0)
      ic.commitText("$c ", 1)
    } else {
      ic.commitText(text, 1)
      autoSpaced = false
    }
    ic.endBatchEdit()
    if (corrected != null) {
      revert = corrected.copy(separator = text)
      undo = EditUndo(corrected.corrected + text, inserted = false, original = corrected.typed + text)
    }
    shiftController.onCharTyped()
    afterSeparator()
  }

  private fun space() {
    val ic = host.inputConnection ?: return
    learnEmailBefore(ic)
    revert = null
    // The previous autocorrect's pill is about this space; a new one takes its place or none does
    if (undo?.original != null) undo = null
    phantomSpace = false
    ic.beginBatchEdit()
    if (composing.isNotEmpty()) {
      val corrected = commitWord(autocorrect = true)
      ic.commitText(" ", 1)
      revert = corrected?.copy(separator = " ")
      undo = corrected?.let { EditUndo(it.corrected + " ", inserted = false, original = it.typed + " ") }
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

  /** Returns the text it deleted, or null when it undid a correction or can't tell what went. */
  private fun backspace(): String? {
    val ic = host.inputConnection ?: return null
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
        return null
      }
    }
    if (composing.isNotEmpty() && glided) {
      // A glide put the whole word down at once, so one backspace takes it all back
      val word = composing.toString()
      ic.commitText("", 1)
      clearWord()
      afterSeparator()
      return word
    }
    if (composing.isNotEmpty()) {
      val n = lastGraphemeLength(composing)
      val gone = composing.substring(composing.length - n)
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
      return gone
    }
    val selected = ic.getSelectedText(0)?.toString()
    val gone = if (!selected.isNullOrEmpty()) {
      ic.commitText("", 1)
      selected
    } else {
      val before = ic.getTextBeforeCursor(GRAPHEME_LOOKBEHIND, 0)?.toString() ?: ""
      val n = lastGraphemeLength(before)
      // Fields that don't expose their text still understand a raw delete key
      if (n > 0) ic.deleteSurroundingText(n, 0) else host.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
      before.takeLast(n).ifEmpty { null }
    }
    if (field.suggest) resumeWord(ic)
    if (composing.isEmpty()) afterSeparator() else requestSuggestions()
    return gone
  }

  /**
   * What ENTER does in the focused field: 0 means newline (multi-line field, or the field opts
   * out), otherwise the editor action to perform. The drawn icon follows this too.
   */
  val enterAction: Int get() {
    val info = host.editorInfo
    val multiLine = info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
    val noAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
    if (multiLine || noAction) return EditorInfo.IME_ACTION_NONE
    return info.imeOptions and EditorInfo.IME_MASK_ACTION
  }

  private fun enter() {
    val ic = host.inputConnection ?: return
    learnEmailBefore(ic)
    revert = null
    phantomSpace = false
    lastWasSpace = false
    autoSpaced = false
    val action = enterAction
    if (composing.isNotEmpty()) {
      ic.beginBatchEdit()
      // An action (search, go, send...) sends the text at once, leaving no chance to undo a
      // correction, so it sends what was typed, as AOSP's keyboard does; a new line still corrects
      commitWord(autocorrect = action == EditorInfo.IME_ACTION_NONE)
      ic.endBatchEdit()
    }
    if (action != EditorInfo.IME_ACTION_NONE) {
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

  /**
   * After backspacing to the end of a word, pick it up again so it can be corrected. A cursor
   * inside a word only offers fixes for it ([midWord]).
   */
  private fun resumeWord(ic: InputConnection) {
    // Digits are part of a word, as when typing it ("covid19", "2nd")
    fun inWord(c: Char) = isWordChar(c) || c.isDigit()
    val after = ic.getTextAfterCursor(RESUME_CHARS, 0) ?: ""
    var end = 0
    while (end < after.length && inWord(after[end])) end++
    // A word running past the lookahead limit may be longer than we can see
    if (end == RESUME_CHARS) return
    val before = ic.getTextBeforeCursor(RESUME_CHARS, 0) ?: return
    var start = before.length
    while (start > 0 && inWord(before[start - 1])) start--
    // A word running into the lookbehind limit may be longer than we can see
    if (start == 0 && before.length == RESUME_CHARS) return
    val word = before.substring(start)
    if (word.isEmpty() || !word[0].isLetterOrDigit() || word.none(Char::isLetter)) return
    val text = ic.getExtractedText(ExtractedTextRequest(), 0)
    val cursor = cursorIn(text)
    if (end > 0) {
      if (cursor < word.length) return
      // Set the mid-word even for a real word: the strip offers similar ones in case the user put the cursor there to swap it
      val textBefore = ic.getTextBeforeCursor(CONTEXT_CHARS + word.length, 0)?.toString() ?: ""
      val wordStart = cursor - word.length
      val wordEnd = cursor + end
      midWord = MidWord(wordStart, word + after.substring(0, end), WordContext.from(textBefore.dropLast(word.length)), hostFixesIn(text, wordStart, wordEnd))
      return
    }
    pickUp(ic, word, cursor)
  }

  /**
   * A letter right after digits joins them ("2" + "nd"), so "2nd" or "10am" is one word that space keeps,
   * not "2" and a word "nd" to autocorrect.
   */
  private fun adoptDigitsBefore(ic: InputConnection) {
    val before = textBefore(ic, RESUME_CHARS)
    val digits = before.takeLastWhile(Char::isDigit)
    if (digits.isEmpty() || digits.length == RESUME_CHARS) return
    // Digits that are part of something longer ("v1.2", "1,000", or letters a cursor move left behind) stay as they are
    val prev = before.getOrNull(before.length - digits.length - 1)
    if (prev != null && (isWordChar(prev) || prev == '.' || prev == ',')) return
    pickUp(ic, digits, cursorIn(ic.getExtractedText(ExtractedTextRequest(), 0)))
  }

  /** The cursor's absolute offset, or -1 when there's a selection or the editor won't say. */
  private fun cursorIn(text: ExtractedText?): Int =
    if (text != null && text.selectionStart == text.selectionEnd && text.selectionStart >= 0) text.startOffset + text.selectionStart else -1

  /** Makes [word], just before the cursor at [cursor], the composing word again. */
  private fun pickUp(ic: InputConnection, word: String, cursor: Int) {
    // Mark the word where it is: deleting and retyping it made Firefox's caret jump to its start
    ic.beginBatchEdit()
    if (cursor >= word.length && ic.setComposingRegion(cursor - word.length, cursor)) {
      // The same word over itself, for the invisible style that keeps Compose fields from underlining it
      ic.compose(word)
    } else {
      // Editors without composing regions get the word retyped instead
      ic.deleteSurroundingText(word.length, 0)
      ic.compose(word)
    }
    ic.endBatchEdit()
    composing.append(word)
    word.forEach { touches += keyMap?.touchFor(it) ?: Touch.exact(it) }
  }

  /**
   * Spell or grammar fixes the host app's own checker has already placed on the word (or a phrase
   * that includes it): read them out of the extracted text's spans. A span may cover more than one
   * word ("is struggling" → "are struggling"); its range is kept so a pick replaces the whole span.
   */
  private fun hostFixesIn(extracted: android.view.inputmethod.ExtractedText?, wordStart: Int, wordEnd: Int): List<HostFix> {
    val spanned = extracted?.text as? Spanned ?: return emptyList()
    val offset = extracted.startOffset
    val relStart = wordStart - offset
    val relEnd = wordEnd - offset
    if (relStart < 0 || relEnd > spanned.length) return emptyList()
    return spanned.getSpans(relStart, relEnd, SuggestionSpan::class.java).asSequence()
      .flatMap { span ->
        val spanStart = offset + spanned.getSpanStart(span)
        val spanEnd = offset + spanned.getSpanEnd(span)
        span.suggestions.asSequence().filter { it.isNotEmpty() }.map { HostFix(it, spanStart, spanEnd) }
      }
      .distinctBy { it.text }
      .take(5)
      .toList()
  }

  /**
   * In an email field: the saved addresses that start with what's typed so far (all of them in an
   * empty field), most used first. False when none match, so the usual strip shows instead.
   */
  private fun showEmails(ic: InputConnection): Boolean {
    val history = Language.engine?.shared ?: return false
    val prefix = (ic.getTextBeforeCursor(EMAIL_LOOKBEHIND, 0) ?: "").toString().takeLastWhile { !it.isWhitespace() }
    val matches = when {
      field.email -> history.emailsStartingWith(prefix)
      // Elsewhere, letters alone are left to the engine's suggestions; past a digit or dot ("jharishabh2")
      // the word being typed is only the part after it, so the whole run is matched here, with the same
      // 4 letters for one address and 7 for all
      field.secret || !field.suggest || prefix.all(::isWordChar) -> emptyList()
      prefix.length >= EMAIL_ALL_LETTERS -> history.emailsStartingWith(prefix)
      prefix.length >= EMAIL_MIN_LETTERS -> history.emailsStartingWith(prefix, 1)
      else -> emptyList()
    }
    if (matches.isEmpty()) return false
    emailPrefix = prefix
    // Best in the middle, like the word suggestions; an empty slot stays empty ("" isn't drawn or
    // tappable), so a lone address sits in the middle, where a long one gets the whole strip
    suggestions = Suggestions(prefix, listOf(matches.getOrNull(1) ?: "", matches[0], matches.getOrNull(2) ?: ""), null, isPrediction = true)
    return true
  }

  /**
   * An address just finished with space or enter, in any field that learns: kept like one typed in an
   * email field, so "rish" can offer it later. Email fields save the whole field when left instead.
   */
  private fun learnEmailBefore(ic: InputConnection) {
    if (!field.learn || Prefs.incognito.value) return
    val token = (ic.getTextBeforeCursor(EMAIL_LOOKBEHIND, 0) ?: "").toString().takeLastWhile { !it.isWhitespace() }
      // A sentence ending on an address ("mail rishabh@gmail.com.") leaves its full stop out
      .trimEnd('.', ',', ';', ':', '!', '?', ')', '"', '\'')
    if (!UserHistory.isEmail(token)) return
    Language.engine?.shared?.recordEmail(token) ?: return
    Language.historyChanged()
  }

  /** What the strip's addresses would replace, while it shows them. */
  private var emailPrefix: String? = null

  private fun requestSuggestions() {
    val engine = Language.engine ?: return
    val ic = host.inputConnection ?: return
    emailPrefix = null
    if (showEmails(ic)) return
    val typed = composing.toString()
    val typedTouches = touches.toList()
    val context = contextBeforeWord(ic)
    val keys = keyMap
    val allowAutocorrect = field.autocorrect
    // Until the decode lands, show the letters typed rather than nothing: an empty strip falls back to the
    // toolbar for a frame (worst from the undo pill in an empty field). Marked a prediction, so space never
    // takes it as this word's result and still decodes for its autocorrect.
    if (suggestions.items.isEmpty() || suggestions.isPrediction) {
      suggestions = Suggestions(typed, listOf("", typed, ""), null, isPrediction = true)
    }
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
    emailPrefix = null
    if (showEmails(ic)) return
    val before = ic.getTextBeforeCursor(CONTEXT_CHARS, 0) ?: ""
    // Nothing written yet: no guesses, so the strip shows the toolbar
    if (before.isBlank()) {
      suggestions = Suggestions.EMPTY
      return
    }
    suggestions = engine.predict(WordContext.from(before), capitalize = shift != ShiftState.OFF)
  }

  private fun afterSeparator() {
    if (field.autoCaps && Prefs.autoCaps.value) {
      host.inputConnection?.let { ic ->
        shiftController.autoCapitalize(ic.getCursorCapsMode(host.editorInfo.inputType) != 0)
      }
    }
    syncShift()
    refreshPredictions()
  }

  private fun learn(word: String, context: WordContext, weight: Int = 1) {
    if (!field.learn || Prefs.incognito.value) return
    // A picked address counts as one more use of it, not a word
    if (UserHistory.isEmail(word)) {
      Language.engine?.shared?.recordEmail(word)
      Language.historyChanged()
      return
    }
    // A picked shortcut's text ("on my way") isn't a word to learn
    if (word.any(Char::isWhitespace)) return
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
    // "12  34" in a number or phone field is spacing, not the end of a sentence
    if (inputClass != InputType.TYPE_CLASS_TEXT) return false
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
  private data class FieldFlags(
    val suggest: Boolean, val autocorrect: Boolean, val learn: Boolean, val autoCaps: Boolean, val secret: Boolean = false, val number: Boolean = false, val email: Boolean = false,
    /** The kinds of image the field takes (its EditorInfo's content MIME types). */
    val contentTypes: List<String> = emptyList(),
  ) {
    companion object {
      val NONE = FieldFlags(suggest = false, autocorrect = false, learn = false, autoCaps = false)

      fun from(info: EditorInfo): FieldFlags {
        val type = info.inputType
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) {
          val number = type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER
          val pin = number && type and InputType.TYPE_MASK_VARIATION == InputType.TYPE_NUMBER_VARIATION_PASSWORD
          return NONE.copy(secret = pin, number = number)
        }
        val variation = type and InputType.TYPE_MASK_VARIATION
        val password = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
          variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
          variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        // Search boxes that draw their own suggestion list often say "no suggestions"; the strip
        // still helps there, so the flag only turns off what changes or remembers the text
        val noSuggestions = type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        val suggest = !password
        val literal = noSuggestions || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
          variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
          variation == InputType.TYPE_TEXT_VARIATION_URI ||
          variation == InputType.TYPE_TEXT_VARIATION_FILTER ||
          variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        val learn = suggest && !noSuggestions && info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
        val caps = type and (InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0
        val email = variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        // An address's pieces ("rishabh123", "gmail") aren't words to learn; the whole address is kept instead
        return FieldFlags(suggest, autocorrect = suggest && !literal, learn = learn && !email, autoCaps = caps, secret = password, email = email && learn)
      }
    }
  }

  companion object {
    // Autofill chips
    const val CHIP_MAX_DP = 280f
    const val CHIP_HEIGHT_DP = 40f
    const val CHIP_GAP_DP = 6f
    const val LETTERS = "qwerty"
    const val SYMBOLS = "symbols"
    // Pads for fields that only take digits; no way to letters, as in AOSP
    const val NUMBER = "number"
    const val PHONE = "phone"
    const val DATETIME = "datetime"
    val PADS = setOf(NUMBER, PHONE, "phone_symbols", DATETIME)
    // Longest grapheme we expect (ZWJ emoji sequences run to ~11 chars), with room to spare
    private const val GRAPHEME_LOOKBEHIND = 32
    // How far ↑ ↓ look for a line break; past this, a field with none is treated as ending there
    private const val LINE_LOOKAROUND = 10_000
    private const val FORGET_ASK_MS = 4_000L
    // Enough for the neural model's 32 words of context
    private const val CONTEXT_CHARS = 200
    private const val RESUME_CHARS = 48
    // How far back a backspace slide can select: a long paragraph's worth of words
    private const val SLIDE_LOOKBEHIND = 2_000
    // Longer than any address someone types
    private const val EMAIL_LOOKBEHIND = 256
    // As in the engine: one saved address from the 4th character, all that match from the 7th
    private const val EMAIL_MIN_LETTERS = 4
    private const val EMAIL_ALL_LETTERS = 7
    /** Letters of a stuck mid-word kept as a prefix to look up similar words from. */
    private const val SIMILAR_PREFIX = 5
    /** Apps echo our edits back well within this; a cursor move later than it came from elsewhere. */
    private const val ECHO_MS = 500L
    private const val LEARN_AS_WORD = 2
    private const val AUTOCORRECT_TRIGGERS = ".,?!:;)\""
    private const val SPACE_SWAPPING = ".,?!:;)"
    // After a swiped word: what attaches to it, and what starts something new after a space
    private const val FOLLOWED_BY_SPACE = ".,?!:;)]}"
    private const val PRECEDED_BY_SPACE = "([{\"&"
  }
}
