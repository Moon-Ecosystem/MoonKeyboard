package com.moonkeyboard.tools

import java.io.File

/**
 * Turns Unicode's emoji-test.txt into the keyboard's emoji list: a "=Group" line per category,
 * then one line per emoji: the emoji and its five skin tones, a tab, then its search words
 * ("|"-separated: the name, plus CLDR's keywords when given).
 *
 *   ./gradlew :tools:run -PmainClass=com.moonkeyboard.tools.BuildEmojiKt \
 *     --args="tools/data/emoji-test.txt tools/data/cldr-annotations-en.xml app/src/main/assets/emoji.txt"
 */
fun main(args: Array<String>) {
  require(args.size == 3) { "usage: BuildEmoji <emoji-test.txt> <cldr-annotations-en.xml> <out>" }
  val keywords = readKeywords(File(args[1]))
  val groups = LinkedHashMap<String, LinkedHashMap<String, Entry>>()
  var group = ""
  for (line in File(args[0]).readLines()) {
    if (line.startsWith("# group: ")) group = line.removePrefix("# group: ").trim()
    if (line.startsWith("#") || !line.contains("; fully-qualified") || group == "Component") continue
    val codes = line.substringBefore(';').trim().split(' ').map { it.toInt(16) }
    val tones = codes.filter { it in SKIN_TONES }
    val emoji = codes.joinToString("") { String(Character.toChars(it)) }
    val byBase = groups.getOrPut(group) { LinkedHashMap() }
    if (tones.isEmpty()) {
      // "# 😀 E1.0 grinning face": the name follows the emoji and its version
      val name = line.substringAfter('#').trim().split(' ', limit = 3).getOrElse(2) { "" }
      val words = listOf(name) + keywords[emoji.replace(VS16, "")].orEmpty()
      byBase[emoji] = Entry(mutableListOf(emoji), words.map { it.lowercase() }.distinct())
    } else if (tones.distinct().size == 1) {
      // Pairs with two different tones would need a two-step picker; one tone for both people is enough
      val base = codes.filter { it !in SKIN_TONES }.joinToString("") { String(Character.toChars(it)) }
      byBase[base]?.variants?.add(emoji)
    }
  }
  val out = StringBuilder()
  for ((name, emoji) in groups) {
    out.append('=').append(name).append('\n')
    for (e in emoji.values) out.append(e.variants.joinToString(" ")).append('\t').append(e.words.joinToString("|")).append('\n')
  }
  File(args[2]).writeText(out.toString())
  println("emoji: ${groups.values.sumOf { it.size }} in ${groups.size} groups, ${keywords.size} with keywords")
}

private class Entry(val variants: MutableList<String>, val words: List<String>)

/** CLDR's `<annotation cp="😂">face | joy | laugh | tear</annotation>` lines, keyed by emoji without U+FE0F. */
private fun readKeywords(file: File): Map<String, List<String>> =
  Regex("""<annotation cp="([^"]+)">([^<]+)</annotation>""").findAll(file.readText()).associate { m ->
    m.groupValues[1].replace(VS16, "") to m.groupValues[2].split('|').map { it.trim() }.filter { it.isNotEmpty() }
  }

private val SKIN_TONES = 0x1F3FB..0x1F3FF
private const val VS16 = "️"
