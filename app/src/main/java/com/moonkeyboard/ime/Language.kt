package com.moonkeyboard.ime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.moonkeyboard.engine.dict.Lexicon
import com.moonkeyboard.engine.history.UserHistory
import com.moonkeyboard.engine.suggest.NeuralLm
import com.moonkeyboard.engine.suggest.SuggestionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The active language's pack and the user's history, loaded once per process and shared by the
 * keyboard service and the test pad. One language is loaded at a time; suggestions are simply
 * absent until it has loaded, after starting or switching.
 */
object Language {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var loading: Job? = null
  private var saveJob: Job? = null
  private lateinit var app: Context
  /** The version of each history last written to its file. */
  private val savedVersions = HashMap<File, Long>()
  /** The active language's history file, matching [engine]. */
  @Volatile
  private var historyFile: File? = null

  private val loaded = MutableStateFlow<SuggestionEngine?>(null)

  /** Emits the engine once it has loaded. */
  val ready: StateFlow<SuggestionEngine?> = loaded

  val engine: SuggestionEngine? get() = loaded.value

  /** The active language's pack; Compose state, so the keys and the space bar follow it. */
  var pack by mutableStateOf<PackFiles?>(null)
    private set

  @Synchronized
  fun load(context: Context) {
    if (::app.isInitialized) return
    app = context.applicationContext
    switchTo(Prefs.language.value)
  }

  /** Makes [id] the active language (English when it isn't installed), loading its pack and learned words. */
  @Synchronized
  fun switchTo(id: String) {
    val next = LanguagePacks.find(app, id) ?: LanguagePacks.builtIn(app)
    if (next.manifest.id == pack?.manifest?.id) return
    pack = next
    Prefs.language.value = next.manifest.id
    val old = engine
    val oldFile = historyFile
    loaded.value = null
    historyFile = null
    neural = null
    val previous = loading
    loading = scope.launch {
      previous?.cancelAndJoin()
      if (old != null && oldFile != null) save(old, oldFile)
      // English's history also holds the emails and shortcuts every language shares
      val shared = old?.shared ?: read(LanguagePacks.historyFile(app, LanguagePacks.BUILT_IN))
      val file = LanguagePacks.historyFile(app, next.manifest.id)
      val history = if (next.manifest.id == LanguagePacks.BUILT_IN) shared else read(file)
      val lexicon = Lexicon.read(next.open(next.dictionary)!!.use { it.readBytes() })
      lexicon.blockOffensive = Prefs.blockOffensive.value
      lexicon.hinglish = Prefs.hinglish.value
      ensureActive()
      historyFile = file
      loaded.value = SuggestionEngine(lexicon, history, next.manifest.engineConfig(), next.manifest.decoderConfig(), shared = shared)
      // After the dictionary, so typing works from it alone while this loads; without it (a failed
      // load, or a pack without a model), suggestions simply stay dictionary-only
      neural = runCatching { loadNeural(next) }.getOrNull()
      applyNeural()
    }
  }

  private fun read(file: File): UserHistory =
    (if (file.exists()) UserHistory.parse(file.readText()) else UserHistory()).also { savedVersions[file] = it.version }

  @Volatile
  private var neural: NeuralLm? = null

  /** Blends the neural model into suggestions, or takes it out, as the setting says. */
  fun applyNeural() {
    engine?.neural = if (Prefs.neuralPredictions.value) neural else null
  }

  /** The pack's neural model; a pack without one throws, leaving suggestions dictionary-only. */
  private fun loadNeural(pack: PackFiles): NeuralLm {
    val env = OrtEnvironment.getEnvironment()
    val model = pack.open(pack.model)!!.use { it.readBytes() }
    // One thread: a single word's prediction is too small to gain from more, and idle threads spin
    val session = env.createSession(model, OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) })
    val vocab = pack.open(pack.vocab)!!.bufferedReader().use { it.readLines() }
    return NeuralLm(vocab) { ids ->
      OnnxTensor.createTensor(env, arrayOf(ids)).use { input ->
        session.run(mapOf("ids" to input)).use {
          @Suppress("UNCHECKED_CAST")
          (it[0].value as Array<Array<FloatArray>>)[0][0]
        }
      }
    }
  }

  /** Saves a couple of seconds after the last change, so a burst of typing is one write. */
  fun historyChanged() {
    saveJob?.cancel()
    saveJob = scope.launch {
      delay(SAVE_DELAY_MS)
      saveActive()
    }
  }

  fun flush() {
    scope.launch { saveActive() }
  }

  private fun saveActive() {
    val e = engine ?: return
    save(e, historyFile ?: return)
  }

  /** Writes [e]'s learned words to [file], and the shared emails and shortcuts to English's file. */
  @Synchronized
  private fun save(e: SuggestionEngine, file: File) {
    write(e.history, file)
    if (e.shared !== e.history) write(e.shared, LanguagePacks.historyFile(app, LanguagePacks.BUILT_IN))
  }

  private fun write(history: UserHistory, file: File) {
    val version = history.version
    if (version == savedVersions[file]) return
    // Write then rename, so a crash mid-write can't lose what was already saved
    val tmp = File(file.path + ".tmp")
    tmp.writeText(history.serialize())
    if (tmp.renameTo(file)) savedVersions[file] = version
  }

  private const val SAVE_DELAY_MS = 2_000L
}
