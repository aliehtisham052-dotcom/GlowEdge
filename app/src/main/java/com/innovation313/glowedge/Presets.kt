package com.innovation313.glowedge

/**
 * One-tap "looks": each preset bundles a glow style + colour theme + motion settings,
 * so a new user gets a beautiful result in a single tap instead of hunting through
 * styles, themes and sliders separately. All names and combinations are our own.
 *
 * themeIndex refers to ProfileManager.themes order; styleId to GlowStyles constants.
 * speed/intensity are the same 1..10 scale the sliders use; thickness matches its slider.
 */
data class GlowPreset(
    val name: String,
    val styleId: Int,
    val themeIndex: Int,
    val speed: Int,
    val intensity: Int,
    val thickness: Int
)

object Presets {
    val all = listOf(
        // The signature look — gold on the Badr-inspired royal palette.
        GlowPreset("Golden Badr",   GlowStyles.GLOW_LINE,  4, 5, 8, 6),
        // Full spectrum flowing comet — the showpiece.
        GlowPreset("Spectrum Flow", GlowStyles.COMET,      0, 6, 8, 6),
        // Electric cyan/magenta pulse.
        GlowPreset("Neon Pulse",    GlowStyles.PULSE,      1, 7, 9, 7),
        // Calm teal aurora waves.
        GlowPreset("Aurora Sky",    GlowStyles.AURORA,     3, 4, 7, 6),
        // Fiery ember particles rising.
        GlowPreset("Ember Storm",   GlowStyles.EMBER,      5, 8, 9, 7),
        // Cool blue rain over neon lines.
        GlowPreset("Mirror Rain",   GlowStyles.NEON_LINES, 9, 5, 7, 5),
        // Deep purple ripples, slow and soft.
        GlowPreset("Velvet Ripple", GlowStyles.RIPPLE,    10, 3, 6, 6),
        // Icy chase around the frame.
        GlowPreset("Ice Chase",     GlowStyles.CHASE,      6, 7, 7, 5)
    )
}
