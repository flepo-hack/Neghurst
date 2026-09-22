package com.example.vision.nativebridge

import android.graphics.Bitmap
import android.util.Log
import com.example.model.DetectionStats
import com.example.model.ThreatLevel
import com.example.model.ThreatVector
import com.example.vision.ScreenThreatDetector
import java.io.Closeable

/**
 * Native C++ OpenCV / NDK JNI Bridge for Rendera Screen Threat Detection.
 *
 * Implements high-throughput, zero-allocation native frame processing using
 * direct primitive buffers and C++ acceleration. When the native library is
 * compiled and available, frames are processed in native C++. When running in
 * environments without the compiled .so, it gracefully delegates to the
 * reference zero-allocation Kotlin pipeline.
 */
class NativeRenderaPipeline(
    val gridWidth: Int = 80,
    val gridHeight: Int = 48
) : Closeable {

    companion object {
        private const val TAG = "NativeRenderaPipeline"
        private var isNativeLoaded = false

        init {
            try {
                System.loadLibrary("rendera_native")
                isNativeLoaded = true
                Log.i(TAG, "Native Rendera C++ library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                isNativeLoaded = false
                Log.w(TAG, "rendera_native not linked, using pure Kotlin deterministic pipeline: ${e.message}")
            }
        }

        fun isNativeSupported(): Boolean = isNativeLoaded
    }

    private var nativeHandle: Long = 0L

    // Fallback Kotlin engine for testing and environments without precompiled NDK binary
    private val fallbackDetector: ScreenThreatDetector by lazy {
        ScreenThreatDetector()
    }

    // Preallocated output buffer for native JNI call (7 floats, zero GC in hot loop)
    private val nativeOutBuffer = FloatArray(7)

    init {
        if (isNativeLoaded) {
            try {
                nativeHandle = nativeInit(gridWidth, gridHeight)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize native pipeline instance", t)
                nativeHandle = 0L
            }
        }
    }

    /**
     * Ingests and processes a frame through the C++ NDK engine or the zero-allocation Kotlin engine.
     */
    fun processFrame(
        bitmap: Bitmap,
        screenWidth: Float,
        screenHeight: Float,
        profile: com.example.model.DodgeProfile = com.example.model.DodgeProfile(),
        onThreatDetected: (ThreatVector) -> Unit
    ) {
        if (isNativeLoaded && nativeHandle != 0L) {
            val grayscaleBytes = fallbackDetector.frameBuffer.grayscaleBuffer
            fallbackDetector.frameBuffer.ingestBitmap(bitmap)

            val ret = nativeProcessFrame(
                nativeHandle,
                grayscaleBytes,
                screenWidth,
                screenHeight,
                nativeOutBuffer
            )

            val threatRaw = nativeOutBuffer[0].toInt()
            val dodgeAngle = nativeOutBuffer[1]
            val playerX = nativeOutBuffer[2]
            val playerY = nativeOutBuffer[3]

            if (ret > 0 && threatRaw > 0) {
                val level = when (threatRaw) {
                    3 -> ThreatLevel.LETHAL
                    2 -> ThreatLevel.IMMINENT_DANGER
                    else -> ThreatLevel.WARNING
                }

                val dodgeRad = Math.toRadians(dodgeAngle.toDouble())
                val threatAngle = (dodgeAngle - 90f + 360f) % 360f
                val threatRad = Math.toRadians(threatAngle.toDouble())
                val speed = 500f

                val threat = ThreatVector(
                    threatX = playerX + 150f,
                    threatY = playerY,
                    velocityX = (-Math.cos(threatRad) * speed).toFloat(),
                    velocityY = (-Math.sin(threatRad) * speed).toFloat(),
                    speed = speed,
                    threatAngleDeg = threatAngle,
                    dodgeAngleDeg = dodgeAngle,
                    dodgeDirX = Math.cos(dodgeRad).toFloat(),
                    dodgeDirY = Math.sin(dodgeRad).toFloat(),
                    threatLevel = level,
                    timeToImpactMs = if (level == ThreatLevel.LETHAL) 90L else 200L,
                    confidence = 0.95f
                )
                onThreatDetected(threat)
            }
        } else {
            // Pure Kotlin deterministic pipeline execution
            val result = fallbackDetector.analyzeFrame(
                frame = bitmap,
                profile = profile,
                screenWidth = screenWidth.toInt(),
                screenHeight = screenHeight.toInt()
            )
            result?.threat?.let { onThreatDetected(it) }
        }
    }

    /**
     * Resets motion history and tracking filters.
     */
    fun reset() {
        if (isNativeLoaded && nativeHandle != 0L) {
            nativeReset(nativeHandle)
        }
        fallbackDetector.reset()
    }

    override fun close() {
        if (isNativeLoaded && nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    // JNI Native methods
    private external fun nativeInit(gridWidth: Int, gridHeight: Int): Long
    private external fun nativeProcessFrame(
        handle: Long,
        grayscaleBytes: ByteArray,
        screenWidth: Float,
        screenHeight: Float,
        outThreatData: FloatArray
    ): Int
    private external fun nativeReset(handle: Long)
    private external fun nativeDestroy(handle: Long)
}
