package com.example.model

/**
 * Live engine state, published once per second for logging and for the Compose
 * status screen.
 *
 * The fields are honest about capability: [nativeVisionAvailable] is false when
 * the compiled C++ engine is not in the APK, and [anchorsCalibratedFor] spells
 * out the display geometry the calibration belongs to so a stale calibration is
 * visible rather than silently substituted.
 */
data class DetectionStats(
    val isRunning: Boolean = false,
    val fps: Int = 0,
    val frameCount: Long = 0,
    val droppedFrames: Long = 0,
    val threatsDetected: Int = 0,
    val dodgesExecuted: Int = 0,
    val lastDodgeAngleDeg: Float = 0f,
    val lastDodgeTimestamp: Long = 0L,
    val currentThreatLevel: ThreatLevel = ThreatLevel.SAFE,
    /** Cost of the last native pipeline run, in milliseconds. */
    val visionMillis: Double = 0.0,
    val nativeVisionAvailable: Boolean = false,
    val anchorsCalibrated: Boolean = false,
    val anchorsCalibratedFor: String = "",
    val isJoystickCalibrated: Boolean = false,
    val isPlayerCalibrated: Boolean = false,
    val autoDodgeArmed: Boolean = false,
    val accessibilityReady: Boolean = false,
    val activeGamePackage: String = "",
    val latestTacticalAdvice: String = "Idle."
)
