package com.example.vision.deterministic

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Deterministic Canny Edge Detection and Health Bar Geometry Tracker.
 *
 * Why this is immune to skins and visual noise:
 * - Player and enemy health bars have fixed, pixel-sharp black borders and rigid rectangular
 *   aspect ratios of approximately 4:1 (width:height ~ 3.5:1 to 4.8:1).
 * - Canny edge detection identifies these sharp geometric boundaries regardless of hero costume
 *   or background terrain colors.
 * - In Brawl Stars, the active player's brawler is always the character closest to the screen center!
 */
class CannyEdgeAndHealthBarDetector(
    private val width: Int,
    private val height: Int,
    private val lowThreshold: Int = 28,
    private val highThreshold: Int = 65
) {
    val totalPixels = width * height

    // Reusable buffers for Canny pipeline (Zero GC allocations per frame)
    val smoothedBuffer = ByteArray(totalPixels)
    val gradientMagnitude = IntArray(totalPixels)
    val gradientDirection = ByteArray(totalPixels) // 0: 0°, 1: 45°, 2: 90°, 3: 135°
    val edgeMap = ByteArray(totalPixels) // 0: none, 1: weak, 2: strong

    data class DetectedHealthBar(
        var left: Int = 0,
        var top: Int = 0,
        var right: Int = 0,
        var bottom: Int = 0,
        var centerX: Float = 0f,
        var centerY: Float = 0f,
        var widthPx: Int = 0,
        var heightPx: Int = 0,
        var aspectRatio: Float = 0f,
        var distToCenter: Float = 0f,
        var isPlayer: Boolean = false
    )

    private val maxBars = 16
    val detectedBars = ArrayList<DetectedHealthBar>(maxBars).apply {
        repeat(maxBars) { add(DetectedHealthBar()) }
    }
    var detectedBarCount = 0
        private set

    var playerBar: DetectedHealthBar? = null

    /**
     * Executes complete Canny Edge Pipeline:
     * 1. 3x3 Gaussian blur smoothing
     * 2. Sobel horizontal & vertical gradients (Gx, Gy)
     * 3. Non-Maximum Suppression (NMS)
     * 4. Double thresholding and edge continuity
     * 5. Aspect-ratio rectangular bounding box search (~4:1)
     */
    fun processFrame(grayscale: ByteArray, screenCenterX: Float = width / 2f, screenCenterY: Float = height / 2f) {
        // Step 1: Fast 3x3 Gaussian smoothing
        applyGaussianSmoothing(grayscale)

        // Step 2 & 3: Sobel Gradients & Non-Maximum Suppression
        computeGradientsAndNms()

        // Step 4: Double thresholding & Hysteresis
        applyHysteresis()

        // Step 5: Detect rectangular 4:1 health bars
        extractHealthBars(screenCenterX, screenCenterY)
    }

    private fun applyGaussianSmoothing(src: ByteArray) {
        for (y in 1 until height - 1) {
            val row = y * width
            val rowPrev = (y - 1) * width
            val rowNext = (y + 1) * width

            for (x in 1 until width - 1) {
                // Approximate 3x3 Gaussian kernel:
                // [ 1  2  1 ]
                // [ 2  4  2 ] / 16
                // [ 1  2  1 ]
                val p00 = src[rowPrev + x - 1].toInt() and 0xFF
                val p01 = src[rowPrev + x].toInt() and 0xFF
                val p02 = src[rowPrev + x + 1].toInt() and 0xFF

                val p10 = src[row + x - 1].toInt() and 0xFF
                val p11 = src[row + x].toInt() and 0xFF
                val p12 = src[row + x + 1].toInt() and 0xFF

                val p20 = src[rowNext + x - 1].toInt() and 0xFF
                val p21 = src[rowNext + x].toInt() and 0xFF
                val p22 = src[rowNext + x + 1].toInt() and 0xFF

                val sum = (p00 + 2 * p01 + p02 +
                    2 * p10 + 4 * p11 + 2 * p12 +
                    p20 + 2 * p21 + p22) shr 4

                smoothedBuffer[row + x] = sum.toByte()
            }
        }
    }

    private fun computeGradientsAndNms() {
        gradientMagnitude.fill(0)
        gradientDirection.fill(0)

        // Sobel Kernels
        for (y in 1 until height - 1) {
            val row = y * width
            val rowPrev = (y - 1) * width
            val rowNext = (y + 1) * width

            for (x in 1 until width - 1) {
                val p00 = smoothedBuffer[rowPrev + x - 1].toInt() and 0xFF
                val p01 = smoothedBuffer[rowPrev + x].toInt() and 0xFF
                val p02 = smoothedBuffer[rowPrev + x + 1].toInt() and 0xFF

                val p10 = smoothedBuffer[row + x - 1].toInt() and 0xFF
                val p12 = smoothedBuffer[row + x + 1].toInt() and 0xFF

                val p20 = smoothedBuffer[rowNext + x - 1].toInt() and 0xFF
                val p21 = smoothedBuffer[rowNext + x].toInt() and 0xFF
                val p22 = smoothedBuffer[rowNext + x + 1].toInt() and 0xFF

                // Gx = [-1 0 1; -2 0 2; -1 0 1]
                val gx = (p02 + 2 * p12 + p22) - (p00 + 2 * p10 + p20)
                // Gy = [ 1 2 1;  0 0 0; -1 -2 -1]
                val gy = (p00 + 2 * p01 + p02) - (p20 + 2 * p21 + p22)

                val mag = abs(gx) + abs(gy)
                val idx = row + x
                gradientMagnitude[idx] = mag

                // Quantize angle into 4 sectors:
                // 0: Horizontal (-22.5° .. 22.5° or 157.5° .. 180°)
                // 1: Diagonal 45° (22.5° .. 67.5°)
                // 2: Vertical 90° (67.5° .. 112.5°)
                // 3: Diagonal 135° (112.5° .. 157.5°)
                val absGx = abs(gx)
                val absGy = abs(gy)

                val dir: Byte = if (absGx > 2 * absGy) {
                    0 // Horizontal edge (Vertical gradient)
                } else if (absGy > 2 * absGx) {
                    2 // Vertical edge (Horizontal gradient)
                } else if ((gx > 0 && gy > 0) || (gx < 0 && gy < 0)) {
                    1 // 45°
                } else {
                    3 // 135°
                }
                gradientDirection[idx] = dir
            }
        }
    }

    private fun applyHysteresis() {
        edgeMap.fill(0)

        for (y in 2 until height - 2) {
            val row = y * width
            for (x in 2 until width - 2) {
                val idx = row + x
                val mag = gradientMagnitude[idx]
                if (mag < lowThreshold) continue

                val dir = gradientDirection[idx]

                // Non-Maximum Suppression check along gradient normal
                val isLocalMax = when (dir.toInt()) {
                    0 -> mag >= gradientMagnitude[idx - 1] && mag >= gradientMagnitude[idx + 1]
                    1 -> mag >= gradientMagnitude[idx - width - 1] && mag >= gradientMagnitude[idx + width + 1]
                    2 -> mag >= gradientMagnitude[idx - width] && mag >= gradientMagnitude[idx + width]
                    3 -> mag >= gradientMagnitude[idx - width + 1] && mag >= gradientMagnitude[idx + width - 1]
                    else -> true
                }

                if (isLocalMax) {
                    if (mag >= highThreshold) {
                        edgeMap[idx] = 2 // Strong edge
                    } else {
                        edgeMap[idx] = 1 // Weak edge
                    }
                }
            }
        }

        // 8-connectivity edge linking
        for (y in 2 until height - 2) {
            val row = y * width
            for (x in 2 until width - 2) {
                val idx = row + x
                if (edgeMap[idx] == 1.toByte()) {
                    val hasStrongNeighbor = (
                        edgeMap[idx - 1] == 2.toByte() || edgeMap[idx + 1] == 2.toByte() ||
                            edgeMap[idx - width] == 2.toByte() || edgeMap[idx + width] == 2.toByte() ||
                            edgeMap[idx - width - 1] == 2.toByte() || edgeMap[idx - width + 1] == 2.toByte() ||
                            edgeMap[idx + width - 1] == 2.toByte() || edgeMap[idx + width + 1] == 2.toByte()
                        )
                    edgeMap[idx] = if (hasStrongNeighbor) 2.toByte() else 0.toByte()
                }
            }
        }
    }

    private fun extractHealthBars(screenCenterX: Float, screenCenterY: Float) {
        detectedBarCount = 0
        playerBar = null

        // Health bars: aspect ratio width / height in [2.0 .. 6.5]
        val minBarW = (width * 0.04f).toInt().coerceAtLeast(4)
        val maxBarW = (width * 0.50f).toInt().coerceAtLeast(16)
        val minBarH = (height * 0.03f).toInt().coerceAtLeast(3)
        val maxBarH = (height * 0.25f).toInt().coerceAtLeast(10)

        for (y in (height * 0.08f).toInt() until (height * 0.90f).toInt()) {
            var inBarEdge = false
            var edgeStartX = 0

            for (x in (width * 0.05f).toInt() until (width * 0.95f).toInt()) {
                val idx = y * width + x
                val isEdge = edgeMap[idx] == 2.toByte()

                if (isEdge && !inBarEdge) {
                    inBarEdge = true
                    edgeStartX = x
                } else if (!isEdge && inBarEdge) {
                    inBarEdge = false
                    val spanW = x - edgeStartX

                    if (spanW in minBarW..maxBarW) {
                        // Check if there is a corresponding parallel bottom edge minBarH..maxBarH below
                        var foundBottom = false
                        var bestH = 0

                        for (testH in minBarH..maxBarH) {
                            val bottomY = y + testH
                            if (bottomY >= height) break

                            var bottomEdgeHits = 0
                            val samplePoints = 6
                            val stepX = (spanW / samplePoints).coerceAtLeast(1)
                            for (s in 1 until samplePoints) {
                                val sx = edgeStartX + s * stepX
                                if (sx !in 0 until width) continue
                                val bIdx = bottomY * width + sx
                                val hit = edgeMap[bIdx] == 2.toByte() ||
                                        (testH > 2 && bottomY > 0 && edgeMap[bIdx - width] == 2.toByte()) ||
                                        (bottomY < height - 1 && edgeMap[bIdx + width] == 2.toByte())
                                if (hit) bottomEdgeHits++
                            }

                            if (bottomEdgeHits >= 2) {
                                foundBottom = true
                                bestH = testH
                                break
                            }
                        }

                        if (foundBottom && detectedBarCount < maxBars) {
                            val ratio = spanW.toFloat() / bestH.toFloat()
                            if (ratio in 2.0f..6.5f) { // Distinct ~4:1 ratio (allows segmented sections)
                                val bar = detectedBars[detectedBarCount]
                                bar.left = edgeStartX
                                bar.top = y
                                bar.right = x
                                bar.bottom = y + bestH
                                bar.widthPx = spanW
                                bar.heightPx = bestH
                                bar.aspectRatio = ratio
                                bar.centerX = edgeStartX + spanW / 2f
                                bar.centerY = y + bestH / 2f
                                bar.distToCenter = hypot(bar.centerX - screenCenterX, bar.centerY - screenCenterY)
                                bar.isPlayer = false
                                detectedBarCount++
                            }
                        }
                    }
                }
            }
        }

        // Rank by distance to screen center: The active brawler is closest to the screen center!
        var closestDist = Float.MAX_VALUE
        var bestPlayerIdx = -1

        for (i in 0 until detectedBarCount) {
            val bar = detectedBars[i]
            if (bar.distToCenter < closestDist) {
                closestDist = bar.distToCenter
                bestPlayerIdx = i
            }
        }

        if (bestPlayerIdx >= 0) {
            val pBar = detectedBars[bestPlayerIdx]
            pBar.isPlayer = true
            playerBar = pBar
        }
    }
}
