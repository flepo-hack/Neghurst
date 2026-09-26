package com.example.vision.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin implementation of the same deterministic pipeline as
 * `rendera_native.cpp`, used when the native library is unavailable for the
 * device ABI. The two are kept behaviourally identical on purpose.
 *
 * Everything runs on flat primitive arrays that are allocated once in [init],
 * so a frame of processing performs no heap allocation and cannot trigger a GC
 * pause in the middle of a dodge.
 */
class VisionPipeline(
    val cols: Int,
    val rows: Int,
    /** Brawl Stars tile size in analysis cells; the game's distance unit. */
    val tileCells: Float = DEFAULT_TILE_CELLS
) {
    companion object {
        const val DEFAULT_TILE_CELLS = 9f

        // Tracker gains, verified numerically against the quantisation noise
        // model of this grid. See rendera_native.cpp for the measurements.
        const val ALPHA = 0.58f
        const val BETA = 0.16f
        const val GATE_K = 3.0f
        const val RESIDUAL_FLOOR = 0.55f
        const val MAX_RESIDUAL = 1.15f
        const val MIN_HITS_FOR_PROJECTILE = 3

        const val MAX_TRACKS = 24
        const val MAX_OBSERVATIONS = 48
        const val MAX_EXCLUSIONS = 16
        const val MAX_BRAWLERS = 16

        private const val MOTION_BLOCK_W = 12
        private const val MOTION_BLOCK_H = 8
        private const val COARSE_STRIDE = 2

        const val TRACK_STRIDE = 8

        private val NBX = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
        private val NBY = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)
        private val COS8 = floatArrayOf(1f, 0.7071f, 0f, -0.7071f, -1f, -0.7071f, 0f, 0.7071f)
        private val SIN8 = floatArrayOf(0f, 0.7071f, 1f, 0.7071f, 0f, -0.7071f, -1f, -0.7071f)

        private val BLOCK_CENTERS = floatArrayOf(
            0.32f, 0.30f,
            0.64f, 0.28f,
            0.46f, 0.50f,
            0.22f, 0.55f
        )
    }

    private val n = cols * rows

    // ---- per-frame buffers (allocated once) ----
    private val grid = ByteArray(n)
    private val prevGrid = ByteArray(n)
    private val diff = ByteArray(n)
    private val mask = ByteArray(n)
    private val visited = ByteArray(n)
    private val queueX = IntArray(n)
    private val queueY = IntArray(n)

    // ---- calibration / Canny buffers ----
    private var calibrationLuma: ByteArray? = null
    private val structure = ByteArray(n)
    private val edgeMap = ByteArray(n)
    private val gradMag = IntArray(n)
    private val gradDir = ByteArray(n)

    // ---- blob scratch ----
    private val obsX = FloatArray(MAX_OBSERVATIONS)
    private val obsY = FloatArray(MAX_OBSERVATIONS)
    private val obsW = FloatArray(MAX_OBSERVATIONS)
    private var obsCount = 0
    private val obsMatched = BooleanArray(MAX_OBSERVATIONS)

    // ---- tracks ----
    val tracks = Array(MAX_TRACKS) { Track() }
    private var nextTrackId = 1

    // ---- output ----
    val outTracks = FloatArray(MAX_TRACKS * TRACK_STRIDE)
    var trackCount = 0; private set
    var projectileCount = 0; private set
    var brawlerCount = 0; private set
    val brawlerX = IntArray(MAX_BRAWLERS)
    val brawlerY = IntArray(MAX_BRAWLERS)

    var motionDx = 0; private set
    var motionDy = 0; private set
    var cameraMoving = false; private set
    var noiseFloor = 14f; private set
    var hasPreviousFrame = false; private set
    var framesSeen = 0L; private set

    // ---- tunables ----
    var motionNoiseFloor = 12f
    var minBlobWeight = 110f
    var maxCameraShift = 6
    var minProjectileSpeed = 45f
    var maxProjectileSpeed = 210f
    var playerRadiusCells = 3f
    var joyRadiusCells = 12f

    // ---- anchors (analysis-grid cells) ----
    var playerAnchorX = -1
    var playerAnchorY = -1
    var joyAnchorX = -1
    var joyAnchorY = -1
    var playerAnchorConfirmed = false
    var joyAnchorConfirmed = false

    private val exclusionRects = IntArray(MAX_EXCLUSIONS * 4)
    private var exclusionCount = 0

    // ---- world-frame state ----
    private var accumX = 0f
    private var accumY = 0f
    private var accumSmoothX = 0f
    private var accumSmoothY = 0f
    private var accumAtAnchorX = 0f
    private var accumAtAnchorY = 0f
    private var playerWorldX = 0f
    private var playerWorldY = 0f
    private val motionHistX = IntArray(3)
    private val motionHistY = IntArray(3)
    private var motionHistIdx = 0

    // ---- calibration outputs ----
    var calibJoyScore = 0f; private set
    var calibJoyRingCells = 0f; private set
    var calibPlayerScore = 0f; private set

    class Track {
        var alive = false
        var id = 0
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var speed = 0f
        var residual = 0f
        var disturbed = 0
        var isProjectile = false
        var isBrawler = false
        var hits = 0
        var misses = 0
        var lastSeenMs = 0L
        var m0x = 0f
        var m0y = 0f
        var m1x = 0f
        var m1y = 0f

        fun clear() {
            alive = false
            x = 0f; y = 0f; vx = 0f; vy = 0f; speed = 0f
            residual = 0f; disturbed = 0
            isProjectile = false; isBrawler = false
            hits = 0; misses = 0; lastSeenMs = 0L
            m0x = 0f; m0y = 0f; m1x = 0f; m1y = 0f
        }
    }

    // =====================================================================
    fun reset() {
        grid.fill(0); prevGrid.fill(0); diff.fill(0)
        mask.fill(0); visited.fill(0)
        edgeMap.fill(0); gradMag.fill(0); gradDir.fill(0)
        for (t in tracks) t.clear()
        nextTrackId = 1
        accumX = 0f; accumY = 0f
        accumSmoothX = 0f; accumSmoothY = 0f
        accumAtAnchorX = 0f; accumAtAnchorY = 0f
        motionHistX.fill(0); motionHistY.fill(0); motionHistIdx = 0
        motionDx = 0; motionDy = 0; cameraMoving = false
        noiseFloor = motionNoiseFloor
        hasPreviousFrame = false
        framesSeen = 0L
        trackCount = 0; projectileCount = 0; brawlerCount = 0
        calibJoyScore = 0f; calibPlayerScore = 0f; calibJoyRingCells = 0f
    }

    fun setExclusionRects(cellRects: IntArray, count: Int) {
        exclusionCount = min(count, MAX_EXCLUSIONS)
        val n = exclusionCount * 4
        if (n > 0) System.arraycopy(cellRects, 0, exclusionRects, 0, n)
    }

    fun anchorPlayerCell(cx: Int, cy: Int, confirmed: Boolean) {
        playerAnchorX = cx
        playerAnchorY = cy
        accumAtAnchorX = accumX
        accumAtAnchorY = accumY
        playerWorldX = cx - accumAtAnchorX
        playerWorldY = cy - accumAtAnchorY
        if (confirmed) playerAnchorConfirmed = true
    }

    fun anchorJoystickCell(cx: Int, cy: Int, confirmed: Boolean) {
        joyAnchorX = cx
        joyAnchorY = cy
        if (confirmed) joyAnchorConfirmed = true
    }

    // =====================================================================
    fun cellsToScreenX(cell: Float, screenW: Int): Float =
        if (screenW <= 0) 0f else cell * screenW.toFloat() / cols

    fun cellsToScreenY(cell: Float, screenH: Int): Float =
        if (screenH <= 0) 0f else cell * screenH.toFloat() / rows

    fun screenToCellX(px: Float, screenW: Int): Int =
        if (screenW <= 0) 0 else ((px / screenW) * cols).roundToInt()

    fun screenToCellY(px: Float, screenH: Int): Int =
        if (screenH <= 0) 0 else ((px / screenH) * rows).roundToInt()

    // =====================================================================
    /** Copies the capture-resolution luma into the analysis grid. */
    fun loadLuma(luma: ByteArray, captureW: Int, captureH: Int, rowStride: Int) {
        if (captureW <= 0 || captureH <= 0) return
        val bw = max(1, captureW / cols)
        val bh = max(1, captureH / rows)
        val rs = if (rowStride > 0) rowStride else captureW
        for (gy in 0 until rows) {
            val y0 = gy * captureH / rows
            val y1 = min(captureH - 1, y0 + bh)
            val ys0 = y0 * rs
            val ys1 = y1 * rs
            val rowBase = gy * cols
            for (gx in 0 until cols) {
                val x0 = gx * captureW / cols
                val x1 = min(captureW - 1, x0 + bw)
                val s = luma[ys0 + x0].toInt() and 0xFF +
                    luma[ys0 + x1].toInt() and 0xFF +
                    luma[ys1 + x0].toInt() and 0xFF +
                    luma[ys1 + x1].toInt() and 0xFF
                grid[rowBase + gx] = (s shr 2).toByte()
            }
        }
    }

    // =====================================================================
    fun process(dtSec: Float, nowMs: Long): Boolean {
        val dt = max(0.008f, min(0.050f, dtSec))
        buildMask()
        if (hasPreviousFrame) {
            estimateMotion()
            accumX += motionDx.toFloat()
            accumY += motionDy.toFloat()
            buildDiff()
            estimateNoiseFloor()
            extractObservations()
            updateTracks(dt, nowMs)
        } else {
            hasPreviousFrame = true
        }
        accumSmoothX += 0.22f * (accumX - accumSmoothX)
        accumSmoothY += 0.22f * (accumY - accumSmoothY)
        System.arraycopy(grid, 0, prevGrid, 0, n)
        publishTracks()
        framesSeen++
        return true
    }

    // ---------------------------------------------------------------- mask
    private fun buildMask() {
        val topBand = (rows * 0.085f).toInt()
        val bottomBand = (rows * 0.900f).toInt()
        val rightEdge = (cols * 0.775f).toInt()
        val rightTop = (rows * 0.600f).toInt()
        val joyRestrict = (cols * 0.52f).toInt()
        for (gy in 0 until rows) {
            val base = gy * cols
            for (gx in 0 until cols) {
                var ok = true
                if (gy < topBand) ok = false                       // score / timer
                if (gy > bottomBand && gx < joyRestrict) ok = false // joystick rest area
                if (gx > rightEdge && gy > rightTop) ok = false     // attack/super/ammo
                mask[base + gx] = if (ok) 1 else 0
            }
        }
        for (i in 0 until exclusionCount) {
            val x0 = max(0, exclusionRects[i * 4])
            val y0 = max(0, exclusionRects[i * 4 + 1])
            val x1 = min(cols, exclusionRects[i * 4 + 2])
            val y1 = min(rows, exclusionRects[i * 4 + 3])
            for (gy in y0 until y1) {
                for (gx in x0 until x1) mask[gy * cols + gx] = 0
            }
        }
    }

    // --------------------------------------------------------------- motion
    private fun estimateMotion() {
        motionDx = 0; motionDy = 0; cameraMoving = false
        val blocks = 4
        var sumDx = 0L
        var sumDy = 0L
        var used = 0
        for (b in 0 until blocks) {
            val bx = (BLOCK_CENTERS[b * 2] * cols - MOTION_BLOCK_W * 0.5f).toInt()
            val by = (BLOCK_CENTERS[b * 2 + 1] * rows - MOTION_BLOCK_H * 0.5f).toInt()
            var samples = 0
            for (y in by until by + MOTION_BLOCK_H) {
                if (y < 0 || y >= rows) continue
                for (x in bx until bx + MOTION_BLOCK_W) {
                    if (x < 0 || x >= cols) continue
                    if (mask[y * cols + x] != 0.toByte()) samples++
                }
            }
            if (samples < 24) continue
            val zeroSad = sadAt(bx, by, 0, 0, 1)
            var bestSad = sadAt(bx, by, 0, 0, COARSE_STRIDE)
            var bestDx = 0
            var bestDy = 0
            var dy = -maxCameraShift
            while (dy <= maxCameraShift) {
                var dx = -maxCameraShift
                while (dx <= maxCameraShift) {
                    if (dx != 0 || dy != 0) {
                        val s = sadAt(bx, by, dx, dy, COARSE_STRIDE)
                        if (s < bestSad) { bestSad = s; bestDx = dx; bestDy = dy }
                    }
                    dx += COARSE_STRIDE
                }
                dy += COARSE_STRIDE
            }
            for (oy in bestDy - 1..bestDy + 1) {
                for (ox in bestDx - 1..bestDx + 1) {
                    if (ox == 0 && oy == 0) continue
                    if (abs(ox) > maxCameraShift || abs(oy) > maxCameraShift) continue
                    val s = sadAt(bx, by, ox, oy, 1)
                    if (s < bestSad) { bestSad = s; bestDx = ox; bestDy = oy }
                }
            }
            if (bestSad + 3 < zeroSad) {
                sumDx += bestDx
                sumDy += bestDy
                used++
            }
        }
        if (used == 0) return
        var dx = (sumDx.toFloat() / used).roundToInt()
        var dy = (sumDy.toFloat() / used).roundToInt()
        dx = max(-maxCameraShift, min(maxCameraShift, dx))
        dy = max(-maxCameraShift, min(maxCameraShift, dy))
        motionHistX[motionHistIdx] = dx
        motionHistY[motionHistIdx] = dy
        motionHistIdx = (motionHistIdx + 1) % 3
        motionDx = median3(motionHistX)
        motionDy = median3(motionHistY)
        cameraMoving = abs(motionDx) + abs(motionDy) > 0
    }

    private fun sadAt(bx: Int, by: Int, dx: Int, dy: Int, step: Int): Int {
        var sad = 0
        var cnt = 0
        var y = by
        while (y < by + MOTION_BLOCK_H) {
            val py = y + dy
            if (py in 0 until rows) {
                var x = bx
                while (x < bx + MOTION_BLOCK_W) {
                    val px = x + dx
                    if (px in 0 until cols && mask[y * cols + x] != 0.toByte()) {
                        sad += abs(
                            (grid[y * cols + x].toInt() and 0xFF) -
                                (prevGrid[py * cols + px].toInt() and 0xFF)
                        )
                        cnt++
                    }
                    x += step
                }
            }
            y += step
        }
        return if (cnt > 0) sad / cnt else Int.MAX_VALUE / 4
    }

    private fun median3(h: IntArray): Int {
        val a = h[0]; val b = h[1]; val c = h[2]
        val s = intArrayOf(a, b, c)
        s.sort()
        return s[1]
    }

    private fun buildDiff() {
        val sx = motionDx
        val sy = motionDy
        for (y in 0 until rows) {
            val py = max(0, min(rows - 1, y + sy))
            val base = y * cols
            val pbase = py * cols
            for (x in 0 until cols) {
                val i = base + x
                if (mask[i] == 0.toByte()) { diff[i] = 0; continue }
                val px = max(0, min(cols - 1, x + sx))
                diff[i] = abs(
                    (grid[i].toInt() and 0xFF) - (prevGrid[pbase + px].toInt() and 0xFF)
                ).toByte()
            }
        }
    }

    private fun estimateNoiseFloor() {
        var sum = 0L
        var cnt = 0
        for (i in 0 until n) {
            if (mask[i] == 0.toByte()) continue
            sum += (diff[i].toInt() and 0xFF)
            cnt++
        }
        if (cnt == 0) return
        val mean = sum.toFloat() / cnt
        val target = max(motionNoiseFloor, mean * 2.6f + 3f)
        noiseFloor += 0.2f * (target - noiseFloor)
    }

    // -------------------------------------------------------------- blobs
    private fun extractObservations() {
        obsCount = 0
        val thr = (noiseFloor.toInt() + 4).coerceIn(3, 200)
        java.util.Arrays.fill(visited, 0.toByte())
        val joyX = joyAnchorX.toFloat()
        val joyY = joyAnchorY.toFloat()

        for (gy in 1 until rows - 1) {
            val base = gy * cols
            for (gx in 1 until cols - 1) {
                val idx = base + gx
                if (visited[idx] != 0.toByte()) continue
                if ((diff[idx].toInt() and 0xFF) <= thr) continue

                var head = 0
                var tail = 0
                queueX[tail] = gx; queueY[tail] = gy; tail++
                visited[idx] = 1
                var sumX = 0.0
                var sumY = 0.0
                var sumW = 0.0
                var cells = 0
                var minX = gx; var maxX = gx; var minY = gy; var maxY = gy
                var bad = false

                while (head < tail) {
                    val cx = queueX[head]
                    val cy = queueY[head]
                    head++
                    val w = (diff[cy * cols + cx].toInt() and 0xFF) - thr + 1f
                    sumX += cx * w
                    sumY += cy * w
                    sumW += w
                    cells++
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy
                    if (cells > 900) { bad = true; break }
                    for (k in 0 until 8) {
                        val nx = cx + NBX[k]
                        val ny = cy + NBY[k]
                        if (nx < 1 || nx >= cols - 1 || ny < 1 || ny >= rows - 1) continue
                        val ni = ny * cols + nx
                        if (visited[ni] != 0.toByte()) continue
                        if ((diff[ni].toInt() and 0xFF) <= thr) continue
                        visited[ni] = 1
                        if (tail >= n) { bad = true; break }
                        queueX[tail] = nx; queueY[tail] = ny; tail++
                    }
                }

                if (bad || cells < 2) continue
                if (sumW < minBlobWeight) continue
                val bw = maxX - minX + 1
                val bh = maxY - minY + 1
                if (bw > cols * 0.45f || bh > rows * 0.45f) continue
                if (obsCount >= MAX_OBSERVATIONS) break

                val cellCx = (sumX / sumW).toFloat()
                val cellCy = (sumY / sumW).toFloat()
                if (hypot(cellCx - joyX, cellCy - joyY) < joyRadiusCells * 1.05f) continue
                val wx = cellCx - accumX
                val wy = cellCy - accumY
                if (hypot(wx - playerWorldX, wy - playerWorldY) < playerRadiusCells * 1.15f) continue

                obsX[obsCount] = wx
                obsY[obsCount] = wy
                obsW[obsCount] = cells.toFloat()
                obsCount++
            }
        }
    }

    // ------------------------------------------------------------ tracking
    private fun updateTracks(dt: Float, nowMs: Long) {
        for (t in tracks) {
            if (!t.alive) continue
            t.x += t.vx * dt
            t.y += t.vy * dt
        }
        java.util.Arrays.fill(obsMatched, false, 0, obsCount)

        for (t in tracks) {
            if (!t.alive) continue
            val sig = sqrt(abs(t.speed) * 0.5f) + 4f
            val gate = 6f * sig
            val gateSq = gate * gate
            var bestD = gateSq
            var bestI = -1
            for (i in 0 until obsCount) {
                if (obsMatched[i]) continue
                val dx = obsX[i] - t.x
                val dy = obsY[i] - t.y
                val d2 = dx * dx + dy * dy
                if (d2 < bestD) { bestD = d2; bestI = i }
            }
            if (bestI < 0) {
                t.misses++
                if (t.misses > 6 || (nowMs - t.lastSeenMs) > 320L) t.alive = false
                continue
            }
            obsMatched[bestI] = true
            observeOnTrack(t, obsX[bestI], obsY[bestI], dt)
            t.misses = 0
            t.lastSeenMs = nowMs
        }

        for (i in 0 until obsCount) {
            if (obsMatched[i]) continue
            var slot: Track? = null
            for (t in tracks) {
                if (!t.alive) { slot = t; break }
            }
            val s = slot ?: break
            s.clear()
            s.alive = true
            s.id = nextTrackId++
            s.x = obsX[i]; s.y = obsY[i]; s.m0x = obsX[i]; s.m0y = obsY[i]
            s.lastSeenMs = nowMs
        }
    }

    /** Gated alpha-beta update with the two-frame velocity bootstrap. */
    private fun observeOnTrack(t: Track, mx: Float, my: Float, dt: Float) {
        val invDt = 1f / dt
        when (t.hits) {
            1 -> {
                t.vx = (mx - t.m0x) * invDt
                t.vy = (my - t.m0y) * invDt
                t.x = mx; t.y = my
            }
            2 -> {
                val shortX = (mx - t.m0x) * invDt
                val shortY = (my - t.m0y) * invDt
                val longX = (mx - t.m1x) * (0.5f * invDt)
                val longY = (my - t.m1y) * (0.5f * invDt)
                t.vx = 0.35f * shortX + 0.65f * longX
                t.vy = 0.35f * shortY + 0.65f * longY
                t.x = mx; t.y = my
            }
            else -> {
                val rx = mx - t.x
                val ry = my - t.y
                val res = hypot(rx, ry)
                t.residual += 0.30f * (res - t.residual)
                if (res > GATE_K * (t.residual + RESIDUAL_FLOOR)) {
                    t.disturbed++
                } else {
                    t.x += ALPHA * rx
                    t.y += ALPHA * ry
                    t.vx += BETA * rx * invDt
                    t.vy += BETA * ry * invDt
                }
            }
        }
        t.m1x = t.m0x; t.m1y = t.m0y
        t.m0x = mx;     t.m0y = my
        t.speed = hypot(t.vx, t.vy)
        t.hits++
        t.isProjectile = t.hits >= MIN_HITS_FOR_PROJECTILE &&
            t.disturbed == 0 &&
            t.speed > minProjectileSpeed &&
            t.speed < maxProjectileSpeed &&
            t.residual < MAX_RESIDUAL
        t.isBrawler = t.hits >= 8 && !t.isProjectile && t.speed > 3f
    }

    private fun publishTracks() {
        var idx = 0
        var proj = 0
        var brawlers = 0
        for (i in 0 until MAX_TRACKS) {
            val t = tracks[i]
            if (!t.alive) continue
            if (idx >= MAX_TRACKS) break
            val b = idx * TRACK_STRIDE
            outTracks[b] = t.x + accumSmoothX
            outTracks[b + 1] = t.y + accumSmoothY
            outTracks[b + 2] = t.vx
            outTracks[b + 3] = t.vy
            outTracks[b + 4] = t.speed
            outTracks[b + 5] = t.residual
            outTracks[b + 6] = t.hits.toFloat()
            outTracks[b + 7] = if (t.isProjectile) 1f else 0f
            if (t.isProjectile) proj++
            if (t.isBrawler) {
                if (brawlers < MAX_BRAWLERS) {
                    brawlerX[brawlers] = t.x + accumSmoothX
                    brawlerY[brawlers] = t.y + accumSmoothY
                }
                brawlers++
            }
            idx++
        }
        trackCount = idx
        projectileCount = proj
        brawlerCount = min(brawlers, MAX_BRAWLERS)
    }

    // -------------------------------------------------------- threat solver
    class Threat(
        val x: Float,
        val y: Float,
        val vx: Float,
        val vy: Float,
        val speed: Float,
        val timeToImpactMs: Long,
        val levelRaw: Int,
        val dodgeAngleDeg: Float,
        val dodgeDirX: Float,
        val dodgeDirY: Float
    )

    /** Closest-point-of-approach test plus perpendicular escape selection. */
    fun solveThreat(): Threat? {
        var bestT = Float.MAX_VALUE
        var found: Threat? = null
        for (t in tracks) {
            if (!t.alive || !t.isProjectile) continue
            val rx = playerWorldX - t.x
            val ry = playerWorldY - t.y
            val vv = t.vx * t.vx + t.vy * t.vy
            if (vv < 1f) continue
            val dot = rx * t.vx + ry * t.vy
            if (dot <= 0f) continue
            val tcpa = dot / vv
            if (tcpa < 0f || tcpa > 0.35f) continue
            val miss = hypot(rx - t.vx * tcpa, ry - t.vy * tcpa)
            if (miss > playerRadiusCells * 1.35f) continue
            if (tcpa >= bestT) continue
            bestT = tcpa

            val traj = kotlin.math.atan2(t.vy.toDouble(), t.vx.toDouble())
            val a1 = traj + Math.PI / 2.0
            val a2 = traj - Math.PI / 2.0
            val chosen = if (escapeScore(a1.toFloat()) >= escapeScore(a2.toFloat())) a1 else a2
            found = Threat(
                x = t.x + accumSmoothX,
                y = t.y + accumSmoothY,
                vx = t.vx,
                vy = t.vy,
                speed = t.speed,
                timeToImpactMs = (tcpa * 1000f).toLong(),
                levelRaw = if (tcpa < 0.15f) 3 else if (tcpa < 0.26f) 2 else 1,
                dodgeAngleDeg = normalizeDeg((Math.toDegrees(chosen) + 360.0).toFloat() % 360f),
                dodgeDirX = cos(chosen).toFloat(),
                dodgeDirY = sin(chosen).toFloat()
            )
        }
        return found
    }

    private fun escapeScore(angleRad: Float): Float {
        val step = max(2f, joyRadiusCells)
        var run = 0f
        var s = 1
        while (s <= 6) {
            val px = playerWorldX + cos(angleRad) * step * s
            val py = playerWorldY + sin(angleRad) * step * s
            if (px < 1f || px > cols - 2f || py < 1f || py > rows - 2f) break
            val cx = (px + accumX).toInt()
            val cy = (py + accumY).toInt()
            if (cx < 0 || cx >= cols || cy < 0 || cy >= rows) break
            if (mask[cy * cols + cx] == 0.toByte()) break
            run = step * s
            s++
        }
        return run
    }

    fun playerCellX(): Float = playerAnchorX - (accumSmoothX - accumAtAnchorX)
    fun playerCellY(): Float = playerAnchorY - (accumSmoothY - accumAtAnchorY)

    fun playerWorldCellX(): Float = playerWorldX
    fun playerWorldCellY(): Float = playerWorldY

    private fun normalizeDeg(d: Float): Float {
        var v = d % 360f
        if (v < 0f) v += 360f
        return v
    }

    // =====================================================================
    //  Auto-calibration detectors (current frame only)
    // =====================================================================

    /**
     * Locates the joystick base ring in the lower-left quadrant by voting along
     * the Canny gradient normals. Returns a score in [0,1]; 0 means "not found".
     */
    fun detectJoystickRing(luma: ByteArray, captureW: Int, captureH: Int, rowStride: Int): Boolean {
        calibrationLuma = luma
        loadLuma(luma, captureW, captureH, rowStride)
        buildStaticStructure(captureW, captureH, rowStride)
        val accScale = 2
        val aw = max(1, cols / accScale)
        val ah = max(1, rows / accScale)
        val acc = IntArray(aw * ah)
        val xLimit = (cols * 0.50f).toInt()
        val yStart = (rows * 0.42f).toInt()
        val yEnd = (rows * 0.94f).toInt()
        for (gy in yStart until yEnd) {
            for (gx in 0 until xLimit) {
                if (edgeMap[gy * cols + gx] != 2.toByte()) continue
                for (r in 5..17 step 2) {
                    for (s in 0 until 8) {
                        val cx = (gx + (r * COS8[s]).toInt()) / accScale
                        val cy = (gy + (SIN8[s]).toInt()) / accScale
                        if (cx in 0 until aw && cy in 0 until ah) acc[cy * aw + cx]++
                    }
                }
            }
        }
        var bestVotes = 0
        var bestAx = -1
        var bestAy = -1
        for (ay in 1 until ah - 1) {
            for (ax in 1 until aw - 1) {
                val v = acc[ay * aw + ax]
                if (v < 40) continue
                var isMax = true
                for (dy in -1..1) {
                    if (!isMax) break
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        if (acc[(ay + dy) * aw + (ax + dx)] > v) { isMax = false; break }
                    }
                }
                if (isMax && v > bestVotes) { bestVotes = v; bestAx = ax; bestAy = ay }
            }
        }
        if (bestAx < 0 || bestVotes < 90) {
            calibJoyScore = 0f
            return false
        }
        val cxf = bestAx * accScale + 0.5f
        val cyf = bestAy * accScale + 0.5f
        var bestRing = -1f
        var bestRadius = 0f
        for (r in 5..20) {
            var hits = 0
            var total = 0
            var a = 0
            while (a < 32) {
                val ang = a * (2.0 * Math.PI / 32.0)
                val sx = (cxf + r * kotlin.math.cos(ang)).toInt()
                val sy = (cyf + r * kotlin.math.sin(ang)).toInt()
                if (sx in 0 until cols && sy in 0 until rows) {
                    total++
                    if (edgeMap[sy * cols + sx] == 2.toByte()) hits++
                }
                a++
            }
            if (total == 0) continue
            val frac = hits.toFloat() / total
            if (frac > bestRing) { bestRing = frac; bestRadius = r.toFloat() }
        }
        calibJoyScore = min(1f, bestVotes / 320f) * 0.6f + bestRing * 0.4f
        calibJoyRingCells = bestRadius
        calibJoyCellX = cxf.toInt()
        calibJoyCellY = cyf.toInt()
        return true
    }

    var calibJoyCellX = -1; private set
    var calibJoyCellY = -1; private set
    var calibPlayerCellX = -1; private set
    var calibPlayerCellY = -1; private set

    /**
     * Proposes our own brawler's anchor from health-bar geometry, ranked by
     * distance to screen centre and by the density of structure directly
     * beneath the bar (a character has a body under its own bar).
     *
     * Deliberately independent of chroma: the Cb/Cr plane order is not
     * guaranteed across Android devices, so any colour-based identity test
     * would silently invert on some hardware.
     */
    fun detectPlayerAnchor(luma: ByteArray, captureW: Int, captureH: Int, rowStride: Int): Boolean {
        calibrationLuma = luma
        loadLuma(luma, captureW, captureH, rowStride)
        buildStaticStructure(captureW, captureH, rowStride)
        val minW = max(5, (cols * 0.055f).toInt())
        val maxW = max(minW + 2, (cols * 0.40f).toInt())
        val minH = max(3, (rows * 0.028f).toInt())
        val maxH = max(minH + 2, (rows * 0.16f).toInt())
        val cx = cols * 0.5f
        val cy = rows * 0.5f
        var bestScore = 0f
        var bestX = -1f
        var bestY = -1f
        val yTop = max(2, (rows * 0.14f).toInt())
        val yBot = (rows * 0.80f).toInt()
        val xL = max(2, (cols * 0.12f).toInt())
        val xR = (cols * 0.88f).toInt()
        for (y in yTop until yBot) {
            var runStart = -1
            for (x in xL..xR) {
                val isEdge = x < xR && edgeMap[y * cols + x] == 2.toByte()
                if (isEdge && runStart < 0) {
                    runStart = x
                } else if (!isEdge && runStart >= 0) {
                    val startX = runStart
                    val spanW = x - startX
                    runStart = -1
                    if (spanW < minW || spanW > maxW) continue
                    var h = minH
                    while (h <= maxH) {
                        val bottomY = y + h
                        if (bottomY >= yBot) break
                        var hits = 0
                        val stepX = max(1, spanW / 5)
                        for (s in 1 until 5) {
                            val sx = startX + s * stepX
                            if (sx < 0 || sx >= cols) continue
                            if (edgeMap[bottomY * cols + sx] == 2.toByte() ||
                                (bottomY > 0 && edgeMap[(bottomY - 1) * cols + sx] == 2.toByte()) ||
                                edgeMap[(bottomY + 1) * cols + sx] == 2.toByte()
                            ) hits++
                        }
                        if (hits >= 3) {
                            val ratio = spanW.toFloat() / h
                            if (ratio in 2.4f..6.0f) {
                                val barCx = startX + spanW * 0.5f
                                val barCy = bottomY.toFloat()
                                val density = blobDensityBelow(barCx, barCy, spanW.toFloat())
                                val dist = hypot(barCx - cx, barCy - cy)
                                val distScore = 1f - min(1f, dist / (cols * 0.34f))
                                val score = distScore * 0.58f + density * 0.42f
                                if (score > bestScore) { bestScore = score; bestX = barCx; bestY = barCy }
                            }
                            break
                        }
                        h++
                    }
                }
            }
        }
        if (bestX < 0f || bestScore < 0.28f) {
            calibPlayerScore = 0f
            return false
        }
        val offset = max(2f, rows * 0.045f)
        calibPlayerScore = bestScore
        calibPlayerCellX = bestX.toInt()
        calibPlayerCellY = (bestY + offset).toInt()
        playerAnchorConfirmed = true
        return true
    }

    private fun blobDensityBelow(cx: Float, cy: Float, halfWidth: Float): Float {
        val y0 = cy.toInt() + 1
        val y1 = min(rows, y0 + (rows * 0.14f).toInt())
        val x0 = (cx - halfWidth * 0.5f).toInt()
        val x1 = (cx + halfWidth * 0.5f).toInt()
        var strong = 0
        var total = 0
        for (y in max(0, y0) until y1) {
            for (x in max(0, x0) until min(cols, x1)) {
                total++
                if (edgeMap[y * cols + x] == 2.toByte()) strong++
            }
        }
        if (total == 0) return 0f
        return min(1f, (strong.toFloat() / total) * 3.2f)
    }

    // ---------------------------------------------- Sobel / NMS / hysteresis
    private fun buildStaticStructure(captureW: Int, captureH: Int, rowStride: Int) {
        if (captureW <= 1 || captureH <= 1) return
        val rs = if (rowStride > 0) rowStride else captureW
        val bw = max(1, captureW / cols)
        val bh = max(1, captureH / rows)
        for (gy in 0 until rows) {
            for (gx in 0 until cols) {
                val x0 = gx * captureW / cols
                val y0 = gy * captureH / rows
                val x1 = min(captureW - 2, max(x0 + bw / 2, x0))
                val y1 = min(captureH - 2, max(y0 + bh / 2, y0))
                val ci = y1 * rs + x1
                val p00 = lumaAt(ci - rs - 1); val p01 = lumaAt(ci - rs); val p02 = lumaAt(ci - rs + 1)
                val p10 = lumaAt(ci - 1); val p12 = lumaAt(ci + 1)
                val p20 = lumaAt(ci + rs - 1); val p21 = lumaAt(ci + rs); val p22 = lumaAt(ci + rs + 1)
                val gxq = (p02 + 2 * p12 + p22) - (p00 + 2 * p10 + p20)
                val gyq = (p00 + 2 * p01 + p02) - (p20 + 2 * p21 + p22)
                val mag = (abs(gxq) + abs(gyq)) shr 2
                val agx = abs(gxq)
                val agy = abs(gyq)
                gradDir[gy * cols + gx] = when {
                    agx > 2 * agy -> 2
                    agy > 2 * agx -> 0
                    (gxq > 0 && gyq > 0) || (gxq < 0 && gyq < 0) -> 1
                    else -> 3
                }
                gradMag[gy * cols + gx] = mag
                structure[gy * cols + gx] = lumaAt(ci).toByte()
            }
        }
        val lowT = 60
        val highT = 130
        for (y in 1 until rows - 1) {
            for (x in 1 until cols - 1) {
                val i = y * cols + x
                val m = gradMag[i]
                if (m < lowT) continue
                when (gradDir[i].toInt() and 3) {
                    2 -> if (m < gradMag[i - 1] || m < gradMag[i + 1]) continue
                    0 -> if (m < gradMag[i - cols] || m < gradMag[i + cols]) continue
                    1 -> if (m < gradMag[i - cols - 1] || m < gradMag[i + cols + 1]) continue
                    else -> if (m < gradMag[i - cols + 1] || m < gradMag[i + cols - 1]) continue
                }
                edgeMap[i] = if (m >= highT) 2 else 1
            }
        }
        for (y in 1 until rows - 1) {
            for (x in 1 until cols - 1) {
                val i = y * cols + x
                if (edgeMap[i] != 1.toByte()) continue
                var strong = false
                for (k in 0 until 8) {
                    if (edgeMap[(y + NBY[k]) * cols + (x + NBX[k])] == 2.toByte()) { strong = true; break }
                }
                if (!strong) edgeMap[i] = 0
            }
        }
    }

    private fun lumaAt(i: Int): Int {
        val a = calibrationLuma ?: return 0
        return if (i in 0 until a.size) a[i].toInt() and 0xFF else 0
    }

}
