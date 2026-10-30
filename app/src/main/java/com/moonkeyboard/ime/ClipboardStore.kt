package com.moonkeyboard.ime

import android.content.ClipData
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.moonkeyboard.engine.text.oneTimeCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

enum class ClipKind { TEXT, IMAGE, GIF }

data class Clip(
  val id: Long,
  val kind: ClipKind,
  /** The text of a TEXT clip; the file name under clips/ otherwise. */
  val content: String,
  val mime: String,
  val hash: String,
  val createdAt: Long,
  val pinned: Boolean,
)

/**
 * Clipboard history: text, images and GIFs the user copied. Text stays until the user deletes it;
 * unpinned images expire after the period chosen in settings. An index file plus one file per image
 * under filesDir/clips/ (a few hundred rows don't need a database). Changed on the main thread only.
 */
object ClipboardStore {
  /** Newest first. */
  val clips = mutableStateListOf<Clip>()

  /**
   * What was just copied, offered in the suggestion strip until it is pasted (typing only puts it
   * aside, see [InputController.clipSetAside]); see [freshClip] for the age limit.
   */
  var fresh by mutableStateOf<Clip?>(null)

  /** [fresh], unless it was copied too long ago to still be what the user means to paste. */
  fun freshClip(now: Long = System.currentTimeMillis()): Clip? = fresh?.takeIf { now - it.createdAt < FRESH_MS }

  private val scope = MainScope()
  @OptIn(ExperimentalCoroutinesApi::class)
  private val io = Dispatchers.IO.limitedParallelism(1)
  private lateinit var dir: File
  private val thumbnails = LruCache<String, ImageBitmap>(48)

  fun init(context: Context) {
    if (::dir.isInitialized) return
    dir = File(context.applicationContext.filesDir, "clips").apply { mkdirs() }
    val index = File(dir, INDEX)
    if (index.exists()) runCatching { clips += parse(index.readText()) }
    expireIfDue()
  }

  /** Records what the system clipboard now holds, unless it was already recorded or is marked sensitive. */
  fun capture(context: Context, clip: ClipData) {
    val description = clip.description
    if (clip.itemCount == 0 || description.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true) return
    val item = clip.getItemAt(0)
    val stamp = if (Build.VERSION.SDK_INT >= 26) description.timestamp else 0L
    val text = item.text?.toString()
    val uri = item.uri
    val identity = when {
      text != null -> "t:${text.hashCode()}:$stamp"
      uri != null -> "u:$uri:$stamp"
      else -> return
    }
    if (identity == Prefs.lastClip.value) return
    Prefs.lastClip.value = identity
    if (text != null) {
      if (text.isBlank()) return
      // A message's one-time code gets its own clip too, for pasting where the strip didn't offer it;
      // added first so the message stays on top and is what the strip offers
      oneTimeCode(text)?.takeIf { it != text.trim() }?.let { add(ClipKind.TEXT, it, "text/plain", "t:${sha(it.toByteArray())}") }
      add(ClipKind.TEXT, text, "text/plain", "t:${sha(text.toByteArray())}")
      return
    }
    val resolver = context.contentResolver
    val mime = resolver.getType(uri!!) ?: return
    if (!mime.startsWith("image/")) return
    // The read permission on a copied image is short-lived: copy the bytes now
    scope.launch {
      val saved = withContext(io) {
        runCatching {
          val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
          val hash = "i:${sha(bytes)}"
          val name = hash.drop(2).take(24) + extension(mime)
          File(dir, name).writeBytes(bytes)
          Triple(name, hash, mime)
        }.getOrNull()
      } ?: return@launch
      add(if (mime == "image/gif") ClipKind.GIF else ClipKind.IMAGE, saved.first, saved.third, saved.second)
    }
  }

  fun file(clip: Clip) = File(dir, clip.content)

  fun setPinned(id: Long, pinned: Boolean) {
    val i = clips.indexOfFirst { it.id == id }
    if (i < 0) return
    clips[i] = clips[i].copy(pinned = pinned)
    if (fresh?.id == id) fresh = clips[i]
    save()
  }

  fun delete(id: Long) {
    clips.firstOrNull { it.id == id }?.let(::remove)
    save()
  }

  fun clearUnpinned() {
    clips.filter { !it.pinned }.forEach(::remove)
    save()
  }

  fun storageBytes(): Long = if (::dir.isInitialized) dir.listFiles()?.sumOf { it.length() } ?: 0L else 0L

  /** How many clips of each kind there are and the bytes they take (text counted by its UTF-8 size). */
  fun usage(): Map<ClipKind, Pair<Int, Long>> = ClipKind.entries.associateWith { kind ->
    val of = clips.filter { it.kind == kind }
    of.size to of.sumOf { if (kind == ClipKind.TEXT) it.content.toByteArray().size.toLong() else file(it).length() }
  }

  /** Deletes unpinned images and GIFs older than the user's setting; runs at most once a day. */
  fun expireIfDue(now: Long = System.currentTimeMillis()) {
    if (now - Prefs.lastExpiryCheck.value < DAY_MS) return
    Prefs.lastExpiryCheck.value = now
    val days = Prefs.imageExpiry.value.days ?: return
    val old = clips.filter { it.kind != ClipKind.TEXT && !it.pinned && now - it.createdAt > days * DAY_MS }
    if (old.isEmpty()) return
    old.forEach(::remove)
    save()
  }

  /** A small decoded copy for the panel (a GIF's first frame); call off the main thread. */
  fun thumbnail(clip: Clip, maxPx: Int): ImageBitmap? {
    thumbnails.get(clip.content)?.let { return it }
    val path = file(clip).path
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx / 2) sample *= 2
    val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return bitmap.asImageBitmap().also { thumbnails.put(clip.content, it) }
  }

  private fun add(kind: ClipKind, content: String, mime: String, hash: String) {
    val existing = clips.firstOrNull { it.hash == hash }
    // Copying something again brings it back to the top (and restarts an image's expiry)
    if (existing != null) clips.remove(existing)
    val id = maxOf(System.currentTimeMillis(), (clips.maxOfOrNull { it.id } ?: 0L) + 1)
    val clip = Clip(id, kind, content, mime, hash, System.currentTimeMillis(), existing?.pinned ?: false)
    clips.add(0, clip)
    fresh = clip
    save()
  }

  private fun remove(clip: Clip) {
    clips.remove(clip)
    if (fresh?.id == clip.id) fresh = null
    if (clip.kind != ClipKind.TEXT && clips.none { it.content == clip.content }) {
      thumbnails.remove(clip.content)
      val f = file(clip)
      scope.launch(io) { f.delete() }
    }
  }

  private fun save() {
    val text = serialize(clips.toList())
    scope.launch(io) {
      // Write then rename, so a crash mid-write can't lose the history
      val tmp = File(dir, "$INDEX.tmp")
      tmp.writeText(text)
      tmp.renameTo(File(dir, INDEX))
    }
  }

  private fun serialize(list: List<Clip>): String = JSONArray().apply {
    for (c in list) {
      put(
        JSONObject().put("id", c.id).put("kind", c.kind.name).put("content", c.content).put("mime", c.mime)
          .put("hash", c.hash).put("createdAt", c.createdAt).put("pinned", c.pinned)
      )
    }
  }.toString()

  private fun parse(text: String): List<Clip> {
    val array = JSONArray(text)
    return (0 until array.length()).map { i ->
      val o = array.getJSONObject(i)
      Clip(o.getLong("id"), ClipKind.valueOf(o.getString("kind")), o.getString("content"), o.getString("mime"),
        o.getString("hash"), o.getLong("createdAt"), o.getBoolean("pinned"))
    }
  }

  private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun extension(mime: String) = when (mime) {
    "image/gif" -> ".gif"
    "image/png" -> ".png"
    "image/webp" -> ".webp"
    else -> ".jpg"
  }

  private const val INDEX = "index.json"
  private const val FRESH_MS = 5L * 60 * 1000
  private const val DAY_MS = 24L * 60 * 60 * 1000
  // ClipDescription.EXTRA_IS_SENSITIVE (Android 13), which password managers set on older versions too
  private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
}
