package com.moonkeyboard.ime

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import com.moonkeyboard.engine.layout.Layout
import com.moonkeyboard.engine.layout.Layouts
import com.moonkeyboard.engine.pack.PackManifest
import com.moonkeyboard.engine.pack.Packs
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/** A language pack's files: the built-in one in the APK, or one installed from a file the user opened. */
abstract class PackFiles(val manifest: PackManifest) {
  /** The pack's file [name], or null when it hasn't one. */
  abstract fun open(name: String): InputStream?

  val dictionary: String get() = "${manifest.id}.dict"
  val model: String get() = "${manifest.id}-lm.onnx"
  val vocab: String get() = "${manifest.id}-lm.vocab"

  /** The layout [name] from the pack, or the built-in one of that name. */
  fun layout(name: String): Layout =
    open("$name.json")?.use { Layouts.parse(it.readBytes().decodeToString(), name) } ?: Layouts.load(name)
}

private class AssetPack(private val assets: AssetManager, private val dir: String, manifest: PackManifest) : PackFiles(manifest) {
  override fun open(name: String): InputStream? =
    try { assets.open("$dir/$name") } catch (_: FileNotFoundException) { null }
}

private class InstalledPack(private val dir: File, manifest: PackManifest) : PackFiles(manifest) {
  override fun open(name: String): InputStream? = File(dir, name).takeIf { it.isFile }?.inputStream()
}

/**
 * English (with Hinglish) is built in; other languages are signed pack files the user downloads and
 * opens with the keyboard, so the app itself never needs the internet. Installed packs live in
 * `filesDir/packs/<id>`.
 */
object LanguagePacks {
  /** Packs must be signed with this key's private half (BuildPack genkey); kept outside the repo. */
  private const val PUBLIC_KEY =
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEEq1lfwDM2niwt2hdnMqXbXgHsfSTAY/xsmEFE41w+6Fekh2ct0+qoBFGnTqkR9/nfpT5r4gs2nYtjkXccQpZSA=="
  private const val BUILT_IN_DIR = "dict"
  const val BUILT_IN = "en"

  fun builtIn(context: Context): PackFiles {
    val assets = context.assets
    val manifest = assets.open("$BUILT_IN_DIR/${Packs.MANIFEST}").use { Packs.parseManifest(it.readBytes()) }
    return AssetPack(assets, BUILT_IN_DIR, manifest)
  }

  private fun packsDir(context: Context) = File(context.filesDir, "packs")

  fun installed(context: Context): List<PackFiles> =
    packsDir(context).listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()
      .mapNotNull { dir -> Packs.installed(dir)?.let { InstalledPack(dir, it) } }
      .sortedBy { it.manifest.name }

  /** Checks and installs the pack at [uri]; throws [com.moonkeyboard.engine.pack.PackException] when it's refused. */
  fun install(context: Context, uri: Uri): PackManifest =
    (context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())).use {
      Packs.install(it, packsDir(context), Packs.publicKey(PUBLIC_KEY))
    }

  fun find(context: Context, id: String): PackFiles? =
    if (id == BUILT_IN) builtIn(context) else installed(context).find { it.manifest.id == id }

  /** Every language, in switching order, and whether it's switched on. */
  fun all(context: Context): List<Pair<PackFiles, Boolean>> {
    val order = Prefs.languages.value.split(',').filter { it.isNotEmpty() }
    val position = order.withIndex().associate { (i, id) -> id.removePrefix("-") to i }
    return (listOf(builtIn(context)) + installed(context))
      .sortedBy { position[it.manifest.id] ?: Int.MAX_VALUE }
      .map { it to ("-" + it.manifest.id !in order) }
  }

  fun setOrder(languages: List<Pair<String, Boolean>>) {
    Prefs.languages.value = languages.joinToString(",") { (id, on) -> if (on) id else "-$id" }
  }

  /** Removes an installed pack; its learned words stay, for when it's installed again. */
  fun remove(context: Context, id: String) {
    if (id == BUILT_IN) return
    File(packsDir(context), id).deleteRecursively()
    setOrder(all(context).map { (pack, on) -> pack.manifest.id to on })
  }

  /** Each language learns on its own; English keeps the file it always had. */
  fun historyFile(context: Context, id: String) = File(context.filesDir, if (id == BUILT_IN) "history.tsv" else "history-$id.tsv")
}
