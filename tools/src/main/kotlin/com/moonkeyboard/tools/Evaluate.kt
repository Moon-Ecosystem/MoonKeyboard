package com.moonkeyboard.tools

import com.moonkeyboard.engine.decode.DecoderConfig
import com.moonkeyboard.engine.decode.GlideConfig
import com.moonkeyboard.engine.dict.Lexicon
import com.moonkeyboard.engine.eval.TypingSimulator
import com.moonkeyboard.engine.history.UserHistory
import com.moonkeyboard.engine.layout.KeyboardGeometry
import com.moonkeyboard.engine.layout.Layouts
import com.moonkeyboard.engine.pack.Packs
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.moonkeyboard.engine.suggest.EngineConfig
import com.moonkeyboard.engine.suggest.NeuralLm
import com.moonkeyboard.engine.suggest.SuggestionEngine
import com.moonkeyboard.engine.suggest.WordContext
import java.io.File

/**
 * Accuracy on held-out sentences with simulated typists, and (optionally) on a flat typo→correct list:
 *   --dict <en.dict> --eval <sentences.txt> [--offset 0] [--limit 600] [--errors 25] [--grid true] [--hinglish false]
 *   any of these with --pack <a pack's source folder> to type with its layout and language rules
 *   --dict <en.dict> --typos <pairs.tsv> [--errors 25]
 *   --dict <a.dict> --compare <b.dict> --eval <sentences.txt> [--limit 600]   next-word prediction, paired, with McNemar's test
 *   --dict <en.dict> --neural <dir> --eval <sentences.txt>   everything without, with (next word only) and with the neural model
 * Run: ./gradlew :tools:run -PmainClass=com.moonkeyboard.tools.EvaluateKt --args="..."
 */
fun main(args: Array<String>) {
  val opts = args.toList().chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
  val lexicon = Lexicon.read(File(opts.getValue("dict")).readBytes())
  // --hinglish false measures the pack the way it types with Hinglish turned off
  lexicon.hinglish = opts["hinglish"] != "false"
  // --pack <a pack's source folder>: its layout and language rules (MoonKeyboard-Languages' <id>/pack)
  val pack = opts["pack"]?.let { File(it) }
  val manifest = pack?.let { Packs.parseManifest(File(it, "pack.json").readBytes()) }
  val layout = if (pack != null && manifest != null) Layouts.parse(File(pack, "${manifest.layout}.json").readText(), manifest.layout) else Layouts.load("qwerty")
  val geometry = KeyboardGeometry.compute(layout, 390f, 46f, 6f, 10f, 4f)
  val base = manifest?.let { Setup(engine = it.engineConfig(), decoder = it.decoderConfig()) } ?: Setup()
  val errors = opts["errors"]?.toInt() ?: 25

  opts["typos"]?.let { path ->
    print(typosReport(lexicon, File(path).readLines().filter { it.isNotBlank() }, geometry, base, errors))
    return
  }
  // --offset skips the sentences the grid tuned on, to check the result on unseen ones
  val sentences = File(opts.getValue("eval")).readLines().filter { it.isNotBlank() }
    .drop(opts["offset"]?.toInt() ?: 0).take(opts["limit"]?.toInt() ?: 600)

  // --compare <other.dict>: the --dict one is A, this one B
  opts["compare"]?.let { other ->
    val b = Lexicon.read(File(other).readBytes()).also { it.hinglish = lexicon.hinglish }
    print(compareReport(lexicon, b, sentences, geometry, base))
    return
  }
  // --hits <file>: each word's next-word result, to pair with another model's on the same words
  opts["hits"]?.let { path ->
    File(path).writeText(hitsReport(lexicon, sentences, geometry, base))
    return
  }
  // --lm-dump <file>: the 3-gram's view at each word, for blending with another model offline
  opts["lm-dump"]?.let { path ->
    lmDump(lexicon, sentences, File(path))
    return
  }
  // --neural <dir with en-lm.onnx and en-lm.vocab>: the full report without the model, with it for
  // next-word predictions only, and with it everywhere (typing and autocorrect too)
  opts["neural"]?.let { dir ->
    val neural = loadNeural(File(dir))
    // --grid true with --neural: retune only the knobs the blend shifts, starting from the shipped setup
    if (opts["grid"] == "true") {
      // shortcut: one pass, as each report takes ~5 minutes with the model; two passes if a retune ever moves far
      grid(lexicon, sentences, geometry, Setup(neural = neural), setOf("neuralLmWeight", "neuralTypingShare", "autocorrectMargin", "realWordMargin", "glideWeight"), passes = 1)
      return
    }
    val setups = listOf(
      "3-gram only" to Setup(),
      "neural blend, next word only" to Setup(engine = EngineConfig(neuralWhileTyping = false), neural = neural),
      "neural blend everywhere" to Setup(neural = neural),
    )
    for ((label, setup) in setups) {
      println("== $label")
      println(report(lexicon, sentences, geometry, setup, opts["errors"]?.toInt() ?: 0).summary)
    }
    return
  }
  if (opts["learning-grid"] == "true") {
    learningGrid(lexicon, sentences)
    return
  }
  if (opts["grid"] == "true") {
    grid(lexicon, sentences, geometry)
    return
  }
  println(report(lexicon, sentences, geometry, base, errors).summary)
}

/**
 * Autocorrect accuracy on a flat list of real-world "typo<TAB>correct" pairs (e.g. the Wikipedia
 * common misspellings list). Each pair is one trial: the typo's letters are tapped at their key
 * centres and the engine's autocorrect is checked against the intended word. Measures a specific
 * failure mode the sentence simulator never produces: the user typed a non-word that looks like
 * another word on the keyboard, and the engine has to pick the right one.
 */
fun typosReport(lexicon: Lexicon, lines: List<String>, geometry: KeyboardGeometry, setup: Setup, showErrors: Int = 0): String {
  val engine = SuggestionEngine(lexicon, UserHistory(), setup.engine, setup.decoder, setup.glide, setup.neural)
  val sim = com.moonkeyboard.engine.eval.TypingSimulator(geometry, seed = 1)
  val out = StringBuilder()
  var total = 0; var fixed = 0; var inStrip = 0; var alreadyAWord = 0
  val examples = ArrayList<String>()
  for (line in lines) {
    val parts = line.split('\t')
    if (parts.size != 2) continue
    val (typo, target) = parts
    if (typo.isBlank() || target.isBlank()) continue
    val (typed, touches) = sim.tap(typo, 0f)
    val s = engine.suggest(typed, touches, sim.keys, WordContext.START, allowAutocorrect = true)
    val result = s.autocorrect ?: typed
    total++
    if (engine.isValid(typed)) alreadyAWord++
    if (result.equals(target, ignoreCase = true)) fixed++
    if (s.items.any { it.equals(target, ignoreCase = true) }) inStrip++
    if (!result.equals(target, ignoreCase = true) && examples.size < showErrors) {
      examples += "  %-16s → %-16s (want %s)  strip %s".format(typo, result, target, s.items)
    }
  }
  out.appendLine("typos: autocorrected %5.1f%%  in strip %5.1f%%  (also-a-word %4.1f%%, so unfixable without context)  (%d pairs)"
    .format(pct(fixed, total), pct(inStrip, total), pct(alreadyAWord, total), total))
  examples.forEach(out::appendLine)
  return out.toString()
}

/** Every tunable in one place, so the grid can vary one at a time. */
data class Setup(
  val engine: EngineConfig = EngineConfig(),
  val decoder: DecoderConfig = DecoderConfig(),
  val glide: GlideConfig = GlideConfig(),
  val neural: NeuralLm? = null,
)

/** The app's neural model (en-lm.onnx and en-lm.vocab in [dir]) on the desktop ONNX Runtime. */
fun loadNeural(dir: File): NeuralLm {
  val env = OrtEnvironment.getEnvironment()
  val session = env.createSession(File(dir, "en-lm.onnx").path, OrtSession.SessionOptions())
  return NeuralLm(File(dir, "en-lm.vocab").readLines()) { ids ->
    OnnxTensor.createTensor(env, arrayOf(ids)).use { input ->
      session.run(mapOf("ids" to input)).use {
        @Suppress("UNCHECKED_CAST")
        (it[0].value as Array<Array<FloatArray>>)[0][0]
      }
    }
  }
}

class Report(val tap: Double, val tapHarm: Double, val glide: Double, val predict: Double, val summary: String) {
  /** One number to optimise: committed words right, with wrong "fixes" of correct words counted double. */
  val score: Double get() = tap - tapHarm + glide
}

private val TAP_NOISE = listOf(0.35f, 0.5f)
/** (corner noise, smoothing, undershoot): a careful swiper, and a fast one who turns early. */
private val GLIDE_NOISE = listOf(Triple(0.2f, 0.4f, 0f), Triple(0.3f, 0.6f, 0.35f))

fun report(lexicon: Lexicon, sentences: List<String>, geometry: KeyboardGeometry, setup: Setup, showErrors: Int = 0): Report {
  val engine = SuggestionEngine(lexicon, UserHistory(), setup.engine, setup.decoder, setup.glide, setup.neural)
  val out = StringBuilder()
  var tapSum = 0.0
  var harmSum = 0.0
  var glideSum = 0.0

  for (sigma in TAP_NOISE) {
    val sim = TypingSimulator(geometry, seed = 1)
    var words = 0; var literal = 0; var committed = 0; var strip = 0; var harmed = 0; var shown = 0
    val examples = ArrayList<String>()
    for (sentence in sentences) {
      var context = WordContext.START
      for (target in sentence.split(' ')) {
        val (typed, touches) = sim.tap(target, sigma)
        if (typed.isEmpty()) continue
        val s = engine.suggest(typed, touches, sim.keys, context, allowAutocorrect = true)
        val result = s.autocorrect ?: typed
        val literalOk = typed.equals(target, ignoreCase = true)
        val ok = result.equals(target, ignoreCase = true)
        words++
        if (literalOk) literal++
        if (ok) committed++
        if (literalOk && !ok) harmed++
        if (s.items.any { it.equals(target, ignoreCase = true) }) strip++
        if (!ok && shown < showErrors) {
          shown++
          examples += "  %-12s typed %-12s → %-12s strip %s".format(target, typed, result, s.items)
        }
        context = context.then(target)
      }
    }
    tapSum += committed.toDouble() / words
    harmSum += harmed.toDouble() / words
    out.appendLine("tap   noise %.2f: raw %5.1f%%  committed %5.1f%%  in strip %5.1f%%  broke a right word %4.1f%%  (%d words)"
      .format(sigma, pct(literal, words), pct(committed, words), pct(strip, words), pct(harmed, words), words))
    examples.forEach(out::appendLine)
  }

  for ((sigma, smoothing, undershoot) in GLIDE_NOISE) {
    val sim = TypingSimulator(geometry, seed = 2)
    var words = 0; var top1 = 0; var strip = 0; var shown = 0
    val examples = ArrayList<String>()
    for (sentence in sentences) {
      var context = WordContext.START
      for (target in sentence.split(' ')) {
        if (target.count { it.isLetter() } >= 2) {
          val s = engine.glide(sim.glide(target, sigma, smoothing, undershoot), sim.keys, context, firstUpper = false, allUpper = false)
          words++
          if (s.typed.equals(target, ignoreCase = true)) top1++
          else if (shown < showErrors) {
            shown++
            examples += "  %-12s → %-12s strip %s".format(target, s.typed, s.items)
          }
          if (s.items.any { it.equals(target, ignoreCase = true) }) strip++
        }
        context = context.then(target)
      }
    }
    glideSum += top1.toDouble() / words
    out.appendLine("glide noise %.2f smooth %.1f undershoot %.2f: right %5.1f%%  in strip %5.1f%%  (%d words)".format(sigma, smoothing, undershoot, pct(top1, words), pct(strip, words), words))
    examples.forEach(out::appendLine)
  }

  // Next word shown before typing a single letter, and once its first letter is typed exactly
  val keys = TypingSimulator(geometry).keys
  var words = 0; var predicted = 0; var long = 0; var afterOne = 0
  for (sentence in sentences) {
    var context = WordContext.START
    for (target in sentence.split(' ')) {
      words++
      if (engine.predict(context, capitalize = false).items.any { it.equals(target, ignoreCase = true) }) predicted++
      if (target.length >= 3) {
        long++
        val first = target.substring(0, 1)
        if (engine.suggest(first, first.map(keys::touchFor), keys, context, allowAutocorrect = false).items.any { it.equals(target, ignoreCase = true) }) afterOne++
      }
      context = context.then(target)
    }
  }
  out.appendLine("next word: in strip before typing %5.1f%%, after its first letter %5.1f%% (words of 3+ letters)".format(pct(predicted, words), pct(afterOne, long)))
  out.append(learningReport(lexicon, sentences, setup))
  return Report(tapSum / TAP_NOISE.size, harmSum / TAP_NOISE.size, glideSum / GLIDE_NOISE.size, predicted.toDouble() / words, out.toString())
}

/**
 * Next-word prediction of two dictionaries on the same sentences, word by word, with McNemar's
 * test on the words only one of them got: a difference counts only if it's unlikely to be noise.
 */
fun compareReport(a: Lexicon, b: Lexicon, sentences: List<String>, geometry: KeyboardGeometry, setup: Setup): String {
  val engines = listOf(a, b).map { SuggestionEngine(it, UserHistory(), setup.engine, setup.decoder, setup.glide, setup.neural) }
  val keys = TypingSimulator(geometry).keys
  // [before typing, after the first letter]: words only A got, only B got, and each one's hits
  val onlyA = IntArray(2); val onlyB = IntArray(2); val hitsA = IntArray(2); val hitsB = IntArray(2)
  var words = 0; var long = 0
  for (sentence in sentences) {
    var context = WordContext.START
    for (target in sentence.split(' ')) {
      words++
      val before = engines.map { e -> e.predict(context, capitalize = false).items.any { it.equals(target, ignoreCase = true) } }
      tally(before, 0, hitsA, hitsB, onlyA, onlyB)
      if (target.length >= 3) {
        long++
        val first = target.substring(0, 1)
        val after = engines.map { e -> e.suggest(first, first.map(keys::touchFor), keys, context, allowAutocorrect = false).items.any { it.equals(target, ignoreCase = true) } }
        tally(after, 1, hitsA, hitsB, onlyA, onlyB)
      }
      context = context.then(target)
    }
  }
  val out = StringBuilder()
  for ((i, label) in listOf("before typing", "after 1st letter").withIndex()) {
    val n = if (i == 0) words else long
    val disagree = onlyA[i] + onlyB[i]
    // Continuity-corrected McNemar statistic, chi-squared with one degree of freedom
    val chi2 = if (disagree == 0) 0.0 else (kotlin.math.abs(onlyA[i] - onlyB[i]) - 1.0).coerceAtLeast(0.0).let { it * it } / disagree
    val p = chiSquared1Tail(chi2)
    out.appendLine("%-17s A %5.1f%%  B %5.1f%%  diff %+5.1f pts  (only A %d, only B %d, p=%.3g, %d words)".format(
      label, pct(hitsA[i], n), pct(hitsB[i], n), pct(hitsB[i] - hitsA[i], n), onlyA[i], onlyB[i], p, n))
  }
  return out.toString()
}

/** One line per sentence, one code per word: in the strip before typing (1/0), then after its first letter (1/0, "-" under 3 letters). */
fun hitsReport(lexicon: Lexicon, sentences: List<String>, geometry: KeyboardGeometry, setup: Setup): String {
  val engine = SuggestionEngine(lexicon, UserHistory(), setup.engine, setup.decoder, setup.glide, setup.neural)
  val keys = TypingSimulator(geometry).keys
  return sentences.joinToString("\n", postfix = "\n") { sentence ->
    var context = WordContext.START
    sentence.split(' ').joinToString(" ") { target ->
      val before = engine.predict(context, capitalize = false).items.any { it.equals(target, ignoreCase = true) }
      val first = target.substring(0, 1)
      val after = if (target.length < 3) "-" else if (engine.suggest(first, first.map(keys::touchFor), keys, context, allowAutocorrect = false).items.any { it.equals(target, ignoreCase = true) }) "1" else "0"
      context = context.then(target)
      (if (before) "1" else "0") + after
    }
  }
}

/**
 * One line per word: "target<TAB>backoff<TAB>word logProb<TAB>...", the words the context expects and
 * the log factor on every other word's unigram. unigram.tsv beside it has each suggestable word's.
 */
fun lmDump(lexicon: Lexicon, sentences: List<String>, file: File) {
  val engine = SuggestionEngine(lexicon, UserHistory(), Setup().engine, Setup().decoder, Setup().glide)
  File(file.parentFile, "unigram.tsv").printWriter().use { out ->
    for (id in 0 until lexicon.wordCount) if (lexicon.isSuggestable(id)) out.println("${lexicon.word(id)}\t${lexicon.unigramLogProb(id)}")
  }
  file.printWriter().use { out ->
    for (sentence in sentences) {
      var context = WordContext.START
      for (target in sentence.split(' ')) {
        val (scores, backoff) = engine.contextScores(context)
        out.println(listOf(target, backoff.toString()).plus(scores.map { (w, lp) -> "$w $lp" }).joinToString("\t"))
        context = context.then(target)
      }
      out.println()
    }
  }
}

private fun tally(hit: List<Boolean>, i: Int, hitsA: IntArray, hitsB: IntArray, onlyA: IntArray, onlyB: IntArray) {
  if (hit[0]) hitsA[i]++
  if (hit[1]) hitsB[i]++
  if (hit[0] && !hit[1]) onlyA[i]++
  if (hit[1] && !hit[0]) onlyB[i]++
}

/** P(X ≥ x) for chi-squared with one degree of freedom: erfc(sqrt(x/2)). */
private fun chiSquared1Tail(x: Double): Double {
  val z = kotlin.math.sqrt(x / 2)
  // Abramowitz–Stegun 7.1.26, accurate to about 1e-7
  val t = 1 / (1 + 0.3275911 * z)
  val poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
  return poly * kotlin.math.exp(-z * z)
}

/** Things one person types again and again; every fourth sentence of the learning run is one of these. */
private val HABITS = listOf(
  "On my way home", "See you at the gym", "Running late be there soon", "Call me when you are free",
  "Did you feed the cat", "Can you pick up the kids", "Love you see you tonight", "Just landed will call you later",
  "Meeting ran over will be late", "What time is dinner", "Thanks for the update", "Let me check and get back to you",
  "I will send the report tomorrow", "Happy birthday have a great day", "Are we still on for lunch",
  "Leaving the office now", "Can we move the call to four", "Good morning how did you sleep",
  "Sounds good see you then", "Please review the pull request",
)

/**
 * Prediction while learning: a simulated user types the held-out sentences in order, every fourth
 * one replaced by one of their [HABITS]. Each word is predicted first, then recorded, as the app does.
 * Reports habit sentences and ordinary sentences separately, with and without learning.
 */
private fun learningReport(lexicon: Lexicon, sentences: List<String>, setup: Setup): String {
  val (habitStatic, otherStatic, habitLearned, otherLearned) = learning(lexicon, sentences, setup)
  return "next word while learning: repeated phrases %5.1f%% → %5.1f%%, everything else %5.1f%% → %5.1f%%\n"
    .format(habitStatic, habitLearned, otherStatic, otherLearned)
}

/** Sweeps only the user-model knobs (fast: predictions only), ranked by habit + everything-else accuracy. */
fun learningGrid(lexicon: Lexicon, sentences: List<String>) {
  val results = ArrayList<Pair<Double, String>>()
  for (share in listOf(0.3f, 0.5f, 0.7f, 0.85f)) for (confidence in listOf(0.5f, 1f, 2f, 4f, 8f)) for (vocabulary in listOf(0f, 0.05f, 0.15f)) {
    val engine = EngineConfig(userShare = share, userConfidence = confidence, userVocabularyShare = vocabulary)
    val (_, _, habit, other) = learning(lexicon, sentences, Setup(engine = engine))
    // Ordinary sentences weigh double: learning must not cost the everyday case much
    results += (habit + 2 * other) to "share %.2f confidence %.1f vocabulary %.2f: habits %5.1f%%, other %5.1f%%".format(share, confidence, vocabulary, habit, other)
  }
  results.sortedByDescending { it.first }.take(10).forEach { println(it.second) }
}

private fun learning(lexicon: Lexicon, sentences: List<String>, setup: Setup): List<Double> {
  val random = java.util.Random(3)
  val stream = sentences.mapIndexed { i, s -> if (i % 4 == 3) HABITS[random.nextInt(HABITS.size)] to true else s to false }
  fun run(learn: Boolean): Pair<Double, Double> {
    val history = UserHistory()
    val engine = SuggestionEngine(lexicon, history, setup.engine, setup.decoder, setup.glide, setup.neural)
    val hits = IntArray(2)
    val totals = IntArray(2)
    for ((sentence, habit) in stream) {
      val k = if (habit) 1 else 0
      var context = WordContext.START
      for (target in sentence.split(' ')) {
        totals[k]++
        if (engine.predict(context, capitalize = false).items.any { it.equals(target, ignoreCase = true) }) hits[k]++
        if (learn) {
          // Same as the app: a capital only there because the sentence started is dropped
          val word = if (context.sentenceStart && lexicon.find(target) < 0) target.lowercase() else target
          history.record(context.userKey, word, prev2 = context.userKey2)
        }
        context = context.then(target)
      }
    }
    return 100.0 * hits[1] / totals[1] to 100.0 * hits[0] / totals[0]
  }
  val (habitStatic, otherStatic) = run(learn = false)
  val (habitLearned, otherLearned) = run(learn = true)
  return listOf(habitStatic, otherStatic, habitLearned, otherLearned)
}

/** Coordinate descent: try each value of each knob, keep whatever scores best, two passes. */
private fun grid(lexicon: Lexicon, sentences: List<String>, geometry: KeyboardGeometry, start: Setup = Setup(), only: Set<String>? = null, passes: Int = 2) {
  val knobs: List<Pair<String, List<(Setup) -> Setup>>> = listOf(
    "neuralLmWeight" to listOf(0.3f, 0.4f, 0.5f, 0.65f, 0.8f).map { v -> { s: Setup -> s.copy(engine = s.engine.copy(neuralLmWeight = v)) } },
    "neuralTypingShare" to listOf(0.3f, 0.4f, 0.5f, 0.6f, 0.7f).map { v -> { s: Setup -> s.copy(engine = s.engine.copy(neuralTypingShare = v)) } },
    "lmWeight" to listOf(0.15f, 0.2f, 0.25f, 0.3f, 0.4f, 0.5f).map { v -> { s: Setup -> s.copy(engine = s.engine.copy(lmWeight = v)) } },
    "autocorrectMargin" to listOf(0.2f, 0.4f, 0.6f, 0.9f, 1.3f).map { v -> { s: Setup -> s.copy(engine = s.engine.copy(autocorrectMargin = v)) } },
    "realWordMargin" to listOf(1.5f, 2f, 3f, 4f, 6f, 99f).map { v -> { s: Setup -> s.copy(engine = s.engine.copy(realWordMargin = v)) } },
    "capitalPenalty" to listOf(0f, 1.5f, 3f, 5f, 8f).map { v -> { s: Setup -> s.copy(engine = s.engine.copy(capitalPenalty = v)) } },
    "spatialScale" to listOf(1.2f, 1.6f, 2f, 2.5f, 3f).map { v -> { s: Setup -> s.copy(decoder = s.decoder.copy(spatialScale = v)) } },
    "insertDelete" to listOf(1.6f, 2f, 2.4f, 3f).map { v -> { s: Setup -> s.copy(decoder = s.decoder.copy(insert = v, delete = v)) } },
    "transpose" to listOf(0.8f, 1.2f, 1.8f).map { v -> { s: Setup -> s.copy(decoder = s.decoder.copy(transpose = v)) } },
    "completion" to listOf(0.5f, 1f, 1.5f, 2.5f).map { v -> { s: Setup -> s.copy(decoder = s.decoder.copy(completion = v)) } },
    "userShare" to listOf(0.4f, 0.6f, 0.8f, 0.9f).map { v -> { s: Setup -> s.copy(engine = s.engine.copy(userShare = v)) } },
    "userConfidence" to listOf(0.5f, 1f, 2f, 4f).map { v -> { s: Setup -> s.copy(engine = s.engine.copy(userConfidence = v)) } },
    "userVocabularyShare" to listOf(0f, 0.05f, 0.15f, 0.3f).map { v -> { s: Setup -> s.copy(engine = s.engine.copy(userVocabularyShare = v)) } },
    "trigramShare" to listOf(0.3f, 0.45f, 0.6f, 0.75f, 0.9f).map { v -> { s: Setup -> s.copy(engine = s.engine.copy(trigramShare = v)) } },
    "bigramShare" to listOf(0.6f, 0.7f, 0.8f, 0.9f).map { v -> { s: Setup -> s.copy(engine = s.engine.copy(bigramShare = v)) } },
    "glideWeight" to listOf(3f, 4.5f, 6f, 8f, 11f).map { v -> { s: Setup -> s.copy(engine = s.engine.copy(glideWeight = v)) } },
    "nearRadius" to listOf(0.7f, 0.9f, 1.1f, 1.3f).map { v -> { s: Setup -> s.copy(glide = s.glide.copy(nearRadius = v)) } },
    "endRadius" to listOf(0.9f, 1.1f, 1.4f).map { v -> { s: Setup -> s.copy(glide = s.glide.copy(endRadius = v)) } },
    "shortfallCost" to listOf(0.05f, 0.12f, 0.2f, 0.3f).map { v -> { s: Setup -> s.copy(glide = s.glide.copy(shortfallCost = v)) } },
    "unexplainedTurnCost" to listOf(0f, 0.15f, 0.25f, 0.4f, 0.6f).map { v -> { s: Setup -> s.copy(glide = s.glide.copy(unexplainedTurnCost = v)) } },
    "turnDegrees" to listOf(35f, 50f, 65f).map { v -> { s: Setup -> s.copy(glide = s.glide.copy(turnDegrees = v)) } },
    "undershootReach" to listOf(1.5f, 2.5f, 3.5f).map { v -> { s: Setup -> s.copy(glide = s.glide.copy(undershootReach = v)) } },
  // Without a model the neural knobs change nothing
  ).filter { if (only == null) !it.first.startsWith("neural") else it.first in only }
  var best = start
  var bestScore = report(lexicon, sentences, geometry, best).score
  println("start %.4f".format(bestScore))
  repeat(passes) { pass ->
    for ((name, options) in knobs) {
      for (option in options) {
        val candidate = option(best)
        if (candidate == best) continue
        val score = report(lexicon, sentences, geometry, candidate).score
        if (score > bestScore + 1e-4) {
          bestScore = score
          best = candidate
          println("pass ${pass + 1} $name → %.4f  $best".format(score))
        }
      }
    }
  }
  println("best %.4f\n$best".format(bestScore))
  println(report(lexicon, sentences, geometry, best).summary)
}

private fun pct(n: Int, of: Int) = 100.0 * n / of
