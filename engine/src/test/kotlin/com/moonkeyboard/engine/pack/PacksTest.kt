package com.moonkeyboard.engine.pack

import com.moonkeyboard.engine.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class PacksTest {
  @get:Rule val tmp = TemporaryFolder()

  private val keys = Packs.generateKeyPair()
  private val qwertz = Layouts::class.java.getResourceAsStream("/layouts/qwerty.json")!!.readBytes()
  private val files = mapOf("de.dict" to "dictionary".encodeToByteArray(), "qwertz.json" to qwertz)

  private fun pack(version: Int = 1, key: java.security.PrivateKey = keys.private) =
    Packs.build(PackManifest(Packs.FORMAT, "de", "Deutsch", version, layout = "qwertz"), files, key)

  private fun install(bytes: ByteArray) = Packs.install(ByteArrayInputStream(bytes), tmp.root, keys.public)

  private fun refused(bytes: ByteArray): Boolean = runCatching { install(bytes) }.exceptionOrNull() is PackException

  /** The pack's zip with [change] applied to its entries (name → bytes). */
  private fun rezip(bytes: ByteArray, change: (MutableMap<String, ByteArray>) -> Unit): ByteArray {
    val entries = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() } }
    change(entries)
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    return out.toByteArray()
  }

  @Test
  fun aSignedPackInstallsWithItsFiles() {
    val manifest = install(pack())
    assertEquals("de", manifest.id)
    val dir = tmp.root.resolve("de")
    assertEquals("dictionary", dir.resolve("de.dict").readText())
    assertEquals(manifest, Packs.installed(dir))
    // Its layout parses like a built-in one
    assertTrue(Layouts.parse(dir.resolve("qwertz.json").readText(), "qwertz").rows.isNotEmpty())
  }

  @Test
  fun anythingTamperedWithIsRefusedAndLeavesTheInstalledPackAlone() {
    install(pack())
    val other = Packs.generateKeyPair()
    assertTrue("another key", refused(pack(version = 2, key = other.private)))
    assertTrue("a changed file", refused(rezip(pack(2)) { it["de.dict"] = "evil".encodeToByteArray() }))
    assertTrue("an extra file", refused(rezip(pack(2)) { it["extra.bin"] = byteArrayOf(1) }))
    assertTrue("a missing file", refused(rezip(pack(2)) { it.remove("qwertz.json") }))
    assertTrue("no signature", refused(rezip(pack(2)) { it.remove(Packs.SIGNATURE) }))
    assertTrue("a path out of the folder", refused(rezip(pack(2)) { it["../escape"] = byteArrayOf(1) }))
    assertEquals(1, Packs.installed(tmp.root.resolve("de"))!!.version)
    assertFalse(tmp.root.resolve("escape").exists())
  }

  @Test
  fun newerVersionsReplaceOlderOnesButNeverTheOtherWayRound() {
    install(pack(1))
    install(pack(3))
    assertEquals(3, Packs.installed(tmp.root.resolve("de"))!!.version)
    assertTrue(refused(pack(2)))
  }

  @Test
  fun aPackFromANewerFormatIsRefused() {
    val manifest = PackManifest(Packs.FORMAT + 1, "de", "Deutsch", 1)
    // Signed properly, so only the format turns it away
    val bytes = rezip(Packs.build(manifest, files, keys.private)) { entries ->
      val text = entries.getValue(Packs.MANIFEST).decodeToString().replace("\"format\":${Packs.FORMAT}", "\"format\":${Packs.FORMAT + 1}")
      entries[Packs.MANIFEST] = text.encodeToByteArray()
      entries[Packs.SIGNATURE] = sign(text.encodeToByteArray())
    }
    val error = runCatching { install(bytes) }.exceptionOrNull()
    assertTrue(error is PackException && "newer version" in error.message!!)
  }

  private fun sign(data: ByteArray) = java.security.Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(data); sign() }
}
