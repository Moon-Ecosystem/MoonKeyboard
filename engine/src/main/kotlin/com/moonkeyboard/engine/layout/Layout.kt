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

  fun load(id: String): Layout {
    val text = Layouts::class.java.getResourceAsStream("/layouts/$id.json")
      ?.use { it.readBytes().decodeToString() }
      ?: error("No layout '$id'")
    val layout = json.decodeFromString(Layout.serializer(), text)
    require(layout.rows.all { row -> row.keys.all { it.type != KeyType.LAYER || it.layer != null } }) {
      "Layout '$id' has a layer key without a target"
    }
    return layout
  }

  /** [layout] with a slightly shorter row of digits on top; the digits leave the letters' hints and popups. */
  fun withNumberRow(layout: Layout): Layout {
    val digits = "1234567890".map { it.toString() }
    val numbers = Row(digits.map { Key(label = it) }, height = NUMBER_ROW_HEIGHT)
    val rows = layout.rows.map { row ->
      row.copy(keys = row.keys.map { k -> if (k.hint in digits) k.copy(hint = null, popup = k.popup - k.hint!!) else k })
    }
    return Layout(listOf(numbers) + rows)
  }

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

  private const val NUMBER_ROW_HEIGHT = 0.87f
}
