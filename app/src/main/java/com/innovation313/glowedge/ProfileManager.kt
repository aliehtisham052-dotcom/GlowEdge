package com.innovation313.glowedge

import android.content.Context
import android.graphics.Color

data class Profile(
    val name: String,
    val colorStart: Int,
    val colorEnd: Int
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

    val all = listOf(
        GlowStyle(GLOW_LINE, "Glow Line", "Smooth glowing frame", false),
        GlowStyle(SIDE_BARS, "Side Bars", "Equalizer bars on both sides", false),
        GlowStyle(BARS_AROUND, "Bars Around", "Bars dancing on every edge", false),
        GlowStyle(CORNER_GLOW, "Corner Glow", "Pulsing corner arcs", false),
        GlowStyle(EMBER, "Ember Flame", "Fiery edge flames", true),
        GlowStyle(CHASE, "Chase", "Lights racing around the screen", true)
    )
}

object ProfileManager {
    private const val PREFS = "glowedge_prefs"
    private const val KEY_THEME = "theme_index"
    private const val KEY_STYLE = "style_id"
    private const val KEY_THICKNESS = "thickness"
    private const val KEY_SPEED = "speed"
    private const val KEY_ONBOARDED = "onboarded"

    val themes = listOf(
        Profile("Neon", Color.parseColor("#00E5FF"), Color.parseColor("#7C4DFF")),
        Profile("Sunset", Color.parseColor("#FF6D00"), Color.parseColor("#D500F9")),
        Profile("Ocean", Color.parseColor("#00B0FF"), Color.parseColor("#00E676")),
        Profile("Royal Gold", Color.parseColor("#FFD54F"), Color.parseColor("#3949AB"))
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setTheme(context: Context, index: Int) =
        prefs(context).edit().putInt(KEY_THEME, index).apply()

    fun themeIndex(context: Context): Int =
        prefs(context).getInt(KEY_THEME, 0).coerceIn(0, themes.size - 1)

    fun theme(context: Context): Profile = themes[themeIndex(context)]

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

    fun setOnboarded(context: Context) =
        prefs(context).edit().putBoolean(KEY_ONBOARDED, true).apply()

    fun isOnboarded(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDED, false)
}
