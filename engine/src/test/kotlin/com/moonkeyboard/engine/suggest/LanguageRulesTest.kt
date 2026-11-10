package com.moonkeyboard.engine.suggest

import com.moonkeyboard.engine.decode.KeyMap
import com.moonkeyboard.engine.dict.LexiconBuilder
import com.moonkeyboard.engine.history.UserHistory
import com.moonkeyboard.engine.layout.KeyboardGeometry
import com.moonkeyboard.engine.layout.Layouts
import com.moonkeyboard.engine.pack.PackManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** What a language pack's manifest changes: its own letter keys, two-letter spellings and capitalised nouns. */
class LanguageRulesTest {
  private val german = PackManifest(1, "de", "Deutsch", 1, digraphs = mapOf("ß" to "ss", "ä" to "ae", "ö" to "oe", "ü" to "ue"), nounCapitals = true)
  private val lexicon = LexiconBuilder().apply {
    addWord("Straße", 190)
    addWord("schön", 200)
    addWord("schon", 210)
    addWord("über", 205)
    addWord("Haus", 200)
    addWord("hass", 120)
  }.build()

  private fun engine(manifest: PackManifest) = SuggestionEngine(lexicon, UserHistory(), manifest.engineConfig(), manifest.decoderConfig())

  // QWERTY's letters plus an ü key of its own past p, as on a German keyboard
  private val keys: KeyMap = run {
    val g = KeyboardGeometry.compute(Layouts.load("qwerty"), 390f, 46f, 6f, 10f, 4f)
    val centers = g.keys.filter { it.key.label.length == 1 && it.key.label[0].isLetter() }.associate { it.key.label[0] to (it.centerX to it.centerY) }
    val p = centers.getValue('p')
    KeyMap(centers + ('ü' to (p.first + 39f to p.second)), g.keys.minOf { it.width })
  }

  private fun suggest(e: SuggestionEngine, typed: String) = e.suggest(typed, typed.map(keys::touchFor), keys, WordContext("ich", false), allowAutocorrect = true)

  @Test
  fun aLetterWithItsOwnKeyIsMatchedThere() {
    val touches = "über".map(keys::touchFor)
    // The ü touch sits on the ü key, far from u, and still reads as ü
    assertEquals(keys.center('ü')!!.first, touches[0].x)
    val s = engine(german).suggest("über", touches, keys, WordContext("ich", false), allowAutocorrect = true)
    assertEquals(null, s.autocorrect)
    // Typed without the umlaut, the word is still found
    assertEquals("über", suggest(engine(german), "uber").autocorrect)
  }

  @Test
  fun twoLetterSpellingsOnlyWhereThePackHasThem() {
    assertEquals("Straße", suggest(engine(german), "strasse").autocorrect)
    assertEquals("schön", suggest(engine(german), "schoen").autocorrect)
    val english = PackManifest(1, "en", "English", 1)
    assertNotEquals("schön", suggest(engine(english), "schoen").autocorrect)
  }

  @Test
  fun capitalisedNounsArentMarkedDownAsNames() {
    // "haus" typed: German capitalises the noun; with the name penalty, "hass" would have won
    assertEquals("Haus", suggest(engine(german), "haus").autocorrect)
  }
}
