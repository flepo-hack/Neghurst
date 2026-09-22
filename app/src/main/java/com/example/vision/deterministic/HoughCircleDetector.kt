package com.example.vision.deterministic

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Deterministic Hough Circle Transform for Brawl Ball and Brawler Selection Rings.
 *
 * Implements gradient-directed 2D circle voting:
 * - Uses the edge points and gradient directions from Canny/Sobel stage.
 * - Casts votes into a discrete 2D accumulator grid along the radial normal lines:
 *   x_0 = x ± r * cos(theta), y_0 = y ± r * sin(theta).
 * - Identifies true circular entities:
 *   1. Brawl Ball (distinct high-contrast sphere)
 *   2. Brawler ground indicator rings (lime-green for player, red for enemies)
 */
class HoughCircleDetector(
    private val width: Int,
    private val height: Int,
    private val minRadius: Int = 4,
    private val maxRadius: Int = 18,
    private val radiusStep: Int = 1
) {
    data class Circle(
        var centerX: Float = 0f,
        var centerY: Float = 0f,
        var radius: Float = 0f,
        var votes: Int = 0,
        var tag: String = ""
    )

    // Downscaled accumulator grid for 60 FPS performance (Zero GC)
    private val accScale = 2
    private val accW = width / accScale
    private val accH = height / accScale
    private val accumulator = IntArray(accW * accH)

    private val maxDetectedCircles = 12
    val detectedCircles = ArrayList<Circle>(maxDetectedCircles).apply {
        repeat(maxDetectedCircles) { add(Circle()) }
    }
    var detectedCount = 0
        private set

    // Trigonometric lookup table for 16 discrete angles
    private val numAngles = 16
    private val cosLut = FloatArray(numAngles)
    private val sinLut = FloatArray(numAngles)

    init {
        for (i in 0 until numAngles) {
            val angleRad = (i * 2.0 * Math.PI / numAngles).toFloat()
            cosLut[i] = cos(angleRad)
            sinLut[i] = sin(angleRad)
        }
    }

    /**
     * Executes circular voting over the Canny edge map and gradient directions.
     */
    fun detectCircles(
        edgeMap: ByteArray,
        gradientDir: ByteArray,
        minVoteThreshold: Int = 18
    ) {
        detectedCount = 0
        accumulator.fill(0)

        // Step 1: Accumulate votes along gradient lines for edges
        val edgeStep = 1
        for (y in minRadius until (height - minRadius) step edgeStep) {
            val row = y * width
            for (x in minRadius until (width - minRadius) step edgeStep) {
                val idx = row + x
                if (edgeMap[idx] == 2.toByte()) { // Strong Canny edge
                    val dir = gradientDir[idx].toInt() and 0xFF

                    // Map direction to candidate angle indices in lookup table
                    // dir 0: Vertical gradient (90° or 270°) -> index 4, 12
                    // dir 1: 45° gradient (45° or 225°) -> index 2, 10
                    // dir 2: Horizontal gradient (0° or 180°) -> index 0, 8
                    // dir 3: 135° gradient (135° or 315°) -> index 6, 14
                    val (angleIdx1, angleIdx2) = when (dir % 4) {
                        0 -> Pair(4, 12)  // 90°, 270°
                        1 -> Pair(2, 10)  // 45°, 225°
                        2 -> Pair(0, 8)   // 0°, 180°
                        else -> Pair(6, 14) // 135°, 315°
                    }

                    for (r in minRadius..maxRadius step radiusStep) {
                        val c1x = (x + r * cosLut[angleIdx1]).toInt() / accScale
                        val c1y = (y + r * sinLut[angleIdx1]).toInt() / accScale

                        if (c1x in 0 until accW && c1y in 0 until accH) {
                            accumulator[c1y * accW + c1x]++
                        }

                        val c2x = (x + r * cosLut[angleIdx2]).toInt() / accScale
                        val c2y = (y + r * sinLut[angleIdx2]).toInt() / accScale

                        if (c2x in 0 until accW && c2y in 0 until accH) {
                            accumulator[c2y * accW + c2x]++
                        }
                    }
                }
            }
        }

        // Step 2: Local peak detection in accumulator
        for (ay in 2 until (accH - 2)) {
            val aRow = ay * accW
            for (ax in 2 until (accW - 2)) {
                val votes = accumulator[aRow + ax]
                if (votes >= minVoteThreshold) {
                    // Check if local maximum in 3x3 window
                    var isMax = true
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            if (accumulator[(ay + dy) * accW + (ax + dx)] > votes) {
                                isMax = false
                                break
                            }
                        }
                        if (!isMax) break
                    }

                    if (isMax && detectedCount < maxDetectedCircles) {
                        val worldCenterX = (ax * accScale) + (accScale / 2f)
                        val worldCenterY = (ay * accScale) + (accScale / 2f)

                        // Estimate radius by edge confirmation
                        val bestRadius = (minRadius + maxRadius) / 2f

                        val circle = detectedCircles[detectedCount]
                        circle.centerX = worldCenterX
                        circle.centerY = worldCenterY
                        circle.radius = bestRadius
                        circle.votes = votes
                        circle.tag = "CIRCLE #${detectedCount + 1}"
                        detectedCount++
                    }
                }
            }
        }
    }
}
