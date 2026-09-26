package com.example.model

/**
 * Everything the dodge engine needs, in one persistable value.
 *
 * Only fields that the pipeline actually reads live here. The previous version
 * carried `threatRadius`, `dodgeDurationMs`, `sensitivity` and
 * `aiDeepVisionEnabled`, none of which were ever consulted by any code path -
 * they were placebo settings.
 */
data class DodgeProfile(
    /** Package name of the target game, or "default" for the universal profile. */
    val packageName: String = "default",
    val profileName: String = "Universal Game Profile",

    // --- calibration, stored normalised to the screen (0..1) so that they
    //     survive rotation, resolution changes and device swaps ---
    val joystickCenterX: Float = 0.20f,
    val joystickCenterY: Float = 0.78f,
    /** Joystick deflection radius in px, measured on the device it was set on. */
    val joystickRadius: Float = 190f,
    val playerCenterX: Float = 0.50f,
    val playerCenterY: Float = 0.50f,

    val anchorConfirmed: Boolean = false,
    val joystickConfirmed: Boolean = false,

    // --- dodger behaviour (all of these are read by the engine) ---

    /**
     * How long the stick stays deflected, in ms. This is the value that decides
     * how far the brawler actually travels. A 15 ms flick moves it ~0 tiles.
     */
    val dodgeHoldMs: Long = 220L,

    /** Fraction of the full joystick radius to deflect to. 1.0 = run speed. */
    val dodgeDeflection: Float = 0.92f,

    /** Minimum gap between two dodges, so a burst does not spam gestures. */
    val dodgeCooldownMs: Long = 320L,

    /**
     * Threat sensitivity 0..1. Scales the player hitbox used by the collision
     * test: higher = reacts earlier to near misses.
     */
    val sensitivity: Float = 0.70f,

    // --- vision tuning ---

    /** Brawl Stars tile size in screen px; derived per device if <= 0. */
    val tilePixels: Float = 0f,

    val autoDodgeEnabled: Boolean = true,
    val debugOverlayEnabled: Boolean = false,
    val hapticEnabled: Boolean = true
) {
    /** Player hitbox radius in px, derived from the tile size. */
    fun playerHitboxPx(tilePx: Float): Float {
        val t = if (tilePixels > 1f) tilePixels else tilePx
        return (0.42f * t).coerceIn(24f, 240f)
    }

    /**
     * Effective player hitbox after the sensitivity multiplier. At the default
     * 0.70 this is slightly larger than the visual body, which is what makes a
     * near miss worth reacting to.
     */
    fun effectiveHitboxPx(tilePx: Float): Float =
        playerHitboxPx(tilePx) * (0.75f + 0.55f * sensitivity.coerceIn(0f, 1f))

    companion object {
        const val DEFAULT_KEY = "default"
        const val BRAWL_STARS_PACKAGE = "com.supercell.brawlstars"

        fun brawlStars(): DodgeProfile = DodgeProfile(
            packageName = BRAWL_STARS_PACKAGE,
            profileName = "Brawl Stars",
            joystickCenterX = 0.20f,
            joystickCenterY = 0.78f,
            joystickRadius = 200f,
            playerCenterX = 0.50f,
            playerCenterY = 0.50f,
            dodgeHoldMs = 230L,
            dodgeDeflection = 0.95f,
            dodgeCooldownMs = 300L,
            sensitivity = 0.80f
        )

        /** Reads the stored profile for [packageName], seeding it if absent. */
        fun forPackage(packageName: String): DodgeProfile = when (packageName) {
            BRAWL_STARS_PACKAGE -> brawlStars()
            "" -> DodgeProfile()
            else -> DodgeProfile(packageName = packageName, profileName = "Universal")
        }
    }
}
