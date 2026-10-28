package com.moonkeyboard.engine.suggest

import com.moonkeyboard.engine.decode.DecoderConfig
import com.moonkeyboard.engine.decode.GlideConfig
import com.moonkeyboard.engine.decode.GlideDecoder
import com.moonkeyboard.engine.decode.KeyMap
import com.moonkeyboard.engine.decode.Spatial
import com.moonkeyboard.engine.decode.TapDecoder
import com.moonkeyboard.engine.decode.Touch
import com.moonkeyboard.engine.dict.Lexicon
import com.moonkeyboard.engine.history.UserHistory
import com.moonkeyboard.engine.trace.TracePoint
import kotlin.math.exp
import kotlin.math.ln

/**
 * What the suggestion strip shows. [items] are in display order (left, centre, right); when
 * [autocorrect] is set it sits in the centre and is what space will commit.
 */
data class Suggestions(
  val typed: String,
  val items: List<String>,
  val autocorrect: String?,
  val isPrediction: Boolean,
  /** Ranked candidates with their scores, for the test pad's debug panel. */
  val debug: List<String> = emptyList(),
) {
  companion object {
    val EMPTY = Suggestions("", emptyList(), null, isPrediction = true)
  }
}

data class EngineConfig(
  /** How much the language model counts against touch accuracy. */
  val lmWeight: Float = 0.2f,
  /** Share of the pair model when a pair is known; the rest is the single-word model. */
  val bigramShare: Float = 0.8f,
  /** ln P for a word only the user has typed. */
  val userWordLogProb: Float = -14f,
  val userBoost: Float = 1f,
  /** Autocorrect a non-word only when the winner beats the next whole-word candidate by this much. */
  val autocorrectMargin: Float = 0.2f,
  /** Correct a real but unlikely word ("tour" → "your") only when the alternative scores this much higher. */
  val realWordMargin: Float = 1.5f,
  /** ln P taken off names and acronyms unless the user typed the capital (the dictionary over-rates them). */
  val capitalPenalty: Float = 8f,
  /** A word typed this often is the user's own and is never corrected away. */
  val learnedAt: Int = 2,
  /** How much a glide path straying from a word's ideal path (in key widths) counts against it. */
  val glideWeight: Float = 4.5f,
)

class SuggestionEngine(
  val lexicon: Lexicon,
  val history: UserHistory,
  val config: EngineConfig = EngineConfig(),
  decoderConfig: DecoderConfig = DecoderConfig(),
  glideConfig: GlideConfig = GlideConfig(),
) {
  private val decoder = TapDecoder(lexicon, decoderConfig)
  private val glideDecoder = GlideDecoder(lexicon, glideConfig)
  private val fallbackPredictions: List<Int> by lazy {
    (0 until lexicon.wordCount).filter { lexicon.isSuggestable(it) }.sortedByDescending { lexicon.frequency(it) }.take(3)
  }

  private class Candidate(val word: String, val cost: Float, val score: Float, val completion: Boolean)

  /** Suggestions for a partly typed word. */
  fun suggest(typed: String, touches: List<Touch>, keys: KeyMap?, context: WordContext, allowAutocorrect: Boolean): Suggestions {
    if (typed.isEmpty()) return predict(context, capitalize = false)
    val spatial = Spatial(touches, keys, decoder.config)
    val prevId = previousId(context)
    val best = HashMap<String, Candidate>()
    // A capital the keyboard added at a sentence start says nothing about wanting a name
    val typedCapital = typed[0].isUpperCase() && !context.sentenceStart

    fun offer(word: String, cost: Float, completion: Boolean, logProb: Float) {
      if (history.isBlocked(word)) return
      val penalty = if (isProper(word) && !typedCapital) config.capitalPenalty else 0f
      val score = -cost + config.lmWeight * (logProb - penalty + userBoost(context.prev, word))
      val old = best[word]
      if (old == null || score > old.score) best[word] = Candidate(word, cost, score, completion)
    }

    decoder.search(spatial) { hit ->
      val id = hit.wordId
      val target = lexicon.whitelistTarget(id)
      if (target >= 0) offer(lexicon.word(target), hit.cost, hit.completion, logProb(target, prevId))
      if (lexicon.isSuggestable(id)) offer(lexicon.word(id), hit.cost, hit.completion, logProb(id, prevId))
    }
    for ((word, _) in history.wordsAtLeast(config.learnedAt)) {
      if (lexicon.find(word) >= 0) continue
      val (cost, prefixCost) = decoder.align(word, spatial)
      val limit = decoder.config.limit(touches.size)
      if (cost <= limit) offer(word, cost, false, config.userWordLogProb)
      else if (prefixCost <= limit) offer(word, prefixCost, true, config.userWordLogProb)
    }

    val ranked = best.values.sortedByDescending { it.score }
    val autocorrect = if (allowAutocorrect) autocorrectFor(typed, ranked) else null

    val shown = ArrayList<String>()
    fun add(word: String) {
      if (shown.size < 3 && shown.none { it.equals(word, ignoreCase = true) }) shown += word
    }
    val others = ranked.asSequence().map { applyCase(it.word, typed) }
    val items = if (isValid(typed) && autocorrect == null) {
      // A real word stays in the middle with the best alternatives either side
      val alternatives = others.filterNot { it.equals(typed, ignoreCase = true) }.take(2).toList()
      listOfNotNull(alternatives.getOrNull(0), typed, alternatives.getOrNull(1))
    } else {
      // Typed word on the left, then the correction (or best guess) in the middle
      add(typed)
      autocorrect?.let(::add)
      others.forEach(::add)
      shown
    }
    val debug = ranked.take(12).map { "%-14s cost %.2f  score %.2f%s".format(it.word, it.cost, it.score, if (it.completion) "  (completion)" else "") }
    return Suggestions(typed, items, autocorrect, isPrediction = false, debug = debug)
  }

  /**
   * Words for a glide [path] (key-area pixels). The best is [Suggestions.typed] and sits in the
   * middle of [Suggestions.items]; nothing is autocorrected because the glide already chose.
   */
  fun glide(path: List<TracePoint>, keys: KeyMap, context: WordContext, firstUpper: Boolean, allUpper: Boolean): Suggestions {
    val prevId = previousId(context)
    val scores = HashMap<String, Pair<Float, Float>>()
    fun offer(word: String, cost: Float, logProb: Float) {
      if (history.isBlocked(word)) return
      val penalty = if (isProper(word)) config.capitalPenalty else 0f
      val score = -config.glideWeight * cost + config.lmWeight * (logProb - penalty + userBoost(context.prev, word))
      if ((scores[word]?.second ?: Float.NEGATIVE_INFINITY) < score) scores[word] = cost to score
    }
    glideDecoder.search(path, keys) { hit ->
      val target = lexicon.whitelistTarget(hit.wordId)
      if (target >= 0) offer(lexicon.word(target), hit.cost, logProb(target, prevId))
      if (lexicon.isSuggestable(hit.wordId)) offer(lexicon.word(hit.wordId), hit.cost, logProb(hit.wordId, prevId))
    }
    for ((word, _) in history.wordsAtLeast(config.learnedAt)) {
      if (lexicon.find(word) >= 0) continue
      glideDecoder.cost(word, path, keys)?.let { offer(word, it, config.userWordLogProb) }
    }
    val ranked = scores.entries.sortedByDescending { it.value.second }
    // "moon" and "Moon" are one choice in the strip; keep the better-scored spelling
    val top = ranked.distinctBy { it.key.lowercase() }.take(3).map { (word, _) ->
      when {
        allUpper -> word.uppercase()
        firstUpper -> word.replaceFirstChar(Char::uppercaseChar)
        else -> word
      }
    }
    val debug = ranked.take(12).map { (w, s) -> "%-14s path %.2f  score %.2f".format(w, s.first, s.second) }
    val best = top.firstOrNull() ?: return Suggestions("", emptyList(), null, isPrediction = false, debug = listOf("no word fits this path"))
    return Suggestions(best, listOfNotNull(top.getOrNull(1), best, top.getOrNull(2)), null, isPrediction = false, debug = debug)
  }

  /** Next-word predictions after a separator. */
  fun predict(context: WordContext, capitalize: Boolean): Suggestions {
    val prevId = previousId(context)
    val scores = HashMap<String, Float>()
    if (prevId != NONE) {
      for ((id, lp) in lexicon.successors(prevId)) {
        if (lexicon.isSuggestable(id)) scores[lexicon.word(id)] = lp
      }
    }
    context.prev?.let { prev ->
      for ((word, count) in history.successors(prev)) {
        scores[word] = (scores[word] ?: config.userWordLogProb) + config.userBoost * ln(1f + count)
      }
    }
    var ranked = scores.entries.filterNot { history.isBlocked(it.key) }.sortedByDescending { it.value }.map { it.key }
    if (ranked.isEmpty()) ranked = fallbackPredictions.map(lexicon::word)
    val top = ranked.take(3).map { if (capitalize) it.replaceFirstChar(Char::uppercaseChar) else it }
    // Best in the middle, like the word suggestions
    val items = listOfNotNull(top.getOrNull(1), top.getOrNull(0), top.getOrNull(2))
    return Suggestions("", items, null, isPrediction = true)
  }

  /** A real word: in the dictionary, or typed often enough by this user. */
  /**
   * A real word: in the dictionary in the form typed (or lower case), or typed often enough by
   * this user. A name only counts when typed with its capital: "jim" isn't a word, "Jim" is.
   */
  fun isValid(word: String): Boolean {
    if (word.isEmpty()) return false
    if (history.count(word) >= config.learnedAt) return true
    var id = lexicon.find(word)
    if (id < 0) id = lexicon.find(word.lowercase())
    if (id < 0 && word[0].isUpperCase()) id = lexicon.findLenient(word)
    return id >= 0 && lexicon.isWord(id)
  }

  private fun autocorrectFor(typed: String, ranked: List<Candidate>): String? {
    if (typed.any { !isWordChar(it) }) return null
    val typedId = lexicon.findLenient(typed)
    if (typedId >= 0) {
      val target = lexicon.whitelistTarget(typedId)
      if (target >= 0 && !history.isBlocked(lexicon.word(target))) return applyCase(lexicon.word(target), typed)
    }
    // The user's own words are never corrected away
    if (history.count(typed) >= config.learnedAt) return null
    // Completions are suggestions, not corrections: only whole-word candidates compete here
    val words = ranked.filter { !it.completion }
    val pick = words.firstOrNull() ?: return null
    if (pick.word.equals(typed, ignoreCase = true)) return null
    if (isValid(typed)) {
      // A real word is replaced only by something far more likely here ("tour" → "your")
      val typedScore = words.firstOrNull { it.word.equals(typed, ignoreCase = true) }?.score ?: return null
      if (pick.score - typedScore < config.realWordMargin) return null
    } else {
      val runnerUp = words.getOrNull(1)
      if (runnerUp != null && pick.score - runnerUp.score < config.autocorrectMargin) return null
    }
    return applyCase(pick.word, typed)
  }

  /** Names and acronyms; the "I" family is ordinary English. */
  private fun isProper(word: String) = word[0].isUpperCase() && word != "I" && !word.startsWith("I'")

  private fun previousId(context: WordContext): Int = when {
    context.sentenceStart -> Lexicon.SENTENCE_START
    context.prev != null -> lexicon.findLenient(context.prev).takeIf { it >= 0 } ?: NONE
    else -> NONE
  }

  /** ln P(word | prev): the pair model mixed with the single-word model when the pair is known. */
  private fun logProb(id: Int, prevId: Int): Float {
    val uni = lexicon.unigramLogProb(id)
    if (prevId == NONE || !lexicon.hasBigrams(prevId)) return uni
    val bi = lexicon.bigramLogProb(prevId, id) ?: return uni + ln(1 - config.bigramShare)
    return ln(config.bigramShare * exp(bi) + (1 - config.bigramShare) * exp(uni))
  }

  private fun userBoost(prev: String?, word: String): Float {
    val count = history.count(word)
    if (count == 0) return 0f
    val pair = prev?.let { history.pairCount(it, word) } ?: 0
    return config.userBoost * (ln(1f + count) + ln(1f + pair))
  }

  private companion object {
    const val NONE = -1
  }
}

/** Follows the user's capitalisation: "Teh" → "The", "TEH" → "THE"; keeps "I" and names as they are. */
fun applyCase(word: String, typed: String): String = when {
  typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
  typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar(Char::uppercaseChar)
  else -> word
}
