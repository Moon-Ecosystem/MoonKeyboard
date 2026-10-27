package com.moonkeyboard.ime

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.moonkeyboard.engine.input.ShiftController
import com.moonkeyboard.engine.input.ShiftState
import com.moonkeyboard.engine.layout.Key
import com.moonkeyboard.engine.layout.KeyType
import com.moonkeyboard.engine.layout.Layout
import com.moonkeyboard.engine.layout.Layouts
import com.moonkeyboard.engine.text.lastGraphemeLength
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

/** Turns key presses into edits on the host's text field. Its state drives the keyboard UI. */
class InputController(private val host: KeyboardHost) {
  private val layouts = HashMap<String, Layout>()
  private val shiftController = ShiftController()

  var layerId by mutableStateOf(LETTERS)
    private set
  var shift by mutableStateOf(ShiftState.OFF)
    private set

  val layout: Layout get() = layouts.getOrPut(layerId) { Layouts.load(layerId) }

  fun onStartInput(info: EditorInfo) {
    layerId = when (info.inputType and InputType.TYPE_MASK_CLASS) {
      InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> SYMBOLS
      else -> LETTERS
    }
    shiftController.reset()
    shift = shiftController.state
  }

  fun onKey(key: Key, nowMs: Long) {
    when (key.type) {
      KeyType.CHAR -> commit(display(key.label))
      KeyType.SPACE -> commit(" ")
      KeyType.BACKSPACE -> backspace()
      KeyType.ENTER -> enter()
      KeyType.SHIFT -> {
        shiftController.onShiftTap(nowMs)
        shift = shiftController.state
      }
      KeyType.LAYER -> layerId = key.layer!!
    }
  }

  fun commitPopup(text: String) = commit(display(text))

  /** Letters follow the shift state; everything else is shown as is. */
  fun display(text: String): String =
    if (shift != ShiftState.OFF) text.uppercase(Locale.ROOT) else text

  private fun commit(text: String) {
    host.inputConnection?.commitText(text, 1)
    shiftController.onCharTyped()
    shift = shiftController.state
  }

  private fun backspace() {
    val ic = host.inputConnection ?: return
    if (!ic.getSelectedText(0).isNullOrEmpty()) {
      ic.commitText("", 1)
      return
    }
    val n = lastGraphemeLength(ic.getTextBeforeCursor(GRAPHEME_LOOKBEHIND, 0) ?: "")
    // Fields that don't expose their text still understand a raw delete key
    if (n > 0) ic.deleteSurroundingText(n, 0) else host.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
  }

  private fun enter() {
    val ic = host.inputConnection ?: return
    val info = host.editorInfo
    val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
    val multiLine = info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
    val noAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
    if (!multiLine && !noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
      ic.performEditorAction(action)
    } else {
      ic.commitText("\n", 1)
    }
  }

  companion object {
    const val LETTERS = "qwerty"
    const val SYMBOLS = "symbols"
    // Longest grapheme we expect (ZWJ emoji sequences run to ~11 chars), with room to spare
    private const val GRAPHEME_LOOKBEHIND = 32
  }
}
