package com.moonkeyboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonkeyboard.ime.Icons
import com.moonkeyboard.ime.Prefs
import com.moonkeyboard.ime.ThemeMode

/** The app's palette, from the design canvas; light mirrors it for the light keyboard theme. */
data class AppColors(
  val background: Color,
  val surface: Color,
  val selected: Color,
  val divider: Color,
  val text: Color,
  val muted: Color,
  val faint: Color,
  val accent: Color,
  val onAccent: Color,
  val iconBg: Color,
  val success: Color,
  val successBg: Color,
  val danger: Color,
  val dangerBorder: Color,
  val note: Color,
  val switchOff: Color,
  val focus: Color,
) {
  companion object {
    val Dark = AppColors(
      background = Color(0xFF0F1117), surface = Color(0xFF181B23), selected = Color(0xFF2E3450), divider = Color(0xFF262A35),
      text = Color(0xFFEEF0F6), muted = Color(0xFFA9AFBF), faint = Color(0xFF6F7586), accent = Color(0xFFB4BDFF),
      onAccent = Color(0xFF12141C), iconBg = Color(0xFF262B44), success = Color(0xFF8FD6A8), successBg = Color(0xFF1D2A22),
      danger = Color(0xFFF2A7B3), dangerBorder = Color(0xFF5A3640), note = Color(0xFF14171F), switchOff = Color(0xFF3A3F4D),
      focus = Color(0xFF3A4170),
    )
    val Light = AppColors(
      background = Color(0xFFF4F5F9), surface = Color(0xFFFFFFFF), selected = Color(0xFFDCE0F5), divider = Color(0xFFE1E4EB),
      text = Color(0xFF161922), muted = Color(0xFF525868), faint = Color(0xFF8A90A0), accent = Color(0xFF3D4BC4),
      onAccent = Color(0xFFFFFFFF), iconBg = Color(0xFFE4E7F7), success = Color(0xFF1E7A45), successBg = Color(0xFFDDF2E5),
      danger = Color(0xFFB3263E), dangerBorder = Color(0xFFE8B7C0), note = Color(0xFFEAECF2), switchOff = Color(0xFFC4C9D4),
      focus = Color(0xFFB9C0EE),
    )
  }
}

val LocalAppColors = staticCompositionLocalOf { AppColors.Dark }

/** Stroke icons the app uses beyond the keyboard's own (24×24 viewport, like those). */
object AppIcons {
  val Shield = icon("M12 3l7 3v5c0 5-3 8.5-7 10-4-1.5-7-5-7-10V6z", "M9 12l2 2 4-4")
  val Check = icon("M5 12l5 5 9-10")
  val Typing = icon("M5 6h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "M7 10h.01M11 10h.01M15 10h.01M8 14h8")
  val Glide = icon("M4 17c3-9 6-9 8-4s5 5 8-6")
  val Learned = icon("M4 19V5a2 2 0 0 1 2-2h12v18H6a2 2 0 0 1-2-2zM8 7h6M8 11h6")
  val TestPad = icon("M4 20l4-4M14 4l6 6-8 8-6-6z")
  val Close = icon("M6 6l12 12M18 6L6 18")

  private fun icon(vararg paths: String): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
      for (d in paths) {
        addPath(PathParser().parsePathString(d).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
      }
    }.build()
}

/** Follows the keyboard's own theme setting, so the app and the keyboard always match. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
  val dark = when (Prefs.theme.value) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
  }
  CompositionLocalProvider(LocalAppColors provides if (dark) AppColors.Dark else AppColors.Light, content = content)
}

/** The crescent moon on its tile, drawn the same way as the design's logo. */
@Composable
fun MoonLogo(size: Int) {
  val c = LocalAppColors.current
  Canvas(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.28f).dp)).background(c.iconBg)) {
    val s = this.size.width / 72f
    // A disc with a smaller one taken out of its upper right leaves the crescent
    drawCircle(c.accent, radius = 19f * s, center = Offset(34f * s, 38f * s))
    drawCircle(c.iconBg, radius = 15f * s, center = Offset(45f * s, 29f * s))
    drawCircle(c.text, radius = 2f * s, center = Offset(55f * s, 19f * s))
  }
}

@Composable
fun TopBar(title: Int, onBack: () -> Unit) {
  val c = LocalAppColors.current
  Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
      Icon(Icons.Back, stringResource(R.string.back), Modifier.size(22.dp), tint = c.text)
    }
    Text(stringResource(title), Modifier.padding(start = 4.dp), color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
  }
}

/** A rounded group of rows with dividers between them. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  val c = LocalAppColors.current
  Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface), content = content)
}

@Composable
fun Divider() {
  val c = LocalAppColors.current
  Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(c.divider))
}

@Composable
fun SwitchRow(label: Int, pref: Prefs.Pref<Boolean>, detail: String? = null) {
  val c = LocalAppColors.current
  Row(
    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { pref.value = !pref.value }.padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(stringResource(label), color = c.text, fontSize = 15.sp)
      if (detail != null) Text(detail, Modifier.padding(top = 2.dp), color = c.muted, fontSize = 12.sp)
    }
    Switch(
      pref.value,
      { pref.value = it },
      colors = SwitchDefaults.colors(
        checkedThumbColor = c.onAccent, checkedTrackColor = c.accent,
        uncheckedThumbColor = c.muted, uncheckedTrackColor = c.switchOff, uncheckedBorderColor = c.switchOff,
      ),
    )
  }
}

/** A choice between a few options: a full-width bar, or compact chips at the end of a row. */
@Composable
fun <T : Any> Segmented(pref: Prefs.Pref<T>, options: List<T>, names: List<Int>, modifier: Modifier = Modifier, compact: Boolean = false) {
  val c = LocalAppColors.current
  Row(
    modifier.clip(RoundedCornerShape(if (compact) 10.dp else 14.dp)).background(if (compact) Color.Transparent else c.surface).padding(if (compact) 0.dp else 4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    options.forEachIndexed { i, option ->
      val on = pref.value == option
      Box(
        Modifier.then(if (compact) Modifier else Modifier.weight(1f)).height(if (compact) 32.dp else 38.dp)
          .clip(RoundedCornerShape(if (compact) 8.dp else 10.dp)).background(if (on) c.selected else Color.Transparent)
          .clickable { pref.value = option }.padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(stringResource(names[i]), color = if (on) c.text else c.muted, fontSize = if (compact) 13.sp else 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
      }
    }
  }
}

/** A labelled setting row with a control at its end (compact choices). */
@Composable
fun LabelRow(label: Int, content: @Composable RowScope.() -> Unit) {
  val c = LocalAppColors.current
  Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(stringResource(label), Modifier.weight(1f), color = c.text, fontSize = 15.sp)
    content()
  }
}

@Composable
fun SectionLabel(text: Int) {
  val c = LocalAppColors.current
  Text(stringResource(text), Modifier.padding(horizontal = 4.dp), color = c.muted, fontSize = 13.sp)
}

/** A row that opens another screen. */
@Composable
fun NavRow(icon: ImageVector, title: Int, detail: String, onClick: () -> Unit) {
  val c = LocalAppColors.current
  Row(Modifier.fillMaxWidth().height(60.dp).clickable(onClick = onClick).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, Modifier.size(20.dp), tint = c.muted)
    Column(Modifier.weight(1f).padding(start = 14.dp)) {
      Text(stringResource(title), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
      Text(detail, Modifier.padding(top = 2.dp), color = c.muted, fontSize = 12.sp)
    }
    Icon(Icons.Next, null, Modifier.size(18.dp), tint = c.faint)
  }
}

/** A large home-screen tile: an icon on its own tile, a title and the current setting. */
@Composable
fun RowScope.Tile(icon: ImageVector, title: Int, detail: String, onClick: () -> Unit) {
  val c = LocalAppColors.current
  Column(
    Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(c.surface).clickable(onClick = onClick).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.iconBg), contentAlignment = Alignment.Center) {
      Icon(icon, null, Modifier.size(20.dp), tint = c.accent)
    }
    Column {
      Text(stringResource(title), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
      Text(detail, Modifier.padding(top = 3.dp), color = c.muted, fontSize = 12.sp, maxLines = 1)
    }
  }
}

/** A quiet note with a shield, for privacy facts. */
@Composable
fun PrivacyNote(text: Int, modifier: Modifier = Modifier) {
  val c = LocalAppColors.current
  Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.note).padding(horizontal = 16.dp, vertical = 14.dp)) {
    Icon(AppIcons.Shield, null, Modifier.size(20.dp), tint = c.muted)
    Spacer(Modifier.width(12.dp))
    Text(stringResource(text), color = c.muted, fontSize = 13.sp, lineHeight = 19.sp)
  }
}

/** A red outlined button for deleting things. */
@Composable
fun DangerButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
  val c = LocalAppColors.current
  Box(
    modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).border(1.dp, c.dangerBorder, RoundedCornerShape(26.dp))
      .clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(label, color = if (enabled) c.danger else c.faint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
  }
}

@Composable
fun PrimaryButton(label: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
  val c = LocalAppColors.current
  Box(modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(c.accent).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
    Text(stringResource(label), color = c.onAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
  }
}
