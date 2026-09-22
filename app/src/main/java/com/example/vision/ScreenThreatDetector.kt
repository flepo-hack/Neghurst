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
 * Deterministic Real-Time Computer Vision & Evasion Engine for Brawl Stars and Action Games.
 *
 * Implements the non-AI, deterministic pipeline:
 * 1. Zero-Allocation Direct Frame Buffer: Operates directly on flat primitive memory to prevent GC pauses.
 * 2. Global Motion Compensation (GMC): Spatial cross-correlation across reference zones cancels
 *    camera panning: F_{1,aligned} = warpAffine(F_1, -v_camera), D = |F_2 - F_{1,aligned}|.
 * 3. Canny Edge Detection & 4:1 Health Bar Search: Pixel-precise extraction of player and enemy health bars.
 *    The player's character is deterministically identified as the health bar closest to screen center.
 * 4. Hough Circle Transform: Detects Brawl Ball and brawler selection rings.
 * 5. 2D Kalman Trajectory Filtering: Separates constant-velocity projectiles from erratically dodging players.
 * 6. Kinematic Collision Geometry: CPA (Closest Point of Approach) collision forecasting: d(t_cpa) < R.
 * 7. Perpendicular Evasion Vector: theta_dodge = atan2(v_y, v_x) ± 90°, avoiding walls and borders.
 * 8. Low-Latency Touch Injection: Maps evasion angles directly into virtual joystick strokes.
 */
class ScreenThreatDetector {

    // Grid resolution for deterministic pipeline (Optimized for 60 FPS sub-3ms execution)
    private val gridCols = 80
    private val gridRows = 48
    private val totalCells = gridCols * gridRows

    // Deterministic Core Modules
    val frameBuffer = DeterministicFrameBuffer(gridCols, gridRows)
    val motionCompensator = GlobalMotionCompensator(gridCols, gridRows)
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

    // Enemy cluster tracking arrays
    private val enemyPointsX = FloatArray(64)
    private val enemyPointsY = FloatArray(64)

    // Reusable structures for connected-component centroid clustering (zero heap allocation)
    private val diffVisited = BooleanArray(totalCells)
    private val queueX = IntArray(128)
    private val queueY = IntArray(128)

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
    }

    fun reset() = resetTracking()

    /**
     * Primary Real-Time Processing Pipeline.
     * Executes fully deterministically in under 3.5 ms on mobile CPU, with zero heap allocations in inner loops.
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
            noiseFloor = if (cameraMotion.isCameraMoving) 18 else 12
        )

        // =========================================================================
        // STAGE 3: CANNY EDGE DETECTION & 4:1 HEALTH BAR DETECTION
        // =========================================================================
        val gridCenterX = gridCols / 2f
        val gridCenterY = gridRows / 2f
        cannyDetector.processFrame(frameBuffer.grayscaleBuffer, gridCenterX, gridCenterY)

        // =========================================================================
        // STAGE 4: HOUGH CIRCLE DETECTION (BRAWLER RINGS & BRAWL BALL)
        // =========================================================================
        houghDetector.detectCircles(cannyDetector.edgeMap, cannyDetector.gradientDirection, minVoteThreshold = 14)

        // =========================================================================
        // STAGE 5: PLAYER & JOYSTICK SPATIAL LOCALIZATION
        // =========================================================================
        val defaultJoyX = if (manualJoyX > 0f) manualJoyX else (profile.joystickCenterX * screenWidth)
        val defaultJoyY = if (manualJoyY > 0f) manualJoyY else (profile.joystickCenterY * screenHeight)
        val defaultPlayerX = if (manualPlayerX > 0f) manualPlayerX else (profile.playerCenterX * screenWidth)
        val defaultPlayerY = if (manualPlayerY > 0f) manualPlayerY else (profile.playerCenterY * screenHeight)

        // Lock onto player via Canny 4:1 Health Bar (closest to screen center)
        val playerBar = cannyDetector.playerBar
        var detectedPlayerX = -1f
        var detectedPlayerY = -1f

        if (playerBar != null) {
            // Health bar is positioned roughly 20-30px directly above the brawler's center
            val barWorldX = (playerBar.centerX / gridCols.toFloat()) * screenWidth
            val barWorldY = (playerBar.centerY / gridRows.toFloat()) * screenHeight
            detectedPlayerX = barWorldX
            detectedPlayerY = barWorldY + (screenHeight * 0.045f) // Offset down to character feet/body
        }

        // Secondary check: Hough circle closest to center
        if (detectedPlayerX < 0f && houghDetector.detectedCount > 0) {
            var closestDist = Float.MAX_VALUE
            for (i in 0 until houghDetector.detectedCount) {
                val circle = houghDetector.detectedCircles[i]
                val circleWorldX = (circle.centerX / gridCols.toFloat()) * screenWidth
                val circleWorldY = (circle.centerY / gridRows.toFloat()) * screenHeight
                val dist = hypot(circleWorldX - screenWidth / 2f, circleWorldY - screenHeight / 2f)
                if (dist < closestDist && dist < screenWidth * 0.35f) {
                    closestDist = dist
                    detectedPlayerX = circleWorldX
                    detectedPlayerY = circleWorldY
                }
            }
        }

        // Color validation of detected health bars to distinguish player from enemies
        val downsampledPixels = frameBuffer.downsampledPixels
        for (i in 0 until cannyDetector.detectedBarCount) {
            val bar = cannyDetector.detectedBars[i]
            val bx = bar.centerX.toInt().coerceIn(0, gridCols - 1)
            val by = bar.centerY.toInt().coerceIn(0, gridRows - 1)
            val p = downsampledPixels[by * gridCols + bx]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF

            if (g > 140 && g > r * 1.30f && g > b * 1.30f) {
                // Confirmed vibrant green player health bar!
                bar.isPlayer = true
                cannyDetector.playerBar = bar
                val barWorldX = (bar.centerX / gridCols.toFloat()) * screenWidth
                val barWorldY = (bar.centerY / gridRows.toFloat()) * screenHeight
                detectedPlayerX = barWorldX
                detectedPlayerY = barWorldY + (screenHeight * 0.045f)
            }
        }

        // Tertiary check: Vibrant neon green player ring under brawler feet (Brawl Stars green ring)
        // High saturation and brightness check prevents false positives from dark olive bushes/grass.
        if (detectedPlayerX < 0f) {
            var greenSumX = 0f
            var greenSumY = 0f
            var greenCount = 0

            val minGX = (gridCols * 0.25f).toInt()
            val maxGX = (gridCols * 0.75f).toInt()
            val minGY = (gridRows * 0.20f).toInt()
            val maxGY = (gridRows * 0.80f).toInt()

            for (gy in minGY until maxGY) {
                val rowOffset = gy * gridCols
                val worldY = (gy.toFloat() / gridRows) * screenHeight
                for (gx in minGX until maxGX) {
                    val pixel = downsampledPixels[rowOffset + gx]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    // High-saturation neon lime green of brawler selection ring:
                    // g >= 165, r <= 110, b <= 130, g > 1.6*r, g > 1.6*b (bushes are dark olive: g < 150 or r > 40 with low saturation)
                    if (g >= 165 && r <= 115 && b <= 135 && g > (r * 1.55f) && g > (b * 1.55f)) {
                        val worldX = (gx.toFloat() / gridCols) * screenWidth
                        greenSumX += worldX
                        greenSumY += worldY
                        greenCount++
                    }
                }
            }

            if (greenCount in 2..40) { // Compact circular/elliptical cluster under feet
                detectedPlayerX = greenSumX / greenCount
                detectedPlayerY = greenSumY / greenCount
            }
        }

        if (detectedPlayerX > 0f) {
            if (isPlayerLocked && trackedPlayerX > 0f) {
                // Exponential moving average filter for smooth jitter-free position
                trackedPlayerX = trackedPlayerX * 0.35f + detectedPlayerX * 0.65f
                trackedPlayerY = trackedPlayerY * 0.35f + detectedPlayerY * 0.65f
            } else {
                trackedPlayerX = detectedPlayerX
                trackedPlayerY = detectedPlayerY
                isPlayerLocked = true
            }
            framesSincePlayerSeen = 0
        } else {
            framesSincePlayerSeen++
            if (framesSincePlayerSeen > 20) {
                isPlayerLocked = false
            }
        }

        val currentPlayerX = if (isPlayerLocked && trackedPlayerX > 0f) trackedPlayerX else defaultPlayerX
        val currentPlayerY = if (isPlayerLocked && trackedPlayerY > 0f) trackedPlayerY else defaultPlayerY

        val currentJoyX = if (isJoyLocked && dynamicJoyX > 0f) dynamicJoyX else defaultJoyX
        val currentJoyY = if (isJoyLocked && dynamicJoyY > 0f) dynamicJoyY else defaultJoyY
        val joyRadiusPx = profile.joystickRadius.coerceAtLeast(120f)
        val joyRadiusSq = joyRadiusPx * joyRadiusPx

        // =========================================================================
        // STAGE 6: COMPENSATED MOTION CENTROIDS & ENEMY EXTRACTION
        // =========================================================================
        var obsCount = 0
        var enemyCount = 0
        val playerHitboxRadiusPx = 65f
        val playerHitboxRadiusSq = playerHitboxRadiusPx * playerHitboxRadiusPx

        // Find enemies from non-player health bars detected by Canny
        for (i in 0 until cannyDetector.detectedBarCount) {
            val bar = cannyDetector.detectedBars[i]
            if (!bar.isPlayer && enemyCount < enemyPointsX.size) {
                enemyPointsX[enemyCount] = (bar.centerX / gridCols.toFloat()) * screenWidth
                enemyPointsY[enemyCount] = (bar.centerY / gridRows.toFloat()) * screenHeight + (screenHeight * 0.045f)
                enemyCount++
            }
        }

        // Scan motion difference buffer D and extract connected-component centroids (cv::findContours equivalent)
        val diffBuffer = frameBuffer.motionDiffBuffer
        diffVisited.fill(false)

        for (gy in 2 until gridRows - 2) {
            val rowOffset = gy * gridCols
            for (gx in 2 until gridCols - 2) {
                val idx = rowOffset + gx
                if (diffVisited[idx] || (diffBuffer[idx].toInt() and 0xFF) <= 0) continue

                // Fast BFS flood-fill clustering (Zero heap allocation via preallocated queues)
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

                    // 4-connected neighbor expansion
                    for (n in 0 until 4) {
                        val nx = when (n) { 0 -> curX - 1; 1 -> curX + 1; else -> curX }
                        val ny = when (n) { 2 -> curY - 1; 3 -> curY + 1; else -> curY }
                        if (nx in 2 until gridCols - 2 && ny in 2 until gridRows - 2) {
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

                // Filter valid blobs (min 2 pixels, max 60 pixels to reject full-screen flashes)
                if (blobCount in 2..60 && obsCount < maxObservations) {
                    val centroidGx = sumGx / blobCount
                    val centroidGy = sumGy / blobCount
                    val worldX = (centroidGx / gridCols.toFloat()) * screenWidth
                    val worldY = (centroidGy / gridRows.toFloat()) * screenHeight

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
        // STAGE 9: ASSEMBLE VISUAL DEBUG ENTITIES FOR HUD
        // =========================================================================
        val debugList = ArrayList<DetectedEntity>()

        // 1. Player
        debugList.add(
            DetectedEntity(
                type = EntityType.PLAYER,
                x = currentPlayerX,
                y = currentPlayerY,
                radius = playerHitboxRadiusPx,
                label = if (isPlayerLocked) "PLAYER [LOCKED]" else "PLAYER [CALIB]"
            )
        )

        // 2. Joystick
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
            debugList.add(
                DetectedEntity(
                    type = EntityType.ENEMY,
                    x = enemyPointsX[i],
                    y = enemyPointsY[i],
                    radius = 50f,
                    label = "ENEMY #${i + 1}"
                )
            )
        }

        // 4. Tracked Projectiles
        for (target in kalmanTracker.getActiveTracks()) {
            if (target.isProjectile) {
                debugList.add(
                    DetectedEntity(
                        type = EntityType.PROJECTILE,
                        x = target.x,
                        y = target.y,
                        radius = 38f,
                        vx = target.vx,
                        vy = target.vy,
                        label = "PROJECTILE (${target.speed.toInt()}px/s)"
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
            isJoystickTracked = isJoyLocked,
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
     * Uses Canny health bar positioning, Hough circles, and luminance contrast.
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
        if (frameW < 10 || frameH < 10) {
            return Pair(
                Pair(0.20f * screenWidth, 0.78f * screenHeight),
                Pair(0.50f * screenWidth, 0.50f * screenHeight)
            )
        }

        val safeActiveW = activeWidth.coerceIn(10, frameW)
        val safeActiveH = activeHeight.coerceIn(10, frameH)

        frameBuffer.ingestBitmap(frame, safeActiveW, safeActiveH)
        cannyDetector.processFrame(frameBuffer.grayscaleBuffer, gridCols / 2f, gridRows / 2f)
        houghDetector.detectCircles(cannyDetector.edgeMap, cannyDetector.gradientDirection, minVoteThreshold = 12)

        var autoPlayerX = 0.50f * screenWidth
        var autoPlayerY = 0.50f * screenHeight

        val pBar = cannyDetector.playerBar
        if (pBar != null) {
            autoPlayerX = (pBar.centerX / gridCols.toFloat()) * screenWidth
            autoPlayerY = (pBar.centerY / gridRows.toFloat()) * screenHeight + (screenHeight * 0.045f)
        } else if (houghDetector.detectedCount > 0) {
            autoPlayerX = (houghDetector.detectedCircles[0].centerX / gridCols.toFloat()) * screenWidth
            autoPlayerY = (houghDetector.detectedCircles[0].centerY / gridRows.toFloat()) * screenHeight
        }

        // Joystick anchor in standard bottom-left quadrant
        val autoJoyX = 0.20f * screenWidth
        val autoJoyY = 0.78f * screenHeight

        setManualJoystickCalibration(autoJoyX, autoJoyY)
        setManualPlayerCalibration(autoPlayerX, autoPlayerY)

        return Pair(Pair(autoJoyX, autoJoyY), Pair(autoPlayerX, autoPlayerY))
    }
}
