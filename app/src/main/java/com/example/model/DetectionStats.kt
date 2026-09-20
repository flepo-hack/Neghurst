package com.example.model

data class DetectionStats(
    val isRunning: Boolean = false,
    val fps: Int = 0,
    val frameCount: Long = 0,
    val threatsDetected: Int = 0,
    val dodgesExecuted: Int = 0,
    val lastDodgeAngleDeg: Float = 0f,
    val lastDodgeTimestamp: Long = 0L,
    val currentThreatLevel: ThreatLevel = ThreatLevel.SAFE,
    val latencyMs: Long = 0L,
    val isJoystickCalibrated: Boolean = false,
    val isPlayerCalibrated: Boolean = false,
    val activeGamePackage: String = "",
    val latestTacticalAdvice: String = "Tactical AI standby - Scan active."
)
