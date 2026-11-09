package com.moonkeyboard.tools

import com.moonkeyboard.engine.pack.PackManifest
import com.moonkeyboard.engine.pack.Packs
import java.io.File

/**
 * Makes signed language packs (see [Packs]). Every file in <folder> goes in: `<id>.dict`, optionally
 * `<id>-lm.onnx` and `<id>-lm.vocab`, and layouts as `<name>.json`. Paths must be absolute, as
 * `:tools:run` runs inside tools/.
 *
 *   ./gradlew :tools:run -PmainClass=com.moonkeyboard.tools.BuildPackKt \
 *     --args="pack <folder> <id> '<name>' <version> <layout> <private key> <out.mkpack>"
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
      require(args.size == 8) { USAGE }
      val (_, folder, id, name, version, layout, keyFile, out) = args
      val files = File(folder).listFiles { f -> f.isFile }!!.sortedBy { it.name }.associate { it.name to it.readBytes() }
      val manifest = PackManifest(Packs.FORMAT, id, name, version.toInt(), layout)
      val bytes = Packs.build(manifest, files, Packs.privateKey(File(keyFile).readBytes()))
      File(out).writeBytes(bytes)
      println("$out: ${files.keys.joinToString()} (${bytes.size / 1024} KB)")
    }
    else -> error(USAGE)
  }
}

private operator fun <T> Array<T>.component6() = this[5]
private operator fun <T> Array<T>.component7() = this[6]
private operator fun <T> Array<T>.component8() = this[7]

private const val USAGE = "usage: BuildPack genkey <private key out> | pack <folder> <id> <name> <version> <layout> <private key> <out.mkpack>"
