package com.moonkeyboard.engine.suggest

import com.moonkeyboard.engine.decode.DecoderConfig
import com.moonkeyboard.engine.decode.GlideConfig
import com.moonkeyboard.engine.decode.GlideDecoder
import com.moonkeyboard.engine.decode.KeyMap
import com.moonkeyboard.engine.decode.Spatial
import com.moonkeyboard.engine.decode.TapDecoder
import com.moonkeyboard.engine.decode.Touch
import com.moonkeyboard.engine.decode.trimOffLetters
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
  /** Share of the two-word model when that context is known; the rest is the pair model. */
  val trigramShare: Float = 0.6f,
  /** ln P for a word only the user has typed. */
  val userWordLogProb: Float = -14f,
  /** Most the user's own habits can count after a word they have typed after many times. */
  val userShare: Float = 0.5f,
  /**
   * Witten–Bell weight: habits count n / (n + this × distinct) of [userShare], n being uses of the
   * context. A phrase repeated word for word earns trust fast; "the", followed by everything, doesn't.
   */
  val userConfidence: Float = 2f,
  /** Share of the user's own word frequencies, regardless of context (scaled up as they type more). */
  val userVocabularyShare: Float = 0.05f,
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
  /** Per letter a word differs in length from what was typed: a prior that same-length fixes beat completions. */
  val lengthPenalty: Float = 0.5f,
  /** Share of the neural model, when there is one, in the built-in probability for next-word predictions; the rest is the dictionary's. */
  val neuralShare: Float = 0.7f,
  /** The same while typing and gliding, where the touches count too: less there kept more typed words right. */
  val neuralTypingShare: Float = 0.5f,
  /** [lmWeight] when the neural model is blended in: a better model earns more say against the touches. */
  val neuralLmWeight: Float = 0.5f,
  /** The neural model also scores the words offered while typing (and so autocorrect), not only the next-word predictions. */
  val neuralWhileTyping: Boolean = true,
  /** Nouns are written with a capital (German): "haus" is corrected to "Haus", which elsewhere is left alone. */
  val nounCapitals: Boolean = false,
  /** Cost of a missed space against the touches, like one wrong key: a split must beat close one-word fixes. */
  val missedSpaceCost: Float = 2.5f,
)

class SuggestionEngine(
  val lexicon: Lexicon,
  val history: UserHistory,
  val config: EngineConfig = EngineConfig(),
  decoderConfig: DecoderConfig = DecoderConfig(),
  glideConfig: GlideConfig = GlideConfig(),
  /** Blended with the dictionary's word model when set; null leaves the dictionary alone. */
  @Volatile var neural: NeuralLm? = null,
) {
  private val decoder = TapDecoder(lexicon, decoderConfig)
  private val glideDecoder = GlideDecoder(lexicon, glideConfig)
  private val fallbackPredictions: List<Int> by lazy {
    (0 until lexicon.wordCount).filter { lexicon.isSuggestable(it) }.sortedByDescending { lexicon.frequency(it) }.take(3)
  }

  private class Candidate(val word: String, val cost: Float, val score: Float, val completion: Boolean)

  /** A word the user added by hand: learned in full at once, so it's suggested, glided and never corrected away. */
  fun addWord(word: String) {
    val w = word.trim()
    // Only what typing can build up as one word: letters, with digits after the first ("rishabh123")
    if (w.isNotEmpty() && w[0].isLetter() && w.all { isWordChar(it) || it.isDigit() }) history.record(null, w, maxOf(config.learnedAt - history.count(w), 1))
  }

  /** Suggestions for a partly typed word. [allowCompletions] off keeps longer words out, for fixes to a word the user has fully typed. */
  fun suggest(typed: String, touches: List<Touch>, keys: KeyMap?, context: WordContext, allowAutocorrect: Boolean, allowCompletions: Boolean = true): Suggestions {
    if (typed.isEmpty()) return predict(context, capitalize = false)
    val spatial = Spatial(touches, keys, decoder.config)
    val lm = Lm(context, config.neuralWhileTyping, config.neuralTypingShare)
    val best = HashMap<String, Candidate>()
    // A capital the keyboard added at a sentence start says nothing about wanting a name
    val typedCapital = typed[0].isUpperCase() && !context.sentenceStart

    fun offer(word: String, id: Int, cost: Float, completion: Boolean) {
      if (history.isBlocked(word)) return
      val penalty = if (isProper(word) && !typedCapital) config.capitalPenalty else 0f
      // Short prefixes are still being typed; a length prior there kills ordinary completions
      val diff = if (typed.length >= 3) kotlin.math.abs(word.length - typed.length) else 0
      val adjusted = cost + config.lengthPenalty * diff
      val score = -adjusted + lm.weight * (lm.logProb(word, id) - penalty)
      val old = best[word]
      if (old == null || score > old.score) best[word] = Candidate(word, adjusted, score, completion)
    }

    decoder.search(spatial) { hit ->
      val id = hit.wordId
      val target = lexicon.whitelistTarget(id)
      if (target >= 0) offer(lexicon.word(target), target, hit.cost, hit.completion)
      if (lexicon.isSuggestable(id)) offer(lexicon.word(id), id, hit.cost, hit.completion)
    }
    val limit = decoder.config.limit(touches.size)
    for ((word, _) in history.wordsAtLeast(config.learnedAt)) {
      if (lexicon.find(word) >= 0) continue
      val (cost, prefixCost) = decoder.align(word, spatial)
      if (cost <= limit) offer(word, -1, cost, false)
      else if (prefixCost <= limit) offer(word, -1, prefixCost, true)
    }
    // The trie only completes to the most frequent words overall; what this context expects
    // ("soon" after "see you") gets matched against the touches too
    for ((word, id) in lm.likelyNext()) {
      if (word in best || history.isBlocked(word)) continue
      val (cost, prefixCost) = decoder.align(word, spatial)
      if (cost <= limit) offer(word, id, cost, false)
      else if (prefixCost <= limit) offer(word, id, prefixCost, true)
    }

    missedSpace(typed, lm)?.let { best.putIfAbsent(it.word, it) }
    val ranked = best.values.asSequence().filter { allowCompletions || !it.completion }.sortedByDescending { it.score }.toList()
    // A shortcut is offered, never forced: it takes the middle slot, and space keeps what was typed
    val expansion = history.shortcut(typed)?.let { applyCase(it, typed) }
    // Saved addresses starting with the word: from the 4th letter one takes the right-hand slot, never the
    // middle ("rish" offers rishabh@gmail.com; fewer letters match too many ordinary words). From the
    // 7th the user is clearly typing an address, so the matches take the strip and autocorrect stands back.
    val emails = when {
      typed.length >= EMAIL_ALL_LETTERS -> history.emailsStartingWith(typed, 3)
      typed.length >= EMAIL_MIN_LETTERS -> history.emailsStartingWith(typed, 1)
      else -> emptyList()
    }
    val emailsLead = emails.isNotEmpty() && typed.length >= EMAIL_ALL_LETTERS
    // Digits in a word: a single one inside it is a slip off the number row ("c4ossover"), corrected like any
    // typo; anywhere else they're meant ("mp3", "2nd", "covid19") and space keeps them
    val meantDigits = typed.any(Char::isDigit) && !isDigitSlip(typed)
    val autocorrect = if (allowAutocorrect && expansion == null && !emailsLead && !meantDigits) autocorrectFor(typed, ranked) else null

    val shown = ArrayList<String>()
    fun add(word: String) {
      if (shown.size < 3 && shown.none { it.equals(word, ignoreCase = true) }) shown += word
    }
    val others = ranked.asSequence().map { applyCase(it.word, typed) }
    val items = if (expansion != null) {
      listOfNotNull(typed, expansion, others.firstOrNull { !it.equals(typed, ignoreCase = true) && it != expansion })
    } else if ((isValid(typed) || meantDigits) && autocorrect == null) {
      // A real word stays in the middle with the best alternatives either side
      val alternatives = others.filterNot { it.equals(typed, ignoreCase = true) }.take(2).toList()
      // Fixed slots: with no alternatives the word still sits in the middle ("" slots aren't drawn)
      listOf(alternatives.getOrNull(0) ?: "", typed, alternatives.getOrNull(1) ?: "")
    } else {
      // Typed word on the left, then the correction (or best guess) in the middle
      add(typed)
      autocorrect?.let(::add)
      others.forEach(::add)
      shown
    }
    val withEmail = when {
      emails.isEmpty() -> items
      // Best address in the middle, the others either side; word suggestions fill any slot left
      emailsLead -> {
        val rest = items.filter { it !in emails }.iterator()
        listOfNotNull(emails.getOrNull(1) ?: rest.nextOrNull(), emails[0], emails.getOrNull(2) ?: rest.nextOrNull())
      }
      else -> items.take(2) + emails[0]
    }
    val debug = ranked.take(12).map { "%-14s cost %.2f  score %.2f%s".format(it.word, it.cost, it.score, if (it.completion) "  (completion)" else "") }
    return Suggestions(typed, withEmail, autocorrect, isPrediction = false, debug = debug + neuralDebug(config.neuralWhileTyping))
  }

  /**
   * Words for a glide [path] (key-area pixels). The best is [Suggestions.typed] and sits in the
   * middle of [Suggestions.items]; nothing is autocorrected because the glide already chose.
   */
  fun glide(swipe: List<TracePoint>, keys: KeyMap, context: WordContext, firstUpper: Boolean, allUpper: Boolean): Suggestions {
    // A finger that wandered off the letters before lifting ends the word where it left them
    val path = trimOffLetters(swipe, keys, glideDecoder.config.endRadius)
    val lm = Lm(context, config.neuralWhileTyping, config.neuralTypingShare)
    val scores = HashMap<String, Pair<Float, Float>>()
    fun offer(word: String, id: Int, cost: Float) {
      if (history.isBlocked(word)) return
      val penalty = if (isProper(word)) config.capitalPenalty else 0f
      val score = -config.glideWeight * cost + lm.weight * (lm.logProb(word, id) - penalty)
      if ((scores[word]?.second ?: Float.NEGATIVE_INFINITY) < score) scores[word] = cost to score
    }
    glideDecoder.search(path, keys) { hit ->
      val target = lexicon.whitelistTarget(hit.wordId)
      if (target >= 0) offer(lexicon.word(target), target, hit.cost)
      if (lexicon.isSuggestable(hit.wordId)) offer(lexicon.word(hit.wordId), hit.wordId, hit.cost)
    }
    for ((word, _) in history.wordsAtLeast(config.learnedAt)) {
      if (lexicon.find(word) >= 0) continue
      glideDecoder.cost(word, path, keys)?.let { offer(word, -1, it) }
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

  /** Next-word predictions after a separator: what follows these two words, this word, or this user's habit here. */
  fun predict(context: WordContext, capitalize: Boolean): Suggestions {
    val lm = Lm(context, useNeural = true)
    val scores = HashMap<String, Float>()
    for ((word, id) in lm.likelyNext()) scores[word] = lm.logProb(word, id)
    var ranked = scores.entries.filterNot { history.isBlocked(it.key) }.sortedByDescending { it.value }.map { it.key }
    if (ranked.isEmpty()) ranked = fallbackPredictions.map(lexicon::word)
    val top = ranked.take(3).map { if (capitalize) it.replaceFirstChar(Char::uppercaseChar) else it }
    // Best in the middle, like the word suggestions
    val items = listOfNotNull(top.getOrNull(1), top.getOrNull(0), top.getOrNull(2))
    return Suggestions("", items, null, isPrediction = true, debug = neuralDebug(true))
  }

  private fun neuralDebug(used: Boolean): List<String> =
    listOfNotNull(neural?.takeIf { used }?.let { "neural model: last run %d ms".format(it.lastMillis) })

  /**
   * For offline comparison with other models: the words this context expects with their log
   * probabilities, and the log factor on the unigram log probability of every other dictionary word.
   */
  fun contextScores(context: WordContext): Pair<Map<String, Float>, Float> {
    val lm = Lm(context, useNeural = false)
    return lm.likelyNext().mapValues { (word, id) -> lm.logProb(word, id) } to lm.backoffLog()
  }

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
    if (typed.any { !isWordChar(it) && !it.isDigit() }) return null
    // The user's own words are never corrected away, fixed spellings ("im" → "I'm") included: one
    // undone correction teaches the word. A sentence start stores it in lower case ("Teh" as "teh").
    if (maxOf(history.count(typed), history.count(typed.lowercase())) >= config.learnedAt) return null
    val typedId = lexicon.findLenient(typed)
    if (typedId >= 0) {
      val target = lexicon.whitelistTarget(typedId)
      if (target >= 0 && !history.isBlocked(lexicon.word(target))) return applyCase(lexicon.word(target), typed)
    }
    // Completions are suggestions, not corrections: only whole-word candidates compete here
    val words = ranked.filter { !it.completion }
    val pick = words.firstOrNull() ?: return null
    if (pick.word.equals(typed, ignoreCase = true)) {
      // Only the capital differs: a noun typed in lower case gets it where nouns are capitalised
      return if (config.nounCapitals && pick.word[0].isUpperCase() && typed[0].isLowerCase()) pick.word else null
    }
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
  /**
   * A missed space ("ofthe" → "of the"): the best split of a non-word into two words, scored like any
   * candidate. Only a pair the dictionary has seen side by side can be autocorrected to, and only when it
   * clearly wins; any other pair ("the quick") is offered in the strip but never forced, so names and
   * slang ("moonlite") don't fall apart into two words.
   */
  private fun missedSpace(typed: String, lm: Lm): Candidate? {
    if (typed.length < MIN_SPLIT || !typed.all(Char::isLetter) || isValid(typed)) return null
    var best: Candidate? = null
    for (i in 1..typed.length - 2) {
      val left = typed.substring(0, i)
      val right = typed.substring(i)
      if (left.length < 2 && !left.equals("a", ignoreCase = true)) continue
      val l = lexicon.findLenient(left)
      val r = lexicon.find(right.lowercase())
      if (l < 0 || r < 0 || !lexicon.isSuggestable(l) || !lexicon.isSuggestable(r) || history.isBlocked(right)) continue
      val pair = lexicon.bigramLogProb(l, r)
      val next = pair ?: (lexicon.unigramLogProb(r) + UNSEEN_PAIR)
      val score = -config.missedSpaceCost + lm.weight * (lm.logProb(lexicon.word(l), l) + next)
      // An unseen pair counts as a completion: offered, never an autocorrect
      if (best == null || score > best.score) best = Candidate("$left $right", config.missedSpaceCost, score, completion = pair == null)
    }
    return best
  }

  /** One digit with letters both sides and enough letters around it to tell the word ("b4u" and "w8" aren't slips). */
  private fun isDigitSlip(typed: String) =
    typed.count(Char::isDigit) == 1 && typed.first().isLetter() && typed.last().isLetter() && typed.count(Char::isLetter) >= 4

  /**
   * A name or acronym, which the dictionary over-rates. Where every noun has a capital (German) only
   * an all-capitals word counts: "Haus" is an ordinary word there, "ADH" still an acronym.
   */
  private fun isProper(word: String) =
    if (config.nounCapitals) word.length > 1 && word.all { !it.isLetter() || it.isUpperCase() }
    else word[0].isUpperCase() && word != "I" && !word.startsWith("I'")

  /**
   * ln P(word | context), worked out once per request: the built-in model (two-word context
   * mixed into the one-word and single-word models), blended with this user's own counts. Their
   * habits after a context count more the more often they've typed after it, so a phrase used
   * every day wins quickly while one-offs barely move anything.
   */
  private inner class Lm(context: WordContext, useNeural: Boolean, private val neuralShare: Float = config.neuralShare) {
    private val nn = if (useNeural) neural?.predict(context.tokens) else null
    /** How much this model counts against the touches. */
    val weight = if (nn != null) config.neuralLmWeight else config.lmWeight
    val prevId: Int = when {
      context.sentenceStart -> Lexicon.SENTENCE_START
      context.prev != null -> lexicon.findLenient(context.prev).takeIf { it >= 0 } ?: NONE
      else -> NONE
    }
    val prev2Id: Int = when {
      context.prev == null -> NONE
      context.prevIsFirst -> Lexicon.SENTENCE_START
      context.prevPrev != null -> lexicon.findLenient(context.prevPrev).takeIf { it >= 0 } ?: NONE
      else -> NONE
    }
    private val key1 = context.userKey
    private val key2 = context.userKey2
    private val n1 = key1?.let(history::pairTotal) ?: 0
    private val n2 = if (key1 != null && key2 != null) history.tripleTotal(key2, key1) else 0
    private val trust = if (n1 == 0) 0f else config.userShare * n1 / (n1 + config.userConfidence * history.pairTypes(key1!!))
    private val trust2 = if (n2 == 0) 0.0 else n2 / (n2 + config.userConfidence * history.tripleTypes(key2!!, key1!!).toDouble())
    private val total = history.totalCount()
    // Like trust, but for the user's vocabulary as a whole; needs a few hundred words to matter
    private val vocabularyShare = config.userVocabularyShare * total / (total + VOCABULARY_CONFIDENCE)

    /** Words this context expects next (word → dictionary id, or -1 for the user's own words). */
    fun likelyNext(): Map<String, Int> {
      val words = LinkedHashMap<String, Int>()
      val ids = ArrayList<Int>()
      if (prev2Id != NONE && prevId != NONE) lexicon.trigramSuccessors(prev2Id, prevId).mapTo(ids) { it.first }
      if (prevId != NONE) lexicon.successors(prevId).mapTo(ids) { it.first }
      for (id in ids) if (lexicon.isSuggestable(id)) words[lexicon.word(id)] = id
      if (key1 != null) {
        val mine = history.successors(key1) + (if (key2 != null) history.successors(key2, key1) else emptyList())
        for ((word, _) in mine) words.getOrPut(word) { lexicon.find(word) }
      }
      // The network's favourites, but only as dictionary words: its vocabulary is raw web text, and
      // the dictionary's filters (offensive words, Hinglish off) apply here as everywhere
      for (w in nn?.top.orEmpty()) {
        val id = lexicon.findLenient(w)
        if (id >= 0 && lexicon.isSuggestable(id)) words.putIfAbsent(lexicon.word(id), id)
      }
      return words
    }

    fun logProb(word: String, id: Int): Float {
      var p = if (id >= 0) exp(staticLogProb(id).toDouble()) else exp(config.userWordLogProb.toDouble())
      // The network knows words in lower case only: "Will" the name doesn't get the verb's odds,
      // but "I" and "London" (no lower-case spelling) do
      if (nn != null && (word == word.lowercase() || lexicon.find(word.lowercase()) < 0)) {
        val pn = nn.logProb(word)?.let { exp(it.toDouble()) } ?: 0.0
        p = neuralShare * pn + (1 - neuralShare) * p
      }
      if (vocabularyShare > 0) p = (1 - vocabularyShare) * p + vocabularyShare * history.count(word) / total
      if (trust > 0) {
        val pair = history.pairCount(key1!!, word).toDouble() / n1
        val mine = if (n2 > 0) trust2 * history.tripleCount(key2!!, key1, word) / n2 + (1 - trust2) * pair else pair
        p = (1 - trust) * p + trust * mine
      }
      return ln(p).toFloat()
    }

    /** What [staticLogProb] keeps of the unigram for a word no pair or triple here predicts. */
    fun backoffLog(): Float {
      var f = 1.0
      if (prevId != NONE && lexicon.hasBigrams(prevId)) {
        f *= 1 - config.bigramShare
        if (prev2Id != NONE && lexicon.hasTrigrams(prev2Id, prevId)) f *= 1 - config.trigramShare
      }
      return ln(f).toFloat()
    }

    private fun staticLogProb(id: Int): Float {
      var p = exp(lexicon.unigramLogProb(id).toDouble())
      if (prevId != NONE && lexicon.hasBigrams(prevId)) {
        val bi = lexicon.bigramLogProb(prevId, id)?.let { exp(it.toDouble()) } ?: 0.0
        p = config.bigramShare * bi + (1 - config.bigramShare) * p
        if (prev2Id != NONE && lexicon.hasTrigrams(prev2Id, prevId)) {
          val tri = lexicon.trigramLogProb(prev2Id, prevId, id)?.let { exp(it.toDouble()) } ?: 0.0
          p = config.trigramShare * tri + (1 - config.trigramShare) * p
        }
      }
      return ln(p).toFloat()
    }
  }

  private companion object {
    const val NONE = -1
    const val EMAIL_MIN_LETTERS = 4
    const val EMAIL_ALL_LETTERS = 7
    /** Shortest run worth splitting at a missed space ("ofthe"); shorter ones are mostly typos of one word. */
    const val MIN_SPLIT = 5
    /** ln P share left for a second word the dictionary never saw after the first: the pair model's backoff. */
    val UNSEEN_PAIR = kotlin.math.ln(0.2f)

    fun <T> Iterator<T>.nextOrNull(): T? = if (hasNext()) next() else null
    const val VOCABULARY_CONFIDENCE = 300.0
  }
}

/** Follows the user's capitalisation: "Teh" → "The", "TEH" → "THE"; keeps "I" and names as they are. */
fun applyCase(word: String, typed: String): String = when {
  typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
  typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar(Char::uppercaseChar)
  else -> word
}
