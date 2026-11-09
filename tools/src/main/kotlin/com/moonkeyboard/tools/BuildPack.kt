package com.moonkeyboard.tools

import com.moonkeyboard.engine.pack.Packs
import java.io.File

/**
 * Makes signed language packs (see [Packs]). A pack's source is a `<id>/pack/` folder in the
 * MoonKeyboard-Languages repo (whose `<id>/fetch-data.sh` runs this), holding
 * `pack.json` (the manifest's settings: id, name, version, layout, digraphs...) and its layouts as
 * `<name>.json`; the built files (`<id>.dict`, optionally `<id>-lm.onnx` and `<id>-lm.vocab`) come
 * from a second folder. Paths must be absolute, as `:tools:run` runs inside tools/.
 *
 *   ./gradlew :tools:run -PmainClass=com.moonkeyboard.tools.BuildPackKt \
 *     --args="pack <source folder> <built folder> <private key> <out.mkpack>"
 *
 * Once, to make the signing key (the private key must stay out of the repo; the public one goes in
 * the app's LanguagePacks.PUBLIC_KEY):
 *
 *   ./gradlew :tools:run -PmainClass=com.moonkeyboard.tools.BuildPackKt --args="genkey <private key out>"
 */
fun main(args: Array<String>) {
  when (args.firstOrNull()) {
    "genkey" -> {
      require(args.size == 2) { USAGE }
      val out = File(args[1])
      require(!out.exists()) { "$out already exists: replacing it would orphan every pack signed with it" }
      val keys = Packs.generateKeyPair()
      out.writeBytes(keys.private.encoded)
      out.setReadable(false, false)
      out.setReadable(true, true)
      println("private key: $out")
      println("public key:  ${Packs.encode(keys.public)}")
    }
    "pack" -> {
      require(args.size == 5) { USAGE }
      val (_, source, built, keyFile, out) = args
      val manifest = Packs.parseManifest(File(source, "pack.json").readBytes())
      val files = (File(source).listFiles()!! + File(built).listFiles()!!)
        .filter { it.isFile && it.name != "pack.json" }
        .sortedBy { it.name }
        .associate { it.name to it.readBytes() }
      require("${manifest.id}.dict" in files) { "no ${manifest.id}.dict in $built" }
      val bytes = Packs.build(manifest, files, Packs.privateKey(File(keyFile).readBytes()))
      File(out).writeBytes(bytes)
      println("$out: ${manifest.name} ${manifest.version}, ${files.keys.joinToString()} (${bytes.size / 1024} KB)")
    }
    else -> error(USAGE)
  }
}

private const val USAGE = "usage: BuildPack genkey <private key out> | pack <source folder> <built folder> <private key> <out.mkpack>"
