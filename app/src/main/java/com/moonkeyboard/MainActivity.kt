package com.moonkeyboard

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Onboarding: turn the keyboard on, switch to it, try it. Settings will live here too. */
class MainActivity : ComponentActivity() {
  private var enabled by mutableStateOf(false)
  private var selected by mutableStateOf(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        var testPad by rememberSaveable { mutableStateOf(false) }
        if (testPad) {
          TestPad(onBack = { testPad = false })
        } else {
          SetupScreen(
            enabled = enabled,
            selected = selected,
            onEnable = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
            onSelect = { getSystemService(InputMethodManager::class.java).showInputMethodPicker() },
            onTestPad = { testPad = true },
          )
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    refresh()
  }

  // The keyboard picker is a dialog, so closing it doesn't resume the activity, only refocuses it
  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) refresh()
  }

  private fun refresh() {
    val imm = getSystemService(InputMethodManager::class.java)
    enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
    val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    selected = current?.let { ComponentName.unflattenFromString(it)?.packageName } == packageName
  }
}

@Composable
private fun SetupScreen(enabled: Boolean, selected: Boolean, onEnable: () -> Unit, onSelect: () -> Unit, onTestPad: () -> Unit) {
  Scaffold { padding ->
    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
      Text(stringResource(R.string.setup_tagline), style = MaterialTheme.typography.bodyLarge)
      Step(R.string.setup_step_enable, R.string.setup_step_enable_body, R.string.setup_open_settings, done = enabled, actionEnabled = true, onEnable)
      Step(R.string.setup_step_select, R.string.setup_step_select_body, R.string.setup_choose, done = selected, actionEnabled = enabled, onSelect)
      if (enabled && selected) {
        var text by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.setup_try)) })
      }
      Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.test_pad), style = MaterialTheme.typography.titleMedium)
          Text(stringResource(R.string.setup_test_pad_body), style = MaterialTheme.typography.bodyMedium)
          OutlinedButton(onTestPad) { Text(stringResource(R.string.test_pad)) }
        }
      }
    }
  }
}

@Composable
private fun Step(title: Int, body: Int, action: Int, done: Boolean, actionEnabled: Boolean, onAction: () -> Unit) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
      Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
      if (done) {
        Text(stringResource(R.string.setup_done), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
      } else {
        Button(onAction, enabled = actionEnabled) { Text(stringResource(action)) }
      }
    }
  }
}
