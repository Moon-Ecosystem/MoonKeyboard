package com.moonkeyboard.ime

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The stroke icons from the design canvas (24×24 viewport); Icon() tints them. */
object Icons {
  val Next = icon("M9 6l6 6-6 6")
  val Back = icon("M15 6l-6 6 6 6")
  val Apps = icon("M5 4h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M15 4h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M5 14h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1z", "M15 14h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1z")
  val Clipboard = icon("M9 3h6a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M8 5H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2")
  val Emoji = icon(circle(12f, 12f, 9f), "M8 14s1.5 2 4 2 4-2 4-2", "M9 9h.01M15 9h.01")
  val TextEdit = icon("M5 4h4M7 4v16M5 20h4", "M13 12h7M17 9l3 3-3 3")
  val OneHanded = icon("M5 6h7a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "M17 12h4M19 10l2 2-2 2")
  val Floating = icon("M5 10h14a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z", "M9 5h6")
  val Split = icon("M4 7h5a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z", "M15 7h5a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z")
  val Open = icon("M14 4h6v6M20 4l-9 9", "M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4")
  val Theme = icon("M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z")
  val Settings = icon("M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12", circle(16f, 6f, 2f), circle(10f, 12f, 2f), circle(18f, 18f, 2f))
  val Trash = icon("M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3")
  val Close = icon("M6 6l12 12M18 6L6 18")
  val Pin =icon("M12 17v5M9 3h6l-1 6 3 3H7l3-3z")
  val Backspace = icon("M9 5h11a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H9l-6-7zM11 9l6 6M17 9l-6 6")
  val Up = icon("M12 19V5M6 11l6-6 6 6")
  val Down = icon("M12 5v14M6 13l6 6 6-6")
  val Left = icon("M19 12H5M11 6l-6 6 6 6")
  val Right = icon("M5 12h14M13 6l6 6-6 6")
  val LineStart = icon("M4 5v14", "M20 12H8M13 7l-5 5 5 5")
  val LineEnd = icon("M20 5v14", "M4 12h12M11 7l5 5-5 5")
  val Expand = icon("M4 9V4h5M20 15v5h-5M4 4l6 6M20 20l-6-6")
  val SwapSide = icon("M4 8h16M16 4l4 4-4 4", "M20 16H4M8 12l-4 4 4 4")
  val Recent = icon(circle(12f, 12f, 9f), "M12 7v5l3 2")
  val Undo = icon("M9 14L4 9l5-5", "M4 9h11a5 5 0 0 1 0 10h-4")
  val Redo = icon("M15 14l5-5-5-5", "M20 9H9a5 5 0 0 0 0 10h4")
  val Search = icon(circle(11f, 11f, 6f), "M20 20l-4.5-4.5")

  /** Emoji categories, in the order of emoji.txt's groups. */
  val EmojiGroups = listOf(
    Emoji,
    icon(circle(12f, 7f, 3.5f), "M5 20v-1a7 7 0 0 1 14 0v1"),
    icon("M5 19c0-8 5-14 14-14 0 9-6 14-14 14z", "M5 19l7-7"),
    icon("M6 9h11v4a5.5 5.5 0 0 1-11 0z", "M17 10h1.5a2 2 0 0 1 0 4H17", "M9 3v3M12 3v3"),
    icon("M4 15l1.5-5h13l1.5 5v3H4z", "M7 18v2M17 18v2", circle(8f, 15f, 0.5f), circle(16f, 15f, 0.5f)),
    icon(circle(12f, 12f, 9f), "M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18M3 12h18"),
    icon("M9 18h6M10 21h4", "M12 3a6 6 0 0 0-3.5 10.9V16h7v-2.1A6 6 0 0 0 12 3z"),
    icon("M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z"),
    icon("M5 21V4", "M5 4h12l-2 4 2 4H5"),
  )

  private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

  private fun icon(vararg paths: String): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
      for (d in paths) {
        addPath(
          PathParser().parsePathString(d).toNodes(),
          stroke = SolidColor(Color.Black),
          strokeLineWidth = 1.8f,
          strokeLineCap = StrokeCap.Round,
          strokeLineJoin = StrokeJoin.Round,
        )
      }
    }.build()
}
