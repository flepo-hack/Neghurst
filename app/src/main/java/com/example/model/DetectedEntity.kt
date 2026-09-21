package com.example.model

enum class EntityType {
    PLAYER,
    ENEMY,
    PROJECTILE,
    JOYSTICK,
    OBSTACLE
}

data class DetectedEntity(
    val type: EntityType,
    val x: Float,
    val y: Float,
    val radius: Float,
    val vx: Float = 0f,
    val vy: Float = 0f,
    val label: String = "",
    val confidence: Float = 1.0f
)
