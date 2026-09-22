package com.example.vision.deterministic

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Deterministic Global Motion Compensator (Background Scrolling Cancellation).
 *
 * Problem: When the player moves, the game camera pans across the arena. A simple
 * difference between frames (absdiff) would detect the entire ground, walls, and grass
 * as moving "threats".
 *
 * Solution:
 * 1. Computes the camera translation vector (v_kamera = dx, dy) using spatial correlation
 *    across 4 UI-free reference zones on the screen.
 * 2. Warps/shifts the previous frame F_1 to align with current frame F_2:
 *    F_{1,aligned}(x, y) = F_1(x + dx, y + dy).
 * 3. Computes the compensated motion difference: D = |F_2 - F_{1,aligned}|.
 *    In D, all static background terrain is cancelled out to ~0, and only objects
 *    moving independently of the camera (projectiles, enemies, balls) remain.
 */
class GlobalMotionCompensator(
    private val width: Int,
    private val height: Int,
    private val maxShiftPx: Int = 18
) {
    data class MotionVector(
        var dx: Int = 0,
        var dy: Int = 0,
        var confidence: Float = 0f,
        var isCameraMoving: Boolean = false
    )

    private val motionResult = MotionVector()

    // 4 Reference Zones away from HUD/Joystick/Super buttons:
    // Zone 0: Top-Left (x: 12%..28%, y: 10%..26%)
    // Zone 1: Top-Right (x: 72%..88%, y: 10%..26%)
    // Zone 2: Mid-Left (x: 10%..26%, y: 44%..60%)
    // Zone 3: Mid-Right (x: 74%..90%, y: 44%..60%)
    private data class RefZone(val startX: Int, val startY: Int, val zoneW: Int, val zoneH: Int)

    private val referenceZones = arrayOf(
        RefZone((width * 0.12f).toInt(), (height * 0.10f).toInt(), (width * 0.16f).toInt(), (height * 0.16f).toInt()),
        RefZone((width * 0.72f).toInt(), (height * 0.10f).toInt(), (width * 0.16f).toInt(), (height * 0.16f).toInt()),
        RefZone((width * 0.10f).toInt(), (height * 0.44f).toInt(), (width * 0.16f).toInt(), (height * 0.16f).toInt()),
        RefZone((width * 0.74f).toInt(), (height * 0.44f).toInt(), (width * 0.16f).toInt(), (height * 0.16f).toInt())
    )

    // Intermediate shift candidate scores for sub-pixel / grid correlation
    private val candidateScores = IntArray((2 * maxShiftPx + 1) * (2 * maxShiftPx + 1))

    /**
     * Estimates global camera motion vector between F_prev and F_curr.
     */
    fun computeCameraMotion(
        currentGrayscale: ByteArray,
        prevGrayscale: ByteArray
    ): MotionVector {
        var totalBestDx = 0
        var totalBestDy = 0
        var validZones = 0

        val shiftSpan = 2 * maxShiftPx + 1

        for (zone in referenceZones) {
            var minSAD = Int.MAX_VALUE
            var bestDx = 0
            var bestDy = 0

            // Step size for correlation speed (16.6ms budget)
            val sampleStep = if (zone.zoneW <= 20) 1 else 2

            for (dy in -maxShiftPx..maxShiftPx step 2) {
                for (dx in -maxShiftPx..maxShiftPx step 2) {
                    var sad = 0
                    var count = 0

                    for (zy in 0 until zone.zoneH step sampleStep) {
                        val currY = zone.startY + zy
                        val prevY = currY + dy
                        if (prevY !in 0 until height) continue

                        val currRowOffset = currY * width
                        val prevRowOffset = prevY * width

                        for (zx in 0 until zone.zoneW step sampleStep) {
                            val currX = zone.startX + zx
                            val prevX = currX + dx
                            if (prevX !in 0 until width) continue

                            val currLum = currentGrayscale[currRowOffset + currX].toInt() and 0xFF
                            val prevLum = prevGrayscale[prevRowOffset + prevX].toInt() and 0xFF

                            sad += abs(currLum - prevLum)
                            count++
                        }
                    }

                    if (count >= 8) {
                        val normalizedSad = sad / count
                        if (normalizedSad < minSAD) {
                            minSAD = normalizedSad
                            bestDx = dx
                            bestDy = dy
                        }
                    }
                }
            }

            // Fine-tune around best candidate with single-pixel step
            var fineMinSAD = minSAD
            var fineBestDx = bestDx
            var fineBestDy = bestDy

            for (dy in (bestDy - 1)..(bestDy + 1)) {
                for (dx in (bestDx - 1)..(bestDx + 1)) {
                    if (dx !in -maxShiftPx..maxShiftPx || dy !in -maxShiftPx..maxShiftPx) continue
                    var sad = 0
                    var count = 0

                    for (zy in 0 until zone.zoneH step sampleStep) {
                        val currY = zone.startY + zy
                        val prevY = currY + dy
                        if (prevY !in 0 until height) continue

                        val currRowOffset = currY * width
                        val prevRowOffset = prevY * width

                        for (zx in 0 until zone.zoneW step sampleStep) {
                            val currX = zone.startX + zx
                            val prevX = currX + dx
                            if (prevX !in 0 until width) continue

                            val currLum = currentGrayscale[currRowOffset + currX].toInt() and 0xFF
                            val prevLum = prevGrayscale[prevRowOffset + prevX].toInt() and 0xFF

                            sad += abs(currLum - prevLum)
                            count++
                        }
                    }

                    if (count >= 8) {
                        val norm = sad / count
                        if (norm < fineMinSAD) {
                            fineMinSAD = norm
                            fineBestDx = dx
                            fineBestDy = dy
                        }
                    }
                }
            }

            if (fineMinSAD < 45) { // Reject noisy / heavily occluded zones
                totalBestDx += fineBestDx
                totalBestDy += fineBestDy
                validZones++
            }
        }

        if (validZones > 0) {
            val avgDx = Math.round(totalBestDx.toFloat() / validZones)
            val avgDy = Math.round(totalBestDy.toFloat() / validZones)
            val shiftMagnitude = hypot(avgDx.toFloat(), avgDy.toFloat())

            motionResult.dx = avgDx
            motionResult.dy = avgDy
            motionResult.confidence = (validZones.toFloat() / referenceZones.size)
            motionResult.isCameraMoving = (shiftMagnitude > 0.8f)
        } else {
            motionResult.dx = 0
            motionResult.dy = 0
            motionResult.confidence = 0f
            motionResult.isCameraMoving = false
        }

        return motionResult
    }

    /**
     * Warps prevGrayscale by -v_kamera and writes aligned image into alignedDest.
     * Computes motion difference D = |curr - alignedDest| into diffDest.
     */
    fun compensateAndSubtract(
        currGrayscale: ByteArray,
        prevGrayscale: ByteArray,
        alignedDest: ByteArray,
        diffDest: ByteArray,
        motion: MotionVector,
        noiseFloor: Int = 14
    ) {
        val shiftX = motion.dx
        val shiftY = motion.dy

        for (y in 0 until height) {
            val alignedSrcY = (y + shiftY).coerceIn(0, height - 1)
            val currRowOffset = y * width
            val srcRowOffset = alignedSrcY * width

            for (x in 0 until width) {
                val alignedSrcX = (x + shiftX).coerceIn(0, width - 1)
                val alignedLum = prevGrayscale[srcRowOffset + alignedSrcX].toInt() and 0xFF
                val currLum = currGrayscale[currRowOffset + x].toInt() and 0xFF

                alignedDest[currRowOffset + x] = alignedLum.toByte()

                val diff = abs(currLum - alignedLum)
                diffDest[currRowOffset + x] = if (diff > noiseFloor) diff.toByte() else 0
            }
        }
    }
}
