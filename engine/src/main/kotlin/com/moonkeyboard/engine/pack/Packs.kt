package com.moonkeyboard.engine.pack

import com.moonkeyboard.engine.decode.DecoderConfig
import com.moonkeyboard.engine.suggest.EngineConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * What a language pack holds. Its files follow the pack's [id]: `<id>.dict` (required), and
 * optionally the neural model `<id>-lm.onnx` with its `<id>-lm.vocab`, plus layouts as `<name>.json`.
 */
@Serializable
data class PackManifest(
  /** The pack format; an app refuses packs newer than [Packs.FORMAT]. */
  val format: Int,
  val id: String,
  val name: String,
  val version: Int,
  /** The letters layout: a `<layout>.json` in the pack, or a built-in layout's id. */
  val layout: String = "qwerty",
  /** Every file in the pack and its SHA-256, in hex; the built-in pack leaves it empty. */
  val files: Map<String, String> = emptyMap(),
  /** Letters with a standard two-letter spelling for when the key isn't to hand: German "ß" → "ss", "ä" → "ae". */
  val digraphs: Map<String, String> = emptyMap(),
  /** Every noun is capitalised (German): a capitalised word isn't taken for a name, so it isn't marked down. */
  val nounCapitals: Boolean = false,
) {
  /** The decoder's settings for this language. */
  fun decoderConfig(base: DecoderConfig = DecoderConfig()): DecoderConfig = base.copy(digraphs = digraphs.mapKeys { it.key.single() })

  /** The engine's settings for this language. */
  fun engineConfig(base: EngineConfig = EngineConfig()): EngineConfig = if (nounCapitals) base.copy(nounCapitals = true) else base
}

class PackException(message: String) : Exception(message)

/**
 * Language packs are zip files: [MANIFEST], its ECDSA P-256 signature [SIGNATURE], and the files the
 * manifest lists with their hashes. A pack comes from a file the user opened, so nothing in it is
 * parsed (not even the manifest) until the signature and every hash check out.
 */
object Packs {
  const val FORMAT = 1
  const val MANIFEST = "manifest.json"
  const val SIGNATURE = "manifest.sig"

  private val json = Json { ignoreUnknownKeys = true }
  private val ID = Regex("[a-z]{2,3}(-[a-z0-9]{2,8})?")
  private val FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
  private const val MAX_FILES = 32

  // A dictionary and a model run to tens of MB; nothing honest comes near this
  private const val MAX_BYTES = 300L shl 20
  private const val ALGORITHM = "SHA256withECDSA"

  fun parseManifest(bytes: ByteArray): PackManifest = json.decodeFromString(PackManifest.serializer(), bytes.decodeToString())

  /** A signed pack of [files] (name → content), with their hashes written into [manifest]. */
  fun build(manifest: PackManifest, files: Map<String, ByteArray>, key: PrivateKey): ByteArray {
    val full = manifest.copy(format = FORMAT, files = files.mapValues { sha256(it.value) }.toSortedMap())
    check(full)
    val manifestBytes = json.encodeToString(PackManifest.serializer(), full).encodeToByteArray()
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
      fun add(name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
      }
      add(MANIFEST, manifestBytes)
      add(SIGNATURE, sign(manifestBytes, key))
      for ((name, bytes) in files.toSortedMap()) add(name, bytes)
    }
    return out.toByteArray()
  }

  /**
   * Checks the pack in [zip] against [key] and puts it in `<packsDir>/<id>`, replacing an older
   * version of it. Throws [PackException] (and leaves what was installed alone) when anything is off.
   */
  fun install(zip: InputStream, packsDir: File, key: PublicKey): PackManifest {
    packsDir.mkdirs()
    val staging = File(packsDir, ".staging").apply { deleteRecursively(); mkdirs() }
    try {
      val hashes = unpack(zip, staging)
      val manifestBytes = File(staging, MANIFEST).takeIf { MANIFEST in hashes }?.readBytes() ?: throw PackException("Not a language pack")
      val signature = File(staging, SIGNATURE).takeIf { SIGNATURE in hashes }?.readBytes() ?: throw PackException("The pack isn't signed")
      if (!verify(manifestBytes, signature, key)) throw PackException("The pack isn't from Moon Keyboard")
      val manifest = parseManifest(manifestBytes)
      if (manifest.format > FORMAT) throw PackException("This pack needs a newer version of Moon Keyboard")
      check(manifest)
      val contents = hashes - MANIFEST - SIGNATURE
      if (contents.keys != manifest.files.keys) throw PackException("The pack's files don't match its manifest")
      for ((name, hash) in manifest.files) if (contents[name] != hash) throw PackException("$name is damaged")

      val target = File(packsDir, manifest.id)
      installed(target)?.let { old ->
        if (old.version > manifest.version) throw PackException("A newer version of ${manifest.name} is already installed")
      }
      // Swap whole folders, so a failure part-way never leaves a half-replaced pack
      val previous = File(packsDir, ".previous").apply { deleteRecursively() }
      if (target.exists() && !target.renameTo(previous)) throw PackException("Couldn't replace the installed pack")
      if (!staging.renameTo(target)) {
        previous.renameTo(target)
        throw PackException("Couldn't install the pack")
      }
      previous.deleteRecursively()
      return manifest
    } finally {
      staging.deleteRecursively()
    }
  }

  /** The manifest of the pack installed in [dir], or null when there isn't a readable one. */
  fun installed(dir: File): PackManifest? = File(dir, MANIFEST).takeIf { it.isFile }?.let { runCatching { parseManifest(it.readBytes()) }.getOrNull() }

  fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

  /** A public key as the app stores it: X.509, base64. */
  fun publicKey(base64: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64)))

  fun encode(key: PublicKey): String = Base64.getEncoder().encodeToString(key.encoded)

  /** A private key from its PKCS#8 bytes, as [generateKeyPair]'s is saved. */
  fun privateKey(pkcs8: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

  private fun sign(data: ByteArray, key: PrivateKey): ByteArray = Signature.getInstance(ALGORITHM).run {
    initSign(key)
    update(data)
    sign()
  }

  private fun verify(data: ByteArray, signature: ByteArray, key: PublicKey): Boolean = runCatching { Signature.getInstance(ALGORITHM).run { initVerify(key); update(data); verify(signature) } }.getOrDefault(false)

  private fun check(manifest: PackManifest) {
    if (!ID.matches(manifest.id)) throw PackException("Bad pack id '${manifest.id}'")
    if (manifest.files.keys.any { !FILE_NAME.matches(it) || it == MANIFEST || it == SIGNATURE }) throw PackException("Bad file name in the pack")
    if (manifest.files.isNotEmpty() && "${manifest.id}.dict" !in manifest.files) throw PackException("The pack has no dictionary")
    if (manifest.digraphs.any { (letter, spelling) -> letter.length != 1 || spelling.length != 2 || !(letter + spelling).all(Char::isLetter) }) {
      throw PackException("Bad two-letter spelling in the pack")
    }
  }

  /** Writes each entry of [zip] into [dir] (flat names only, within size limits) and returns their hashes. */
  private fun unpack(zip: InputStream, dir: File): Map<String, String> {
    val hashes = HashMap<String, String>()
    var total = 0L
    val buffer = ByteArray(1 shl 16)
    ZipInputStream(zip).use { entries ->
      while (true) {
        val entry = entries.nextEntry ?: break
        // Flat names only: no folders, no "../" climbing out of the pack's own
        if (entry.isDirectory || !FILE_NAME.matches(entry.name)) throw PackException("Unexpected file in the pack")
        if (entry.name in hashes || hashes.size >= MAX_FILES) throw PackException("Unexpected file in the pack")
        val digest = MessageDigest.getInstance("SHA-256")
        File(dir, entry.name).outputStream().use { out ->
          while (true) {
            val n = entries.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw PackException("The pack is too large")
            digest.update(buffer, 0, n)
            out.write(buffer, 0, n)
          }
        }
        hashes[entry.name] = digest.digest().toHex()
      }
    }
    return hashes
  }

  private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

  private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
