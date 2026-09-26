package com.example.vision.nativebridge

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kotlin side of the native vision engine.
 *
 * Design constraints that this class exists to satisfy:
 *  * No `Bitmap` ever crosses the JNI boundary. Frames are handed over as direct
 *    [ByteBuffer]s holding the Y / Cb / Cr planes of a MediaProjection
 *    `YUV_420_888` image, copied once into a reusable ring.
 *  * No allocation in the hot path. All result arrays are preallocated and the
 *    native layer writes straight into them.
 *  * No fabricated results. If the native library is missing the caller is told
 *    so and can fall back to the Kotlin pipeline; nothing is ever invented.
 */
class NativeVisionEngine(
    val gridWidth: Int = 200,
    val gridHeight: Int = 112,
    screenWidth: Int = 1080,
    screenHeight: Int = 1920
) : AutoCloseable {

    companion object {
        private const val TAG = "NativeVisionEngine"

        /** Number of floats the native layer writes. Must match `kOutFloatCount`. */
        const val OUT_FLOATS = 24

        /** Number of ints the native layer writes. Must match `kOutIntCount`. */
        const val OUT_INTS = 13

        /** Must match `kExpected` in `nativeConfigure`. */
        const val CONFIG_FLOATS = 48

        const val MAX_MASKS = 8
        const val MAX_BLOBS = 48
        const val MAX_ENEMIES = 10
        const val MAX_TRACKS = 16

        /**
         * Floats per track in [readTracks]: x, y, vx, vy, speedNorm, isProjectile,
         * kind. Must match `kTrackFloats` on the native side.
         */
        const val TRACK_FLOATS = 7

        private val libraryLoaded: Boolean by lazy {
            try {
                System.loadLibrary("rendera_native")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "rendera_native could not be loaded; native vision is unavailable", e)
                false
            }
        }

        /** True when the compiled native engine is present in this APK. */
        fun isNativeSupported(): Boolean = libraryLoaded

        /** Allocates a direct, native-order buffer for plane staging. */
        fun allocatePlane(bytes: Int): ByteBuffer =
            ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }

    private var handle: Long = 0L

    private val outFloats = FloatArray(OUT_FLOATS)
    private val outInts = IntArray(OUT_INTS)
    private val configBuffer = FloatArray(CONFIG_FLOATS)
    private val maskBuffer = FloatArray(5 * MAX_MASKS)
    private val blobBuffer = FloatArray(4 * MAX_BLOBS)
    private val enemyBuffer = FloatArray(3 * MAX_ENEMIES)
    private val trackBuffer = FloatArray(TRACK_FLOATS * MAX_TRACKS)

    val isOpen: Boolean get() = handle != 0L

    init {
        if (libraryLoaded) {
            handle = nativeCreate(gridWidth, gridHeight, screenWidth, screenHeight)
            if (handle == 0L) {
                Log.e(TAG, "nativeCreate returned a null handle")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------------

    fun setScreenSize(width: Int, height: Int) {
        if (!isOpen) return
        nativeSetScreenSize(handle, width, height)
    }

    /**
     * Installs the whole tunable set. [tuning] is the single source of truth and
     * is written into a preallocated array, so applying it allocates nothing.
     */
    fun applyConfig(tuning: VisionTuning) {
        if (!isOpen) return
        tuning.writeInto(configBuffer)
        nativeConfigure(handle, configBuffer)
    }

    /**
     * Declares screen regions (normalised 0..1) that belong to Rendera's own
     * overlay and must never produce detections. The bubble, the mini menu and
     * the debug HUD are all captured by MediaProjection along with the game, so
     * without this they show up as moving objects.
     */
    fun setMasks(regions: List<ScreenRegion>) {
        if (!isOpen) return
        val n = minOf(regions.size, MAX_MASKS)
        for (i in 0 until n) {
            val r = regions[i]
            val o = i * 5
            maskBuffer[o] = r.centerX
            maskBuffer[o + 1] = r.centerY
            maskBuffer[o + 2] = r.halfWidth
            maskBuffer[o + 3] = r.halfHeight
            maskBuffer[o + 4] = if (r.enabled) 1f else 0f
        }
        if (n == 0) {
            nativeSetMask(handle, null)
        } else {
            nativeSetMask(handle, maskBuffer)
        }
    }

    fun reset() {
        if (!isOpen) return
        nativeReset(handle)
    }

    // -----------------------------------------------------------------------
    // Frame submission
    // -----------------------------------------------------------------------

    /**
     * Submits one captured frame and runs the full pipeline.
     *
     * @return a fresh [VisionResult], or `null` when native is unavailable.
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
        ptsNanos: Long
    ): VisionResult? {
        if (!isOpen) return null
        val rc = nativeProcess(
            handle, yPlane, yStride, uPlane, vPlane, uvStride,
            frameWidth, frameHeight, chromaWidth, chromaHeight, ptsNanos,
            outFloats, outInts
        )
        if (rc < 0) return null
        return VisionResult(outFloats, outInts)
    }

    // -----------------------------------------------------------------------
    // Debug readouts. These allocate on the JNI side and are only called when
    // the debug HUD is actually visible, so they stay off the hot path.
    // -----------------------------------------------------------------------

    /** Motion blobs. Each entry is (screenX, screenY, area, meanStrength). */
    fun readBlobs(): FloatArray {
        if (!isOpen) return FloatArray(0)
        val n = nativeCopyBlobs(handle, blobBuffer, MAX_BLOBS)
        return blobBuffer.copyOf(n * 4)
    }

    /** Enemy marks. Each entry is (screenX, screenY, area). */
    fun readEnemies(): FloatArray {
        if (!isOpen) return FloatArray(0)
        val n = nativeCopyEnemies(handle, enemyBuffer, MAX_ENEMIES)
        return enemyBuffer.copyOf(n * 3)
    }

    /**
     * Tracks. Each entry is
     * (x, y, vx, vy, speedNorm, isProjectile, kind) with kind a
     * [com.example.vision.nativebridge.TrackKind] code.
     */
    fun readTracks(): FloatArray {
        if (!isOpen) return FloatArray(0)
        val n = nativeCopyTracks(handle, trackBuffer, MAX_TRACKS)
        return trackBuffer.copyOf(n * TRACK_FLOATS)
    }

    /** Wall clock cost of the last native pipeline run, in milliseconds. */
    fun lastProcessMillis(): Double =
        if (isOpen) nativeLastProcessMillis(handle) else 0.0

    override fun close() {
        if (isOpen) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    // -----------------------------------------------------------------------
    // JNI
    // -----------------------------------------------------------------------

    private external fun nativeCreate(
        gridWidth: Int, gridHeight: Int, screenWidth: Int, screenHeight: Int
    ): Long

    private external fun nativeDestroy(handle: Long)
    private external fun nativeReset(handle: Long)
    private external fun nativeSetScreenSize(handle: Long, screenWidth: Int, screenHeight: Int)
    private external fun nativeConfigure(handle: Long, config: FloatArray)
    private external fun nativeSetMask(handle: Long, mask: FloatArray?)
    private external fun nativeProcess(
        handle: Long,
        yPlane: ByteBuffer, yStride: Int,
        uPlane: ByteBuffer?, vPlane: ByteBuffer?, uvStride: Int,
        frameWidth: Int, frameHeight: Int, chromaWidth: Int, chromaHeight: Int,
        ptsNanos: Long,
        outFloats: FloatArray, outInts: IntArray
    ): Int

    private external fun nativeCopyBlobs(handle: Long, out: FloatArray, maxItems: Int): Int
    private external fun nativeCopyEnemies(handle: Long, out: FloatArray, maxItems: Int): Int
    private external fun nativeCopyTracks(handle: Long, out: FloatArray, maxItems: Int): Int
    private external fun nativeLastProcessMillis(handle: Long): Double
}
