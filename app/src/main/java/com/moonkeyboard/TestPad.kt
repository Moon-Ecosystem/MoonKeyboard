package com.moonkeyboard

import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonkeyboard.ime.GestureRecorder
import com.moonkeyboard.ime.InputController
import com.moonkeyboard.ime.KeyboardHost
import com.moonkeyboard.ime.KeyboardUi
import com.moonkeyboard.ime.Language

/** A text buffer the keyboard can type into without being the system keyboard. */
internal class TestPadHost(view: View) : KeyboardHost {
  var text by mutableStateOf("")
    private set

  private val buffer = SpannableStringBuilder().also { Selection.setSelection(it, 0) }

  init {
    // SpannableStringBuilder notifies TextWatcher spans; INCLUSIVE keeps this one covering all text
    buffer.setSpan(object : TextWatcher {
      override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
      override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
      override fun afterTextChanged(s: Editable) { text = s.toString() }
    }, 0, 0, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
  }

  override val editorInfo = EditorInfo().apply {
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
      InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
    imeOptions = EditorInfo.IME_ACTION_NONE
  }

  override val inputConnection: InputConnection = object : BaseInputConnection(view, true) {
    override fun getEditable(): Editable = buffer
  }

  override fun sendDownUpKeyEvents(keyCode: Int) = Unit

  fun clear() = buffer.clear()
}

@Composable
fun TestPad(onBack: () -> Unit) {
  BackHandler(onBack = onBack)
  val view = LocalView.current
  val host = remember { TestPadHost(view) }
  val controller = remember {
    Language.load(view.context)
    InputController(host).also { it.onStartInput(host.editorInfo) }
  }
  DisposableEffect(controller) { onDispose { controller.close() } }
  val recorder = remember { GestureRecorder() }
  var showDebug by rememberSaveable { mutableStateOf(true) }

  Surface(Modifier.fillMaxSize()) {
  Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      TextButton(onBack) { Text(stringResource(R.string.back)) }
      Text(stringResource(R.string.test_pad), style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.weight(1f))
      TextButton({ host.clear(); recorder.clear() }) { Text(stringResource(R.string.clear)) }
      TextButton({ showDebug = !showDebug }) { Text(stringResource(if (showDebug) R.string.hide_debug else R.string.show_debug)) }
    }
    Text(
      host.text + "▏",
      Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
      style = MaterialTheme.typography.bodyLarge,
    )
    if (showDebug) DebugPanel(recorder, controller, Modifier.heightIn(max = 240.dp))
    KeyboardUi(controller, recorder)
  }
  }
}

@Composable
private fun DebugPanel(recorder: GestureRecorder, controller: InputController, modifier: Modifier) {
  val clipboard = LocalClipboardManager.current
  val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
  val geometry = recorder.geometry
  val last = recorder.traces.lastOrNull()

  Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
  Column(
    Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(stringResource(R.string.touch_paths), style = MaterialTheme.typography.labelLarge)
      Spacer(Modifier.padding(4.dp))
      Switch(recorder.showPaths, { recorder.showPaths = it })
      Spacer(Modifier.weight(1f))
      TextButton({ recorder.exportJson()?.let { clipboard.setText(AnnotatedString(it)) } }, enabled = recorder.traces.isNotEmpty()) {
        Text(stringResource(R.string.copy_traces, recorder.traces.size))
      }
    }
    Text("layer ${controller.layerId}  shift ${controller.shift}  board ${geometry?.width?.toInt()}×${geometry?.height?.toInt()} px", style = mono)
    if (last != null && geometry != null) {
      val avgSampleMs = if (last.points.size > 1) last.durationMs.toFloat() / (last.points.size - 1) else 0f
      val speed = if (last.durationMs > 0) last.lengthPx / last.durationMs else 0f
      Text("last  typed '${last.typed}'  ${last.points.size} pts  ${last.durationMs} ms  ${last.lengthPx.toInt()} px", style = mono)
      Text("      ${"%.1f".format(speed)} px/ms  sample every ${"%.1f".format(avgSampleMs)} ms", style = mono)
      Text("keys  ${last.keysAlong(geometry).joinToString(" → ")}", style = mono)
    }
    val suggestions = controller.suggestions
    if (suggestions.debug.isNotEmpty()) {
      Text("decoder  '${suggestions.typed}' → ${suggestions.autocorrect ?: "(no autocorrect)"}", style = mono)
      suggestions.debug.forEach { Text("  $it", style = mono) }
    } else if (Language.engine == null) {
      Text("decoder  dictionary loading…", style = mono)
    }
    recorder.log.asReversed().forEach { Text(it, style = mono) }
  }
  }
}
