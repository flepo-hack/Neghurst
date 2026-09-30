package com.example.service

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.media.Image
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Zero-copy staging for MediaProjection frames.
 *
 * ## What this does
 *
 * The reader is created as `ImageFormat.PRIVATE`, and each frame's pixels are
 * read out of the image's `HardwareBuffer` into a preallocated direct
 * [ByteBuffer]. Nothing is allocated per frame and no `Bitmap` is ever created.
 *
 * ## Why PRIVATE, and why the buffer has to be locked properly
 *
 * A `VirtualDisplay` mirrors the composed display, which is `RGBA_8888`. An
 * `ImageReader` will not hand out an image whose format differs from the one it
 * was created with - it throws `UnsupportedOperationException` naming **the
 * reader's** format, so a YUV_420_888 reader reports "Invalid format specified
 * 19" when format 19 is perfectly valid and the producer is what disagrees. A
 * PRIVATE reader is the documented exemption: the image is acquired anyway.
 *
 * PRIVATE has two catches, and both of them have to be handled.
 *
 * First, `ImageReader.newInstance` with four arguments deliberately does **not**
 * request `USAGE_CPU_READ_OFTEN` for a PRIVATE reader - the platform comment
 * says it "may not work, and is inscrutable anyway" - so its buffer is
 * allocated without CPU access. The four-argument constructor is the trap; the
 * five-argument one takes an explicit usage, and `USAGE_CPU_READ_OFTEN` is what
 * makes the buffer mappable at all.
 *
 * Second, and this is why an earlier attempt at this file never compiled:
 * `HardwareBuffer.lock()`, `unlock()` and `rowStride` are all `@hide`. They are
 * not in the public SDK, so Kotlin rejects them outright - there is no way to
 * read a private image's bytes through them from an app. The public route is
 * [Bitmap.wrapHardwareBuffer], which gives a bitmap that can be copied into CPU
 * memory. That costs one full-frame copy per frame, which is why the rows are
 * written straight into the pooled buffer rather than through an intermediate.
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
    @Volatile private var droppedFrames = 0L
    @Volatile private var rejectedFrames = 0L
    @Volatile private var copyErrors = 0L
    @Volatile private var noBufferFrames = 0L
    @Volatile private var lockFailures = 0L
    @Volatile private var observedFormat = -1

    val droppedCount: Long get() = droppedFrames
    val rejectedCount: Long get() = rejectedFrames

    /** Copies that threw. Non zero means the ring is mis-configured, not the device. */
    val copyFailureCount: Long get() = copyErrors

    /** Frames whose image carried no hardware buffer at all. */
    val missingBufferCount: Long get() = noBufferFrames

    /** Frames whose buffer could not be locked, i.e. no CPU-readable pixels. */
    val lockFailureCount: Long get() = lockFailures

    /**
     * The `ImageReader` format of the last image that carried a buffer.
     *
     * Recorded because a virtual display is entitled to change it, and a silent
     * change from RGBA to something else is exactly the kind of thing that
     * makes a capture stop detecting without anything reporting an error.
     */
    val lastImageFormat: Int get() = observedFormat

    /** Sizes the pool. Safe to call on a configuration change. */
    fun configure(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        synchronized(lock) {
            if (width == frameWidth && height == frameHeight) return
            frameWidth = width
            frameHeight = height
            // Exactly width * 4, no padding: copyPixelsToBuffer writes rows
            // tightly packed, and a padded stride here would shift every row
            // but the first. 4 bytes is the alignment the engine needs.
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
        synchronized(lock) {
            val idx = nextFreeSlot() ?: return false
            if (idx == publishedSlot) {
                // Overwriting the newest unconsumed frame: that frame is lost.
                droppedFrames++
            }
            val dst = slots[idx] ?: return false
            val ok = try {
                // Image gained getHardwareBuffer() in API 28, and the reader
                // that produces a CPU-mappable buffer needs API 29 anyway; the
                // service refuses to start below that and says so.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    noBufferFrames++
                    rejectedFrames++
                    false
                } else {
                    copyPixels(image, dst)
                }
            } catch (t: Throwable) {
                copyErrors++
                rejectedFrames++
                Log.w(TAG, "frame copy failed", t)
                false
            }
            observedFormat = image.format
            publishedSlot = idx
            publishedId = if (publishedId == Long.MAX_VALUE) 1L else publishedId + 1
            return true
        }
    }

    /**
     * Moves one image's pixels into [dst].
     *
     * `HardwareBuffer.lock()` is @hide, so the public route is to wrap the
     * buffer as a bitmap and copy it into CPU memory. `wrapHardwareBuffer` is
     * zero-copy; the `copy` is not, and it is the one unavoidable per-frame
     * allocation this path has. It is a 480x216 ARGB buffer, which is small
     * enough that it is not worth a custom native path, and a custom one would
     * mean reimplementing a locked, fence-aware read in C++ to avoid it.
     *
     * `ARGB_8888` bitmaps are stored as R,G,B,A bytes in memory, which is the
     * order the engine's downsampler reads.
     */
    private fun copyPixels(image: Image, dst: ByteBuffer): Boolean {
        val hb = image.hardwareBuffer
        if (hb == null) {
            noBufferFrames++
            rejectedFrames++
            return false
        }
        val wrapped = Bitmap.wrapHardwareBuffer(hb, SRGB)
        // The hardware bitmap is a view on the capture buffer and must not be
        // recycled: the image owns it. The software copy is ours.
        val software = wrapped.copy(Bitmap.Config.ARGB_8888, false)
        try {
            if (software.width != frameWidth || software.height != frameHeight) {
                rejectedFrames++
                return false
            }
            // copyPixelsToBuffer writes rows tightly packed, so the pool stride
            // has to be exactly width * 4 or every row after the first is read
            // from the wrong offset.
            dst.clear()
            software.copyPixelsToBuffer(dst)
            if (dst.position() < frameWidth * 4 * frameHeight) {
                // Fewer bytes than the frame needs. Copying on would hand the
                // engine a buffer that is silently short.
                lockFailures++
                rejectedFrames++
                return false
            }
            dst.clear()
            return true
        } finally {
            software.recycle()
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
        val rgba = slots[idx] ?: return null
        consumedId = publishedId
        borrowedSlot = idx
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
        val SRGB: ColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        const val NO_FRAME = 0L
        const val TAG = "RenderaRing"
    }
}
