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
    private const val KEY_FORCE_GLOW = "force_glow"
    private const val KEY_WALLPAPER_TEMPLATE = "wallpaper_template"

    val themes = listOf(
        // Vivid, distinct designer palette: each theme is a clearly different hue pair,
        // pushed to high saturation so it reads brightly on screen.
        Profile("Spectrum (All Colors)", Color.parseColor("#FF4D6D"), Color.parseColor("#7C4DFF"), rainbow = true),
        Profile("Neon", Color.parseColor("#00E5FF"), Color.parseColor("#D500F9")),
        Profile("Sunset", Color.parseColor("#FF6B35"), Color.parseColor("#FF2D95")),
        Profile("Ocean", Color.parseColor("#00B4FF"), Color.parseColor("#00E6A8")),
        Profile("Royal Gold", Color.parseColor("#FFD54F"), Color.parseColor("#5B2EFF")),
        Profile("Fire", Color.parseColor("#FF3B3B"), Color.parseColor("#FFC93B")),
        Profile("Ice", Color.parseColor("#4DEEFF"), Color.parseColor("#E8FBFF")),
        Profile("Rose Calm", Color.parseColor("#FF4FA3"), Color.parseColor("#C061FF")),
        Profile("Forest Calm", Color.parseColor("#9CE521"), Color.parseColor("#0B8457")),
        Profile("Mirror Rain", Color.parseColor("#B8D8FF"), Color.parseColor("#3B5BA5")),
        Profile("Velvet Night", Color.parseColor("#9B4DFF"), Color.parseColor("#2D1B69")),
        Profile("Solar Flare", Color.parseColor("#FF9500"), Color.parseColor("#FF1744")),
        Profile("Diamond Dust", Color.parseColor("#E0F7FF"), Color.parseColor("#4DA6E0"))
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setTheme(context: Context, index: Int) =
        prefs(context).edit().putInt(KEY_THEME, index).apply()

    fun themeIndex(context: Context): Int =
        prefs(context).getInt(KEY_THEME, 0).coerceIn(0, themes.size)

    fun customStart(context: Context): Int =
        prefs(context).getInt(KEY_CUSTOM_START, Color.parseColor("#00E5FF"))

    fun customEnd(context: Context): Int =
        prefs(context).getInt(KEY_CUSTOM_END, Color.parseColor("#D500F9"))

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
        prefs(context).getInt(KEY_SENSITIVITY, 2).coerceIn(1, 10)

    fun setIntro(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_INTRO, on).apply()

    fun intro(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INTRO, true)

    fun setOnboarded(context: Context) =
        prefs(context).edit().putBoolean(KEY_ONBOARDED, true).apply()

    fun isOnboarded(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDED, false)

    // ---- Manual Force Glow override: one-tap from the notification, bypasses
    // automatic music/naat detection entirely so the user always has a reliable
    // fallback if the auto-detector gets something wrong. ----
    fun setForceGlow(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_FORCE_GLOW, on).apply()

    fun forceGlow(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FORCE_GLOW, false)

    fun setWallpaperTemplate(context: Context, template: Int) =
        prefs(context).edit().putInt(KEY_WALLPAPER_TEMPLATE, template).apply()

    fun wallpaperTemplate(context: Context): Int =
        prefs(context).getInt(KEY_WALLPAPER_TEMPLATE, WallpaperGenerator.TEMPLATE_BORDER)
            .coerceIn(0, WallpaperGenerator.TEMPLATE_COUNT - 1)
}
