package com.innovation313.glowedge

import android.content.Context
import android.graphics.Color

data class Profile(
    val name: String,
    val colorStart: Int,
    val colorEnd: Int,
    val rainbow: Boolean = false
)

data class GlowStyle(
    val id: Int,
    val name: String,
    val tagline: String,
    val premium: Boolean
)

object GlowStyles {
    const val GLOW_LINE = 0
    const val SIDE_BARS = 1
    const val BARS_AROUND = 2
    const val CORNER_GLOW = 3
    const val EMBER = 4
    const val CHASE = 5
    const val PULSE = 6
    const val DOTS = 7
    const val AURORA = 8
    const val COMET = 9
    const val RIPPLE = 10
    const val SEGMENTS = 11

    val all = listOf(
        GlowStyle(GLOW_LINE, "Glow Line", "Smooth glowing frame", false),
        GlowStyle(SIDE_BARS, "Side Bars", "Equalizer bars on both sides", false),
        GlowStyle(BARS_AROUND, "Bars Around", "Bars dancing on every edge", false),
        GlowStyle(CORNER_GLOW, "Corner Glow", "Pulsing corner arcs", false),
        GlowStyle(EMBER, "Ember Flame", "Fiery edge flames", false),
        GlowStyle(CHASE, "Chase", "Lights racing around the screen", false),
        GlowStyle(PULSE, "Pulse", "Whole edge breathes with the beat", false),
        GlowStyle(DOTS, "Dots", "Glowing dots bouncing to the music", false),
        GlowStyle(AURORA, "Aurora", "Flowing northern-lights waves", false),
        GlowStyle(COMET, "Comet", "Shooting lights with glowing tails", false),
        GlowStyle(RIPPLE, "Ripple", "Rings that ripple out on every beat", false),
        GlowStyle(SEGMENTS, "Segments", "Edge split into dancing segments", false)
    )
}

object ProfileManager {
    private const val PREFS = "glowedge_prefs"
    private const val KEY_THEME = "theme_index"
    private const val KEY_STYLE = "style_id"
    private const val KEY_THICKNESS = "thickness"
    private const val KEY_SPEED = "speed"
    private const val KEY_INTENSITY = "intensity"
    private const val KEY_SAVER = "battery_saver"
    private const val KEY_CUSTOM_START = "custom_start"
    private const val KEY_CUSTOM_END = "custom_end"
    private const val KEY_INTRO = "intro_anim"
    private const val KEY_MUSIC_ONLY = "music_only"
    private const val KEY_SENSITIVITY = "music_sensitivity"
    private const val KEY_ONBOARDED = "onboarded"
    private const val KEY_PERSONAL_TEXT = "personal_text"
    private const val KEY_PERSONAL_TEXT_ON = "personal_text_on"
    private const val KEY_PERSONAL_TEXT_COLOR = "personal_text_color"

    val themes = listOf(
        // Softened, eye-soothing palette: rich but not harsh (balanced saturation/brightness)
        Profile("Spectrum (All Colors)", Color.parseColor("#F06060"), Color.parseColor("#B07CE8"), rainbow = true),
        Profile("Neon", Color.parseColor("#4DD9EC"), Color.parseColor("#9B7DE8")),
        Profile("Sunset", Color.parseColor("#F49B6A"), Color.parseColor("#D98BC9")),
        Profile("Ocean", Color.parseColor("#5AB8E8"), Color.parseColor("#5FD6A7")),
        Profile("Royal Gold", Color.parseColor("#EFCB74"), Color.parseColor("#7B8BC9")),
        Profile("Fire", Color.parseColor("#EF6E7A"), Color.parseColor("#F2CE6B")),
        Profile("Ice", Color.parseColor("#7FDDEB"), Color.parseColor("#DCEFF7")),
        Profile("Rose Calm", Color.parseColor("#E8A0B4"), Color.parseColor("#B8A9E0")),
        Profile("Forest Calm", Color.parseColor("#7FC98F"), Color.parseColor("#5BA8A0"))
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setTheme(context: Context, index: Int) =
        prefs(context).edit().putInt(KEY_THEME, index).apply()

    fun themeIndex(context: Context): Int =
        prefs(context).getInt(KEY_THEME, 0).coerceIn(0, themes.size)

    fun customStart(context: Context): Int =
        prefs(context).getInt(KEY_CUSTOM_START, Color.parseColor("#4DD9EC"))

    fun customEnd(context: Context): Int =
        prefs(context).getInt(KEY_CUSTOM_END, Color.parseColor("#E8A0B4"))

    fun setCustomColors(context: Context, start: Int, end: Int) =
        prefs(context).edit().putInt(KEY_CUSTOM_START, start).putInt(KEY_CUSTOM_END, end).apply()

    /** Index == themes.size is the "Custom" slot (user-made colors). */
    fun theme(context: Context): Profile {
        val i = themeIndex(context)
        return if (i >= themes.size)
            Profile("Custom", customStart(context), customEnd(context))
        else themes[i]
    }

    fun setStyle(context: Context, id: Int) =
        prefs(context).edit().putInt(KEY_STYLE, id).apply()

    fun style(context: Context): Int {
        val id = prefs(context).getInt(KEY_STYLE, GlowStyles.GLOW_LINE)
        val s = GlowStyles.all.firstOrNull { it.id == id }
        return if (s == null || s.premium) GlowStyles.GLOW_LINE else id
    }

    fun setThickness(context: Context, v: Int) =
        prefs(context).edit().putInt(KEY_THICKNESS, v).apply()

    fun thickness(context: Context): Int =
        prefs(context).getInt(KEY_THICKNESS, 16).coerceIn(6, 40)

    fun setSpeed(context: Context, v: Int) =
        prefs(context).edit().putInt(KEY_SPEED, v).apply()

    fun speed(context: Context): Int =
        prefs(context).getInt(KEY_SPEED, 10).coerceIn(2, 20)

    fun setIntensity(context: Context, v: Int) =
        prefs(context).edit().putInt(KEY_INTENSITY, v).apply()

    fun intensity(context: Context): Int =
        prefs(context).getInt(KEY_INTENSITY, 10).coerceIn(3, 20)

    fun setBatterySaver(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_SAVER, on).apply()

    fun batterySaver(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SAVER, true)

    fun setMusicOnly(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_MUSIC_ONLY, on).apply()

    fun musicOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MUSIC_ONLY, true)

    fun setSensitivity(context: Context, v: Int) =
        prefs(context).edit().putInt(KEY_SENSITIVITY, v).apply()

    /** 1 (strict: only clear music) .. 10 (loose: almost any sound). Default 5. */
    fun sensitivity(context: Context): Int =
        prefs(context).getInt(KEY_SENSITIVITY, 4).coerceIn(1, 10)

    fun setIntro(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_INTRO, on).apply()

    fun intro(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INTRO, true)

    fun setOnboarded(context: Context) =
        prefs(context).edit().putBoolean(KEY_ONBOARDED, true).apply()

    fun isOnboarded(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDED, false)

    // ---- Personal Glow Text: optional name/keyword that travels around the edge ----
    fun setPersonalText(context: Context, text: String) =
        prefs(context).edit().putString(KEY_PERSONAL_TEXT, text.take(40)).apply()

    fun personalText(context: Context): String =
        prefs(context).getString(KEY_PERSONAL_TEXT, "") ?: ""

    fun setPersonalTextEnabled(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_PERSONAL_TEXT_ON, on).apply()

    /** Off by default — this feature is optional, never forced on the user. */
    fun personalTextEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PERSONAL_TEXT_ON, false)

    fun setPersonalTextColor(context: Context, color: Int) =
        prefs(context).edit().putInt(KEY_PERSONAL_TEXT_COLOR, color).apply()

    fun personalTextColor(context: Context): Int =
        prefs(context).getInt(KEY_PERSONAL_TEXT_COLOR, Color.parseColor("#FFFFFF"))
}
