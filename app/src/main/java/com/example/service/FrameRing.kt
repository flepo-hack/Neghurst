package com.example.service

import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.util.Log
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Copies captured frames into a reusable direct buffer, in whichever format the
 * device actually produces.
 *
 * ## Why there are two modes
 *
 * A virtual display feeding a `YUV_420_888` [ImageReader] works on most
 * devices and is the cheaper path, since luma and chroma arrive pre-separated.
 *
 * It does **not** work everywhere. A device can insist on
 * `ImageFormat.PRIVATE` (0x1): `acquireLatestImage()` then throws
 * `UnsupportedOperationException` with
 * *"the producer output buffer format 0x1 doesn't match the ImageReader's
 * configured buffer format 0x23"* - and it does so on **every frame**, so a
 * YUV-only capture produces nothing at all while appearing to run normally.
 *
 * That is not hypothetical: it is what one test device does, and it is the
 * single reason capture produced nothing there. The fallback is to request
 * `PRIVATE` and read the `HardwareBuffer` directly.
 *
 * ## Threading
 *
 * One producer (the reader's own callback thread) and one consumer (the vision
 * loop), sharing a small pool. When the consumer falls behind the producer
 * overwrites the oldest frame rather than blocking, because a slightly stale
 * frame is better than a stalled capture.
 */
class FrameRing(poolSize: Int = 3) {

    companion object {
        private const val TAG = "RenderaRing"
    }

    /** One frame, already in a tight buffer the JNI layer can read directly. */
    class Slot(
        val width: Int,
        val height: Int,
        val rowStride: Int,
        val format: Int
    ) {
        /** Interleaved RGBA/RGBX, `rowStride` bytes per row. */
        val rgba: ByteBuffer =
            ByteBuffer.allocateDirect(rowStride * height).order(java.nio.ByteOrder.nativeOrder())

        fun reset() {
            rgba.clear()
        }
    }

    private val _poolSize = maxOf(1, poolSize)
    private val free = ArrayDeque<Slot>()
    private val slotId = AtomicInteger(0)

    private var borrowedSlot: Slot? = null
    private var publishedSlot: Slot? = null
    private var publishedId = 0L
    private var consumedId = 0L

    @Volatile private var frameW = 0
    @Volatile private var frameH = 0
    @Volatile private var frameStride = 0
    @Volatile private var frameFormat = -1
    @Volatile private var dropped = 0L
    @Volatile private var received = 0L
    @Volatile private var rejected = 0L

    val width: Int get() = frameW
    val height: Int get() = frameH
    val receivedCount: Long get() = received
    val droppedCount: Long get() = dropped
    val rejectedCount: Long get() = rejected
    val formatName: String
        get() = when (frameFormat) {
            PixelFormat.RGBA_8888 -> "RGBA_8888"
            PixelFormat.RGBX_8888 -> "RGBX_8888"
            -1 -> "none"
            else -> "0x${Integer.toHexString(frameFormat)}"
        }

    /** Reallocates the pool. Called when the capture size or format changes. */
    fun configure(width: Int, height: Int, rowStride: Int, format: Int) {
        if (width == frameW && height == frameH && rowStride == frameStride && format == frameFormat) {
            return
        }
        synchronized(this) {
            frameW = width
            frameH = height
            frameStride = rowStride
            frameFormat = format
            free.clear()
            publishedSlot = null
            borrowedSlot = null
            publishedId = 0L
            consumedId = 0L
            repeat(_poolSize) { free.addLast(Slot(width, height, rowStride, format)) }
        }
    }

    /**
     * Copies a `PRIVATE` image's hardware buffer into the pool.
     *
     * The buffer is locked, copied, and unlocked. Locking is required: the bytes
     * are not addressable otherwise, and holding the lock across anything else
     * stalls the compositor, which is what makes a capture stutter the game.
     */
    fun publishPrivate(image: Image): Boolean {
        val buffer: HardwareBuffer = try {
            image.hardwareBuffer
        } catch (t: Throwable) {
            Log.w(TAG, "no hardware buffer on this image", t)
            rejected++
            return false
        } ?: run { rejected++; return false }

        val slot = acquire() ?: run { rejected++; return false }
        var copied = false
        try {
            val rowBytes = frameW * 4
            if (buffer.rowStride < rowBytes || frameH <= 0 || frameW <= 0) {
                Log.w(
                    TAG,
                    "unexpected buffer: stride=${buffer.rowStride} w=$frameW h=$frameH"
                )
                rejected++
                return false
            }
            // The bytes are only addressable while the buffer is locked, and
            // holding the lock across anything slow stalls the compositor,
            // which is what makes a capture stutter the game.
            val src = buffer.lock()
            try {
                slot.rgba.clear()
                val limit = minOf(src.limit(), src.capacity())
                for (y in 0 until frameH) {
                    val srcStart = y * buffer.rowStride
                    val dstStart = y * slot.rowStride
                    if (srcStart + rowBytes > limit) break
                    val srcRow = src.duplicate()
                    srcRow.position(srcStart)
                    srcRow.limit(srcStart + rowBytes)
                    val dstRow = slot.rgba.duplicate()
                    dstRow.position(dstStart)
                    dstRow.limit(dstStart + rowBytes)
                    dstRow.put(srcRow)
                }
                copied = true
            } finally {
                runCatching { buffer.unlock() }
                    .onFailure { Log.w(TAG, "unlock failed", it) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "private frame copy failed", t)
            rejected++
            recycle(slot)
            return false
        }
        if (!copied) {
            recycle(slot)
            return false
        }
        received++
        publish(slot)
        return true
    }

    private fun acquire(): Slot? = synchronized(this) {
        free.pollFirst() ?: if (borrowedSlot != null) {
            // The consumer has a frame; the producer is allowed to take it, which
            // means the consumer will find its frame recycled underneath it. To
            // keep that impossible, the consumer's frame is only reclaimed by
            // `take`, and the producer only reclaims slots it has already
            // published. Anything else is a dropped frame, which is fine.
            val s = borrowedSlot
            borrowedSlot = null
            dropped++
            s
        } else null
    }

    private fun publish(slot: Slot) = synchronized(this) {
        if (publishedSlot != null && publishedSlot !== slot) dropped++
        publishedSlot = slot
        publishedId++
    }

    private fun recycle(slot: Slot) = synchronized(this) {
        slot.reset()
        free.addLast(slot)
        while (free.size > _poolSize) free.pollFirst()
    }

    /** Takes the newest published frame, or null when nothing new arrived. */
    fun take(): Frame? = synchronized(this) {
        val slot = publishedSlot ?: return null
        if (consumedId >= publishedId) return null
        consumedId = publishedId
        publishedSlot = null
        borrowedSlot = slot
        Frame(
            id = consumedId,
            rgba = slot.rgba,
            rowStride = slot.rowStride,
            width = slot.width,
            height = slot.height,
            format = slot.format
        ) { release(slot) }
    }

    private fun release(slot: Slot) = synchronized(this) {
        if (borrowedSlot === slot) borrowedSlot = null
        recycle(slot)
    }

    fun releaseAll() = synchronized(this) {
        free.clear()
        publishedSlot = null
        borrowedSlot = null
        publishedId = 0L
        consumedId = 0L
    }

    /** A frame borrowed from the ring. [recycle] must be called when done. */
    class Frame(
        val id: Long,
        val rgba: ByteBuffer,
        val rowStride: Int,
        val width: Int,
        val height: Int,
        val format: Int,
        private val onRecycle: () -> Unit
    ) {
        private var returned = false
        fun recycle() {
            if (returned) return
            returned = true
            onRecycle()
        }
    }
}
