package com.moonkeyboard

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import com.moonkeyboard.engine.pack.PackException
import com.moonkeyboard.ime.Haptics
import com.moonkeyboard.ime.Language
import com.moonkeyboard.ime.LanguagePacks
import com.moonkeyboard.ime.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LanguageRowHeight = 64.dp
private const val MORE_LANGUAGES = "https://github.com/Moon-Ecosystem/MoonKeyboard-Languages/releases"

/**
 * The languages to switch between (by holding space), in order, each switched on or off; installed
 * packs can be removed. Packs come from files the user downloaded: the app itself never goes online.
 */
@Composable
fun LanguagesScreen(onBack: () -> Unit) {
  val c = LocalAppColors.current
  val context = LocalContext.current
  val view = LocalView.current
  val scope = rememberCoroutineScope()
  // Bumped after an install or removal, so the list is read again
  var version by remember { mutableIntStateOf(0) }
  val languages = remember(version, Prefs.languages.value) { LanguagePacks.all(context) }
  var message by remember { mutableStateOf<String?>(null) }

  /** Saves [list]'s order and switches, and moves typing off a language that's no longer on. */
  fun save(list: List<Pair<String, Boolean>>) {
    LanguagePacks.setOrder(list)
    val on = list.filter { it.second }.map { it.first }
    if (Prefs.language.value !in on) on.firstOrNull()?.let(Language::switchTo)
  }

  val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
      message = withContext(Dispatchers.IO) {
        try {
          val pack = LanguagePacks.install(context, uri)
          context.getString(R.string.language_installed, pack.name, pack.version)
        } catch (e: PackException) {
          context.getString(R.string.language_refused, e.message)
        } catch (e: Exception) {
          context.getString(R.string.language_unreadable)
        }
      }
      version++
    }
  }

  // A row and the divider under it
  val pitch = with(LocalDensity.current) { (LanguageRowHeight + 1.dp).toPx() }
  var dragging by remember { mutableStateOf<String?>(null) }
  var dragY by remember { mutableFloatStateOf(0f) }
  val moveUp = stringResource(R.string.move_up)
  val moveDown = stringResource(R.string.move_down)
  fun move(id: String, by: Int): Boolean {
    val list = LanguagePacks.all(context).map { (p, on) -> p.manifest.id to on }.toMutableList()
    val from = list.indexOfFirst { it.first == id }
    if (from < 0 || from + by !in list.indices) return false
    list.add(from + by, list.removeAt(from))
    save(list)
    Haptics.tap(view)
    return true
  }

  Column(Modifier.fillMaxSize().background(c.background)) {
    TopBar(R.string.languages, onBack)
    Column(
      Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(stringResource(R.string.languages_explain), Modifier.padding(horizontal = 4.dp), color = c.muted, fontSize = 13.sp, lineHeight = 19.sp)
      Card {
        val onCount = languages.count { it.second }
        languages.forEachIndexed { i, (pack, on) ->
          val id = pack.manifest.id
          key(id) {
            if (i > 0) Divider()
            val lifted = id == dragging
            Row(
              Modifier.fillMaxWidth().height(LanguageRowHeight)
                .zIndex(if (lifted) 1f else 0f)
                .graphicsLayer { translationY = if (lifted) dragY else 0f }
                .background(if (lifted) c.selected else Color.Transparent)
                .semantics {
                  customActions = listOf(
                    CustomAccessibilityAction(moveUp) { move(id, -1) },
                    CustomAccessibilityAction(moveDown) { move(id, 1) },
                  )
                }
                .padding(start = 16.dp, end = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(Modifier.weight(1f)) {
                Text(pack.manifest.name, color = c.text, fontSize = 15.sp)
                val detail = if (id == LanguagePacks.BUILT_IN) stringResource(R.string.language_built_in) else stringResource(R.string.language_version, pack.manifest.version)
                Text(detail, Modifier.padding(top = 2.dp), color = c.muted, fontSize = 12.sp)
              }
              if (id != LanguagePacks.BUILT_IN) {
                Box(
                  Modifier.size(44.dp).clip(CircleShape).clickable {
                    LanguagePacks.remove(context, id)
                    save(LanguagePacks.all(context).map { (p, o) -> p.manifest.id to o })
                    message = context.getString(R.string.language_removed, pack.manifest.name)
                    version++
                  },
                  contentAlignment = Alignment.Center,
                ) {
                  Icon(AppIcons.Remove, stringResource(R.string.remove_language, pack.manifest.name), Modifier.size(22.dp), tint = c.muted)
                }
              }
              // The last language switched on stays on
              Switch(
                on,
                { now -> save(languages.map { (p, o) -> p.manifest.id to if (p.manifest.id == id) now else o }) },
                enabled = !on || onCount > 1,
                colors = switchColors(),
              )
              Box(
                Modifier.size(44.dp).pointerInput(id) {
                  detectDragGestures(
                    onDragStart = { dragging = id; dragY = 0f },
                    onDragEnd = { dragging = null; dragY = 0f },
                    onDragCancel = { dragging = null; dragY = 0f },
                  ) { change, amount ->
                    change.consume()
                    val at = LanguagePacks.all(context).indexOfFirst { it.first.manifest.id == id }
                    val last = languages.lastIndex
                    // Not past either end of the list
                    dragY = (dragY + amount.y).coerceIn(-at * pitch, (last - at) * pitch)
                    if (dragY > pitch / 2 && move(id, 1)) dragY -= pitch
                    if (dragY < -pitch / 2 && move(id, -1)) dragY += pitch
                  }
                },
                contentAlignment = Alignment.Center,
              ) {
                Icon(AppIcons.DragHandle, stringResource(R.string.drag_to_reorder), Modifier.size(22.dp), tint = c.muted)
              }
            }
          }
        }
      }
      message?.let { Text(it, Modifier.padding(horizontal = 4.dp), color = c.text, fontSize = 13.sp, lineHeight = 19.sp) }
      Card {
        NavRow(AppIcons.Add, R.string.import_language, stringResource(R.string.import_language_detail)) {
          pick.launch(arrayOf("*/*"))
        }
        Divider()
        NavRow(AppIcons.Language, R.string.more_languages, stringResource(R.string.more_languages_detail)) {
          context.startActivity(Intent(Intent.ACTION_VIEW, MORE_LANGUAGES.toUri()))
        }
      }
      PrivacyNote(R.string.languages_privacy)
    }
  }
}
