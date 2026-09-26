package com.example.vision.core

import android.graphics.ImageFormat
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer

/**
 * Zero-copy screen frame source.
 *
 * The luma plane of a `YUV_420_888` ImageReader frame is already exactly the
 * 8-bit grayscale image the vision pipeline needs, so it is copied straight into
 * a reusable [ByteArray]. No `android.graphics.Bitmap` is ever created, which
 * removes both the per-frame allocation and the GC hitch the old pipeline had.
 */
class FrameGrabber(
    private val captureWidth: Int,
    private val captureHeight: Int
) {
    companion object {
        private const val TAG = "FrameGrabber"

        /** Long side of the capture buffer, in pixels. */
        private const val TARGET_LONG_SIDE = 960
        private const val MAX_IMAGES = 3

        /**
         * Chooses a capture size that preserves the aspect ratio, has even
         * dimensions (required for 4:2:0 chroma) and is not larger than
         * [TARGET_LONG_SIDE] on its long side.
         */
        fun chooseCaptureSize(screenW: Int, screenH: Int): Pair<Int, Int> {
            if (screenW <= 0 || screenH <= 0) return Pair(640, 360)
            val longSide = maxOf(screenW, screenH)
            val scale = if (longSide > TARGET_LONG_SIDE) TARGET_LONG_SIDE.toFloat() / longSide else 1.0f
            var w = Math.round(screenW * scale)
            var h = Math.round(screenH * scale)
            if (w % 2 != 0) w -= 1
            if (h % 2 != 0) h -= 1
            if (w < 64) w = 64
            if (h < 64) h = 64
            return Pair(w, h)
        }
    }

    val width: Int = captureWidth
    val height: Int = captureHeight
    val rowStride: Int = captureWidth

    /** Reusable luma buffer, `rowStride * height` bytes. Never reallocated. */
    val luma = ByteArray(captureWidth * captureHeight)

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var lastTimestampNanos = 0L

    fun createReader(): ImageReader =
        ImageReader.newInstance(captureWidth, captureHeight, ImageFormat.YUV_420_888, MAX_IMAGES)
            .also { reader = it }

    fun reader(): ImageReader? = reader

    fun surface(): android.view.Surface? = reader?.surface

    fun attach(display: VirtualDisplay?) {
        this.display = display
    }

    fun releaseDisplay() {
        try {
            display?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "VirtualDisplay release failed", t)
        }
        display = null
    }

    fun release() {
        releaseDisplay()
        try {
            reader?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "ImageReader close failed", t)
        }
        reader = null
        lastTimestampNanos = 0L
    }

    val isActive: Boolean get() = reader != null

    /**
     * Copies the most recent luma plane into [luma].
     *
     * @return true when a new frame was written. False means "no frame
     *   available this tick", which is normal and must not be treated as an error.
     */
    fun grab(): Boolean {
        val r = reader ?: return false
        val image: Image? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                r.acquireLatestImage()
            } else {
                // acquireLatestImage() does not exist before API 26.
                r.acquireNextImage()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "acquireImage failed", t)
            null
        } ?: return false

        var ok = false
        try {
            if (image.width != captureWidth || image.height != captureHeight) return false
            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val stride = plane.rowStride
            if (pixelStride == 1 && stride == captureWidth) {
                // Fast path: contiguous rows, no shuffling needed.
                val need = captureWidth * captureHeight
                buffer.position(0)
                buffer.limit(minOf(buffer.remaining(), need))
                buffer.get(luma, 0, buffer.remaining())
                ok = buffer.remaining() >= need
            } else {
                ok = copyStrided(buffer, stride, pixelStride)
            }
            if (ok) lastTimestampNanos = image.timestamp
        } catch (t: Throwable) {
            Log.w(TAG, "luma copy failed", t)
            ok = false
        } finally {
            try {
                image.close()
            } catch (t: Throwable) {
                // ignore
            }
        }
        return ok
    }

    private fun copyStrided(buffer: ByteBuffer, stride: Int, pixelStride: Int): Boolean {
        val base = buffer.position()
        for (y in 0 until captureHeight) {
            val rowStart = base + y * stride
            if (rowStart + captureWidth * pixelStride > base + buffer.limit()) return false
            var src = rowStart
            val dst = y * captureWidth
            for (x in 0 until captureWidth) {
                luma[dst + x] = buffer.get(src)
                src += pixelStride
            }
        }
        return true
    }

    /** Frame timestamp in nanoseconds, 0 before the first successful grab. */
    fun timestampNanos(): Long = lastTimestampNanos
}
