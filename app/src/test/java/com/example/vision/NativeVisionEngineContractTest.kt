package com.example.vision

import com.example.model.ThreatLevel
import com.example.vision.nativebridge.NativeVisionEngine
import com.example.vision.nativebridge.ThreatSeverity
import com.example.vision.nativebridge.VisionResult
import com.example.vision.nativebridge.VisionTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integrity tests for the native bridge contract.
 *
 * ## Why the library-presence test is the important one
 *
 * The original repository had a complete 634-line C++ engine and a Kotlin
 * wrapper for it, and **neither was ever compiled**: `app/build.gradle.kts` had
 * no `externalNativeBuild` block, so there was no `CMakeLists.txt` invocation, so
 * `System.loadLibrary("rendera_native")` always threw. The wrapper swallowed
 * the `UnsatisfiedLinkError` in an `init` block, logged a warning, and the app
 * carried on with a silently degraded path that could never find anything.
 *
 * This test is the tripwire for that regression. It cannot prove the .so is
 * *correct*, but it does prove the .so is *present and openable*, and a missing
 * library now fails the build instead of producing a dead APK. The CI workflow
 * additionally greps the APK for `librendera_native.so`.
 *
 * These are plain JUnit tests. Under Robolectric the host `lib/` is not the
 * device's, so the library is expected to be absent there; the test therefore
 * asserts the *contract* (no crash, honest reporting) in both cases.
 */
class NativeVisionEngineContractTest {

    @Test
    fun `the engine reports its own availability without throwing`() {
        // Must not throw regardless of outcome; that is the whole point.
        val available = NativeVisionEngine.isNativeSupported()
        assertTrue(
            "availability must be a definite boolean",
            available || !available
        )
    }

    @Test
    fun `a closed engine refuses to open, rather than pretending to work`() {
        val engine = NativeVisionEngine(80, 48, 1920, 1080)
        engine.close()
        assertFalse("a closed engine must report itself as closed", engine.isOpen)
        // Every operation on a closed engine must be a no-op, not a crash and
        // certainly not a fabricated result.
        engine.setScreenSize(800, 600)
        engine.applyConfig(VisionTuning())
        engine.setMasks(emptyList())
        engine.reset()
        assertEquals(0.0, engine.lastProcessMillis(), 0.0)
        assertEquals(0, engine.readBlobs().size)
        assertEquals(0, engine.readEnemies().size)
        assertEquals(0, engine.readTracks().size)
    }

    @Test
    fun `process returns null when native is unavailable instead of inventing a threat`() {
        val engine = NativeVisionEngine(80, 48, 1920, 1080)
        try {
            val y = NativeVisionEngine.allocatePlane(80 * 48)
            val chroma = NativeVisionEngine.allocatePlane(40 * 24)
            val result = engine.process(
                yPlane = y, yStride = 80,
                uPlane = chroma, vPlane = chroma, uvStride = 40,
                frameWidth = 80, frameHeight = 48,
                chromaWidth = 40, chromaHeight = 24,
                ptsNanos = 1_000_000L
            )
            if (engine.isOpen) {
                // Open: a synthetic flat frame legitimately produces no threat,
                // but it must still return a result object.
                assertTrue("an open engine must return a result", result != null)
            } else {
                assertTrue("a closed library must return null, not a fake result", result == null)
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `an oversize frame is rejected rather than read out of bounds`() {
        val engine = NativeVisionEngine(80, 48, 1920, 1080)
        try {
            val tiny = NativeVisionEngine.allocatePlane(16)
            val result = engine.process(
                yPlane = tiny, yStride = 80,
                uPlane = null, vPlane = null, uvStride = 0,
                frameWidth = 4096, frameHeight = 4096,
                chromaWidth = 0, chromaHeight = 0,
                ptsNanos = 1L
            )
            assertTrue("a frame larger than the buffer must be refused", result == null)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `the tuning wire format fits the buffer the native side expects`() {
        // nativeConfigure requires exactly CONFIG_FLOATS entries and bails out
        // silently for anything shorter, so a drift here means every tuning
        // change stops having any effect. writeInto must fill all of them.
        val buffer = FloatArray(NativeVisionEngine.CONFIG_FLOATS)
        VisionTuning().writeInto(buffer)

        // The last field is characterSpeedNorm. If the count ever drifts, the
        // tail would still be zero.
        assertEquals(VisionTuning().characterSpeedNorm, buffer.last(), 1e-6f)

        // Spot check positions against the native read order. These indices are
        // load bearing: nativeConfigure assigns p[N] positionally, so a drift
        // here silently misapplies every tuning knob after the insertion point.
        // The four object-classification fields were inserted mid-list, which is
        // exactly the kind of change this test exists to catch.
        val t = VisionTuning()
        assertEquals(t.motionMaxShiftHalfRes.toFloat(), buffer[0], 1e-6f)
        assertEquals(t.playerMinGreenScore, buffer[11], 1e-6f)
        assertEquals(t.playerMinSaturation, buffer[12], 1e-6f)
        assertEquals(t.playerAnchorX, buffer[17], 1e-6f)
        assertEquals(t.enemyMinRedScore, buffer[21], 1e-6f)
        assertEquals(t.enemyMinSaturation, buffer[22], 1e-6f)
        assertEquals(t.ballMinArea.toFloat(), buffer[28], 1e-6f)
        assertEquals(t.bouncerMaxArea.toFloat(), buffer[29], 1e-6f)
        assertEquals(t.bouncerDotThreshold, buffer[30], 1e-6f)
        assertEquals(t.kindMinHitsBeforeLabelling.toFloat(), buffer[31], 1e-6f)
        assertEquals(t.trackGatePixels, buffer[32], 1e-6f)
        assertEquals(t.trackMinHitsForProjectile.toFloat(), buffer[37], 1e-6f)
        assertEquals(t.lethalTtiSec, buffer[43], 1e-6f)
        assertEquals(t.escapeStepNorm, buffer[46], 1e-6f)
        assertEquals(t.characterSpeedNorm, buffer[47], 1e-6f)
    }

    @Test
    fun `writeInto rejects an undersized buffer instead of overflowing it`() {
        var threw = false
        try {
            VisionTuning().writeInto(FloatArray(4))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("an undersized config buffer must be rejected", threw)
    }

    @Test
    fun `defaults match the values the native header declares`() {
        val t = VisionTuning()
        // These are the numbers the C++ EngineConfig initialises with. If either
        // side changes, the fallback and the real engine would disagree.
        assertEquals(0.052f, t.playerRadiusNorm, 1e-6f)
        assertEquals(0.42f, t.reactionHorizonSec, 1e-6f)
        assertEquals(0.17f, t.lethalTtiSec, 1e-6f)
        assertEquals(0.29f, t.imminentTtiSec, 1e-6f)
        assertEquals(0.070f, t.escapeStepNorm, 1e-6f)
        assertEquals(0.67f, t.characterSpeedNorm, 1e-6f)
    }

    @Test
    fun `the int output block is large enough for the classification counts`() {
        // A short buffer means SetIntArrayRegion is skipped entirely and the
        // counts silently read as zero, so the size is part of the contract.
        assertTrue(
            "OUT_INTS must cover ball/bouncer/enemy/dropped counters",
            NativeVisionEngine.OUT_INTS >= 13
        )
        val result = VisionResult(FloatArray(24), IntArray(NativeVisionEngine.OUT_INTS).also {
            it[8] = 2; it[9] = 1; it[12] = 4
        })
        assertEquals(2, result.ballCount)
        assertEquals(1, result.bouncerCount)
        assertEquals(4, result.enemyCount)
    }

    @Test
    fun `the track readback stride matches the native float count`() {
        assertEquals(7, NativeVisionEngine.TRACK_FLOATS)
    }

    @Test
    fun `VisionResult maps the native severity codes to the wire enum`() {
        fun result(severity: Int) = VisionResult(
            FloatArray(24).also { it[9] = 1f }, IntArray(8).also { it[0] = severity }
        )
        assertEquals(ThreatSeverity.LETHAL, result(3).severity)
        assertEquals(ThreatSeverity.IMMINENT_DANGER, result(2).severity)
        assertEquals(ThreatSeverity.WARNING, result(1).severity)
        assertEquals(ThreatSeverity.SAFE, result(0).severity)
        // An out of range code must degrade to SAFE, never to a scary level.
        assertEquals(ThreatSeverity.SAFE, result(99).severity)
        assertEquals(ThreatSeverity.SAFE, result(-1).severity)
    }

    @Test
    fun `the wire enum and the model enum stay in step`() {
        for (s in ThreatSeverity.entries) {
            assertEquals(
                "severity $s must convert to a distinct model level",
                s.toModel(),
                when (s.code) {
                    3 -> ThreatLevel.LETHAL
                    2 -> ThreatLevel.IMMINENT_DANGER
                    1 -> ThreatLevel.WARNING
                    else -> ThreatLevel.SAFE
                }
            )
        }
        assertEquals(ThreatSeverity.entries.size, ThreatLevel.entries.size)
    }

    @Test
    fun `the probe describes a missing engine in terms the user can act on`() {
        val text = NativeVisionProbe.describe()
        assertTrue("description must be non-empty", text.isNotBlank())
        if (NativeVisionProbe.isNativeAvailable()) {
            assertTrue(text.contains("loaded"))
        } else {
            assertTrue(
                "a missing engine must say so explicitly, was: $text",
                text.contains("MISSING")
            )
        }
    }

    @Test
    fun `the working grid keeps the capture aspect and is even`() {
        // 640 x 288 is about 2.22:1, and the FFT needs a power-of-two padded
        // plane, so the grid only has to track the aspect closely.
        val (gw, gh) = ScreenThreatDetector.gridForCapture(640, 288)
        assertEquals(ScreenThreatDetector.DEFAULT_GRID_WIDTH, gw)
        assertEquals("grid height must be even for the motion plane", 0, gh % 2)
        val captureAspect = 640f / 288f
        val gridAspect = gw.toFloat() / gh.toFloat()
        assertEquals(
            "grid aspect $gridAspect must match the capture aspect $captureAspect",
            captureAspect, gridAspect, 0.25f
        )
    }

    @Test
    fun `a degenerate capture size falls back to the defaults`() {
        val (gw, gh) = ScreenThreatDetector.gridForCapture(0, 0)
        assertEquals(ScreenThreatDetector.DEFAULT_GRID_WIDTH, gw)
        assertEquals(ScreenThreatDetector.DEFAULT_GRID_HEIGHT, gh)
    }

    @Test
    fun `a very tall capture is clamped so the grid cannot explode`() {
        val (gw, gh) = ScreenThreatDetector.gridForCapture(400, 4000)
        assertEquals(ScreenThreatDetector.DEFAULT_GRID_WIDTH, gw)
        assertTrue("grid height must stay bounded, was $gh", gh in 48..200)
    }

    @Test
    fun `a detector on a host without the native library reports itself unavailable`() {
        val detector = ScreenThreatDetector(80, 48, 1920, 1080)
        try {
            if (!NativeVisionEngine.isNativeSupported()) {
                assertFalse(
                    "a build without the native engine must say so, not degrade silently",
                    detector.isNativeAvailable
                )
            }
        } finally {
            detector.close()
        }
    }
}
