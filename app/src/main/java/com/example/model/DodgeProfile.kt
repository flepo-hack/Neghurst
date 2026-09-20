package com.example.model

data class DodgeProfile(
    val packageName: String = "default",
    val profileName: String = "Universal Game Profile",
    val joystickCenterX: Float = 0.22f, // Default bottom-left virtual stick
    val joystickCenterY: Float = 0.75f,
    val joystickRadius: Float = 140f, // in px
    val playerCenterX: Float = 0.50f, // Default screen center
    val playerCenterY: Float = 0.50f,
    val threatRadius: Float = 0.35f, // Scan zone radius around player (relative to screen width)
    val dodgeDurationMs: Long = 180L,
    val dodgeDistanceFactor: Float = 0.90f,
    val dodgeCooldownMs: Long = 280L,
    val sensitivity: Float = 0.70f,
    val autoDodgeEnabled: Boolean = true,
    val soundHapticEnabled: Boolean = true,
    val aiDeepVisionEnabled: Boolean = true
)
