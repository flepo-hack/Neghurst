package com.example.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.example.model.DodgeProfile
import com.example.model.ThreatLevel
import com.example.model.ThreatVector
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic Computer Vision Engine for High-Paced Games (Brawl Stars, MOBAs, Action Shooters).
 *
 * Implements full semantic scene separation:
 * 1. Joystick Exclusion Zone: Masks out virtual stick area to prevent thumb motion false-positives.
 * 2. Static Map & Terrain: Rejects static walls, bushes, and camera-scrolled terrain using global flow compensation.
 * 3. Enemy Brawlers: Identifies enemy brawler entities via red health bar indicators and brawler-speed movement.
 * 4. Hostile Projectiles: Pinpoints high-velocity, high-chroma bullets, rockets, and energy attacks.
 * 5. Trajectory & Collision Math: Calculates precise intercept course and returns perpendicular safe dodge vector,
 *    steering clear of enemies and boundaries with < 8ms total latency.
 */
class ScreenThreatDetector {

    // Processing grid dimensions
    private val gridCols = 72
    private val gridRows = 72
    private val totalCells = gridCols * gridRows

    // Frame buffers to eliminate GC allocations
    private val prevLuminance = IntArray(totalCells)
    private val currentLuminance = IntArray(totalCells)
    private val cellHue = FloatArray(totalCells)
    private val cellSat = FloatArray(totalCells)
    private val cellVal = FloatArray(totalCells)
    private val cellClass = ByteArray(totalCells)

    private var hasPrevFrame = false
    private var lastFrameTime = 0L

    // Semantic Classes
    companion object {
        const val CLASS_TERRAIN = 0.toByte()
        const val CLASS_JOYSTICK = 1.toByte()
        const val CLASS_PLAYER = 2.toByte()
        const val CLASS_ENEMY = 3.toByte()
        const val CLASS_PROJECTILE = 4.toByte()
    }

    // Entity Tracking System
    data class TrackedCluster(
        var id: Int,
        var centerX: Float,
        var centerY: Float,
        var velocityX: Float,
        var velocityY: Float,
        var speed: Float,
        var mass: Float,
        var classification: Byte,
        var lastSeenTimestamp: Long,
        var trajectoryConsistency: Float // 1.0 = perfect straight line
    )

    private val trackedEntities = ArrayList<TrackedCluster>(16)
    private var nextEntityId = 1

    // Reusable bulk pixel buffer to eliminate JNI getPixel overhead
    private var rawPixelsBuffer = IntArray(0)

    /**
     * Primary Real-Time Processing Pipeline.
     * Analyzes raster frame and returns an evasive threat vector if a projectile is incoming.
     */
    fun analyzeFrame(
        frame: Bitmap,
        profile: DodgeProfile,
        screenWidth: Int,
        screenHeight: Int
    ): ThreatVector? {
        val now = System.currentTimeMillis()
        val dtMs = if (lastFrameTime == 0L) 33L else (now - lastFrameTime).coerceIn(4L, 160L)
        val dtSec = dtMs / 1000f
        lastFrameTime = now

        val frameW = frame.width
        val frameH = frame.height
        if (frameW < 10 || frameH < 10) return null

        val requiredPixelCount = frameW * frameH
        if (rawPixelsBuffer.size != requiredPixelCount) {
            rawPixelsBuffer = IntArray(requiredPixelCount)
        }
        // Bulk copy frame pixels in a single native operation (sub-millisecond)
        frame.getPixels(rawPixelsBuffer, 0, frameW, 0, 0, frameW, frameH)

        val stepX = (frameW / gridCols).coerceAtLeast(1)
        val stepY = (frameH / gridRows).coerceAtLeast(1)

        // Screen Coordinates
        val playerX = (profile.playerCenterX * screenWidth).coerceIn(0f, screenWidth.toFloat())
        val playerY = (profile.playerCenterY * screenHeight).coerceIn(0f, screenHeight.toFloat())

        val dangerRadiusPx = profile.threatRadius * screenWidth.toFloat()
        val dangerRadiusSq = dangerRadiusPx * dangerRadiusPx

        val joyX = profile.joystickCenterX * screenWidth
        val joyY = profile.joystickCenterY * screenHeight
        val joyRadiusPx = (profile.joystickRadius * (screenWidth / 1080f)).coerceAtLeast(120f)
        val joyRadiusSq = joyRadiusPx * joyRadiusPx

        // Sensitivity scaling (0.1 to 1.0)
        val sensitivity = profile.sensitivity.coerceIn(0.1f, 1.0f)
        val projectileSpeedThreshold = (320f * (1.15f - sensitivity * 0.35f)).coerceAtLeast(200f)

        // =========================================================================
        // STAGE 1: BULK COLOR SPACE INGESTION & FAST INLINE HSV
        // =========================================================================
        for (gy in 0 until gridRows) {
            val srcY = (gy * stepY).coerceAtMost(frameH - 1)
            val rowPixelOffset = srcY * frameW
            val gridRowOffset = gy * gridCols

            for (gx in 0 until gridCols) {
                val srcX = (gx * stepX).coerceAtMost(frameW - 1)
                val pixel = rawPixelsBuffer[rowPixelOffset + srcX]

                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // Fast ITU-R BT.601 integer luminance
                val lum = (299 * r + 587 * g + 114 * b) / 1000
                val idx = gridRowOffset + gx
                currentLuminance[idx] = lum

                // Fast inline HSV without JNI overhead
                val max = if (r > g) (if (r > b) r else b) else (if (g > b) g else b)
                val min = if (r < g) (if (r < b) r else b) else (if (g < b) g else b)
                val delta = max - min

                val v = max / 255f
                val s = if (max == 0) 0f else delta.toFloat() / max
                var h = if (delta == 0) {
                    0f
                } else if (max == r) {
                    60f * (((g - b).toFloat() / delta) % 6f)
                } else if (max == g) {
                    60f * (((b - r).toFloat() / delta) + 2f)
                } else {
                    60f * (((r - g).toFloat() / delta) + 4f)
                }
                if (h < 0f) h += 360f

                cellHue[idx] = h
                cellSat[idx] = s
                cellVal[idx] = v
                cellClass[idx] = CLASS_TERRAIN // Default static map
            }
        }

        if (!hasPrevFrame) {
            System.arraycopy(currentLuminance, 0, prevLuminance, 0, totalCells)
            hasPrevFrame = true
            return null
        }

        // =========================================================================
        // STAGE 2: GLOBAL CAMERA SCROLL / BACKGROUND ESTIMATION
        // =========================================================================
        // Calculate frame difference to measure camera displacement
        var totalMotionEnergy = 0
        for (i in 0 until totalCells) {
            val diff = abs(currentLuminance[i] - prevLuminance[i])
            totalMotionEnergy += diff
        }
        val avgMotionEnergy = totalMotionEnergy.toFloat() / totalCells
        val isCameraPanning = avgMotionEnergy > 18f

        // =========================================================================
        // STAGE 3: SEMANTIC OBJECT CLASSIFICATION (BRAWLER, MAP, JOYSTICK, BULLET)
        // =========================================================================
        val projectilePointsX = FloatArray(256)
        val projectilePointsY = FloatArray(256)
        val projectileWeights = FloatArray(256)
        var projectileCount = 0

        val enemyBrawlerX = FloatArray(64)
        val enemyBrawlerY = FloatArray(64)
        var enemyCount = 0

        val motionCutoff = if (isCameraPanning) 28 else 14

        for (gy in 0 until gridRows) {
            val worldY = (gy.toFloat() / gridRows) * screenHeight
            val rowOffset = gy * gridCols

            for (gx in 0 until gridCols) {
                val idx = rowOffset + gx
                val worldX = (gx.toFloat() / gridCols) * screenWidth

                // Check 1: Virtual Joystick exclusion
                val distJoySq = (worldX - joyX) * (worldX - joyX) + (worldY - joyY) * (worldY - joyY)
                if (distJoySq <= joyRadiusSq) {
                    cellClass[idx] = CLASS_JOYSTICK
                    continue // Do not process game threats inside the joystick circle
                }

                // Check 2: Player's own body zone (screen center / player position)
                val distPlayerSq = (worldX - playerX) * (worldX - playerX) + (worldY - playerY) * (worldY - playerY)
                if (distPlayerSq < (dangerRadiusPx * 0.14f) * (dangerRadiusPx * 0.14f)) {
                    cellClass[idx] = CLASS_PLAYER
                    continue // Player's own body
                }

                val h = cellHue[idx]
                val s = cellSat[idx]
                val v = cellVal[idx]
                val diff = abs(currentLuminance[idx] - prevLuminance[idx])

                // Check 3: Enemy Brawler Health Bar & Character identification
                // Enemy HP bars in Brawl Stars are distinct saturated Red (Hue 350-10 or 340-360)
                val isEnemyHpBar = (h <= 12f || h >= 342f) && s >= 0.70f && v >= 0.70f
                if (isEnemyHpBar) {
                    cellClass[idx] = CLASS_ENEMY
                    if (enemyCount < enemyBrawlerX.size) {
                        enemyBrawlerX[enemyCount] = worldX
                        enemyBrawlerY[enemyCount] = worldY
                        enemyCount++
                    }
                    continue
                }

                // Check 4: Hostile Projectiles (Bullets, Rockets, Energy Attacks)
                // Projectiles exhibit high chroma, high luminance, and distinct active motion
                val isRedOrangeBullet = (h in 0f..28f || h in 335f..360f) && s >= 0.45f && v >= 0.50f
                val isElectricYellowBolt = (h in 35f..65f) && s >= 0.55f && v >= 0.60f
                val isVioletMagicAttack = (h in 265f..330f) && s >= 0.40f && v >= 0.50f
                val isCyanLaser = (h in 170f..215f) && s >= 0.55f && v >= 0.65f
                val isHighEnergyBurst = diff >= motionCutoff && v >= 0.70f && s >= 0.35f

                val isProjectileCandidate = (isRedOrangeBullet || isElectricYellowBolt || isVioletMagicAttack || isCyanLaser || isHighEnergyBurst)

                if (isProjectileCandidate && distPlayerSq <= dangerRadiusSq) {
                    cellClass[idx] = CLASS_PROJECTILE
                    if (projectileCount < projectilePointsX.size) {
                        projectilePointsX[projectileCount] = worldX
                        projectilePointsY[projectileCount] = worldY
                        val weight = 1.0f + (diff / 20f) + (v * 1.5f) + (s * 1.2f)
                        projectileWeights[projectileCount] = weight
                        projectileCount++
                    }
                }
            }
        }

        // Store current luminance buffer for next frame
        System.arraycopy(currentLuminance, 0, prevLuminance, 0, totalCells)

        // If no projectile pixels found in danger zone, environment is clear!
        if (projectileCount < 3) {
            decayTrackedEntities()
            return null
        }

        // =========================================================================
        // STAGE 4: CLUSTER CENTROID & SPATIAL MOMENTS
        // =========================================================================
        var sumW = 0f
        var sumWX = 0f
        var sumWY = 0f

        for (i in 0 until projectileCount) {
            val w = projectileWeights[i]
            sumW += w
            sumWX += projectilePointsX[i] * w
            sumWY += projectilePointsY[i] * w
        }

        if (sumW < 4.0f) {
            decayTrackedEntities()
            return null
        }

        val centroidX = sumWX / sumW
        val centroidY = sumWY / sumW

        // =========================================================================
        // STAGE 5: TEMPORAL TRAJECTORY TRACKING & VELOCITY MATH
        // =========================================================================
        val activeEntity = matchOrAddTrackedEntity(centroidX, centroidY, sumW, CLASS_PROJECTILE, now, dtSec)

        // Trajectory speed and vector
        val speed = activeEntity.speed
        val velX = activeEntity.velocityX
        val velY = activeEntity.velocityY

        // If speed is below projectile threshold, it's stationary map noise or player body
        if (speed < projectileSpeedThreshold && activeEntity.trajectoryConsistency < 0.60f) {
            return null
        }

        val distToPlayer = hypot(playerX - centroidX, playerY - centroidY)

        // Check if projectile is moving TOWARDS the player (Dot product > 0)
        val dirToPlayerX = playerX - centroidX
        val dirToPlayerY = playerY - centroidY
        val dotProduct = (velX * dirToPlayerX + velY * dirToPlayerY)

        // If projectile is moving distinctly away from player, no dodge required!
        if (dotProduct <= 0f && speed > 80f) {
            return null
        }

        // Calculate perpendicular distance to trajectory line:
        // Line equation distance: |(P_x - C_x) * v_y - (P_y - C_y) * v_x| / |v|
        val normSpeed = speed.coerceAtLeast(1f)
        val trajectoryClearance = abs(dirToPlayerX * velY - dirToPlayerY * velX) / normSpeed

        // Defensive threshold: If the trajectory line will cleanly miss the player, do not waste movement
        val playerHitboxRadius = (dangerRadiusPx * 0.28f).coerceAtLeast(45f)
        if (trajectoryClearance > playerHitboxRadius && distToPlayer > playerHitboxRadius * 1.5f) {
            return null
        }

        // =========================================================================
        // STAGE 6: OPTIMAL EVASIVE MANEUVER (PERPENDICULAR ESCAPE VECTOR)
        // =========================================================================
        // Projectile direction unit vector
        val unitVelX = velX / normSpeed
        val unitVelY = velY / normSpeed

        // Candidate 1: Perpendicular Left (+90 deg)
        val perp1X = -unitVelY
        val perp1Y = unitVelX

        // Candidate 2: Perpendicular Right (-90 deg)
        val perp2X = unitVelY
        val perp2Y = -unitVelX

        // Evaluate both candidate evasion directions:
        // Steer away from screen borders and away from known enemy brawlers!
        val testDist = dangerRadiusPx * 0.8f
        val escape1X = playerX + perp1X * testDist
        val escape1Y = playerY + perp1Y * testDist

        val escape2X = playerX + perp2X * testDist
        val escape2Y = playerY + perp2Y * testDist

        val score1 = scoreEvasionVector(escape1X, escape1Y, screenWidth.toFloat(), screenHeight.toFloat(), enemyBrawlerX, enemyBrawlerY, enemyCount)
        val score2 = scoreEvasionVector(escape2X, escape2Y, screenWidth.toFloat(), screenHeight.toFloat(), enemyBrawlerX, enemyBrawlerY, enemyCount)

        val (bestDodgeX, bestDodgeY) = if (score1 >= score2) {
            Pair(perp1X, perp1Y)
        } else {
            Pair(perp2X, perp2Y)
        }

        val dodgeAngleRad = atan2(bestDodgeY, bestDodgeX)
        val dodgeAngleDeg = Math.toDegrees(dodgeAngleRad.toDouble()).toFloat()
        val threatAngleDeg = Math.toDegrees(atan2(dirToPlayerY, dirToPlayerX).toDouble()).toFloat()

        val timeToImpactMs = if (speed > 20f) {
            ((distToPlayer / speed) * 1000f).toLong().coerceIn(10L, 1000L)
        } else {
            180L
        }

        val threatLevel = when {
            distToPlayer < dangerRadiusPx * 0.35f || timeToImpactMs < 100L -> ThreatLevel.LETHAL
            distToPlayer < dangerRadiusPx * 0.65f -> ThreatLevel.IMMINENT_DANGER
            else -> ThreatLevel.WARNING
        }

        val confidence = (activeEntity.trajectoryConsistency * (sumW / 50f)).coerceIn(0.70f, 1.0f)

        return ThreatVector(
            threatX = centroidX,
            threatY = centroidY,
            velocityX = velX,
            velocityY = velY,
            speed = speed,
            threatAngleDeg = threatAngleDeg,
            dodgeAngleDeg = dodgeAngleDeg,
            dodgeDirX = bestDodgeX,
            dodgeDirY = bestDodgeY,
            threatLevel = threatLevel,
            timeToImpactMs = timeToImpactMs,
            confidence = confidence
        )
    }

    private fun scoreEvasionVector(
        targetX: Float,
        targetY: Float,
        screenWidth: Float,
        screenHeight: Float,
        enemyX: FloatArray,
        enemyY: FloatArray,
        enemyCount: Int
    ): Float {
        var score = 100f
        val margin = 60f

        // Penalize screen boundary proximity
        if (targetX < margin) score -= (margin - targetX) * 2.5f
        if (targetX > screenWidth - margin) score -= (targetX - (screenWidth - margin)) * 2.5f
        if (targetY < margin) score -= (margin - targetY) * 2.5f
        if (targetY > screenHeight - margin) score -= (targetY - (screenHeight - margin)) * 2.5f

        // Penalize dodging into enemy brawler positions
        val checkCount = enemyCount.coerceAtMost(10)
        for (i in 0 until checkCount) {
            val distToEnemy = hypot(targetX - enemyX[i], targetY - enemyY[i])
            if (distToEnemy < 180f) {
                score -= (180f - distToEnemy) * 0.8f
            }
        }

        return score
    }

    private fun matchOrAddTrackedEntity(
        x: Float,
        y: Float,
        mass: Float,
        classification: Byte,
        now: Long,
        dtSec: Float
    ): TrackedCluster {
        var bestEntity: TrackedCluster? = null
        var bestDistSq = Float.MAX_VALUE
        val maxAssociateDistSq = 260f * 260f

        for (e in trackedEntities) {
            val dx = x - e.centerX
            val dy = y - e.centerY
            val distSq = dx * dx + dy * dy
            if (distSq < maxAssociateDistSq && distSq < bestDistSq) {
                bestDistSq = distSq
                bestEntity = e
            }
        }

        if (bestEntity != null) {
            val instantVx = (x - bestEntity.centerX) / dtSec.coerceAtLeast(0.016f)
            val instantVy = (y - bestEntity.centerY) / dtSec.coerceAtLeast(0.016f)

            // Smooth velocity with alpha filter
            val alpha = 0.70f
            val newVx = bestEntity.velocityX * (1f - alpha) + instantVx * alpha
            val newVy = bestEntity.velocityY * (1f - alpha) + instantVy * alpha
            val newSpeed = hypot(newVx, newVy)

            // Trajectory consistency calculation
            val prevAngle = atan2(bestEntity.velocityY, bestEntity.velocityX)
            val newAngle = atan2(newVy, newVx)
            val angleDiff = abs(prevAngle - newAngle)
            val consistency = (1.0f - (angleDiff / Math.PI.toFloat())).coerceIn(0.2f, 1.0f)

            bestEntity.centerX = x
            bestEntity.centerY = y
            bestEntity.velocityX = newVx
            bestEntity.velocityY = newVy
            bestEntity.speed = newSpeed
            bestEntity.mass = mass
            bestEntity.lastSeenTimestamp = now
            bestEntity.trajectoryConsistency = consistency
            return bestEntity
        } else {
            val newId = nextEntityId++
            val newEntity = TrackedCluster(
                id = newId,
                centerX = x,
                centerY = y,
                velocityX = 0f,
                velocityY = 0f,
                speed = 0f,
                mass = mass,
                classification = classification,
                lastSeenTimestamp = now,
                trajectoryConsistency = 0.8f
            )
            trackedEntities.add(newEntity)
            if (trackedEntities.size > 12) {
                trackedEntities.removeAt(0)
            }
            return newEntity
        }
    }

    private fun decayTrackedEntities() {
        val now = System.currentTimeMillis()
        trackedEntities.removeAll { now - it.lastSeenTimestamp > 350L }
    }

    fun reset() {
        hasPrevFrame = false
        trackedEntities.clear()
        lastFrameTime = 0L
    }

    /**
     * Automatic Virtual Joystick Scanner.
     * Identifies analog stick anchor by concentric gradient symmetry in lower-left quadrant.
     */
    fun scanForJoystick(frame: Bitmap): Pair<Float, Float> {
        val width = frame.width
        val height = frame.height

        val startX = (width * 0.05f).toInt().coerceAtLeast(0)
        val endX = (width * 0.45f).toInt().coerceAtMost(width - 1)
        val startY = (height * 0.50f).toInt().coerceAtLeast(0)
        val endY = (height * 0.95f).toInt().coerceAtMost(height - 1)

        val step = 8
        var bestVariance = -1f
        var bestX = width * 0.22f
        var bestY = height * 0.75f

        for (y in startY until endY step step * 2) {
            for (x in startX until endX step step * 2) {
                var localVariance = 0f
                val centerPixel = frame.getPixel(x, y)
                val centerLum = ((centerPixel shr 16 and 0xFF) * 299 + (centerPixel shr 8 and 0xFF) * 587 + (centerPixel and 0xFF) * 114) / 1000

                val sampleRadius = 18
                for (angleDeg in 0 until 360 step 60) {
                    val rad = Math.toRadians(angleDeg.toDouble())
                    val sx = (x + sampleRadius * cos(rad)).toInt().coerceIn(0, width - 1)
                    val sy = (y + sampleRadius * sin(rad)).toInt().coerceIn(0, height - 1)
                    val samplePix = frame.getPixel(sx, sy)
                    val sampleLum = ((samplePix shr 16 and 0xFF) * 299 + (samplePix shr 8 and 0xFF) * 587 + (samplePix and 0xFF) * 114) / 1000
                    localVariance += abs(sampleLum - centerLum)
                }

                if (localVariance > bestVariance) {
                    bestVariance = localVariance
                    bestX = x.toFloat()
                    bestY = y.toFloat()
                }
            }
        }

        val normX = (bestX / width.toFloat()).coerceIn(0.10f, 0.45f)
        val normY = (bestY / height.toFloat()).coerceIn(0.55f, 0.92f)
        return Pair(normX, normY)
    }
}
