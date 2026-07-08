package com.innovation313.glowedge

import android.content.Context
import android.graphics.Color

data class Profile(
    val name: String,
    val colorStart: Int,
    val colorEnd: Int,
    val thickness: Float,
    val speed: Float
)

object ProfileManager {
    private const val PREFS = "glowedge_prefs"
    private const val KEY_SELECTED = "selected_profile"
    private const val KEY_ONBOARDED = "onboarded"

    val profiles = listOf(
        Profile("Neon", Color.parseColor("#00E5FF"), Color.parseColor("#7C4DFF"), 16f, 1.0f),
        Profile("Sunset", Color.parseColor("#FF6D00"), Color.parseColor("#D500F9"), 16f, 0.8f),
        Profile("Ocean", Color.parseColor("#00B0FF"), Color.parseColor("#00E676"), 14f, 0.7f),
        Profile("Royal Gold", Color.parseColor("#FFD54F"), Color.parseColor("#3949AB"), 18f, 0.9f)
    )

    fun select(context: Context, index: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SELECTED, index).apply()
    }

    fun selectedIndex(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SELECTED, 0)

    fun selectedProfile(context: Context): Profile =
        profiles[selectedIndex(context).coerceIn(0, profiles.size - 1)]

    fun setOnboarded(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ONBOARDED, true).apply()
    }

    fun isOnboarded(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ONBOARDED, false)
}
