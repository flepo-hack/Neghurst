package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.model.DodgeProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RenderaPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("rendera_config", Context.MODE_PRIVATE)

    private val _currentProfile = MutableStateFlow(loadProfile())
    val currentProfile: StateFlow<DodgeProfile> = _currentProfile.asStateFlow()

    fun loadProfile(packageName: String = "default"): DodgeProfile {
        val prefix = if (packageName.isEmpty()) "default_" else "${packageName}_"
        return DodgeProfile(
            packageName = packageName,
            profileName = prefs.getString("${prefix}name", "Universal Game Profile") ?: "Universal Game Profile",
            joystickCenterX = prefs.getFloat("${prefix}joy_x", 0.22f),
            joystickCenterY = prefs.getFloat("${prefix}joy_y", 0.75f),
            joystickRadius = prefs.getFloat("${prefix}joy_radius", 140f),
            playerCenterX = prefs.getFloat("${prefix}player_x", 0.50f),
            playerCenterY = prefs.getFloat("${prefix}player_y", 0.50f),
            threatRadius = prefs.getFloat("${prefix}threat_radius", 0.35f),
            dodgeDurationMs = prefs.getLong("${prefix}dodge_duration", 180L),
            dodgeDistanceFactor = prefs.getFloat("${prefix}dodge_dist", 0.90f),
            dodgeCooldownMs = prefs.getLong("${prefix}dodge_cooldown", 280L),
            sensitivity = prefs.getFloat("${prefix}sensitivity", 0.70f),
            autoDodgeEnabled = prefs.getBoolean("${prefix}auto_dodge", true),
            soundHapticEnabled = prefs.getBoolean("${prefix}haptic", true),
            aiDeepVisionEnabled = prefs.getBoolean("${prefix}ai_vision", true)
        )
    }

    fun saveProfile(profile: DodgeProfile) {
        val prefix = if (profile.packageName.isEmpty()) "default_" else "${profile.packageName}_"
        prefs.edit().apply {
            putString("${prefix}name", profile.profileName)
            putFloat("${prefix}joy_x", profile.joystickCenterX)
            putFloat("${prefix}joy_y", profile.joystickCenterY)
            putFloat("${prefix}joy_radius", profile.joystickRadius)
            putFloat("${prefix}player_x", profile.playerCenterX)
            putFloat("${prefix}player_y", profile.playerCenterY)
            putFloat("${prefix}threat_radius", profile.threatRadius)
            putLong("${prefix}dodge_duration", profile.dodgeDurationMs)
            putFloat("${prefix}dodge_dist", profile.dodgeDistanceFactor)
            putLong("${prefix}dodge_cooldown", profile.dodgeCooldownMs)
            putFloat("${prefix}sensitivity", profile.sensitivity)
            putBoolean("${prefix}auto_dodge", profile.autoDodgeEnabled)
            putBoolean("${prefix}haptic", profile.soundHapticEnabled)
            putBoolean("${prefix}ai_vision", profile.aiDeepVisionEnabled)
            apply()
        }
        _currentProfile.value = profile
    }

    fun updateJoystickCalibration(centerX: Float, centerY: Float, radius: Float) {
        prefs.edit().putBoolean("joystick_calibrated", true).apply()
        val updated = _currentProfile.value.copy(
            joystickCenterX = centerX,
            joystickCenterY = centerY,
            joystickRadius = radius
        )
        saveProfile(updated)
    }

    fun isJoystickCalibrated(): Boolean {
        return prefs.getBoolean("joystick_calibrated", false)
    }

    fun resetJoystickCalibration() {
        prefs.edit().putBoolean("joystick_calibrated", false).apply()
    }

    fun updatePlayerCalibration(centerX: Float, centerY: Float) {
        val updated = _currentProfile.value.copy(
            playerCenterX = centerX,
            playerCenterY = centerY
        )
        saveProfile(updated)
    }

    fun toggleAutoDodge(enabled: Boolean) {
        val updated = _currentProfile.value.copy(autoDodgeEnabled = enabled)
        saveProfile(updated)
    }

    fun updateSensitivity(sensitivity: Float) {
        val updated = _currentProfile.value.copy(sensitivity = sensitivity.coerceIn(0.1f, 1.0f))
        saveProfile(updated)
    }
}
