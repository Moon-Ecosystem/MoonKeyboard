package com.moonkeyboard.ime

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class OneHanded { OFF, LEFT, RIGHT }
enum class KeySize(val scale: Float) { SHORT(0.9f), NORMAL(1f), TALL(1.12f) }
enum class ImageExpiry(val days: Int?) { WEEK(7), MONTH(30), NEVER(null) }

/**
 * User settings, shared by the keyboard and the settings screen (same process). Each one is Compose
 * state, so the keyboard redraws the moment a setting changes.
 */
object Prefs {
  private lateinit var sp: SharedPreferences
  private val all = ArrayList<Pref<*>>()

  class Pref<T : Any>(private val key: String, default: T, private val parse: (String) -> T) {
    private val state = mutableStateOf(default)
    var value: T
      get() = state.value
      set(v) {
        state.value = v
        sp.edit().putString(key, v.toString()).apply()
      }

    init {
      all += this
    }

    internal fun load() {
      sp.getString(key, null)?.let { s -> runCatching { parse(s) }.onSuccess { state.value = it } }
    }
  }

  val theme = Pref("theme", ThemeMode.SYSTEM, ThemeMode::valueOf)
  val borders = Pref("borders", true, String::toBoolean)
  val numberRow = Pref("number_row", false, String::toBoolean)
  val keySize = Pref("key_size", KeySize.NORMAL, KeySize::valueOf)
  val oneHanded = Pref("one_handed", OneHanded.OFF, OneHanded::valueOf)
  /** The side one-handed mode goes back to when switched on from the toolbar. */
  val oneHandedSide = Pref("one_handed_side", OneHanded.RIGHT, OneHanded::valueOf)
  val haptics = Pref("haptics", true, String::toBoolean)
  val sound = Pref("sound", false, String::toBoolean)
  val glideTrail = Pref("glide_trail", true, String::toBoolean)
  val glideTyping = Pref("glide_typing", true, String::toBoolean)
  val autocorrect = Pref("autocorrect", true, String::toBoolean)
  val blockOffensive = Pref("block_offensive", false, String::toBoolean)
  val emojiSuggestions = Pref("emoji_suggestions", true, String::toBoolean)
  /** Offer a new copy in the suggestion strip, once. */
  val offerCopied = Pref("offer_copied", true, String::toBoolean)
  val imageExpiry = Pref("image_expiry", ImageExpiry.WEEK, ImageExpiry::valueOf)
  val emojiRecents = Pref("emoji_recents", "") { it }
  /** The last clip the system clipboard held, so reopening the keyboard doesn't re-add a deleted one. */
  val lastClip = Pref("last_clip", "") { it }
  val lastExpiryCheck = Pref("last_expiry_check", 0L, String::toLong)

  @Synchronized
  fun init(context: Context) {
    if (::sp.isInitialized) return
    sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    all.forEach { it.load() }
  }
}
