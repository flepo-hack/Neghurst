package com.example.service

import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Zero-copy staging for MediaProjection frames.
 *
 * ## Why this exists
 *
 * The previous capture path allocated and recycled a `Bitmap` every frame:
 * `Bitmap.createBitmap` + `copyPixelsFromBuffer` + a per-frame `getPixels` into
 * an `IntArray`. That is exactly the garbage collection churn the design
 * explicitly rules out, and it was also a source of real failure: an image
 * plane's `ByteBuffer.limit()` is often `rowStride * (height - 1) +
 * pixelStride * width`, which is **smaller** than the `rowStride * height` the
 * old code assumed, so `copyPixelsFromBuffer` threw and the frame was silently
 * dropped. That, plus a missing `MediaProjection.Callback` registration, is why
 * screen reading appeared to do nothing at all.
 *
 * ## What this does instead
 *
 * The reader is created as `YUV_420_888` and only the **luma** plane and the two
 * chroma planes are read, each row copied into a preallocated direct
 * `ByteBuffer`. Luma is one byte per pixel, so this is a quarter of the traffic
 * of `RGBA_8888`, and the native engine downsamples to its grid. No `Bitmap` is
 * ever created.
 *
 * ## Threading
 *
 * Exactly one producer (the `OnImageAvailableListener` callback) and one consumer
 * (the vision loop) share a small pool. When the consumer falls behind, the
 * producer overwrites the newest-unconsumed frame instead of blocking, because
 * for threat tracking a slightly stale frame is strictly better than a stalled
 * capture: the alternative is that the pool empties, `ImageReader` stops
 * accepting buffers, and the platform starts dropping frames behind our back.
 */
class YuvFrameRing(private val poolSize: Int = 3) {

    private val lock = Any()

    private var slots: Array<ByteBuffer?> = arrayOfNulls(poolSize)
    private var uSlots: Array<ByteBuffer?> = arrayOfNulls(poolSize)
    private var vSlots: Array<ByteBuffer?> = arrayOfNulls(poolSize)

    private var frameWidth = 0
    private var frameHeight = 0
    private var chromaWidth = 0
    private var chromaHeight = 0
    private var yStride = 0
    private var uvStride = 0

    /** Slot currently owned by the consumer; the producer must never touch it. */
    private var borrowedSlot = -1

    /** Slot whose contents are the newest published frame. */
    private var publishedSlot = -1
    private var publishedId = NO_FRAME
    private var consumedId = NO_FRAME
    private var writeCursor = 0

    // Written under `lock` by the capture thread, read without it by the vision
    // thread. A 64 bit read is not atomic on 32-bit ART, and Int reads of geometry
    // would not see the paired write, so these are volatile.
    @Volatile private var consumedFrames = 0L
    @Volatile private var droppedFrames = 0L
    @Volatile private var frameWidthVolatile = 0
    @Volatile private var frameHeightVolatile = 0

    val width: Int get() = frameWidthVolatile
    val height: Int get() = frameHeightVolatile
    val hasFrame: Boolean get() = publishedId != NO_FRAME
    val consumedCount: Long get() = consumedFrames
    val droppedCount: Long get() = droppedFrames

    /** Sizes the pool. Safe to call on a configuration change. */
    fun configure(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        synchronized(lock) {
            if (width == frameWidth && height == frameHeight) return

            frameWidth = width
            frameHeight = height
            frameWidthVolatile = width
            frameHeightVolatile = height
            // Pad strides to 16 bytes, matching what the hardware planes use and
            // keeping the native gather aligned.
            yStride = (width + 15) and 15.inv()
            chromaWidth = (width + 1) / 2
            chromaHeight = (height + 1) / 2
            uvStride = (chromaWidth + 15) and 15.inv()

            slots = Array(poolSize) { allocate(yStride * height) }
            uSlots = Array(poolSize) { allocate(uvStride * chromaHeight) }
            vSlots = Array(poolSize) { allocate(uvStride * chromaHeight) }

            borrowedSlot = -1
            publishedSlot = -1
            publishedId = NO_FRAME
            consumedId = NO_FRAME
            writeCursor = 0
        }
    }

    private fun allocate(bytes: Int): ByteBuffer =
        ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    /**
     * Copies [image]'s planes into a free pool slot and publishes it.
     *
     * Returns false when the image cannot be used, which happens legitimately on
     * the frame where the geometry changed but the pool has not been
     * reconfigured yet.
     */
    fun publish(image: Image): Boolean {
        synchronized(lock) {
            if (frameWidth <= 0 || image.width != frameWidth || image.height != frameHeight) {
                return false
            }
            val planes = image.planes
            if (planes.size < 3) return false

            val idx = nextFreeSlot() ?: return false
            if (idx == publishedSlot) {
                // Overwriting the newest unconsumed frame: that frame is lost.
                droppedFrames++
            }
            val dstY = slots[idx] ?: return false
            val dstU = uSlots[idx] ?: return false
            val dstV = vSlots[idx] ?: return false

            val okCpy = try {
                copyPlane(planes[0], dstY, yStride, image.width, image.height)
                copyPlane(planes[1], dstU, uvStride, chromaWidth, chromaHeight)
                copyPlane(planes[2], dstV, uvStride, chromaWidth, chromaHeight)
                true
            } catch (t: Throwable) {
                false
            }
            if (!okCpy) return false

            dstY.position(0)
            dstU.position(0)
            dstV.position(0)

            publishedSlot = idx
            publishedId = if (publishedId == Long.MAX_VALUE) 1L else publishedId + 1
            return true
        }
    }

    /** Next slot the producer may write, skipping the consumer's slot. */
    private fun nextFreeSlot(): Int? {
        for (k in 0 until poolSize) {
            val idx = (writeCursor + k) % poolSize
            if (idx != borrowedSlot) {
                writeCursor = (idx + 1) % poolSize
                return idx
            }
        }
        return null
    }

    /**
     * Atomically takes ownership of the newest published frame.
     *
     * Returns null when nothing new has been published since the last call, so
     * the vision loop can idle instead of reprocessing a stale frame.
     */
    fun take(): Frame? = synchronized(lock) {
        if (publishedId == NO_FRAME || publishedId == consumedId) return null
        val idx = publishedSlot
        if (idx < 0) return null
        val y = slots[idx] ?: return null
        val u = uSlots[idx] ?: return null
        val v = vSlots[idx] ?: return null
        consumedId = publishedId
        borrowedSlot = idx
        consumedFrames++
        Frame(
            id = publishedId,
            y = y,
            u = u,
            v = v,
            yStride = yStride,
            uvStride = uvStride,
            width = frameWidth,
            height = frameHeight,
            chromaWidth = chromaWidth,
            chromaHeight = chromaHeight
        ) { releaseSlot(idx) }
    }

    private fun releaseSlot(idx: Int) {
        synchronized(lock) {
            if (borrowedSlot == idx) borrowedSlot = -1
        }
    }

    /**
     * Copies one plane row by row, honouring the plane's pixel stride.
     *
     * Reading with the plane's own `rowStride` and `pixelStride` is what makes
     * this correct everywhere: some devices report a tight plane, some pad the
     * row, and some use a semi-planar layout with `pixelStride == 2` for chroma.
     */
    private fun copyPlane(
        plane: Image.Plane,
        dst: ByteBuffer,
        dstRowStride: Int,
        samples: Int,
        rows: Int
    ) {
        if (samples <= 0 || rows <= 0) return
        val buffer = plane.buffer
        val srcRowStride = plane.rowStride
        val srcPixelStride = plane.pixelStride
        val limit = buffer.limit()
        dst.clear()

        if (srcPixelStride == 1 && srcRowStride == dstRowStride) {
            // Fast path: tight plane with matching stride, one bulk copy. The
            // length is clamped because the plane limit is frequently smaller
            // than rows * rowStride.
            val needed = (rows - 1) * srcRowStride + samples
            val n = minOf(needed, limit)
            if (n > 0) dst.put(buffer, 0, n)
            dst.position(0)
            return
        }

        val row = ByteArray(samples)
        for (y in 0 until rows) {
            val rowStart = y * srcRowStride
            if (rowStart >= limit) break
            val toRead = minOf(samples * srcPixelStride, limit - rowStart)
            if (toRead <= 0) break
            buffer.position(rowStart)
            buffer.get(row, 0, toRead)
            var i = 0
            var o = y * dstRowStride
            val rowEnd = o + samples
            while (i < toRead && o < rowEnd) {
                dst.put(o, row[i])
                o++
                i += srcPixelStride
            }
        }
        dst.position(0)
    }

    fun release() {
        synchronized(lock) {
            slots = arrayOfNulls(poolSize)
            uSlots = arrayOfNulls(poolSize)
            vSlots = arrayOfNulls(poolSize)
            borrowedSlot = -1
            publishedSlot = -1
            publishedId = NO_FRAME
            consumedId = NO_FRAME
            writeCursor = 0
        }
    }

    /** A borrowed frame. [recycle] must be called exactly once when done. */
    class Frame(
        val id: Long,
        val y: ByteBuffer,
        val u: ByteBuffer,
        val v: ByteBuffer,
        val yStride: Int,
        val uvStride: Int,
        val width: Int,
        val height: Int,
        val chromaWidth: Int,
        val chromaHeight: Int,
        private val onRecycle: () -> Unit
    ) {
        private var returned = false

        fun recycle() {
            if (returned) return
            returned = true
            onRecycle()
        }
    }

    private companion object {
        const val NO_FRAME = 0L
    }
}
