package com.example.vision

import com.example.vision.nativebridge.NativeRenderaEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bridge must never fabricate results. When the native library is absent the
 * engine reports itself unavailable and the caller falls back to the Kotlin
 * pipeline; there is no path that invents a threat.
 *
 * This replaces the previous test, which used `AndroidJUnit4` inside the JVM
 * `src/test` source set (no device) and so could never have run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ScreenThreatDetectorSmokeTest {

    @Test
    fun `detector reports which backend it will use`() {
        val d = ScreenThreatDetector()
        // Whichever backend is chosen, exactly one of them must be active and the
        // reported name must match it.
        if (d.isNativeActive) {
            assertTrue("native backend", d.backendName == "C++/NDK")
        } else {
            assertTrue("kotlin fallback", d.backendName == "Kotlin")
        }
    }

    @Test
    fun `no frames processed means no native proof claimed`() {
        val d = ScreenThreatDetector()
        if (!d.isNativeActive) {
            assertTrue("Kotlin fallback must not claim native frames", d.nativeProofFrames == 0)
        }
    }

    @Test
    fun `native engine is not available as a raw handle when unloaded`() {
        if (!NativeRenderaEngine.isAvailable) {
            assertTrue(
                "create() must return null when the .so is missing",
                NativeRenderaEngine.create(240, 114) == null
            )
        }
    }
}
