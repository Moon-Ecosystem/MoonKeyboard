package com.moonkeyboard

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.moonkeyboard.ime.ClipboardStore
import com.moonkeyboard.ime.Emoji
import com.moonkeyboard.ime.Language
import com.moonkeyboard.ime.Prefs

/** The app: setup until the keyboard is on and selected, then its settings and the test pad. */
class MainActivity : ComponentActivity() {
  private var enabled by mutableStateOf(false)
  private var selected by mutableStateOf(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Prefs.init(this)
    ClipboardStore.init(this)
    Language.load(this)
    Emoji.load(this)
    enableEdgeToEdge()
    setContent {
      AppTheme {
        val colors = LocalAppColors.current
        var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
        val home = { screen = Screen.HOME }
        if (screen != Screen.HOME) BackHandler(onBack = home)
        Box(Modifier.fillMaxSize().background(colors.background).statusBarsPadding()) {
          // The look screen's keyboard preview pads itself for the navigation bar
          val bars = if (screen == Screen.LOOK || screen == Screen.TEST_PAD) Modifier else Modifier.navigationBarsPadding().imePadding()
          Box(bars) {
            when (screen) {
              Screen.HOME -> if (enabled && selected) {
                HomeScreen { screen = it }
              } else {
                WelcomeScreen(
                  enabled = enabled,
                  onEnable = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                  onSelect = { getSystemService(InputMethodManager::class.java).showInputMethodPicker() },
                  onTestPad = { screen = Screen.TEST_PAD },
                )
              }
              Screen.LOOK -> LookScreen(home)
              Screen.TYPING -> TypingScreen(home)
              Screen.GLIDE -> GlideScreen(home)
              Screen.EMOJI -> EmojiScreen(home)
              Screen.CLIPBOARD -> ClipboardScreen(home)
              Screen.LEARNED -> LearnedScreen(home)
              Screen.TEST_PAD -> MaterialTheme(colorScheme = if (colors == AppColors.Dark) darkColorScheme() else lightColorScheme()) {
                TestPad(onBack = home)
              }
            }
          }
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
