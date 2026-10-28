package com.moonkeyboard.ime

import android.content.Context
import com.moonkeyboard.engine.dict.Lexicon
import com.moonkeyboard.engine.history.UserHistory
import com.moonkeyboard.engine.suggest.SuggestionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The English pack plus the user's history, loaded once per process and shared by the keyboard
 * service and the test pad. Suggestions are simply absent until loading finishes.
 */
object Language {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var loading: Job? = null
  private var saveJob: Job? = null
  private var savedVersion = 0L
  private lateinit var historyFile: File

  private val loaded = MutableStateFlow<SuggestionEngine?>(null)

  /** Emits the engine once it has loaded. */
  val ready: StateFlow<SuggestionEngine?> = loaded

  val engine: SuggestionEngine? get() = loaded.value

  @Synchronized
  fun load(context: Context) {
    if (loading != null) return
    val app = context.applicationContext
    historyFile = File(app.filesDir, "history.tsv")
    loading = scope.launch {
      val lexicon = Lexicon.read(app.assets.open("dict/en.dict").use { it.readBytes() })
      val history = if (historyFile.exists()) UserHistory.parse(historyFile.readText()) else UserHistory()
      savedVersion = history.version
      loaded.value = SuggestionEngine(lexicon, history)
    }
  }

  /** Saves a couple of seconds after the last change, so a burst of typing is one write. */
  fun historyChanged() {
    saveJob?.cancel()
    saveJob = scope.launch {
      delay(SAVE_DELAY_MS)
      save()
    }
  }

  fun flush() {
    scope.launch { save() }
  }

  @Synchronized
  private fun save() {
    val history = engine?.history ?: return
    val version = history.version
    if (version == savedVersion) return
    // Write then rename, so a crash mid-write can't lose what was already saved
    val tmp = File(historyFile.path + ".tmp")
    tmp.writeText(history.serialize())
    if (tmp.renameTo(historyFile)) savedVersion = version
  }

  private const val SAVE_DELAY_MS = 2_000L
}
