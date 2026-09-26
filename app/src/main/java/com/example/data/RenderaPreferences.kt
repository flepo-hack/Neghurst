package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.vision.AnchorCalibrator
import com.example.vision.Anchors
import com.example.vision.ScreenOrientation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted configuration and calibration.
 *
 * ## Two real bugs fixed here
 *
 * **State was not shared.** `MainActivity` and `RenderaOverlayService` each
 * constructed their own `RenderaPreferences`, and `saveProfile` only updated the
 * writer's own `MutableStateFlow`. The Compose slider, the in-game menu and the
 * vision engine could therefore disagree until the process was restarted. There
 * is now one process-wide instance, published as [instance], and every writer
 * goes through it.
 *
 * **Calibration was keyed on a flat name and stored in pixels.** Joystick and
 * player positions shared one key namespace, the stick radius was a raw pixel
 * value, and there was no record of which display geometry the calibration was
 * taken on. Rotating the device, or switching between displays, therefore left
 * stale anchors that the detector then silently replaced with hard-coded
 * fractions of the screen. Anchors are now [Anchors] values: normalised,
 * orientation-aware, and explicitly invalidated when the geometry changes.
 */
class RenderaPreferences private constructor(context: Context) {

    companion object {
        private const val FILE = "rendera_config"

        @Volatile
        private var singleton: RenderaPreferences? = null

        /** Process-wide instance. Safe to call from any thread. */
        fun get(context: Context): RenderaPreferences =
            singleton ?: synchronized(this) {
                singleton ?: RenderaPreferences(context.applicationContext).also {
                    singleton = it
                }
            }

        /** Existing singleton without creating one, for teardown paths. */
        fun peek(): RenderaPreferences? = singleton
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _anchors = MutableStateFlow(loadAnchors())
    val anchors: StateFlow<Anchors> = _anchors.asStateFlow()

    private val _autoDodgeEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_DODGE, true))
    val autoDodgeEnabled: StateFlow<Boolean> = _autoDodgeEnabled.asStateFlow()

    private val _debugOverlayEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_DEBUG_OVERLAY, false))
    val debugOverlayEnabled: StateFlow<Boolean> = _debugOverlayEnabled.asStateFlow()

    private val _sensitivity = MutableStateFlow(prefs.getFloat(KEY_SENSITIVITY, 0.70f))
    val sensitivity: StateFlow<Float> = _sensitivity.asStateFlow()

    private val _dodgeCooldownMs = MutableStateFlow(prefs.getLong(KEY_DODGE_COOLDOWN, 320L))
    val dodgeCooldownMs: StateFlow<Long> = _dodgeCooldownMs.asStateFlow()

    private val _targetPackage = MutableStateFlow(prefs.getString(KEY_TARGET_PACKAGE, "") ?: "")
    val targetPackage: StateFlow<String> = _targetPackage.asStateFlow()

    private val _targetGameName = MutableStateFlow(prefs.getString(KEY_TARGET_GAME, "Universal") ?: "Universal")
    val targetGameName: StateFlow<String> = _targetGameName.asStateFlow()

    // -----------------------------------------------------------------------
    // Anchors
    // -----------------------------------------------------------------------

    private fun loadAnchors(): Anchors {
        val w = prefs.getInt(KEY_ANCHOR_W, 0)
        val h = prefs.getInt(KEY_ANCHOR_H, 0)
        val calibrated = prefs.getBoolean(KEY_ANCHORS_CALIBRATED, false)
        val stored = Anchors(
            joystickX = prefs.getFloat(KEY_JOY_X, 0.17f),
            joystickY = prefs.getFloat(KEY_JOY_Y, 0.76f),
            playerX = prefs.getFloat(KEY_PLAYER_X, 0.50f),
            playerY = prefs.getFloat(KEY_PLAYER_Y, 0.52f),
            joystickRadiusNorm = prefs.getFloat(KEY_JOY_RADIUS_NORM, 0.13f),
            calibrated = calibrated,
            calibratedForWidth = w,
            calibratedForHeight = h
        )
        // Without a stored geometry there is nothing to validate against, so the
        // orientation-appropriate default is the only honest answer.
        return if (w <= 0 || h <= 0) Anchors.defaultFor(w, h) else stored
    }

    /**
     * Anchors that are valid for the given display.
     *
     * A calibration taken in landscape is **not** reinterpreted in portrait:
     * rotating the device does not move the on-screen stick to the same relative
     * spot, so pretending it did is exactly the class of bug this class exists
     * to prevent. The caller gets the orientation default with
     * `calibrated == false` and is expected to ask the user to re-calibrate.
     */
    fun anchorsFor(displayWidth: Int, displayHeight: Int): Anchors =
        AnchorCalibrator.adaptToDisplay(_anchors.value, displayWidth, displayHeight)

    fun currentAnchors(): Anchors = _anchors.value

    fun setAnchors(value: Anchors) {
        prefs.edit().apply {
            putFloat(KEY_JOY_X, value.joystickX)
            putFloat(KEY_JOY_Y, value.joystickY)
            putFloat(KEY_JOY_RADIUS_NORM, value.joystickRadiusNorm)
            putFloat(KEY_PLAYER_X, value.playerX)
            putFloat(KEY_PLAYER_Y, value.playerY)
            putInt(KEY_ANCHOR_W, value.calibratedForWidth)
            putInt(KEY_ANCHOR_H, value.calibratedForHeight)
            putBoolean(KEY_ANCHORS_CALIBRATED, value.calibrated)
        }.apply()
        _anchors.value = value
    }

    /** Records a calibration touch for [target] taken at screen pixel (x, y). */
    fun applyCalibrationTouch(
        target: com.example.vision.AnchorTarget,
        screenX: Float,
        screenY: Float,
        displayWidth: Int,
        displayHeight: Int
    ): Anchors {
        val updated = AnchorCalibrator.applyTouch(
            anchors = _anchors.value,
            target = target,
            screenX = screenX,
            screenY = screenY,
            displayWidth = displayWidth,
            displayHeight = displayHeight
        )
        setAnchors(updated)
        return updated
    }

    fun orientationOf(displayWidth: Int, displayHeight: Int): ScreenOrientation =
        ScreenOrientation.of(displayWidth, displayHeight)

    // -----------------------------------------------------------------------
    // Toggles and tuning
    // -----------------------------------------------------------------------

    fun setDebugOverlayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_OVERLAY, enabled).apply()
        _debugOverlayEnabled.value = enabled
    }

    fun setAutoDodge(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DODGE, enabled).apply()
        _autoDodgeEnabled.value = enabled
    }

    fun setSensitivity(value: Float) {
        val clamped = value.coerceIn(0.1f, 1f)
        prefs.edit().putFloat(KEY_SENSITIVITY, clamped).apply()
        _sensitivity.value = clamped
    }

    fun setDodgeCooldownMs(value: Long) {
        // A cooldown shorter than the gesture itself would queue dispatches the
        // system is guaranteed to drop, so it is floored at the gesture length.
        val clamped = value.coerceIn(180L, 1200L)
        prefs.edit().putLong(KEY_DODGE_COOLDOWN, clamped).apply()
        _dodgeCooldownMs.value = clamped
    }

    fun setTarget(packageName: String, gameName: String) {
        prefs.edit()
            .putString(KEY_TARGET_PACKAGE, packageName)
            .putString(KEY_TARGET_GAME, gameName)
            .apply()
        _targetPackage.value = packageName
        _targetGameName.value = gameName
    }

    /** Wipes calibration but keeps tuning, so the user can start clean. */
    fun clearCalibration() {
        setAnchors(Anchors.defaultFor(0, 0))
    }
}

private const val KEY_ANCHORS_CALIBRATED = "anchors_calibrated"
private const val KEY_ANCHOR_W = "anchor_width"
private const val KEY_ANCHOR_H = "anchor_height"
private const val KEY_JOY_X = "joy_x"
private const val KEY_JOY_Y = "joy_y"
private const val KEY_JOY_RADIUS_NORM = "joy_radius_norm"
private const val KEY_PLAYER_X = "player_x"
private const val KEY_PLAYER_Y = "player_y"
private const val KEY_DEBUG_OVERLAY = "debug_overlay_enabled"
private const val KEY_AUTO_DODGE = "auto_dodge"
private const val KEY_SENSITIVITY = "sensitivity"
private const val KEY_DODGE_COOLDOWN = "dodge_cooldown_ms"
private const val KEY_TARGET_PACKAGE = "target_package"
private const val KEY_TARGET_GAME = "target_game"
