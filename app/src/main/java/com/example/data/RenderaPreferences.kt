package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.model.DodgeProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistence for the dodge profile.
 *
 * Profiles are stored per target package, which the previous version never did:
 * `EXTRA_PACKAGE_NAME` was passed to the service and then dropped on the floor,
 * so selecting "Brawl Stars" loaded the generic profile and none of the
 * Brawl Stars tuning was ever applied.
 */
class RenderaPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("rendera_config", Context.MODE_PRIVATE)

    private val _profile = MutableStateFlow(load(DodgeProfile.DEFAULT_KEY))
    val currentProfile: StateFlow<DodgeProfile> = _profile.asStateFlow()

    /** The profile the running service is actually using. */
    @Volatile
    var activeKey: String = DodgeProfile.DEFAULT_KEY
        private set

    private fun keyFor(packageName: String): String =
        if (packageName.isBlank() || packageName == DodgeProfile.DEFAULT_KEY) {
            DodgeProfile.DEFAULT_KEY
        } else {
            packageName
        }

    fun load(packageName: String = activeKey): DodgeProfile {
        val k = keyFor(packageName)
        val seed = DodgeProfile.forPackage(k)
        return DodgeProfile(
            packageName = seed.packageName,
            profileName = prefs.getString("$k.name", seed.profileName) ?: seed.profileName,
            joystickCenterX = prefs.getFloat("$k.joy_x", seed.joystickCenterX),
            joystickCenterY = prefs.getFloat("$k.joy_y", seed.joystickCenterY),
            joystickRadius = prefs.getFloat("$k.joy_r", seed.joystickRadius),
            playerCenterX = prefs.getFloat("$k.player_x", seed.playerCenterX),
            playerCenterY = prefs.getFloat("$k.player_y", seed.playerCenterY),
            anchorConfirmed = prefs.getBoolean("$k.anchor_ok", false),
            joystickConfirmed = prefs.getBoolean("$k.joy_ok", false),
            dodgeHoldMs = prefs.getLong("$k.hold_ms", seed.dodgeHoldMs),
            dodgeDeflection = prefs.getFloat("$k.deflect", seed.dodgeDeflection),
            dodgeCooldownMs = prefs.getLong("$k.cooldown", seed.dodgeCooldownMs),
            sensitivity = prefs.getFloat("$k.sensitivity", seed.sensitivity),
            tilePixels = prefs.getFloat("$k.tile_px", 0f),
            autoDodgeEnabled = prefs.getBoolean("$k.auto_dodge", seed.autoDodgeEnabled),
            debugOverlayEnabled = prefs.getBoolean("$k.debug_overlay", seed.debugOverlayEnabled),
            hapticEnabled = prefs.getBoolean("$k.haptic", seed.hapticEnabled)
        )
    }

    fun activate(packageName: String) {
        activeKey = keyFor(packageName)
        _profile.value = load(activeKey)
    }

    fun save(profile: DodgeProfile) {
        val k = keyFor(profile.packageName)
        prefs.edit().apply {
            putString("$k.name", profile.profileName)
            putFloat("$k.joy_x", profile.joystickCenterX)
            putFloat("$k.joy_y", profile.joystickCenterY)
            putFloat("$k.joy_r", profile.joystickRadius)
            putFloat("$k.player_x", profile.playerCenterX)
            putFloat("$k.player_y", profile.playerCenterY)
            putBoolean("$k.anchor_ok", profile.anchorConfirmed)
            putBoolean("$k.joy_ok", profile.joystickConfirmed)
            putLong("$k.hold_ms", profile.dodgeHoldMs)
            putFloat("$k.deflect", profile.dodgeDeflection)
            putLong("$k.cooldown", profile.dodgeCooldownMs)
            putFloat("$k.sensitivity", profile.sensitivity)
            putFloat("$k.tile_px", profile.tilePixels)
            putBoolean("$k.auto_dodge", profile.autoDodgeEnabled)
            putBoolean("$k.debug_overlay", profile.debugOverlayEnabled)
            putBoolean("$k.haptic", profile.hapticEnabled)
            apply()
        }
        if (keyFor(profile.packageName) == activeKey) _profile.value = profile
    }

    private fun update(transform: (DodgeProfile) -> DodgeProfile) {
        val next = transform(_profile.value)
        save(next)
    }

    fun setJoystick(xNorm: Float, yNorm: Float, radiusPx: Float, confirmed: Boolean = true) = update {
        it.copy(
            joystickCenterX = xNorm.coerceIn(0.02f, 0.98f),
            joystickCenterY = yNorm.coerceIn(0.02f, 0.98f),
            joystickRadius = radiusPx.coerceIn(40f, 1200f),
            joystickConfirmed = confirmed
        )
    }

    fun setPlayer(xNorm: Float, yNorm: Float, confirmed: Boolean = true) = update {
        it.copy(
            playerCenterX = xNorm.coerceIn(0.02f, 0.98f),
            playerCenterY = yNorm.coerceIn(0.02f, 0.98f),
            anchorConfirmed = confirmed
        )
    }

    fun setAutoDodge(enabled: Boolean) = update { it.copy(autoDodgeEnabled = enabled) }

    fun setDebugOverlay(enabled: Boolean) = update { it.copy(debugOverlayEnabled = enabled) }

    fun setSensitivity(value: Float) = update { it.copy(sensitivity = value.coerceIn(0f, 1f)) }

    fun setDodgeHoldMs(ms: Long) = update { it.copy(dodgeHoldMs = ms.coerceIn(60L, 900L)) }

    fun setTilePixels(px: Float) = update { it.copy(tilePixels = px) }

    /** True only when both anchors were explicitly confirmed. */
    fun isCalibrated(): Boolean {
        val p = _profile.value
        return p.anchorConfirmed && p.joystickConfirmed
    }

    fun clearCalibration() = update {
        it.copy(anchorConfirmed = false, joystickConfirmed = false)
    }
}
