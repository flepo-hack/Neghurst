package com.example.vision

import android.graphics.Bitmap
import com.example.model.DetectedEntity
import com.example.model.DodgeProfile
import com.example.model.EntityType
import com.example.model.ThreatLevel
import com.example.model.ThreatVector
import com.example.vision.deterministic.CannyEdgeAndHealthBarDetector
import com.example.vision.deterministic.CollisionDodgeEngine
import com.example.vision.deterministic.DeterministicFrameBuffer
import com.example.vision.deterministic.GlobalMotionCompensator
import com.example.vision.deterministic.HoughCircleDetector
import com.example.vision.deterministic.KalmanTrajectoryTracker
import com.example.vision.deterministic.LowLatencyTouchController
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Deterministic Real-Time Computer Vision & Evasion Engine for Brawl Stars.
 *
 * Implements the non-AI, deterministic pipeline:
 * 1. Zero-Allocation Direct Frame Buffer: Operates directly on flat primitive memory to prevent GC pauses.
 * 2. Global Motion Compensation (GMC): Spatial cross-correlation across reference zones cancels
 *    camera panning: F_{1,aligned} = warpAffine(F_1, -v_camera), D = |F_2 - F_{1,aligned}|.
 * 3. Color & Geometry Fusion:
 *    - Player character: Identified by green health bar / green ground selection ring.
 *    - Enemy brawlers: Identified by red health bars and red ground selection rings.
 *    - Projectiles: Fast-moving high-luminance difference blobs tracked via 2D Kalman filter.
 * 4. CPA (Closest Point of Approach) collision forecasting.
 * 5. Perpendicular Evasion Vector: theta_dodge = atan2(v_y, v_x) ± 90°.
 */
class ScreenThreatDetector {

    // Grid resolution for deterministic pipeline (Optimized for 60 FPS sub-3ms execution)
    private val gridCols = 160
    private val gridRows = 90
    private val totalCells = gridCols * gridRows

    // Deterministic Core Modules
    val frameBuffer = DeterministicFrameBuffer(gridCols, gridRows)
    val motionCompensator = GlobalMotionCompensator(gridCols, gridRows, maxShiftPx = 8)
    val cannyDetector = CannyEdgeAndHealthBarDetector(gridCols, gridRows)
    val houghDetector = HoughCircleDetector(gridCols, gridRows)
    val kalmanTracker = KalmanTrajectoryTracker()
    val collisionEngine = CollisionDodgeEngine()
    val touchController = LowLatencyTouchController()

    // Persistent Smoothed Player Position (Screen Space Coordinates in Pixels)
    private var trackedPlayerX = -1f
    private var trackedPlayerY = -1f
    private var isPlayerLocked = false
    private var framesSincePlayerSeen = 0

    // Dynamic Joystick Anchor (Screen Space Coordinates in Pixels)
    private var dynamicJoyX = -1f
    private var dynamicJoyY = -1f
    private var isJoyLocked = false

    // Manual Overrides (if set by interactive calibration)
    private var manualJoyX = -1f
    private var manualJoyY = -1f
    private var manualPlayerX = -1f
    private var manualPlayerY = -1f

    private var lastFrameTime = 0L

    // Observation arrays for Kalman Tracker (Zero GC allocation)
    private val maxObservations = 32
    private val obsX = FloatArray(maxObservations)
    private val obsY = FloatArray(maxObservations)

    // Enemy cluster tracking arrays (Screen space coordinates in pixels)
    private val maxEnemies = 16
    private val enemyPointsX = FloatArray(maxEnemies)
    private val enemyPointsY = FloatArray(maxEnemies)
    private var enemyCount = 0

    // Reusable structures for connected-component centroid clustering (zero heap allocation)
    private val diffVisited = BooleanArray(totalCells)
    private val queueX = IntArray(1024)
    private val queueY = IntArray(1024)

    data class FrameAnalysisResult(
        val threat: ThreatVector?,
        val playerX: Float,
        val playerY: Float,
        val isPlayerGreenRingTracked: Boolean,
        val joystickX: Float,
        val joystickY: Float,
        val isJoystickTracked: Boolean,
        val enemyCount: Int,
        val wallCount: Int,
        val debugEntities: List<DetectedEntity> = emptyList(),
        val dodgeAngleDeg: Float? = null,
        val cameraDx: Int = 0,
        val cameraDy: Int = 0,
        val isCameraMoving: Boolean = false,
        val detectedHealthBars: List<CannyEdgeAndHealthBarDetector.DetectedHealthBar> = emptyList(),
        val detectedCircles: List<HoughCircleDetector.Circle> = emptyList()
    )

    fun setManualJoystickCalibration(x: Float, y: Float) {
        manualJoyX = x
        manualJoyY = y
        dynamicJoyX = x
        dynamicJoyY = y
        isJoyLocked = true
    }

    fun setManualPlayerCalibration(x: Float, y: Float) {
        manualPlayerX = x
        manualPlayerY = y
        trackedPlayerX = x
        trackedPlayerY = y
        isPlayerLocked = true
        framesSincePlayerSeen = 0
    }

    fun resetTracking() {
        frameBuffer.reset()
        kalmanTracker.reset()
        trackedPlayerX = -1f
        trackedPlayerY = -1f
        isPlayerLocked = false
        dynamicJoyX = -1f
        dynamicJoyY = -1f
        isJoyLocked = false
        framesSincePlayerSeen = 0
        lastFrameTime = 0L
        enemyCount = 0
    }

    fun reset() = resetTracking()

    /**
     * Primary Real-Time Processing Pipeline.
     * Executes fully deterministically in under 3 ms on mobile CPU, with zero heap allocations in inner loops.
     */
    fun analyzeFrame(
        frame: Bitmap,
        profile: DodgeProfile,
        screenWidth: Int,
        screenHeight: Int,
        activeWidth: Int = frame.width,
        activeHeight: Int = frame.height
    ): FrameAnalysisResult? {
        val now = System.currentTimeMillis()
        val dtSec = if (lastFrameTime > 0L) {
            ((now - lastFrameTime) / 1000f).coerceIn(0.008f, 0.060f)
        } else {
            0.016f
        }
        lastFrameTime = now

        val frameW = frame.width
        val frameH = frame.height
        if (frameW < 10 || frameH < 10) return null

        val safeActiveW = activeWidth.coerceIn(10, frameW)
        val safeActiveH = activeHeight.coerceIn(10, frameH)

        // =========================================================================
        // STAGE 1: ZERO-ALLOCATION INGESTION
        // =========================================================================
        frameBuffer.ingestBitmap(frame, safeActiveW, safeActiveH)

        if (!frameBuffer.hasPreviousFrame) {
            return null
        }

        // =========================================================================
        // STAGE 2: GLOBAL MOTION COMPENSATION (BACKGROUND PANNING CANCELLATION)
        // =========================================================================
        val cameraMotion = motionCompensator.computeCameraMotion(
            currentGrayscale = frameBuffer.grayscaleBuffer,
            prevGrayscale = frameBuffer.prevGrayscaleBuffer
        )

        motionCompensator.compensateAndSubtract(
            currGrayscale = frameBuffer.grayscaleBuffer,
            prevGrayscale = frameBuffer.prevGrayscaleBuffer,
            alignedDest = frameBuffer.alignedGrayscaleBuffer,
            diffDest = frameBuffer.motionDiffBuffer,
            motion = cameraMotion,
            noiseFloor = if (cameraMotion.isCameraMoving) 16 else 12
        )

        // =========================================================================
        // STAGE 3: CANNY EDGE & HEALTH BAR GEOMETRY
        // =========================================================================
        val gridCenterX = gridCols / 2f
        val gridCenterY = gridRows / 2f
        cannyDetector.processFrame(frameBuffer.grayscaleBuffer, gridCenterX, gridCenterY)

        // =========================================================================
        // STAGE 4: PLAYER SPATIAL LOCALIZATION (GREEN SIGNATURE IN BRAWL STARS)
        // =========================================================================
        val isLandscape = screenWidth > screenHeight
        val defaultJoyX = if (manualJoyX > 0f) manualJoyX else (if (isLandscape) 0.20f * screenWidth else 0.25f * screenWidth)
        val defaultJoyY = if (manualJoyY > 0f) manualJoyY else (if (isLandscape) 0.78f * screenHeight else 0.80f * screenHeight)
        val defaultPlayerX = if (manualPlayerX > 0f) manualPlayerX else (0.50f * screenWidth)
        val defaultPlayerY = if (manualPlayerY > 0f) manualPlayerY else (0.50f * screenHeight)

        val downsampledPixels = frameBuffer.downsampledPixels
        var detectedPlayerX = -1f
        var detectedPlayerY = -1f

        // Search for Player Green Ring & Health Bar in central playfield
        val minGX = (gridCols * 0.22f).toInt()
        val maxGX = (gridCols * 0.78f).toInt()
        val minGY = (gridRows * 0.20f).toInt()
        val maxGY = (gridRows * 0.85f).toInt()

        var greenSumX = 0f
        var greenSumY = 0f
        var greenCount = 0

        for (gy in minGY until maxGY) {
            val rowOffset = gy * gridCols
            for (gx in minGX until maxGX) {
                val pixel = downsampledPixels[rowOffset + gx]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // Distinct neon lime green of player selection ring or health bar
                if (g >= 105 && g > (r * 1.20f) && g > (b * 1.15f)) {
                    greenSumX += gx
                    greenSumY += gy
                    greenCount++
                }
            }
        }

        if (greenCount in 3..250) {
            val avgGx = greenSumX / greenCount
            val avgGy = greenSumY / greenCount
            detectedPlayerX = (avgGx / gridCols.toFloat()) * screenWidth
            detectedPlayerY = (avgGy / gridRows.toFloat()) * screenHeight
        }

        // Cross-verify with Canny health bar if available
        val pBar = cannyDetector.playerBar
        if (pBar != null && detectedPlayerX < 0f) {
            val barWorldX = (pBar.centerX / gridCols.toFloat()) * screenWidth
            val barWorldY = (pBar.centerY / gridRows.toFloat()) * screenHeight
            detectedPlayerX = barWorldX
            detectedPlayerY = barWorldY + (screenHeight * 0.045f)
        }

        if (detectedPlayerX > 0f) {
            if (isPlayerLocked && trackedPlayerX > 0f) {
                // Exponential moving average filter for smooth jitter-free position
                trackedPlayerX = trackedPlayerX * 0.40f + detectedPlayerX * 0.60f
                trackedPlayerY = trackedPlayerY * 0.40f + detectedPlayerY * 0.60f
            } else {
                trackedPlayerX = detectedPlayerX
                trackedPlayerY = detectedPlayerY
                isPlayerLocked = true
            }
            framesSincePlayerSeen = 0
        } else {
            framesSincePlayerSeen++
            if (framesSincePlayerSeen > 25) {
                isPlayerLocked = false
            }
        }

        val currentPlayerX = if (isPlayerLocked && trackedPlayerX > 0f) trackedPlayerX else defaultPlayerX
        val currentPlayerY = if (isPlayerLocked && trackedPlayerY > 0f) trackedPlayerY else defaultPlayerY

        val currentJoyX = if (isJoyLocked && dynamicJoyX > 0f) dynamicJoyX else defaultJoyX
        val currentJoyY = if (isJoyLocked && dynamicJoyY > 0f) dynamicJoyY else defaultJoyY
        val joyRadiusPx = profile.joystickRadius.coerceAtLeast(130f)
        val joyRadiusSq = joyRadiusPx * joyRadiusPx
        val playerHitboxRadiusPx = 65f
        val playerHitboxRadiusSq = playerHitboxRadiusPx * playerHitboxRadiusPx

        // =========================================================================
        // STAGE 5: ENEMY EXTRACTION (RED HEALTH BARS & RED SELECTION RINGS)
        // =========================================================================
        enemyCount = 0
        diffVisited.fill(false)

        val enemyMinGY = (gridRows * 0.08f).toInt()
        val enemyMaxGY = (gridRows * 0.94f).toInt()
        val enemyMinGX = 4
        val enemyMaxGX = gridCols - 4

        for (gy in enemyMinGY until enemyMaxGY) {
            val rowOffset = gy * gridCols
            for (gx in enemyMinGX until enemyMaxGX) {
                val idx = rowOffset + gx
                if (diffVisited[idx]) continue

                val pixel = downsampledPixels[idx]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // Distinct crimson red of enemy health bars and enemy ground rings
                if (r >= 110 && r > (g * 1.25f) && r > (b * 1.25f)) {
                    // Fast connected-component clustering
                    var head = 0
                    var tail = 0
                    queueX[tail] = gx
                    queueY[tail] = gy
                    tail++
                    diffVisited[idx] = true

                    var sumX = 0f
                    var sumY = 0f
                    var count = 0

                    while (head < tail && tail < queueX.size) {
                        val cx = queueX[head]
                        val cy = queueY[head]
                        head++

                        sumX += cx
                        sumY += cy
                        count++

                        for (n in 0 until 4) {
                            val nx = when (n) { 0 -> cx - 1; 1 -> cx + 1; else -> cx }
                            val ny = when (n) { 2 -> cy - 1; 3 -> cy + 1; else -> cy }

                            if (nx in enemyMinGX until enemyMaxGX && ny in enemyMinGY until enemyMaxGY) {
                                val nIdx = ny * gridCols + nx
                                if (!diffVisited[nIdx]) {
                                    val np = downsampledPixels[nIdx]
                                    val nr = (np shr 16) and 0xFF
                                    val ng = (np shr 8) and 0xFF
                                    val nb = np and 0xFF

                                    if (nr >= 105 && nr > (ng * 1.20f) && nr > (nb * 1.20f)) {
                                        diffVisited[nIdx] = true
                                        if (tail < queueX.size) {
                                            queueX[tail] = nx
                                            queueY[tail] = ny
                                            tail++
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (count in 2..150 && enemyCount < maxEnemies) {
                        val eWorldX = ((sumX / count) / gridCols.toFloat()) * screenWidth
                        val eWorldY = ((sumY / count) / gridRows.toFloat()) * screenHeight

                        // Exclude joystick region
                        val dJoy = hypot(eWorldX - currentJoyX, eWorldY - currentJoyY)
                        if (dJoy > joyRadiusPx * 0.9f) {
                            // Check if close to an already found enemy (merge health bar + foot ring)
                            var merged = false
                            for (ei in 0 until enemyCount) {
                                val distToOther = hypot(eWorldX - enemyPointsX[ei], eWorldY - enemyPointsY[ei])
                                if (distToOther < 60f) {
                                    // Average the coordinates
                                    enemyPointsX[ei] = (enemyPointsX[ei] + eWorldX) / 2f
                                    enemyPointsY[ei] = maxOf(enemyPointsY[ei], eWorldY) // Bias toward character body/feet
                                    merged = true
                                    break
                                }
                            }
                            if (!merged) {
                                enemyPointsX[enemyCount] = eWorldX
                                enemyPointsY[enemyCount] = eWorldY
                                enemyCount++
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // STAGE 6: COMPENSATED MOTION CENTROIDS & PROJECTILES (AMMO)
        // =========================================================================
        var obsCount = 0
        val diffBuffer = frameBuffer.motionDiffBuffer
        diffVisited.fill(false)

        for (gy in 4 until gridRows - 4) {
            val rowOffset = gy * gridCols
            for (gx in 4 until gridCols - 4) {
                val idx = rowOffset + gx
                if (diffVisited[idx] || (diffBuffer[idx].toInt() and 0xFF) <= 0) continue

                var head = 0
                var tail = 0
                queueX[tail] = gx
                queueY[tail] = gy
                tail++
                diffVisited[idx] = true

                var sumGx = 0f
                var sumGy = 0f
                var blobCount = 0

                while (head < tail && tail < queueX.size) {
                    val curX = queueX[head]
                    val curY = queueY[head]
                    head++

                    sumGx += curX
                    sumGy += curY
                    blobCount++

                    for (n in 0 until 4) {
                        val nx = when (n) { 0 -> curX - 1; 1 -> curX + 1; else -> curX }
                        val ny = when (n) { 2 -> curY - 1; 3 -> curY + 1; else -> curY }
                        if (nx in 3 until gridCols - 3 && ny in 3 until gridRows - 3) {
                            val nIdx = ny * gridCols + nx
                            if (!diffVisited[nIdx] && (diffBuffer[nIdx].toInt() and 0xFF) > 0) {
                                diffVisited[nIdx] = true
                                if (tail < queueX.size) {
                                    queueX[tail] = nx
                                    queueY[tail] = ny
                                    tail++
                                }
                            }
                        }
                    }
                }

                // Filter valid blobs (min 2 cells, max 80 cells to reject full-screen flashes)
                if (blobCount in 2..80 && obsCount < maxObservations) {
                    val worldX = ((sumGx / blobCount) / gridCols.toFloat()) * screenWidth
                    val worldY = ((sumGy / blobCount) / gridRows.toFloat()) * screenHeight

                    // Skip Joystick Exclusion Area
                    val dJoySq = (worldX - currentJoyX) * (worldX - currentJoyX) + (worldY - currentJoyY) * (worldY - currentJoyY)
                    if (dJoySq <= joyRadiusSq) continue

                    // Skip Player Body Area
                    val dPlayerSq = (worldX - currentPlayerX) * (worldX - currentPlayerX) + (worldY - currentPlayerY) * (worldY - currentPlayerY)
                    if (dPlayerSq <= playerHitboxRadiusSq) continue

                    obsX[obsCount] = worldX
                    obsY[obsCount] = worldY
                    obsCount++
                }
            }
        }

        // =========================================================================
        // STAGE 7: 2D KALMAN FILTER TRAJECTORY TRACKING
        // =========================================================================
        kalmanTracker.processObservations(
            observedX = obsX,
            observedY = obsY,
            observedCount = obsCount,
            nowTimestamp = now,
            dtSec = dtSec
        )

        // =========================================================================
        // STAGE 8: KINEMATIC COLLISION GEOMETRY & PERPENDICULAR EVASION
        // =========================================================================
        val evasionSolution = collisionEngine.evaluateCollision(
            playerX = currentPlayerX,
            playerY = currentPlayerY,
            playerRadius = playerHitboxRadiusPx,
            projectiles = kalmanTracker.getActiveTracks(),
            screenWidth = screenWidth.toFloat(),
            screenHeight = screenHeight.toFloat()
        )

        // =========================================================================
        // STAGE 9: ASSEMBLE VISUAL DEBUG ENTITIES FOR RADAR HUD
        // =========================================================================
        val debugList = ArrayList<DetectedEntity>()

        // 1. Player Reticle
        debugList.add(
            DetectedEntity(
                type = EntityType.PLAYER,
                x = currentPlayerX,
                y = currentPlayerY,
                radius = playerHitboxRadiusPx,
                label = if (isPlayerLocked) "PLAYER [LOCKED]" else "PLAYER [CALIB]"
            )
        )

        // 2. Joystick Anchor
        debugList.add(
            DetectedEntity(
                type = EntityType.JOYSTICK,
                x = currentJoyX,
                y = currentJoyY,
                radius = joyRadiusPx,
                label = "JOYSTICK ANCHOR"
            )
        )

        // 3. Enemies
        for (i in 0 until enemyCount) {
            val dist = hypot(enemyPointsX[i] - currentPlayerX, enemyPointsY[i] - currentPlayerY).toInt()
            debugList.add(
                DetectedEntity(
                    type = EntityType.ENEMY,
                    x = enemyPointsX[i],
                    y = enemyPointsY[i],
                    radius = 48f,
                    label = "ENEMY #${i + 1} (${dist}px)"
                )
            )
        }

        // 4. Tracked Projectiles / Ammo
        for (target in kalmanTracker.getActiveTracks()) {
            if (target.isProjectile) {
                debugList.add(
                    DetectedEntity(
                        type = EntityType.PROJECTILE,
                        x = target.x,
                        y = target.y,
                        radius = 34f,
                        vx = target.vx,
                        vy = target.vy,
                        label = "AMMO (${target.speed.toInt()}px/s)"
                    )
                )
            }
        }

        return FrameAnalysisResult(
            threat = evasionSolution.threat,
            playerX = currentPlayerX,
            playerY = currentPlayerY,
            isPlayerGreenRingTracked = isPlayerLocked,
            joystickX = currentJoyX,
            joystickY = currentJoyY,
            isJoystickTracked = true,
            enemyCount = enemyCount,
            wallCount = 0,
            debugEntities = debugList,
            dodgeAngleDeg = evasionSolution.dodgeAngleDeg,
            cameraDx = cameraMotion.dx,
            cameraDy = cameraMotion.dy,
            isCameraMoving = cameraMotion.isCameraMoving,
            detectedHealthBars = cannyDetector.detectedBars.take(cannyDetector.detectedBarCount),
            detectedCircles = houghDetector.detectedCircles.take(houghDetector.detectedCount)
        )
    }

    /**
     * Smart Auto-Detection of Joystick and Player anchors based on live screen pixels.
     */
    fun autoCalibrateFromFrame(
        frame: Bitmap,
        screenWidth: Int,
        screenHeight: Int,
        activeWidth: Int = frame.width,
        activeHeight: Int = frame.height
    ): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val frameW = frame.width
        val frameH = frame.height
        val isLandscape = screenWidth > screenHeight

        val autoJoyX = if (isLandscape) 0.20f * screenWidth else 0.25f * screenWidth
        val autoJoyY = if (isLandscape) 0.78f * screenHeight else 0.80f * screenHeight
        var autoPlayerX = 0.50f * screenWidth
        var autoPlayerY = 0.50f * screenHeight

        if (frameW >= 10 && frameH >= 10) {
            val safeActiveW = activeWidth.coerceIn(10, frameW)
            val safeActiveH = activeHeight.coerceIn(10, frameH)

            frameBuffer.ingestBitmap(frame, safeActiveW, safeActiveH)

            val downsampledPixels = frameBuffer.downsampledPixels
            val minGX = (gridCols * 0.25f).toInt()
            val maxGX = (gridCols * 0.75f).toInt()
            val minGY = (gridRows * 0.20f).toInt()
            val maxGY = (gridRows * 0.85f).toInt()

            var greenSumX = 0f
            var greenSumY = 0f
            var greenCount = 0

            for (gy in minGY until maxGY) {
                val rowOffset = gy * gridCols
                for (gx in minGX until maxGX) {
                    val pixel = downsampledPixels[rowOffset + gx]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF

                    if (g >= 105 && g > (r * 1.20f) && g > (b * 1.15f)) {
                        greenSumX += gx
                        greenSumY += gy
                        greenCount++
                    }
                }
            }

            if (greenCount in 3..250) {
                autoPlayerX = ((greenSumX / greenCount) / gridCols.toFloat()) * screenWidth
                autoPlayerY = ((greenSumY / greenCount) / gridRows.toFloat()) * screenHeight
            } else {
                // Fallback to Canny edge health bar detection near playfield center
                cannyDetector.processFrame(frameBuffer.grayscaleBuffer, gridCols / 2f, gridRows / 2f)
                val pBar = cannyDetector.playerBar
                if (pBar != null) {
                    autoPlayerX = (pBar.centerX / gridCols.toFloat()) * screenWidth
                    autoPlayerY = (pBar.centerY / gridRows.toFloat()) * screenHeight + (screenHeight * 0.045f)
                }
            }
        }

        setManualJoystickCalibration(autoJoyX, autoJoyY)
        setManualPlayerCalibration(autoPlayerX, autoPlayerY)

        return Pair(Pair(autoJoyX, autoJoyY), Pair(autoPlayerX, autoPlayerY))
    }
}
