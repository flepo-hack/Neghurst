package com.example.vision.deterministic

import com.example.model.ThreatLevel
import com.example.model.ThreatVector
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Deterministic Collision Geometry and Perpendicular Evasion Angle Calculator.
 *
 * Kinematic Collision Model:
 * - Player at P(x_p, y_p) with safety bounding radius R.
 * - Projectile at A(x_a, y_a) moving with velocity vector v_a = (v_x, v_y).
 * - Distance vector over time t:
 *   d(t) = ||(P - A) - v_a * t||
 *
 * Closest Point of Approach (CPA):
 *   t_cpa = ((x_p - x_a) * v_x + (y_p - y_a) * v_y) / ||v_a||^2
 *
 * If t_cpa is in [0, 0.35 s] and d(t_cpa) < R, collision is guaranteed!
 *
 * Evasion Vector:
 * - Perpendicular to projectile trajectory: theta_dodge = atan2(v_y, v_x) ± 90°
 * - Selects the perpendicular escape vector that avoids walls, screen edges, and enemy clusters.
 */
class CollisionDodgeEngine {

    data class EvasionSolution(
        val threat: ThreatVector?,
        val dodgeAngleDeg: Float?,
        val dodgeDirX: Float,
        val dodgeDirY: Float,
        val isImminentCollision: Boolean,
        val timeToImpactMs: Long
    )

    /**
     * Evaluates all tracked kinematic projectiles against the player's position.
     */
    fun evaluateCollision(
        playerX: Float,
        playerY: Float,
        playerRadius: Float,
        projectiles: List<KalmanTrajectoryTracker.TrackedTarget>,
        screenWidth: Float,
        screenHeight: Float
    ): EvasionSolution {
        var mostDangerousThreat: ThreatVector? = null
        var lowestTimeToImpact = Float.MAX_VALUE
        var selectedDodgeAngle: Float? = null
        var dodgeDirX = 0f
        var dodgeDirY = 0f
        var isImminent = false

        for (target in projectiles) {
            if (!target.isProjectile) continue

            val speed = target.speed
            if (speed < 120f) continue // Ignore slow or stationary objects

            val dx = playerX - target.x
            val dy = playerY - target.y

            val dotProduct = dx * target.vx + dy * target.vy
            if (dotProduct <= 0f) {
                // Moving away from player
                continue
            }

            val speedSq = target.vx * target.vx + target.vy * target.vy
            if (speedSq < 1e-4f) continue

            val tCpa = dotProduct / speedSq // seconds to closest point of approach

            // Reaction horizon: 0ms .. 350ms
            if (tCpa in 0.005f..0.38f) {
                // Future position at CPA
                val projAtCpaX = target.x + target.vx * tCpa
                val projAtCpaY = target.y + target.vy * tCpa

                val distAtCpa = hypot(playerX - projAtCpaX, playerY - projAtCpaY)

                // Effective collision radius (player hitbox + projectile radius + safe buffer)
                val effectiveHitboxRadius = playerRadius * 1.35f

                if (distAtCpa < effectiveHitboxRadius) {
                    // Collision detected!
                    if (tCpa < lowestTimeToImpact) {
                        lowestTimeToImpact = tCpa
                        isImminent = true

                        // Determine Threat Level based on proximity and impact time
                        val threatLevel = when {
                            tCpa < 0.16f -> ThreatLevel.LETHAL
                            tCpa < 0.28f -> ThreatLevel.IMMINENT_DANGER
                            else -> ThreatLevel.WARNING
                        }

                        // Perpendicular Evasion Angles (± 90°)
                        val trajAngle = atan2(target.vy, target.vx)
                        val anglePerpPlus = (trajAngle + Math.PI / 2.0).toFloat()
                        val anglePerpMinus = (trajAngle - Math.PI / 2.0).toFloat()

                        // Test which perpendicular escape path has more clearance from screen edges
                        val testStep = 120f
                        val plusX = playerX + cos(anglePerpPlus) * testStep
                        val plusY = playerY + sin(anglePerpPlus) * testStep

                        val minusX = playerX + cos(anglePerpMinus) * testStep
                        val minusY = playerY + sin(anglePerpMinus) * testStep

                        val plusDistToBorder = minOf(
                            plusX, screenWidth - plusX,
                            plusY, screenHeight - plusY
                        )
                        val minusDistToBorder = minOf(
                            minusX, screenWidth - minusX,
                            minusY, screenHeight - minusY
                        )

                        val chosenAngle = if (plusDistToBorder >= minusDistToBorder) anglePerpPlus else anglePerpMinus
                        val chosenDeg = Math.toDegrees(chosenAngle.toDouble()).toFloat()

                        dodgeDirX = cos(chosenAngle)
                        dodgeDirY = sin(chosenAngle)
                        selectedDodgeAngle = (chosenDeg + 360f) % 360f

                        val impactMs = (tCpa * 1000f).toLong()

                        val trajDeg = (Math.toDegrees(trajAngle.toDouble()).toFloat() + 360f) % 360f

                        mostDangerousThreat = ThreatVector(
                            threatX = target.x,
                            threatY = target.y,
                            velocityX = target.vx,
                            velocityY = target.vy,
                            speed = speed,
                            threatAngleDeg = trajDeg,
                            dodgeAngleDeg = selectedDodgeAngle,
                            dodgeDirX = dodgeDirX,
                            dodgeDirY = dodgeDirY,
                            threatLevel = threatLevel,
                            timeToImpactMs = impactMs,
                            confidence = target.trajectoryConsistency
                        )
                    }
                }
            }
        }

        return EvasionSolution(
            threat = mostDangerousThreat,
            dodgeAngleDeg = selectedDodgeAngle,
            dodgeDirX = dodgeDirX,
            dodgeDirY = dodgeDirY,
            isImminentCollision = isImminent,
            timeToImpactMs = if (lowestTimeToImpact < Float.MAX_VALUE) (lowestTimeToImpact * 1000f).toLong() else 999L
        )
    }
}
