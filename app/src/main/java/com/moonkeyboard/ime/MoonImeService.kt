package com.moonkeyboard.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * The keyboard itself. An InputMethodService has no lifecycle owner of its own, so it provides one
 * for Compose to attach to.
 */
class MoonImeService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {
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
    controller = InputController(asKeyboardHost())
    Language.load(this)
  }

  override fun onCreateInputView(): View {
    window.window?.decorView?.let {
      it.setViewTreeLifecycleOwner(this)
      it.setViewTreeSavedStateRegistryOwner(this)
    }
    return ComposeView(this).apply { setContent { KeyboardUi(controller) } }
  }

  override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
    super.onStartInputView(info, restarting)
    if (!restarting) controller.onStartInput(info)
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

  override fun onDestroy() {
    controller.close()
    Language.flush()
    lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    super.onDestroy()
  }
}
