package com.innovation313.glowedge

import android.content.Context
import android.graphics.Color

/** A color theme for the glow. */
data class Theme(
    val name: String,
    val start: Int,
    val end: Int,
    val rainbow: Boolean = false
)

/** A visualizer style. */
data class Style(val id: Int, val name: String, val tagline: String)

/** All glow styles (11). */
object Styles {
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

    val all = listOf(
        Style(GLOW_LINE, "Glow Line", "Smooth glowing frame"),
        Style(SIDE_BARS, "Side Bars", "Equalizer bars on both sides"),
        Style(BARS_AROUND, "Bars Around", "Bars dancing on every edge"),
        Style(CORNER_GLOW, "Corner Glow", "Pulsing corner arcs"),
        Style(EMBER, "Ember Flame", "Fiery edge flames"),
        Style(CHASE, "Chase", "Lights racing around the screen"),
        Style(PULSE, "Pulse", "The whole edge breathes with the beat"),
        Style(DOTS, "Dots", "Glowing dots bouncing to the music"),
        Style(AURORA, "Aurora", "Flowing northern-lights waves"),
        Style(COMET, "Comet", "Shooting lights with glowing tails"),
        Style(RIPPLE, "Ripple", "Rings that ripple on every beat")
    )

    fun accent(id: Int): Int = when (id) {
        GLOW_LINE -> Color.parseColor("#00E5FF")
        SIDE_BARS -> Color.parseColor("#7C4DFF")
        BARS_AROUND -> Color.parseColor("#00E676")
        CORNER_GLOW -> Color.parseColor("#FFD54F")
        EMBER -> Color.parseColor("#FF6D00")
        CHASE -> Color.parseColor("#FF1744")
        PULSE -> Color.parseColor("#00E5FF")
        DOTS -> Color.parseColor("#FFD54F")
        AURORA -> Color.parseColor("#1DE9B6")
        COMET -> Color.parseColor("#B388FF")
        RIPPLE -> Color.parseColor("#40C4FF")
        else -> Color.parseColor("#FFD54F")
    }
}

/** Central store for all user settings and choices. */
object Settings {
    private const val PREFS = "glowedge_prefs"

    private const val K_THEME = "theme"
    private const val K_STYLE = "style"
    private const val K_THICKNESS = "thickness"
    private const val K_SPEED = "speed"
    private const val K_INTENSITY = "intensity"
    private const val K_MUSIC_ONLY = "music_only"
    private const val K_SENSITIVITY = "sensitivity"
    private const val K_SAVER = "battery_saver"
    private const val K_AUTOSTART = "autostart"
    private const val K_NOTIF_GLOW = "notif_glow"
    private const val K_ONBOARDED = "onboarded"

    val themes = listOf(
        Theme("Spectrum (All Colors)", Color.parseColor("#FF0000"), Color.parseColor("#AA00FF"), rainbow = true),
        Theme("Neon", Color.parseColor("#00E5FF"), Color.parseColor("#7C4DFF")),
        Theme("Sunset", Color.parseColor("#FF6D00"), Color.parseColor("#D500F9")),
        Theme("Ocean", Color.parseColor("#00B0FF"), Color.parseColor("#00E676")),
        Theme("Royal Gold", Color.parseColor("#FFD54F"), Color.parseColor("#3949AB")),
        Theme("Fire", Color.parseColor("#FF1744"), Color.parseColor("#FFD600")),
        Theme("Ice", Color.parseColor("#00E5FF"), Color.parseColor("#E1F5FE"))
    )

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Theme
    fun themeIndex(c: Context) = p(c).getInt(K_THEME, 0).coerceIn(0, themes.size - 1)
    fun setThemeIndex(c: Context, i: Int) = p(c).edit().putInt(K_THEME, i).apply()
    fun theme(c: Context) = themes[themeIndex(c)]

    // Style
    fun styleId(c: Context) = p(c).getInt(K_STYLE, Styles.GLOW_LINE)
    fun setStyleId(c: Context, id: Int) = p(c).edit().putInt(K_STYLE, id).apply()

    // Sliders
    fun thickness(c: Context) = p(c).getInt(K_THICKNESS, 16).coerceIn(6, 40)
    fun setThickness(c: Context, v: Int) = p(c).edit().putInt(K_THICKNESS, v).apply()

    fun speed(c: Context) = p(c).getInt(K_SPEED, 10).coerceIn(2, 20)
    fun setSpeed(c: Context, v: Int) = p(c).edit().putInt(K_SPEED, v).apply()

    fun intensity(c: Context) = p(c).getInt(K_INTENSITY, 10).coerceIn(3, 20)
    fun setIntensity(c: Context, v: Int) = p(c).edit().putInt(K_INTENSITY, v).apply()

    // Music-only detection
    fun musicOnly(c: Context) = p(c).getBoolean(K_MUSIC_ONLY, true)
    fun setMusicOnly(c: Context, on: Boolean) = p(c).edit().putBoolean(K_MUSIC_ONLY, on).apply()

    fun sensitivity(c: Context) = p(c).getInt(K_SENSITIVITY, 4).coerceIn(1, 10)
    fun setSensitivity(c: Context, v: Int) = p(c).edit().putInt(K_SENSITIVITY, v).apply()

    // Toggles
    fun batterySaver(c: Context) = p(c).getBoolean(K_SAVER, false)
    fun setBatterySaver(c: Context, on: Boolean) = p(c).edit().putBoolean(K_SAVER, on).apply()

    fun autostart(c: Context) = p(c).getBoolean(K_AUTOSTART, false)
    fun setAutostart(c: Context, on: Boolean) = p(c).edit().putBoolean(K_AUTOSTART, on).apply()

    fun notifGlow(c: Context) = p(c).getBoolean(K_NOTIF_GLOW, false)
    fun setNotifGlow(c: Context, on: Boolean) = p(c).edit().putBoolean(K_NOTIF_GLOW, on).apply()

    // Onboarding
    fun onboarded(c: Context) = p(c).getBoolean(K_ONBOARDED, false)
    fun setOnboarded(c: Context) = p(c).edit().putBoolean(K_ONBOARDED, true).apply()
}
