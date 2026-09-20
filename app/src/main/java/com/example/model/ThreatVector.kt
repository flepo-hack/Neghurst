package com.example.model

data class ThreatVector(
    val threatX: Float,
    val threatY: Float,
    val velocityX: Float,
    val velocityY: Float,
    val speed: Float,
    val threatAngleDeg: Float,
    val dodgeAngleDeg: Float,
    val dodgeDirX: Float,
    val dodgeDirY: Float,
    val threatLevel: ThreatLevel,
    val timeToImpactMs: Long,
    val confidence: Float
)

enum class ThreatLevel {
    SAFE,
    WARNING,
    IMMINENT_DANGER,
    LETHAL
}
