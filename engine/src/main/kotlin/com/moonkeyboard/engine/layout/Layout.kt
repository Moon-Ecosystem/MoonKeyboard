package com.moonkeyboard.engine.layout

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A keyboard layer (letters, symbols…) as defined in `resources/layouts/<id>.json`. */
@Serializable
data class Layout(val rows: List<Row>)

@Serializable
data class Row(
  val keys: List<Key>,
  /** Key height as a share of a normal row's. */
  val height: Float = 1f,
)

@Serializable
data class Key(
  val type: KeyType = KeyType.CHAR,
  /** Text a CHAR key types, or the caption of a LAYER/SPACE key. */
  val label: String = "",
  /** Small character drawn in the corner, usually the first popup item. */
  val hint: String? = null,
  /** Long-press choices; the first one is selected when the popup opens. */
  val popup: List<String> = emptyList(),
  /** Width in letter-key units. */
  val width: Float = 1f,
  /** Target layout id for a LAYER key. */
  val layer: String? = null,
  /** Drawn with the functional-key colour (e.g. comma and period). */
  val special: Boolean = false,
  /** An action a long press runs instead of a popup ("emoji": the emoji panel). */
  val longPress: String? = null,
)

@Serializable
enum class KeyType {
  @SerialName("char") CHAR,
  @SerialName("shift") SHIFT,
  @SerialName("backspace") BACKSPACE,
  @SerialName("enter") ENTER,
  @SerialName("space") SPACE,
  @SerialName("layer") LAYER,
  /** Opens the emoji panel. */
  @SerialName("emoji") EMOJI,
}

object Layouts {
  private val json = Json

  /** A built-in layout. */
  fun load(id: String): Layout {
    val text = Layouts::class.java.getResourceAsStream("/layouts/$id.json")
      ?.use { it.readBytes().decodeToString() }
      ?: error("No layout '$id'")
    return parse(text, id)
  }

  /** A layout from its JSON, as a language pack carries one. */
  fun parse(text: String, id: String): Layout {
    val layout = json.decodeFromString(Layout.serializer(), text)
    require(layout.rows.all { row -> row.keys.all { it.type != KeyType.LAYER || it.layer != null } }) {
      "Layout '$id' has a layer key without a target"
    }
    return layout
  }

  /** [layout] with a slightly shorter row of digits on top; the digits leave the letters' hints and popups. */
  fun withNumberRow(layout: Layout): Layout {
    val digits = "1234567890".map { it.toString() }
    // As wide as the widest row: ten digits over QWERTY's ten keys, and over German's eleven too
    val width = layout.rows.maxOf { row -> row.keys.sumOf { it.width.toDouble() } }.toFloat() / digits.size
    val numbers = Row(digits.map { Key(label = it, width = width) }, height = NUMBER_ROW_HEIGHT)
    val rows = layout.rows.map { row ->
      row.copy(keys = row.keys.map { k -> if (k.hint in digits) k.copy(hint = null, popup = k.popup - k.hint!!) else k })
    }
    return Layout(listOf(numbers) + rows)
  }

  /**
   * [layout] with a symbol on each letter key's long press, shown as its hint: by position, so every
   * language's layout gets them in the same places. A key that already has a hint (a digit, without the
   * number row) keeps it and gets the symbol second; accents follow. [currency] goes on the third key
   * of the middle row.
   */
  fun withSymbols(layout: Layout, currency: String): Layout {
    val symbols = listOf(
      listOf("%", "\\", "|", "=", "[", "]", "<", ">", "{", "}"),
      listOf("@", "#", currency, "-", "&", "_", "+", "(", ")"),
      listOf("*", "\"", "'", ":", ";", "!", "?"),
    )
    // The letter rows, in order; a number row added on top has no letters
    val letterRows = layout.rows.withIndex().filter { (_, row) -> row.keys.any { it.isLetter() } }.map { it.index }
    val rows = layout.rows.mapIndexed { r, row ->
      val forRow = symbols.getOrNull(letterRows.indexOf(r)) ?: return@mapIndexed row
      var letter = 0
      row.copy(keys = row.keys.map { k ->
        if (!k.isLetter()) return@map k
        val symbol = forRow.getOrNull(letter++) ?: return@map k
        if (k.hint != null) k.copy(popup = listOf(k.hint) + symbol + (k.popup - k.hint - symbol))
        else k.copy(hint = symbol, popup = listOf(symbol) + (k.popup - symbol))
      })
    }
    return Layout(rows)
  }

  private fun Key.isLetter() = type == KeyType.CHAR && label.singleOrNull()?.isLetter() == true

  /**
   * [layout] with an emoji key after the bottom row's comma, the space bar a key narrower to make
   * room; the comma no longer opens emoji on a long press.
   */
  fun withEmojiKey(layout: Layout): Layout {
    val bottom = layout.rows.last()
    val keys = bottom.keys.flatMap { k ->
      when {
        k.type == KeyType.SPACE -> listOf(k.copy(width = k.width - 1f))
        k.longPress == "emoji" -> listOf(k.copy(longPress = null), Key(type = KeyType.EMOJI, special = true))
        else -> listOf(k)
      }
    }
    return Layout(layout.rows.dropLast(1) + bottom.copy(keys = keys))
  }

  /** [layout] with the space bar [extra] units wider, the rest of its row shrunk to keep the row's width, for landscape's roomy rows. */
  fun wideSpace(layout: Layout, extra: Float = 1.5f): Layout {
    val bottom = layout.rows.last()
    val space = bottom.keys.firstOrNull { it.type == KeyType.SPACE } ?: return layout
    val rest = bottom.keys.sumOf { it.width.toDouble() }.toFloat() - space.width
    if (rest <= extra) return layout
    val shrink = 1 - extra / rest
    val keys = bottom.keys.map { if (it === space) it.copy(width = it.width + extra) else it.copy(width = it.width * shrink) }
    return Layout(layout.rows.dropLast(1) + bottom.copy(keys = keys))
  }

  private const val NUMBER_ROW_HEIGHT = 0.87f
}
