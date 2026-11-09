package com.moonkeyboard.ime

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The accent (glide trail, chosen popup item, the app's switches), as a light shade for dark themes
 * and a deeper one for light themes, where a pale accent would vanish. [WALLPAPER] takes the whole
 * palette from the wallpaper (Material You, Android 12+).
 */
enum class Accent(val dark: Long, val light: Long) {
  MOON(0xFFB4BDFF, 0xFF3D4BC4),
  EMERALD(0xFF69B37E, 0xFF2E7D4F),
  AMBER(0xFFE5A855, 0xFFA45F00),
  CYAN(0xFF70C4E8, 0xFF00739F),
  CORAL(0xFFE87A65, 0xFFC0412B),
  VIOLET(0xFFB88EE8, 0xFF7442C8),
  WALLPAPER(0xFFB4BDFF, 0xFF3D4BC4),
}
enum class OneHanded { OFF, LEFT, RIGHT }
enum class ImageExpiry(val days: Int?) { WEEK(7), MONTH(30), NEVER(null) }

/** What the user can put on the toolbar, in the order they choose; options and clipboard are always there. */
enum class ToolbarItem {
  EMOJI, THEME, FLOATING, ONE_HANDED, TEXT_EDIT, SPLIT, INCOGNITO;

  companion object {
    /** As many as fit beside options, clipboard and the back arrow on a narrow phone. */
    const val MAX = 5
  }
}

/** Where the floating keyboard sits, in dp from the top left of the screen, and how wide it is. */
data class FloatSpot(val x: Float, val y: Float, val width: Float) {
  override fun toString() = "$x,$y,$width"

  companion object {
    val UNSET = FloatSpot(-1f, -1f, -1f)

    fun parse(s: String) = s.split(',').map(String::toFloat).let { (x, y, w) -> FloatSpot(x, y, w) }
  }
}

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
  val accent = Pref("accent", Accent.MOON, Accent::valueOf)
  /** Dark theme on a true black background, for OLED screens. */
  val pitchBlack = Pref("pitch_black", false, String::toBoolean)
  val borders = Pref("borders", true, String::toBoolean)
  val numberRow = Pref("number_row", false, String::toBoolean)
  /** A key of its own for emoji beside the comma, instead of holding the comma. */
  val emojiKey = Pref("emoji_key", false, String::toBoolean)
  /** The keys' height as a percentage of the size that fits the screen. */
  val keyboardHeight = Pref("keyboard_height", 100) { it.toInt().coerceIn(KEYBOARD_HEIGHT_MIN, KEYBOARD_HEIGHT_MAX) }
  val oneHanded = Pref("one_handed", OneHanded.OFF, OneHanded::valueOf)
  /** The side one-handed mode goes back to when switched on from the toolbar. */
  val oneHandedSide = Pref("one_handed_side", OneHanded.RIGHT, OneHanded::valueOf)
  val floating = Pref("floating", false, String::toBoolean)
  // Portrait and landscape each keep their own spot: one rarely fits the other
  val floatPortrait = Pref("float_portrait", FloatSpot.UNSET, FloatSpot::parse)
  val floatLandscape = Pref("float_landscape", FloatSpot.UNSET, FloatSpot::parse)
  val splitLandscape = Pref("split_landscape", true, String::toBoolean)
  /** Toolbar items as comma-separated names, in the bar's order; use [toolbar] and [setToolbar]. */
  val toolbarItems = Pref("toolbar", "EMOJI,THEME,FLOATING") { it }

  fun toolbar(): List<ToolbarItem> =
    toolbarItems.value.split(',').mapNotNull { name -> ToolbarItem.entries.find { it.name == name } }.distinct()

  fun setToolbar(items: List<ToolbarItem>) {
    toolbarItems.value = items.joinToString(",")
  }

  val haptics = Pref("haptics", true, String::toBoolean)
  /** 1 to [Haptics.MAX_STRENGTH]; a 0 saved by the old "system default" step reads as 1. */
  val hapticStrength = Pref("haptic_strength", 2) { it.toInt().coerceAtLeast(1) }
  /** How long a key must be held before its popup opens, in milliseconds. */
  val longPressMs = Pref("long_press_ms", 300) { it.toInt().coerceIn(100, 700) }
  val autoCaps = Pref("auto_caps", true, String::toBoolean)
  /** Turned on from the toolbar: nothing typed is learned, in any field, until it's turned off. */
  val incognito = Pref("incognito", false, String::toBoolean)
  val sound = Pref("sound", false, String::toBoolean)
  val keyPreview = Pref("key_preview", true, String::toBoolean)
  val spaceSlide = Pref("space_slide", true, String::toBoolean)
  val backspaceSlide = Pref("backspace_slide", true, String::toBoolean)
  val glideTrail = Pref("glide_trail", true, String::toBoolean)
  val glideTyping = Pref("glide_typing", true, String::toBoolean)
  val autocorrect = Pref("autocorrect", true, String::toBoolean)
  val blockOffensive = Pref("block_offensive", false, String::toBoolean)
  val hinglish = Pref("hinglish", true, String::toBoolean)
  val neuralPredictions = Pref("neural_predictions", true, String::toBoolean)
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
    // The old short / normal / tall key height carries over as the nearest percentage
    if (!sp.contains("keyboard_height")) {
      when (sp.getString("key_size", null)) {
        "SHORT" -> keyboardHeight.value = 90
        "TALL" -> keyboardHeight.value = 110
      }
    }
  }

  const val KEYBOARD_HEIGHT_MIN = 80
  const val KEYBOARD_HEIGHT_MAX = 140
}
