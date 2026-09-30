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
 * The reader is created as `PixelFormat.RGBA_8888` and each frame's single
 * interleaved plane is copied row by row into a preallocated direct
 * [ByteBuffer], honouring the plane's own `rowStride` and `pixelStride`. No
 * `Bitmap` is created, no `HardwareBuffer` is wrapped, and nothing is allocated
 * per frame.
 *
 * ## Why the plane, and not a hardware buffer
 *
 * This is the recipe the platform's own tests use for exactly this job:
 * AOSP `VirtualDisplayTest` and CTS `MediaProjectionMirroringTest` both create
 * the reader with `PixelFormat.RGBA_8888` and read `image.planes[0]`, copying
 * out the row padding by hand. That path touches no native platform code beyond
 * the `Image` API, so it has no null-pointer path to fault on.
 *
 * The alternative - `image.hardwareBuffer` then
 * `Bitmap.wrapHardwareBuffer(...).copy(ARGB_8888, false)` - is what an earlier
 * version of this file did, and it produced a native SIGSEGV on the first frame
 * (`SEGV_MAPERR` at a small offset from null, on the capture thread). The reason
 * is structural rather than accidental: `wrapHardwareBuffer` is a GPU-path API.
 * It is documented as requiring `USAGE_GPU_SAMPLED_IMAGE`, and its CPU "copy" is
 * a RenderThread readback, not a memcpy. For a buffer whose format is
 * `AHARDWAREBUFFER_FORMAT_PRIVATE` (0x22) hwui's `createImageInfo` has no case at
 * all, so the wrapped bitmap is silently built with an unknown colour type and a
 * row stride of zero, and reading from it writes through a null pixel pointer.
 * No production implementation does this: scrcpy and media3 never ask for CPU
 * pixels at all, and the AOSP capture clients that do use `RGBA_8888` plus
 * `getPlanes()[0]`.
 *
 * ## Format constants, which are not what they look like
 *
 * `android.graphics.ImageFormat` and `android.graphics.PixelFormat` are separate
 * enums that deliberately do not overlap, and the platform renumbered
 * `ImageFormat` when it moved out of `PixelFormat`:
 *
 *  - `ImageFormat.PRIVATE` is **0x22 (34)**, not 1
 *  - `ImageFormat.YUV_420_888` is **0x23 (35)**, not 0x13 (19)
 *  - `PixelFormat.RGBA_8888` is **0x01 (1)**
 *
 * So 19 is not a valid format at all, and "Invalid format specified 19" is
 * literally true rather than a producer mismatch. A virtual display mirrors the
 * composed display, which is RGBA_8888, so that is what is asked for here.
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
class CaptureFrameRing(private val poolSize: Int = 3) {

    private val lock = Any()

    private var slots: Array<ByteBuffer?> = arrayOfNulls(poolSize)
    private var rgbaStride = 0

    private var frameWidth = 0
    private var frameHeight = 0

    /** Slot currently owned by the consumer; the producer must never touch it. */
    private var borrowedSlot = -1

    /** Slot whose contents are the newest published frame. */
    private var publishedSlot = -1
    private var publishedId = NO_FRAME
    private var consumedId = NO_FRAME
    private var writeCursor = 0

    // Written under `lock` by the capture thread and read without it for
    // diagnostics. A 64 bit read is not atomic on 32-bit ART, so these are
    // volatile.
    @Volatile private var consumedFrames = 0L
    @Volatile private var droppedFrames = 0L
    @Volatile private var rejectedFrames = 0L
    @Volatile private var copyErrors = 0L
    @Volatile private var noPlaneFrames = 0L
    @Volatile private var shortPlaneFrames = 0L
    @Volatile private var observedFormat = -1
    @Volatile private var observedRowStride = -1
    @Volatile private var observedPixelStride = -1
    @Volatile private var firstFrameReported = false

    val droppedCount: Long get() = droppedFrames
    val consumedCount: Long get() = consumedFrames
    val rejectedCount: Long get() = rejectedFrames

    /** Copies that threw. Non zero means the ring is mis-configured, not the device. */
    val copyFailureCount: Long get() = copyErrors

    /** Images that arrived with no readable plane at all. */
    val missingPlaneCount: Long get() = noPlaneFrames

    /** Images whose plane held fewer bytes than one row needs. */
    val shortPlaneCount: Long get() = shortPlaneFrames

    /**
     * What the first frame actually looked like.
     *
     * Reported once, because "no frames" and "frames that are the wrong shape"
     * are indistinguishable from outside and both mean the capture geometry and
     * the device disagree.
     */
    fun firstFrameDescription(): String =
        "format=$observedFormat planes=${observedPlanes} " +
            "rowStride=$observedRowStride pixelStride=$observedPixelStride"

    @Volatile private var observedPlanes = -1

    /** Sizes the pool. Safe to call on a configuration change. */
    fun configure(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        synchronized(lock) {
            if (width == frameWidth && height == frameHeight) return
            frameWidth = width
            frameHeight = height
            // Exactly width * 4, no padding. copyPixelsToBuffer is not used, so
            // the stride is ours to choose, and an exact stride means the native
            // gather can walk rows without a bounds check per row.
            rgbaStride = width * 4
            slots = Array(poolSize) { allocate(rgbaStride * height) }
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
     * Copies one image into a free pool slot and publishes it.
     *
     * Returns false when the frame cannot be used, which happens legitimately on
     * the frame where the geometry changed but the pool has not been
     * reconfigured yet.
     */
    fun publish(image: Image): Boolean {
        if (frameWidth <= 0 || image.width != frameWidth || image.height != frameHeight) {
            rejectedFrames++
            return false
        }
        val planes = image.planes
        if (planes.isEmpty()) {
            // A PRIVATE image has no planes by design. Seeing one here means the
            // reader is not the format it was created as, which is worth a
            // distinct count rather than a generic rejection.
            noPlaneFrames++
            rejectedFrames++
            observedFormat = image.format
            observedPlanes = 0
            reportFirstFrameOnce("no planes")
            return false
        }

        synchronized(lock) {
            val idx = nextFreeSlot() ?: return false
            if (idx == publishedSlot) {
                // Overwriting the newest unconsumed frame: that frame is lost.
                droppedFrames++
            }
            val dst = slots[idx] ?: return false
            val ok = try {
                copyPlane(planes[0], dst, frameWidth, frameHeight)
            } catch (t: Throwable) {
                copyErrors++
                rejectedFrames++
                Log.w(TAG, "frame copy failed", t)
                false
            }
            if (!ok) return false

            observedFormat = image.format
            observedPlanes = planes.size
            observedRowStride = planes[0].rowStride
            observedPixelStride = planes[0].pixelStride
            reportFirstFrameOnce(null)

            publishedSlot = idx
            publishedId = if (publishedId == Long.MAX_VALUE) 1L else publishedId + 1
            return true
        }
    }

    /**
     * Copies one interleaved plane, honouring its strides.
     *
     * `rowStride` may exceed `width * 4`: the driver is free to pad rows, and a
     * tight read over a padded plane shears every row but the first. The plane
     * buffer's own `limit` is also frequently smaller than
     * `rows * rowStride`, because it is the used size and not the allocation, so
     * every row is bounds-checked against it.
     */
    private fun copyPlane(
        plane: Image.Plane,
        dst: ByteBuffer,
        width: Int,
        height: Int
    ): Boolean {
        val src = plane.buffer
        val srcRowStride = plane.rowStride
        val srcPixelStride = plane.pixelStride
        val limit = src.limit()

        if (srcPixelStride != 4) {
            // An RGBA plane with any other pixel stride is not what this code
            // is written against, and copying it as if it were 4 would produce
            // plausible-looking garbage. Better to say so.
            shortPlaneFrames++
            rejectedFrames++
            return false
        }
        val rowBytes = width * 4
        if (srcRowStride < rowBytes || limit < (height - 1L) * srcRowStride + rowBytes) {
            shortPlaneFrames++
            rejectedFrames++
            return false
        }

        dst.clear()
        if (srcRowStride == rowBytes) {
            // Tight plane: one bulk copy, no per-row window.
            val srcView = src.duplicate()
            srcView.position(0)
            srcView.limit(rowBytes * height)
            dst.put(srcView)
            dst.clear()
            return true
        }

        val row = ByteArray(rowBytes)
        for (y in 0 until height) {
            val from = y * srcRowStride
            src.position(from)
            src.get(row, 0, rowBytes)
            dst.position(y * rgbaStride)
            dst.put(row)
        }
        dst.clear()
        return true
    }

    /** One line, once, describing the first frame the device actually delivered. */
    private fun reportFirstFrameOnce(reason: String?) {
        if (firstFrameReported) return
        firstFrameReported = true
        val text = if (reason == null) {
            "first frame: $observedPlanes plane(s), ${firstFrameDescription()}"
        } else {
            "first frame rejected ($reason): ${firstFrameDescription()}"
        }
        Log.i(TAG, text)
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
        val rgba = slots[idx] ?: return null
        consumedId = publishedId
        borrowedSlot = idx
        consumedFrames++
        Frame(
            id = publishedId,
            rgba = rgba,
            rgbaStride = rgbaStride,
            width = frameWidth,
            height = frameHeight
        ) { releaseSlot(idx) }
    }

    private fun releaseSlot(idx: Int) {
        synchronized(lock) {
            if (borrowedSlot == idx) borrowedSlot = -1
        }
    }

    fun release() {
        synchronized(lock) {
            slots = arrayOfNulls(poolSize)
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
        val rgba: ByteBuffer,
        val rgbaStride: Int,
        val width: Int,
        val height: Int,
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
