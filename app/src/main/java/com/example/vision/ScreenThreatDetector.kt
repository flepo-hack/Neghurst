package com.example.vision

import com.example.model.ThreatLevel
import com.example.model.ThreatVector
import com.example.vision.nativebridge.NativeVisionEngine
import com.example.vision.nativebridge.ScreenRegion
import com.example.vision.nativebridge.VisionResult
import com.example.vision.nativebridge.VisionTuning
import java.nio.ByteBuffer

/**
 * Thin, honest facade over the native vision engine.
 *
 * ## What changed, and why
 *
 * The previous `ScreenThreatDetector` was a ~620 line pure-Kotlin pipeline that
 * competed with a C++ pipeline which **was never compiled** (`build.gradle.kts`
 * had no `externalNativeBuild`, so `System.loadLibrary("rendera_native")` always
 * threw). On top of that it had real algorithmic defects: the "player" was the
 * centroid of every green pixel in a screen-sized box, so a patch of grass moved
 * the reported hitbox; the Kalman velocity covariance diverged so velocity was
 * never corrected after a few frames and nothing was ever classified as a
 * projectile; the camera motion search was clamped to a window smaller than a
 * single frame of a real pan, so the terrain lit up as motion; and the "escape"
 * was a `+/-90 degree` coin flip between a wall and an enemy.
 *
 * Rather than keep two half-working implementations, the pixel pipeline now
 * lives in exactly one place, the compiled C++ engine, and this class is the
 * typed boundary around it. Pure geometry ([CollisionSolver],
 * [DodgeGesturePlanner], [AnchorCalibrator]) stays in Kotlin because it is what
 * the unit tests pin down and it must be readable.
 *
 * ## Honesty guarantee
 *
 * If the native library is missing, [isNativeAvailable] is false, [process]
 * returns null, and the caller is expected to tell the user. Nothing is ever
 * invented: no fabricated threats, no placeholder speeds, no silent fallbacks to
 * hard-coded coordinates.
 */
class ScreenThreatDetector(
    gridWidth: Int = DEFAULT_GRID_WIDTH,
    gridHeight: Int = DEFAULT_GRID_HEIGHT,
    screenWidth: Int = 1080,
    screenHeight: Int = 1920
) : AutoCloseable {

    companion object {
        const val DEFAULT_GRID_WIDTH = 200
        const val DEFAULT_GRID_HEIGHT = 112

        /**
         * Grid aspect must track the capture aspect or every grid to screen
         * mapping skews and positions land in the wrong place.
         *
         * The height is rounded to a multiple of 8 as well as 2, because
         * `motH_ = gridH / 2` and the FFT pads `motH_` to the next power of two.
         * Forcing a multiple of 8 keeps `motH_` a multiple of 4, so a small
         * aspect change cannot jump the padded FFT height from 64 to 128 and
         * double the correlation cost.
         */
        fun gridForCapture(captureWidth: Int, captureHeight: Int): Pair<Int, Int> {
            if (captureWidth <= 0 || captureHeight <= 0) {
                return DEFAULT_GRID_WIDTH to DEFAULT_GRID_HEIGHT
            }
            val target = DEFAULT_GRID_WIDTH
            val h = (target.toLong() * captureHeight / captureWidth).toInt()
            val snapped = (h.coerceIn(56, 200) / 8) * 8
            return target to snapped
        }
    }

    private val engine = NativeVisionEngine(gridWidth, gridHeight, screenWidth, screenHeight)

    /** True when the compiled native engine is present and open. */
    val isNativeAvailable: Boolean get() = engine.isOpen

    private var anchors: Anchors = Anchors.defaultFor(screenWidth, screenHeight)
    private var tuning = VisionTuning()

    /** Debug readouts, only populated when the HUD asks for them. */
    private var debugBlobs: FloatArray = FloatArray(0)
    private var debugEnemies: FloatArray = FloatArray(0)
    private var debugTracks: FloatArray = FloatArray(0)

    init {
        if (engine.isOpen) {
            syncTuningToEngine()
        }
    }

    // -----------------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------------

    fun setDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        engine.setScreenSize(width, height)
    }

    /**
     * Installs the calibration.
     *
     * When [Anchors.calibrated] is true the native side is told to treat the
     * anchor as authoritative for its search centre, so a stray green scenery
     * blob outside the calibrated neighbourhood can never steal the lock.
     */
    fun setAnchors(value: Anchors) {
        anchors = value
        if (!engine.isOpen) return
        tuning = tuning.copy(
            playerAnchorLocked = value.calibrated,
            playerAnchorX = value.playerX,
            playerAnchorY = value.playerY
        )
        syncTuningToEngine()
    }

    fun currentAnchors(): Anchors = anchors

    fun applyTuning(value: VisionTuning) {
        tuning = value
        syncTuningToEngine()
    }

    fun currentTuning(): VisionTuning = tuning

    fun setMaskRegions(regions: List<ScreenRegion>) {
        if (!engine.isOpen) return
        engine.setMasks(regions)
    }

    private fun syncTuningToEngine() {
        if (!engine.isOpen) return
        engine.applyConfig(tuning)
    }

    fun reset() {
        if (!engine.isOpen) return
        engine.reset()
        debugBlobs = FloatArray(0)
        debugEnemies = FloatArray(0)
        debugTracks = FloatArray(0)
    }

    // -----------------------------------------------------------------------
    // Frame processing
    // -----------------------------------------------------------------------

    /**
     * One analysed frame.
     *
     * [playerX]/[playerY] are the live detected brawler position in **real screen
     * pixels**, which is the coordinate space the overlay, the HUD and the
     * gesture dispatch all use. [playerFromAnchor] is true when the position
     * came from the calibrated anchor rather than from a fresh detection, so the
     * UI can be honest about it instead of implying a lock it does not have.
     */
    data class Analysis(
        val raw: VisionResult,
        val playerX: Float,
        val playerY: Float,
        val playerDetected: Boolean,
        val playerFromAnchor: Boolean,
        val threat: ThreatVector?,
        val escape: CollisionSolver.Solution,
        val processMillis: Double,
        val blobCount: Int,
        val projectileCount: Int,
        val enemyCount: Int
    ) {
        val hasDodgeableThreat: Boolean
            get() = threat != null && escape.hasThreat
    }

    /**
     * Resolves the collider centre for a frame, in real screen pixels.
     *
     * When the player is detected, the live position wins. When it is not, the
     * **calibrated anchor** is used. It used to be `0.50f * screenWidth`, which
     * meant that a few missed detections silently teleported the collider to the
     * screen centre and made the whole thing look like it had lost the player.
     * That hard-coded fallback is gone; there is no other option.
     *
     * Exposed so the rule can be unit tested directly, since the failure it
     * prevents is invisible in normal use.
     */
    fun resolvePlayerPosition(
        result: VisionResult,
        screenWidth: Int,
        screenHeight: Int
    ): Triple<Float, Float, Boolean> {
        if (result.playerVisible) {
            return Triple(result.playerX, result.playerY, true)
        }
        val anchor = anchors.playerPx(screenWidth, screenHeight)
        return Triple(anchor.x, anchor.y, false)
    }

    /**
     * Runs the pipeline on one captured frame.
     *
     * @param collectDebug when false, the debug readouts are not copied out.
     *        Those copies allocate, so they are kept off the default path.
     * @return null when native is unavailable or the frame was rejected.
     */
    fun process(
        yPlane: ByteBuffer,
        yStride: Int,
        uPlane: ByteBuffer?,
        vPlane: ByteBuffer?,
        uvStride: Int,
        frameWidth: Int,
        frameHeight: Int,
        chromaWidth: Int,
        chromaHeight: Int,
        ptsNanos: Long,
        screenWidth: Int,
        screenHeight: Int,
        collectDebug: Boolean = false
    ): Analysis? {
        if (!engine.isOpen) return null
        if (frameWidth <= 0 || frameHeight <= 0) return null

        val result = engine.process(
            yPlane = yPlane, yStride = yStride,
            uPlane = uPlane, vPlane = vPlane, uvStride = uvStride,
            frameWidth = frameWidth, frameHeight = frameHeight,
            chromaWidth = chromaWidth, chromaHeight = chromaHeight,
            ptsNanos = ptsNanos
        ) ?: return null

        if (collectDebug) {
            debugBlobs = engine.readBlobs()
            debugEnemies = engine.readEnemies()
            debugTracks = engine.readTracks()
        }

        val playerRadius = tuning.playerRadiusNorm * screenWidth
        val (playerX, playerY, detected) = resolvePlayerPosition(result, screenWidth, screenHeight)

        val projectile = if (result.threatValid) {
            CollisionSolver.Projectile(
                x = result.threatX,
                y = result.threatY,
                vx = result.threatVx,
                vy = result.threatVy,
                confidence = result.threatConfidence.coerceIn(0f, 1f)
            )
        } else {
            null
        }

        // Recompute the escape locally so the plan is expressed in the same units
        // the gesture planner needs, and so the Kotlin unit tests cover the exact
        // maths that ends up being dispatched.
        val escape = if (projectile != null) {
            CollisionSolver.solve(
                playerX = playerX,
                playerY = playerY,
                playerRadiusPx = playerRadius,
                projectiles = listOf(projectile),
                screenWidthPx = screenWidth.toFloat(),
                screenHeightPx = screenHeight.toFloat(),
                reactionHorizonSec = tuning.reactionHorizonSec,
                lethalTtiSec = tuning.lethalTtiSec,
                imminentTtiSec = tuning.imminentTtiSec,
                joystickRadiusPx = anchors.joystickRadiusPx(screenWidth),
                characterSpeedPxPerSec = tuning.characterSpeedNorm * screenWidth,
                enemies = emptyList()
            )
        } else {
            CollisionSolver.Solution()
        }

        val threat = if (escape.hasThreat && projectile != null) {
            ThreatVector(
                threatX = projectile.x,
                threatY = projectile.y,
                velocityX = projectile.vx,
                velocityY = projectile.vy,
                speed = projectile.speed,
                threatAngleDeg = CollisionSolver.trajectoryHeadingDeg(projectile),
                dodgeAngleDeg = escape.escapeHeadingDeg,
                dodgeDirX = escape.escapeDirX,
                dodgeDirY = escape.escapeDirY,
                threatLevel = escape.severity,
                timeToImpactMs = escape.timeToImpactMs,
                confidence = projectile.confidence
            )
        } else {
            null
        }

        return Analysis(
            raw = result,
            playerX = playerX,
            playerY = playerY,
            playerDetected = detected,
            playerFromAnchor = !detected,
            threat = threat,
            escape = escape,
            processMillis = engine.lastProcessMillis(),
            blobCount = result.blobCount,
            projectileCount = result.projectileCount,
            ballCount = result.ballCount,
            bouncerCount = result.bouncerCount,
            // The engine classifies enemies every frame; `debugEnemies` is only
            // copied out when the HUD is on, so counting the debug array here
            // would report zero most of the time.
            enemyCount = result.enemyCount
        )
    }

    // -----------------------------------------------------------------------
    // Planning
    // -----------------------------------------------------------------------

    /**
     * Turns an analysed frame into an executable gesture.
     *
     * Returns an empty plan when there is nothing to dodge or when the anchors
     * are not calibrated, because a gesture from an uncalibrated stick position
     * would drag the wrong place on screen and, worse, look like it worked.
     */
    fun planDodge(
        analysis: Analysis?,
        screenWidth: Int,
        screenHeight: Int
    ): DodgeGesturePlanner.Plan {
        if (analysis == null || !analysis.hasDodgeableThreat) {
            return DodgeGesturePlanner.Plan(emptyList(), 0L, 0f, 0f)
        }
        if (!anchors.calibrated) return DodgeGesturePlanner.Plan(emptyList(), 0L, 0f, 0f)

        val stick = anchors.joystickPx(screenWidth, screenHeight)

        // Pick the timing from the urgency of the threat. On a point blank shot
        // the default 45 + 35 ms onset would consume most of the window before
        // the stick had moved at all, so the imminent and lethal cases use the
        // compressed press/drag.
        val timing = when (analysis.escape.severity) {
            ThreatLevel.LETHAL -> DodgeGesturePlanner.Timing().urgent()
            ThreatLevel.IMMINENT_DANGER -> DodgeGesturePlanner.Timing(
                pressMs = 28L,
                dragMs = 24L
            )
            else -> DodgeGesturePlanner.Timing()
        }

        return DodgeGesturePlanner.planFromSolution(
            solution = analysis.escape,
            stickX = stick.x,
            stickY = stick.y,
            screenWidthPx = screenWidth.toFloat(),
            screenHeightPx = screenHeight.toFloat(),
            timing = timing
        )
    }

    fun debugBlobSnapshot(): FloatArray = debugBlobs
    fun debugEnemySnapshot(): FloatArray = debugEnemies
    fun debugTrackSnapshot(): FloatArray = debugTracks

    override fun close() {
        engine.close()
    }
}
