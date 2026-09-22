package com.example.vision.deterministic

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance, zero-allocation frame buffer for deterministic computer vision.
 *
 * Pre-allocates direct native memory and flat primitive arrays to ensure that frame processing
 * never allocates heap objects or triggers Android Garbage Collection (GC) pauses during the 60 FPS loop.
 */
class DeterministicFrameBuffer(
    val width: Int,
    val height: Int
) {
    val totalPixels: Int = width * height

    // Reusable flat primitive arrays (Zero GC allocations per frame)
    val grayscaleBuffer: ByteArray = ByteArray(totalPixels)
    val prevGrayscaleBuffer: ByteArray = ByteArray(totalPixels)
    val alignedGrayscaleBuffer: ByteArray = ByteArray(totalPixels)
    val motionDiffBuffer: ByteArray = ByteArray(totalPixels)

    // Direct byte buffer for fast JNI / hardware buffer transfers
    val directBuffer: ByteBuffer = ByteBuffer.allocateDirect(totalPixels * 4).apply {
        order(ByteOrder.nativeOrder())
    }

    // Temporary pixel array for Bitmap.getPixels
    private val rawIntPixels: IntArray = IntArray(totalPixels)
    private var reusableRow: IntArray = IntArray(2560)
    val downsampledPixels: IntArray = IntArray(totalPixels)

    var hasPreviousFrame: Boolean = false
        private set

    /**
     * Ingests a frame bitmap and converts it into pure 8-bit grayscale luminance (0..255).
     * Uses integer ITU-R BT.601 coefficients: Y = (299*R + 587*G + 114*B) / 1000.
     */
    fun ingestBitmap(bitmap: Bitmap, activeWidth: Int = width, activeHeight: Int = height) {
        if (hasPreviousFrame) {
            // Copy current frame to previous frame buffer
            System.arraycopy(grayscaleBuffer, 0, prevGrayscaleBuffer, 0, totalPixels)
        }

        val effW = activeWidth.coerceIn(1, bitmap.width)
        val effH = activeHeight.coerceIn(1, bitmap.height)

        if (effW == width && effH == height && bitmap.width == width) {
            bitmap.getPixels(rawIntPixels, 0, width, 0, 0, width, height)
            for (i in 0 until totalPixels) {
                val pixel = rawIntPixels[i]
                downsampledPixels[i] = pixel
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val y = (299 * r + 587 * g + 114 * b) / 1000
                grayscaleBuffer[i] = y.toByte()
            }
        } else {
            // Downsample or stretch directly into the fixed resolution, strictly within active area
            val stepX = (effW.toFloat() / width)
            val stepY = (effH.toFloat() / height)

            if (reusableRow.size < effW) {
                reusableRow = IntArray(effW)
            }

            for (y in 0 until height) {
                val srcY = ((y * stepY).toInt()).coerceIn(0, effH - 1)
                bitmap.getPixels(reusableRow, 0, effW, 0, srcY, effW, 1)
                val rowOffset = y * width

                for (x in 0 until width) {
                    val srcX = ((x * stepX).toInt()).coerceIn(0, effW - 1)
                    val pixel = reusableRow[srcX]
                    downsampledPixels[rowOffset + x] = pixel
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val lum = (299 * r + 587 * g + 114 * b) / 1000
                    grayscaleBuffer[rowOffset + x] = lum.toByte()
                }
            }
        }

        if (!hasPreviousFrame) {
            System.arraycopy(grayscaleBuffer, 0, prevGrayscaleBuffer, 0, totalPixels)
            hasPreviousFrame = true
        }
    }

    /**
     * Direct ingestion from a raw luminance byte array (e.g. YUV420 Y-plane or R8_UNORM)
     */
    fun ingestGrayscaleBytes(data: ByteArray, sourceOffset: Int = 0) {
        if (hasPreviousFrame) {
            System.arraycopy(grayscaleBuffer, 0, prevGrayscaleBuffer, 0, totalPixels)
        }
        val copyLen = minOf(totalPixels, data.size - sourceOffset)
        System.arraycopy(data, sourceOffset, grayscaleBuffer, 0, copyLen)

        if (!hasPreviousFrame) {
            System.arraycopy(grayscaleBuffer, 0, prevGrayscaleBuffer, 0, totalPixels)
            hasPreviousFrame = true
        }
    }

    /**
     * Resets frame history (e.g. on match start or death).
     */
    fun reset() {
        hasPreviousFrame = false
        grayscaleBuffer.fill(0)
        prevGrayscaleBuffer.fill(0)
        alignedGrayscaleBuffer.fill(0)
        motionDiffBuffer.fill(0)
    }

    inline fun getLuminance(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        return grayscaleBuffer[y * width + x].toInt() and 0xFF
    }

    inline fun getPrevLuminance(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        return prevGrayscaleBuffer[y * width + x].toInt() and 0xFF
    }
}
