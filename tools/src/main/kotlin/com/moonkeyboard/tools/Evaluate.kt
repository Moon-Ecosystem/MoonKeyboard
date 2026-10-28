package com.moonkeyboard.tools

import com.moonkeyboard.engine.decode.DecoderConfig
import com.moonkeyboard.engine.decode.GlideConfig
import com.moonkeyboard.engine.dict.Lexicon
import com.moonkeyboard.engine.eval.TypingSimulator
import com.moonkeyboard.engine.history.UserHistory
import com.moonkeyboard.engine.layout.KeyboardGeometry
import com.moonkeyboard.engine.layout.Layouts
import com.moonkeyboard.engine.suggest.EngineConfig
import com.moonkeyboard.engine.suggest.SuggestionEngine
import com.moonkeyboard.engine.suggest.WordContext
import java.io.File

/**
 * Accuracy on held-out sentences with simulated typists:
 *   --dict <en.dict> --eval <sentences.txt> [--offset 0] [--limit 600] [--errors 25] [--grid true]
 * Run: ./gradlew :tools:run -PmainClass=com.moonkeyboard.tools.EvaluateKt --args="..."
 */
fun main(args: Array<String>) {
  val opts = args.toList().chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
  val lexicon = Lexicon.read(File(opts.getValue("dict")).readBytes())
  // --offset skips the sentences the grid tuned on, to check the result on unseen ones
  val sentences = File(opts.getValue("eval")).readLines().filter { it.isNotBlank() }
    .drop(opts["offset"]?.toInt() ?: 0).take(opts["limit"]?.toInt() ?: 600)
  val geometry = KeyboardGeometry.compute(Layouts.load("qwerty"), 390f, 46f, 6f, 10f, 4f)
  val errors = opts["errors"]?.toInt() ?: 25

  if (opts["learning-grid"] == "true") {
    learningGrid(lexicon, sentences)
    return
  }
  if (opts["grid"] == "true") {
    grid(lexicon, sentences, geometry)
    return
  }
  println(report(lexicon, sentences, geometry, Setup(), errors).summary)
}

/** Every tunable in one place, so the grid can vary one at a time. */
data class Setup(
  val engine: EngineConfig = EngineConfig(),
  val decoder: DecoderConfig = DecoderConfig(),
  val glide: GlideConfig = GlideConfig(),
)

class Report(val tap: Double, val tapHarm: Double, val glide: Double, val predict: Double, val summary: String) {
  /** One number to optimise: committed words right, with wrong "fixes" of correct words counted double. */
  val score: Double get() = tap - tapHarm + glide
}

private val TAP_NOISE = listOf(0.35f, 0.5f)
/** (corner noise, smoothing, undershoot): a careful swiper, and a fast one who turns early. */
private val GLIDE_NOISE = listOf(Triple(0.2f, 0.4f, 0f), Triple(0.3f, 0.6f, 0.35f))

fun report(lexicon: Lexicon, sentences: List<String>, geometry: KeyboardGeometry, setup: Setup, showErrors: Int = 0): Report {
  val engine = SuggestionEngine(lexicon, UserHistory(), setup.engine, setup.decoder, setup.glide)
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
    val engine = SuggestionEngine(lexicon, history, setup.engine, setup.decoder, setup.glide)
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
private fun grid(lexicon: Lexicon, sentences: List<String>, geometry: KeyboardGeometry) {
  val knobs: List<Pair<String, List<(Setup) -> Setup>>> = listOf(
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
  )
  var best = Setup()
  var bestScore = report(lexicon, sentences, geometry, best).score
  println("start %.4f".format(bestScore))
  repeat(2) { pass ->
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
