package com.example.vision

import android.util.Log
import com.example.model.DodgeProfile
import com.example.model.EntityType
import com.example.model.ThreatLevel
import com.example.model.ThreatVector
import com.example.vision.core.FrameGrabber
import com.example.vision.core.VisionPipeline
import com.example.vision.nativebridge.NativeRenderaEngine
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Front end of the vision system.
 *
 * Owns the calibration anchors, drives the C++ engine when it is available and
 * falls back to the identical Kotlin pipeline when it is not, and converts the
 * result into screen-space model objects.
 *
 * Coordinate systems
 * ------------------
 *  * **cell**  - analysis grid, `cols x rows`. This is the pipeline's domain.
 *  * **world**  - `cell - accumulatedCameraShift`. Stable arena coordinates.
 *                Terrain has exactly zero world velocity; only things that move
 *                independently of the camera appear to move here.
 *  * **screen** - Android pixels. Because the world->screen mapping is a pure
 *                translation, a world direction is the same angle as the
 *                corresponding screen direction, so dodge angles transfer 1:1.
 */
class ScreenThreatDetector(
    private val cols: Int = DEFAULT_COLS,
    private val rows: Int = DEFAULT_ROWS
) {
    companion object {
        private const val TAG = "ScreenThreatDetector"
        const val DEFAULT_COLS = 240

        /** Matches the capture aspect ratio; recomputed by [configureForCapture]. */
        const val DEFAULT_ROWS = 114

        /**
         * Brawl Stars tile size expressed in analysis cells. One tile is roughly
         * 1/9 of a 240-column view of the arena, which is what makes the
         * projectile speed band below meaningful.
         */
        const val TILE_CELLS = 9f

        /** 3.5 tiles/s -> 5 tiles/s. Separates brawlers (<=2.5 t/s) from shots. */
        const val MIN_PROJECTILE_TILES_PER_SEC = 3.2f
        const val MAX_PROJECTILE_TILES_PER_SEC = 9.0f

    }

    // ---------------------------------------------------------------- engines
    private val native: NativeRenderaEngine? = NativeRenderaEngine.create(cols, rows)
    private val fallbackPipeline: VisionPipeline? =
        if (native == null) VisionPipeline(cols, rows, TILE_CELLS) else null

    val isNativeActive: Boolean get() = native != null

    val backendName: String
        get() = if (native != null) "C++/NDK" else "Kotlin"

    /**
     * True when the native engine is present *and* has actually processed
     * frames. Used by the HUD so the reported backend can never lie.
     */
    val nativeProofFrames: Int get() = native?.processedFrames ?: 0

    // ---------------------------------------------------------------- anchors
    private var playerAnchorCellX = -1
    private var playerAnchorCellY = -1
    private var joyAnchorCellX = -1
    private var joyAnchorCellY = -1
    private var anchorConfirmed = false
    private var joyConfirmed = false

    private var captureW = 0
    private var captureH = 0
    private var screenW = 1
    private var screenH = 1
    private var lastFrameNs = 0L

    // ---------------------------------------------------------------- output
    data class FrameAnalysisResult(
        val threat: ThreatVector?,
        val playerX: Float,
        val playerY: Float,
        val playerAnchorConfirmed: Boolean,
        val joystickX: Float,
        val joystickY: Float,
        val joystickRadiusPx: Float,
        val joystickAnchorConfirmed: Boolean,
        val projectileCount: Int,
        val brawlerCount: Int,
        val cameraDx: Int,
        val cameraDy: Int,
        val isCameraMoving: Boolean,
        val noiseFloor: Float,
        val engine: String,
        val debugEntities: List<com.example.model.DetectedEntity> = emptyList(),
        val dodgeAngleDeg: Float? = null
    )

    data class CalibrationProposal(
        val playerX: Float,
        val playerY: Float,
        val playerScore: Float,
        val joystickX: Float,
        val joystickY: Float,
        val joystickScore: Float,
        val joystickRadiusPx: Float
    ) {
        val playerFound: Boolean get() = playerScore > 0.15f && playerX > 0f
        val joystickFound: Boolean get() = joystickScore > 0.15f && joystickX > 0f
    }

    init {
        Log.i(TAG, "Vision backend: $backendName (native available=$isNativeActive)")
    }

    // =====================================================================
    fun configureForCapture(
        capW: Int,
        capH: Int,
        scrW: Int,
        scrH: Int,
        tilePixels: Float
    ) {
        captureW = capW
        captureH = capH
        screenW = max(1, scrW)
        screenH = max(1, scrH)
        val pxPerCell = screenW.toFloat() / cols
        val tilePx = if (tilePixels > 1f) tilePixels else tileCellsPixels(pxPerCell)
        playerRadiusCells = (0.42f * tileCellsPx(tilePx, pxPerCell)).coerceIn(1.5f, cols * 0.2f)
        val minSpeed = MIN_PROJECTILE_TILES_PER_SEC * tileCellsPx(tilePx, pxPerCell)
        val maxSpeed = MAX_PROJECTILE_TILES_PER_SEC * tileCellsPx(tilePx, pxPerCell)
        pushAnchors(minSpeed, maxSpeed)
    }

    private fun tileCellsPx(tilePx: Float, pxPerCell: Float): Float =
        if (pxPerCell > 0.01f) tilePx / pxPerCell else TILE_CELLS

    private fun tileCellsPixels(pxPerCell: Float): Float = TILE_CELLS * pxPerCell

    private var playerRadiusCells = 3f
    private var joyRadiusCells = 12f

    private fun pushAnchors(minSpeed: Float = currentMinSpeed(), maxSpeed: Float = currentMaxSpeed()) {
        val jx = joyAnchorCellX.takeIf { it >= 0 } ?: (cols * 0.20f).roundToInt()
        val jy = joyAnchorCellY.takeIf { it >= 0 } ?: (rows * 0.78f).roundToInt()
        val px = playerAnchorCellX.takeIf { it >= 0 } ?: (cols * 0.5f).roundToInt()
        val py = playerAnchorCellY.takeIf { it >= 0 } ?: (rows * 0.5f).roundToInt()
        val jr = if (joyRadiusCells > 0f) joyRadiusCells else (cols * 0.10f)
        native?.configure(
            captureW, captureH, screenW, screenH,
            px, py, playerRadiusCells,
            jx, jy, jr,
            minSpeed, maxSpeed,
            anchorConfirmed, joyConfirmed
        )
        fallbackPipeline?.apply {
            playerRadiusCells = this@ScreenThreatDetector.playerRadiusCells
            joyRadiusCells = jr
            minProjectileSpeed = minSpeed
            maxProjectileSpeed = maxSpeed
            if (playerAnchorCellX >= 0) anchorPlayerCell(px, py, anchorConfirmed)
            if (joyAnchorCellX >= 0) anchorJoystickCell(jx, jy, joyConfirmed)
        }
    }

    private fun currentMinSpeed() = MIN_PROJECTILE_TILES_PER_SEC * TILE_CELLS
    private fun currentMaxSpeed() = MAX_PROJECTILE_TILES_PER_SEC * TILE_CELLS

    /** Screen rects (px) that contain our own UI and must not be tracked. */
    fun setExclusions(cellRects: IntArray, count: Int) {
        native?.setExclusionRects(cellRects, count)
        fallbackPipeline?.setExclusionRects(cellRects, count)
    }

    fun reset() {
        native?.reset()
        fallbackPipeline?.reset()
        lastFrameNs = 0L
    }

    // =====================================================================
    /**
     * Locks the player anchor. [playerX]/[playerY] are screen pixels.
     * Passing `confirmed = true` (a real tap or a real detection) is what
     * distinguishes a verified anchor from the default guess.
     */
    fun lockPlayerAnchor(playerX: Float, playerY: Float, confirmed: Boolean) {
        playerAnchorCellX = toCellX(playerX)
        playerAnchorCellY = toCellY(playerY)
        anchorConfirmed = confirmed
        native?.let {
            it.configure(
                captureW, captureH, screenW, screenH,
                playerAnchorCellX, playerAnchorCellY, playerRadiusCells,
                joyAnchorCellX.coerceAtLeast(0), joyAnchorCellY.coerceAtLeast(0), joyRadiusCells,
                currentMinSpeed(), currentMaxSpeed(),
                anchorConfirmed, joyConfirmed
            )
            if (confirmed) it.markAnchorConfirmed()
        }
        fallbackPipeline?.anchorPlayerCell(playerAnchorCellX, playerAnchorCellY, confirmed)
        pushAnchors()
    }

    fun lockJoystickAnchor(joyX: Float, joyY: Float, radiusPx: Float, confirmed: Boolean) {
        joyAnchorCellX = toCellX(joyX)
        joyAnchorCellY = toCellY(joyY)
        joyConfirmed = confirmed
        val pxPerCell = screenW.toFloat() / cols
        joyRadiusCells = if (pxPerCell > 0.01f) max(3f, radiusPx / pxPerCell) else 12f
        pushAnchors()
    }

    fun clearAnchors() {
        playerAnchorCellX = -1
        playerAnchorCellY = -1
        joyAnchorCellX = -1
        joyAnchorCellY = -1
        anchorConfirmed = false
        joyConfirmed = false
        pushAnchors()
    }

    val isPlayerAnchorConfirmed: Boolean get() = anchorConfirmed
    val isJoystickAnchorConfirmed: Boolean get() = joyConfirmed
    val playerAnchorScreenX: Float get() = toScreenX(playerAnchorCellX)
    val playerAnchorScreenY: Float get() = toScreenY(playerAnchorCellY)
    val joystickAnchorScreenX: Float get() = toScreenX(joyAnchorCellX)
    val joystickAnchorScreenY: Float get() = toScreenY(joyAnchorCellY)
    val joystickRadiusPx: Float get() = joyRadiusCells * (screenW.toFloat() / cols)

    private fun toCellX(px: Float): Int =
        if (screenW <= 0) -1 else ((px / screenW) * cols).roundToInt().coerceIn(0, cols - 1)

    private fun toCellY(px: Float): Int =
        if (screenH <= 0) -1 else ((px / screenH) * rows).roundToInt().coerceIn(0, rows - 1)

    private fun toScreenX(cell: Int): Float =
        if (cell < 0) -1f else cell * screenW.toFloat() / cols

    private fun toScreenY(cell: Int): Float =
        if (cell < 0) -1f else cell * screenH.toFloat() / rows

    // =====================================================================
    /**
     * Runs auto-calibration against the current frame. Returns real proposals
     * with scores; when a proposal is missing the score is 0 and the caller is
     * expected to fall back to a default, not to a fabricated value.
     */
    fun autoCalibrate(grabber: FrameGrabber, tilePixels: Float): CalibrationProposal {
        configureForCapture(grabber.width, grabber.height, screenW, screenH, tilePixels)
        val luma = grabber.luma
        val rs = grabber.rowStride

        var px = -1f; var py = -1f; var pScore = 0f
        var jx = -1f; var jy = -1f; var jScore = 0f
        var jRingPx = 0f

        val eng = native
        if (eng != null) {
            val p = eng.calibratePlayer(luma, grabber.width, grabber.height, rs)
            px = p.x; py = p.y; pScore = p.score
            val j = eng.calibrateJoystick(luma, grabber.width, grabber.height, rs)
            jx = j.x; jy = j.y; jScore = j.score
            // Ring radius is derived from the same geometry the detector used.
            val pxPerCell = screenW.toFloat() / cols
            jRingPx = (cols * 0.11f) * pxPerCell
        } else {
            val pipe = fallbackPipeline!!
            if (pipe.detectPlayerAnchor(luma, grabber.width, grabber.height, rs)) {
                px = pipe.cellsToScreenX(pipe.calibPlayerCellX.toFloat(), screenW)
                py = pipe.cellsToScreenY(pipe.calibPlayerCellY.toFloat(), screenH)
                pScore = pipe.calibPlayerScore
            }
            if (pipe.detectJoystickRing(luma, grabber.width, grabber.height, rs)) {
                jx = pipe.cellsToScreenX(pipe.calibJoyCellX.toFloat(), screenW)
                jy = pipe.cellsToScreenY(pipe.calibJoyCellY.toFloat(), screenH)
                jScore = pipe.calibJoyScore
                jRingPx = pipe.calibJoyRingCells * (screenW.toFloat() / cols)
            }
        }

        return CalibrationProposal(
            playerX = px, playerY = py, playerScore = pScore,
            joystickX = jx, joystickY = jy, joystickScore = jScore,
            joystickRadiusPx = if (jRingPx > 8f) jRingPx else cols * 0.11f * (screenW.toFloat() / cols)
        )
    }

    // =====================================================================
    /** Processes one captured frame. Returns null when there is no new frame. */
    fun analyze(
        grabber: FrameGrabber,
        profile: DodgeProfile,
        tilePixels: Float
    ): FrameAnalysisResult? {
        if (!grabber.grab()) return null
        configureForCapture(grabber.width, grabber.height, screenW, screenH, tilePixels)

        val luma = grabber.luma
        val ts = grabber.timestampNanos()
        val dt = if (lastFrameNs > 0L && ts > lastFrameNs) {
            ((ts - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0.008f, 0.050f)
        } else {
            1f / 60f
        }
        lastFrameNs = if (ts > 0L) ts else lastFrameNs

        val nowMs = System.currentTimeMillis()
        val eng = native
        return if (eng != null) {
            val r = eng.processFrame(luma, grabber.width, grabber.height, grabber.rowStride, dt) ?: return null
            assembleNative(r)
        } else {
            val pipe = fallbackPipeline!!
            pipe.loadLuma(luma, grabber.width, grabber.height, grabber.rowStride)
            pipe.process(dt, nowMs)
            assembleKotlin(pipe)
        }
    }

    private fun assembleNative(r: NativeRenderaEngine.NativeFrameResult): FrameAnalysisResult {
        val spc = 0.5f * (screenW.toFloat() / cols + screenH.toFloat() / rows)
        val threat = if (r.threat && r.dodgeAngleDeg >= 0f) {
            val rad = Math.toRadians(r.dodgeAngleDeg.toDouble())
            ThreatVector(
                threatX = r.threatX,
                threatY = r.threatY,
                velocityX = r.threatVx,
                velocityY = r.threatVy,
                speed = r.threatSpeed,
                threatAngleDeg = ((r.dodgeAngleDeg - 90f) + 360f) % 360f,
                dodgeAngleDeg = r.dodgeAngleDeg,
                dodgeDirX = kotlin.math.cos(rad).toFloat(),
                dodgeDirY = kotlin.math.sin(rad).toFloat(),
                threatLevel = levelFromRaw(r.threatLevelRaw),
                timeToImpactMs = r.timeToImpactMs,
                confidence = 1f
            )
        } else {
            null
        }

        val entities = ArrayList<com.example.model.DetectedEntity>(r.trackCount + 2)
        for (i in 0 until r.trackCount) {
            val t = r.trackAt(i) ?: continue
            entities.add(
                com.example.model.DetectedEntity(
                    type = if (t.isProjectile) EntityType.PROJECTILE else EntityType.ENEMY,
                    x = t.x,
                    y = t.y,
                    radius = if (t.isProjectile) 34f else 48f,
                    vx = t.vx * spc,
                    vy = t.vy * spc,
                    label = if (t.isProjectile) {
                        "AMMO ${t.speed.roundToInt()}px/s"
                    } else {
                        "BRAWLER ${t.speed.roundToInt()}px/s"
                    },
                    confidence = 1f
                )
            )
        }

        return FrameAnalysisResult(
            threat = threat,
            playerX = r.playerX,
            playerY = r.playerY,
            playerAnchorConfirmed = r.playerAnchorConfirmed,
            joystickX = if (joyAnchorCellX >= 0) joystickAnchorScreenX else cols * 0.20f * (screenW.toFloat() / cols),
            joystickY = if (joyAnchorCellY >= 0) joystickAnchorScreenY else rows * 0.78f * (screenH.toFloat() / rows),
            joystickRadiusPx = joystickRadiusPx,
            joystickAnchorConfirmed = joyConfirmed,
            projectileCount = r.projectileCount,
            brawlerCount = r.brawlerCount,
            cameraDx = r.cameraDx,
            cameraDy = r.cameraDy,
            isCameraMoving = r.cameraMoving,
            noiseFloor = r.noiseFloor,
            engine = "C++/NDK (${nativeProofFrames} frames)",
            debugEntities = entities,
            dodgeAngleDeg = r.dodgeAngleDeg.takeIf { it >= 0f }
        )
    }

    private fun assembleKotlin(k: VisionPipeline): FrameAnalysisResult {
        val t = k.solveThreat()
        val spc = 0.5f * (screenW.toFloat() / cols + screenH.toFloat() / rows)
        val threat = t?.let {
            ThreatVector(
                threatX = toScreenXF(it.x),
                threatY = toScreenYF(it.y),
                velocityX = it.vx * spc,
                velocityY = it.vy * spc,
                speed = it.speed * spc,
                threatAngleDeg = ((it.dodgeAngleDeg - 90f) + 360f) % 360f,
                dodgeAngleDeg = it.dodgeAngleDeg,
                dodgeDirX = it.dodgeDirX,
                dodgeDirY = it.dodgeDirY,
                threatLevel = levelFromRaw(it.levelRaw),
                timeToImpactMs = it.timeToImpactMs,
                confidence = 1f
            )
        }

        val entities = ArrayList<com.example.model.DetectedEntity>(k.trackCount + 2)
        for (i in 0 until k.trackCount) {
            val b = i * VisionPipeline.TRACK_STRIDE
            val isProj = k.outTracks[b + 7] > 0.5f
            entities.add(
                com.example.model.DetectedEntity(
                    type = if (isProj) EntityType.PROJECTILE else EntityType.ENEMY,
                    x = toScreenXF(k.outTracks[b]),
                    y = toScreenYF(k.outTracks[b + 1]),
                    radius = if (isProj) 34f else 48f,
                    vx = k.outTracks[b + 2] * spc,
                    vy = k.outTracks[b + 3] * spc,
                    label = if (isProj) "AMMO ${(k.outTracks[b + 4] * spc).roundToInt()}px/s"
                    else "BRAWLER ${(k.outTracks[b + 4] * spc).roundToInt()}px/s",
                    confidence = 1f
                )
            )
        }

        return FrameAnalysisResult(
            threat = threat,
            playerX = toScreenXF(k.playerCellX()),
            playerY = toScreenYF(k.playerCellY()),
            playerAnchorConfirmed = k.playerAnchorConfirmed,
            joystickX = if (joyAnchorCellX >= 0) joystickAnchorScreenX else cols * 0.20f * (screenW.toFloat() / cols),
            joystickY = if (joyAnchorCellY >= 0) joystickAnchorScreenY else rows * 0.78f * (screenH.toFloat() / rows),
            joystickRadiusPx = joystickRadiusPx,
            joystickAnchorConfirmed = joyConfirmed,
            projectileCount = k.projectileCount,
            brawlerCount = k.brawlerCount,
            cameraDx = k.motionDx,
            cameraDy = k.motionDy,
            isCameraMoving = k.cameraMoving,
            noiseFloor = k.noiseFloor,
            engine = "Kotlin (${k.framesSeen} frames)",
            debugEntities = entities,
            dodgeAngleDeg = t?.dodgeAngleDeg
        )
    }

    private fun toScreenXF(cell: Float): Float = if (screenW <= 0) 0f else cell * screenW.toFloat() / cols
    private fun toScreenYF(cell: Float): Float = if (screenH <= 0) 0f else cell * screenH.toFloat() / rows

    private fun levelFromRaw(raw: Int): ThreatLevel = when (raw) {
        3 -> ThreatLevel.LETHAL
        2 -> ThreatLevel.IMMINENT_DANGER
        1 -> ThreatLevel.WARNING
        else -> ThreatLevel.SAFE
    }

}
