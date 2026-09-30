package com.example.service

import android.media.Image
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Zero-copy staging for MediaProjection frames.
 *
 * ## What this does
 *
 * The reader is created as `ImageFormat.YUV_420_888`, which is the one format
 * that is both accepted by [android.media.ImageReader] and guaranteed readable
 * through the public SDK. Each of the three planes is copied row by row into a
 * preallocated direct [ByteBuffer]. No `Bitmap` is ever created and nothing is
 * allocated per frame.
 *
 * ## Why YUV_420_888 and not PRIVATE
 *
 * `ImageFormat.PRIVATE` is a trap. `ImageReader.newInstance` deliberately does
 * NOT request `USAGE_CPU_READ_OFTEN` for it - AOSP `ImageReader.java` says
 * "If the format is private don't default to USAGE_CPU_READ_OFTEN since it may
 * not work, and is inscrutable anyway" - so a private image exposes no planes
 * and its `HardwareBuffer` is not CPU-mappable. A capture on PRIVATE therefore
 * yields no usable pixels at all, which is why this app used to show a running
 * capture and never detect anything.
 *
 * `YUV_420_888` gets `USAGE_CPU_READ_OFTEN` automatically and hands back three
 * readable planes. It is also the only format `ImageReader` rejects by name: the
 * constructor's sole format check is for `NV21`. An earlier version of this
 * file claimed `YUV_420_888` is refused with "Invalid format specified 19". No
 * such check exists in the platform source, and the reader built here works.
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

    // Written under `lock` by the capture thread, read without it for
    // diagnostics. A 64 bit read is not atomic on 32-bit ART, so these are
    // volatile.
    @Volatile private var droppedFrames = 0L
    @Volatile private var rejectedFrames = 0L
    @Volatile private var planeErrors = 0L

    val droppedCount: Long get() = droppedFrames
    val rejectedCount: Long get() = rejectedFrames

    /** Copies that threw. Non zero means the ring is mis-configured, not the device. */
    val copyFailureCount: Long get() = planeErrors

    /** Sizes the pool. Safe to call on a configuration change. */
    fun configure(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        synchronized(lock) {
            if (width == frameWidth && height == frameHeight) return

            frameWidth = width
            frameHeight = height
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
                rejectedFrames++
                return false
            }
            val planes = image.planes
            if (planes.size < 3) {
                rejectedFrames++
                return false
            }

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
                planeErrors++
                Log.w(TAG, "plane copy failed", t)
                rejectedFrames++
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
            if (n > 0) {
                // `put(ByteBuffer, int, int)` was only added to java.nio in Java
                // 13 / API 33, so on a minSdk 24 device the compiler resolves
                // that call to the ByteArray overload and it fails. Duplicate,
                // window it, and use put(ByteBuffer), which always exists.
                val src = buffer.duplicate()
                src.position(0)
                src.limit(n)
                dst.put(src)
            }
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
        const val TAG = "RenderaRing"
    }
}
