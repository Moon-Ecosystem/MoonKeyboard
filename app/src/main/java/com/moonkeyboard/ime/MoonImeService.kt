package com.moonkeyboard.ime

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.moonkeyboard.R

/**
 * The keyboard itself. An InputMethodService has no lifecycle owner of its own, so it provides one
 * for Compose to attach to.
 */
class MoonImeService :
  InputMethodService(),
  LifecycleOwner,
  SavedStateRegistryOwner {
  private val lifecycleRegistry = LifecycleRegistry(this)
  private val savedStateController = SavedStateRegistryController.create(this)
  private lateinit var controller: InputController

  override val lifecycle: Lifecycle get() = lifecycleRegistry
  override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

  override fun onCreate() {
    super.onCreate()
    savedStateController.performRestore(null)
    // Compose only recomposes while STARTED; the service lives exactly as long as the keyboard
    lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    Prefs.init(this)
    ClipboardStore.init(this)
    controller = InputController(asKeyboardHost())
    Language.load(this)
    Emoji.load(this)
    clipboard.addPrimaryClipChangedListener(clipListener)
  }

  private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
  private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }

  // Only the focused app and the default keyboard may read the clipboard, and only while it shows
  private fun captureClip() {
    runCatching { clipboard.primaryClip }.getOrNull()?.let { ClipboardStore.capture(this, it) }
  }

  /** Where the keyboard is in the window; the rest of the window is see-through and lets touches through. */
  private val keyboardBounds = Rect()
  private var inputView: View? = null

  override fun onCreateInputView(): View {
    window.window?.decorView?.let {
      it.setViewTreeLifecycleOwner(this)
      it.setViewTreeSavedStateRegistryOwner(this)
    }
    return ComposeView(this).apply {
      setContent {
        KeyboardWindow(controller) { b ->
          if (b != keyboardBounds) {
            keyboardBounds.set(b)
            // Insets are recomputed on a layout pass; a card being dragged doesn't ask for one itself
            requestLayout()
          }
        }
      }
    }.also { inputView = it }
  }

  // The view covers the whole window so the floating keyboard can go anywhere on screen
  override fun setInputView(view: View) {
    super.setInputView(view)
    window.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    window.window?.findViewById<View>(android.R.id.inputArea)?.updateLayoutParams { height = ViewGroup.LayoutParams.MATCH_PARENT }
    view.updateLayoutParams { height = ViewGroup.LayoutParams.MATCH_PARENT }
  }

  // Landscape keeps the app on screen instead of a full-screen text box
  override fun onEvaluateFullscreenMode() = false

  override fun onComputeInsets(outInsets: Insets) {
    super.onComputeInsets(outInsets)
    val height = inputView?.height ?: return
    // A floating keyboard covers nothing the app has to move out of the way of (nor one not laid out yet)
    val top = if (Prefs.floating.value || keyboardBounds.isEmpty) height else keyboardBounds.top
    outInsets.contentTopInsets = top
    outInsets.visibleTopInsets = top
    outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
    outInsets.touchableRegion.set(keyboardBounds)
  }

  override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
    super.onStartInputView(info, restarting)
    controller.onStartInput(info, restarting)
    // Picks up what was copied while the keyboard wasn't running
    captureClip()
    ClipboardStore.expireIfDue()
  }

  override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
    super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
    controller.onSelectionUpdate(newSelStart, newSelEnd, candidatesEnd)
  }

  override fun onFinishInput() {
    controller.onFinishInput()
    Language.flush()
    super.onFinishInput()
  }

  /**
   * Opts in to the system's inline-autofill suggestions: a password manager (or similar) draws its
   * own chip into our strip, and tapping it fills the field. Returns null on older Android, where
   * the system falls back to its own dropdown.
   */
  @RequiresApi(Build.VERSION_CODES.R)
  override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest {
    val density = resources.displayMetrics.density
    // Follow the keyboard's own theme so the chip blends in; services may ignore the hint
    val dark = when (Prefs.theme.value) {
      ThemeMode.SYSTEM -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
      ThemeMode.LIGHT -> false
      ThemeMode.DARK -> true
    }
    val colors = if (dark) KeyboardColors.Dark else KeyboardColors.Light
    val chipBg = Icon.createWithResource(this, if (dark) R.drawable.inline_chip_background else R.drawable.inline_chip_background_light)
    val titleStyle = TextViewStyle.Builder()
      .setTextColor(colors.text.toArgb())
      .setTextSize(14f)
      .build()
    val subtitleStyle = TextViewStyle.Builder()
      .setTextColor(colors.muted.toArgb())
      .setTextSize(12f)
      .build()

    // Public API for keyboards, as the autofill sample keyboard uses it; the restriction is mislabelled
    @SuppressLint("RestrictedApi")
    val chipStyle = ViewStyle.Builder()
      .setBackground(chipBg)
      .build()
    val style = UiVersions.newStylesBuilder()
      .addStyle(
        InlineSuggestionUi.newStyleBuilder()
          .setChipStyle(chipStyle)
          .setTitleStyle(titleStyle)
          .setSubtitleStyle(subtitleStyle)
          .build(),
      )
      .build()
    val minSize = Size((60 * density).toInt(), (36 * density).toInt())
    val maxSize = Size((InputController.CHIP_MAX_DP * density).toInt(), (InputController.CHIP_HEIGHT_DP * density).toInt())
    val spec = InlinePresentationSpec.Builder(minSize, maxSize).setStyle(style).build()
    // Strip is small; only two chips comfortably fit beside the leader icons
    return InlineSuggestionsRequest.Builder(List(2) { spec }).build()
  }

  @RequiresApi(Build.VERSION_CODES.R)
  override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
    controller.setInlineSuggestions(response.inlineSuggestions)
    return true
  }

  override fun onDestroy() {
    clipboard.removePrimaryClipChangedListener(clipListener)
    controller.close()
    Language.flush()
    lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    super.onDestroy()
  }
}
