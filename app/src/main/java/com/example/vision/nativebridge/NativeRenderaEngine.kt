package com.example.vision.nativebridge

import android.util.Log

/**
 * Thin, honest JNI bridge to the C++ vision engine in `app/src/main/cpp`.
 *
 * This class does **no** detection of its own. It moves the luma plane into the
 * native engine and copies the engine's own numbers back. If the library is
 * missing (e.g. an ABI that was not built) [isAvailable] is false and the caller
 * must fall back to the Kotlin pipeline; there is no fabricated output path.
 */
class NativeRenderaEngine private constructor(
    private val handle: Long,
    private val cols: Int,
    private val rows: Int
) {

    /** Preallocated result block, one slot per `SLOT_*` index in rendera_native.h. */
    private val result = FloatArray(RESULT_SLOTS)
    private val tracks = FloatArray(MAX_TRACKS * TRACK_STRIDE)
    private val exclusionBuffer = IntArray(EXCLUSION_CAPACITY)

    @Volatile
    var processedFrames: Int = 0
        private set

    /**
     * Configures the engine for the current capture geometry.
     *
     * @param playerCellX/y anchor of our own brawler in analysis-grid cells.
     * @param playerRadiusCells hitbox radius in cells (Brawl Stars tile ~= 9 cells).
     * @param joyRadiusCells joystick exclusion radius in cells.
     * @param minProjectileSpeedCellsPerSec lower edge of the projectile speed band.
     */
    fun configure(
        captureW: Int,
        captureH: Int,
        screenW: Int,
        screenH: Int,
        playerCellX: Int,
        playerCellY: Int,
        playerRadiusCells: Float,
        joyCellX: Int,
        joyCellY: Int,
        joyRadiusCells: Float,
        minProjectileSpeedCellsPerSec: Float,
        maxProjectileSpeedCellsPerSec: Float,
        playerAnchorConfirmed: Boolean,
        joystickAnchorConfirmed: Boolean
    ) {
        nativeSetGeometry(
            handle, cols, rows, captureW, captureH, screenW, screenH,
            playerCellX, playerCellY, joyCellX, joyCellY,
            playerRadiusCells, joyRadiusCells,
            minProjectileSpeedCellsPerSec, maxProjectileSpeedCellsPerSec,
            playerAnchorConfirmed, joystickAnchorConfirmed
        )
    }

    /**
     * Registers screen regions that contain our own moving UI (the floating
     * bubble, the radar strip, the menu) so the tracker never mistakes them for
     * projectiles. Coordinates are in analysis-grid cells.
     */
    fun setExclusionRects(cellRects: IntArray, count: Int) {
        val n = minOf(count * 4, exclusionBuffer.size)
        if (n > 0) System.arraycopy(cellRects, 0, exclusionBuffer, 0, n)
        nativeSetExclusions(handle, if (n > 0) exclusionBuffer else IntArray(0))
    }

    fun setTuning(
        motionNoiseFloor: Float,
        minBlobWeight: Float,
        maxCameraShiftCells: Int,
        minProjectileSpeed: Float,
        maxProjectileSpeed: Float
    ) {
        nativeSetTuning(
            handle, motionNoiseFloor, minBlobWeight, maxCameraShiftCells,
            minProjectileSpeed, maxProjectileSpeed
        )
    }

    fun markAnchorConfirmed() = nativeMarkAnchorConfirmed(handle)

    fun reset() = nativeReset(handle)

    /**
     * Runs the full pipeline over one frame.
     *
     * @param luma direct copy of the ImageReader Y plane, `lumaRowStride` bytes
     *   per row (row padding included), at least `captureW * captureH` long.
     * @return the engine's own [NativeFrameResult], or null if the frame was
     *   rejected (wrong size, library gone).
     */
    fun processFrame(
        luma: ByteArray,
        captureW: Int,
        captureH: Int,
        lumaRowStride: Int,
        dtSec: Float
    ): NativeFrameResult? {
        val ok = nativeProcessFrame(
            handle, luma, captureW, captureH, lumaRowStride, dtSec, result, tracks
        )
        processedFrames = nativeProcessedFrames(handle)
        if (!ok && result[SLOT_FRAMES_SEEN] <= 0f) return null
        return NativeFrameResult(
            threat = result[SLOT_THREAT] > 0.5f,
            threatLevelRaw = result[SLOT_THREAT_LEVEL].toInt(),
            dodgeAngleDeg = result[SLOT_DODGE_ANGLE_DEG],
            threatX = result[SLOT_THREAT_X],
            threatY = result[SLOT_THREAT_Y],
            threatVx = result[SLOT_THREAT_VX],
            threatVy = result[SLOT_THREAT_VY],
            threatSpeed = result[SLOT_THREAT_SPEED],
            timeToImpactMs = result[SLOT_THREAT_TTI_MS].toLong(),
            playerX = result[SLOT_PLAYER_X],
            playerY = result[SLOT_PLAYER_Y],
            playerConfidence = result[SLOT_PLAYER_CONFIDENCE],
            playerAnchorConfirmed = result[SLOT_PLAYER_DETECTED] > 0.5f,
            cameraDx = result[SLOT_CAMERA_DX].toInt(),
            cameraDy = result[SLOT_CAMERA_DY].toInt(),
            cameraMoving = result[SLOT_CAMERA_MOVING] > 0.5f,
            projectileCount = result[SLOT_PROJECTILE_COUNT].toInt(),
            brawlerCount = result[SLOT_ENEMY_COUNT].toInt(),
            trackCount = result[SLOT_TRACK_COUNT].toInt().coerceIn(0, MAX_TRACKS),
            noiseFloor = result[SLOT_NOISE_FLOOR],
            calibJoyX = result[SLOT_CALIB_JOY_X],
            calibJoyY = result[SLOT_CALIB_JOY_Y],
            calibJoyScore = result[SLOT_CALIB_JOY_SCORE],
            calibPlayerX = result[SLOT_CALIB_PLAYER_X],
            calibPlayerY = result[SLOT_CALIB_PLAYER_Y],
            calibPlayerScore = result[SLOT_CALIB_PLAYER_SCORE],
            tracks = tracks
        )
    }

    fun calibrateJoystick(
        luma: ByteArray,
        captureW: Int,
        captureH: Int,
        lumaRowStride: Int
    ): CalibrationProposal {
        nativeCalibrateJoystick(handle, luma, captureW, captureH, lumaRowStride, result)
        return CalibrationProposal(
            x = result[SLOT_CALIB_JOY_X],
            y = result[SLOT_CALIB_JOY_Y],
            score = result[SLOT_CALIB_JOY_SCORE]
        )
    }

    fun calibratePlayer(
        luma: ByteArray,
        captureW: Int,
        captureH: Int,
        lumaRowStride: Int
    ): CalibrationProposal {
        nativeCalibratePlayer(handle, luma, captureW, captureH, lumaRowStride, result)
        return CalibrationProposal(
            x = result[SLOT_CALIB_PLAYER_X],
            y = result[SLOT_CALIB_PLAYER_Y],
            score = result[SLOT_CALIB_PLAYER_SCORE]
        )
    }

    fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
        }
    }

    /** One tracked object, in screen pixels / pixels-per-second. */
    data class NativeTrack(
        val x: Float,
        val y: Float,
        val vx: Float,
        val vy: Float,
        val speed: Float,
        val residual: Float,
        val hits: Int,
        val isProjectile: Boolean
    )

    data class CalibrationProposal(
        val x: Float,
        val y: Float,
        val score: Float
    ) {
        val isValid: Boolean get() = score > 0.15f && x > 0f && y > 0f
    }

    class NativeFrameResult(
        val threat: Boolean,
        val threatLevelRaw: Int,
        val dodgeAngleDeg: Float,
        val threatX: Float,
        val threatY: Float,
        val threatVx: Float,
        val threatVy: Float,
        val threatSpeed: Float,
        val timeToImpactMs: Long,
        val playerX: Float,
        val playerY: Float,
        val playerConfidence: Float,
        val playerAnchorConfirmed: Boolean,
        val cameraDx: Int,
        val cameraDy: Int,
        val cameraMoving: Boolean,
        val projectileCount: Int,
        val brawlerCount: Int,
        val trackCount: Int,
        val noiseFloor: Float,
        val calibJoyX: Float,
        val calibJoyY: Float,
        val calibJoyScore: Float,
        val calibPlayerX: Float,
        val calibPlayerY: Float,
        val calibPlayerScore: Float,
        private val tracks: FloatArray
    ) {
        fun trackAt(i: Int): NativeTrack? {
            if (i < 0 || i >= trackCount) return null
            val b = i * TRACK_STRIDE
            return NativeTrack(
                x = tracks[b],
                y = tracks[b + 1],
                vx = tracks[b + 2],
                vy = tracks[b + 3],
                speed = tracks[b + 4],
                residual = tracks[b + 5],
                hits = tracks[b + 6].toInt(),
                isProjectile = tracks[b + 7] > 0.5f
            )
        }
    }

    companion object {
        private const val TAG = "NativeRenderaEngine"

        // Must match rendera_native.h exactly.
        const val SLOT_THREAT = 0
        const val SLOT_THREAT_LEVEL = 1
        const val SLOT_DODGE_ANGLE_DEG = 2
        const val SLOT_THREAT_X = 3
        const val SLOT_THREAT_Y = 4
        const val SLOT_THREAT_VX = 5
        const val SLOT_THREAT_VY = 6
        const val SLOT_THREAT_SPEED = 7
        const val SLOT_THREAT_TTI_MS = 8
        const val SLOT_PLAYER_X = 9
        const val SLOT_PLAYER_Y = 10
        const val SLOT_PLAYER_CONFIDENCE = 11
        const val SLOT_PLAYER_DETECTED = 12
        const val SLOT_CAMERA_DX = 13
        const val SLOT_CAMERA_DY = 14
        const val SLOT_CAMERA_MOVING = 15
        const val SLOT_PROJECTILE_COUNT = 16
        const val SLOT_ENEMY_COUNT = 17
        const val SLOT_TRACK_COUNT = 18
        const val SLOT_FRAMES_SEEN = 19
        const val SLOT_NOISE_FLOOR = 20
        const val SLOT_CALIB_JOY_X = 21
        const val SLOT_CALIB_JOY_Y = 22
        const val SLOT_CALIB_PLAYER_X = 23
        const val SLOT_CALIB_PLAYER_Y = 24
        const val SLOT_CALIB_JOY_SCORE = 25
        const val SLOT_CALIB_PLAYER_SCORE = 26
        const val RESULT_SLOTS = 27

        const val TRACK_STRIDE = 8
        const val MAX_TRACKS = 24
        const val EXCLUSION_CAPACITY = 64

        @Volatile
        var loadError: String? = null
            private set

        val isAvailable: Boolean by lazy {
            try {
                System.loadLibrary("rendera_native")
                true
            } catch (t: UnsatisfiedLinkError) {
                loadError = t.message
                Log.e(TAG, "rendera_native could not be loaded; using the Kotlin pipeline", t)
                false
            }
        }

        /** Creates an engine, or returns null when the native library is absent. */
        fun create(cols: Int, rows: Int): NativeRenderaEngine? {
            if (!isAvailable) return null
            return try {
                NativeRenderaEngine(nativeInit(cols, rows), cols, rows)
            } catch (t: Throwable) {
                Log.e(TAG, "nativeInit failed", t)
                null
            }
        }

        private external fun nativeInit(cols: Int, rows: Int): Long
        private external fun nativeProcessedFrames(handle: Long): Int
        private external fun nativeSetGeometry(
            handle: Long, cols: Int, rows: Int, captureW: Int, captureH: Int,
            screenW: Int, screenH: Int, playerCellX: Int, playerCellY: Int,
            joyCellX: Int, joyCellY: Int, playerRadiusCells: Float,
            joyRadiusCells: Float, minProj: Float, maxProj: Float,
            playerLocked: Boolean, joyLocked: Boolean
        )
        private external fun nativeSetExclusions(handle: Long, rects: IntArray)
        private external fun nativeSetTuning(
            handle: Long, motionNoiseFloor: Float, minBlobWeight: Float,
            maxCameraShift: Int, minProj: Float, maxProj: Float
        )
        private external fun nativeMarkAnchorConfirmed(handle: Long)
        private external fun nativeReset(handle: Long)
        private external fun nativeDestroy(handle: Long)
        private external fun nativeProcessFrame(
            handle: Long, luma: ByteArray, captureW: Int, captureH: Int,
            lumaRowStride: Int, dtSec: Float,
            outResult: FloatArray, outTracks: FloatArray
        ): Boolean
        private external fun nativeCalibrateJoystick(
            handle: Long, luma: ByteArray, captureW: Int, captureH: Int,
            lumaRowStride: Int, outResult: FloatArray
        ): Boolean
        private external fun nativeCalibratePlayer(
            handle: Long, luma: ByteArray, cb: ByteArray, cr: ByteArray,
            captureW: Int, captureH: Int, lumaRowStride: Int, outResult: FloatArray
        ): Boolean
        private external fun nativeVersion(): Int
    }
}
