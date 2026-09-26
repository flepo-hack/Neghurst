package com.example.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End to end contract for the detector facade.
 *
 * The pixel pipeline now lives in the compiled C++ engine, so there is no
 * pure-Kotlin frame analysis to unit test here any more. What *is* testable, and
 * what actually matters, is the facade's contract:
 *
 *  * it must never fabricate a threat, a player position or a dodge plan;
 *  * it must refuse to plan a dodge from uncalibrated anchors, because a gesture
 *    from the wrong stick position both fails and looks like it worked;
 *  * it must fall back to the *calibrated* anchor, never to a hard-coded
 *    fraction of the screen, when the player is momentarily undetected.
 *
 * That last point was the "player and joystick are in the wrong place" bug, so
 * it is pinned explicitly below.
 */
class ScreenThreatDetectorTest {

    @Test
    fun `grid sizing follows the capture aspect`() {
        val (gw, gh) = ScreenThreatDetector.gridForCapture(480, 216)
        assertEquals(160, gw)
        // 480 x 216 is 2.22:1, so the grid must land near 160 x 72.
        assertTrue("grid height was $gh, expected about 72", gh in 68..76)
    }

    @Test
    fun `the facade reports native availability instead of degrading silently`() {
        val detector = ScreenThreatDetector(80, 48, 1920, 1080)
        try {
            // On a JVM unit test host the device .so is not loadable, so the
            // only correct answer is "unavailable". What must never happen is a
            // detector that claims to be available while doing nothing.
            assertFalse(
                "a JVM host has no librendera_native.so, so it must report unavailable",
                detector.isNativeAvailable
            )
        } finally {
            detector.close()
        }
    }

    @Test
    fun `process returns null when the engine is unavailable rather than a fake result`() {
        val detector = ScreenThreatDetector(80, 48, 1920, 1080)
        try {
            if (detector.isNativeAvailable) return
            val y = com.example.vision.nativebridge.NativeVisionEngine.allocatePlane(80 * 48)
            val result = detector.process(
                yPlane = y, yStride = 80,
                uPlane = null, vPlane = null, uvStride = 0,
                frameWidth = 80, frameHeight = 48,
                chromaWidth = 0, chromaHeight = 0,
                ptsNanos = 1L,
                screenWidth = 1920, screenHeight = 1080
            )
            assertTrue("no result may be invented", result == null)
        } finally {
            detector.close()
        }
    }

    @Test
    fun `a null analysis produces an empty dodge plan, not a default direction`() {
        val detector = ScreenThreatDetector(80, 48, 2400, 1080)
        try {
            val plan = detector.planDodge(analysis = null, screenWidth = 2400, screenHeight = 1080)
            assertTrue("no analysis means no dodge", plan.isEmpty)
        } finally {
            detector.close()
        }
    }

    @Test
    fun `dodging is refused while the anchors are uncalibrated`() {
        val detector = ScreenThreatDetector(80, 48, 2400, 1080)
        try {
            val anchors = Anchors.defaultFor(2400, 1080)
            assertFalse("the default must not claim calibration", anchors.calibrated)
            detector.setAnchors(anchors)

            // Even with a perfectly formed threat, an uncalibrated stick position
            // must not produce a gesture: it would drag the wrong pixels.
            val result = com.example.vision.nativebridge.VisionResult(
                FloatArray(64).also {
                    it[9] = 1f          // playerVisible
                    it[12] = 0.15f      // tti
                    it[13] = 300f       // threatX
                    it[14] = 400f       // threatY
                    it[15] = -600f      // vx
                    it[16] = 0f         // vy
                    it[17] = 600f       // speed
                    it[18] = 1f         // confidence
                },
                IntArray(14).also { it[1] = 1 }  // threatValid
            )
            // `threat` MUST be non-null here: planDodge returns early when there
            // is no dodgeable threat, so a null threat made this test pass without
            // ever reaching the calibration guard it is supposed to cover.
            val solved = CollisionSolver.solve(
                    playerX = 240f, playerY = 500f,
                    playerRadiusPx = 125f,
                    projectiles = listOf(
                        CollisionSolver.Projectile(300f, 500f, -600f, 0f, 1f)
                    ),
                screenWidthPx = 2400f, screenHeightPx = 1080f
            )
            assertTrue("the fixture must actually produce a threat", solved.hasThreat)
            val analysis = ScreenThreatDetector.Analysis(
                raw = result,
                playerX = 240f,
                playerY = 500f,
                playerDetected = true,
                playerFromAnchor = false,
                threat = com.example.model.ThreatVector(
                    threatX = 300f, threatY = 500f,
                    velocityX = -600f, velocityY = 0f,
                    speed = 600f, threatAngleDeg = 180f,
                    dodgeAngleDeg = solved.escapeHeadingDeg,
                    dodgeDirX = solved.escapeDirX, dodgeDirY = solved.escapeDirY,
                    threatLevel = solved.severity,
                    timeToImpactMs = solved.timeToImpactMs,
                    confidence = 1f
                ),
                escape = solved,
                processMillis = 0.0,
                blobCount = 0,
                projectileCount = 1,
                enemyCount = 0
            )
            assertTrue(
                "fixture must be a dodgeable threat, or the guard below is untested",
                analysis.hasDodgeableThreat
            )
            val plan = detector.planDodge(analysis, 2400, 1080)
            assertTrue(
                "an uncalibrated joystick must never produce a dispatchable gesture",
                plan.isEmpty
            )

            // Same analysis, now calibrated: the plan must appear. If this failed
            // too, the test above would pass for the wrong reason.
            detector.setAnchors(
                AnchorCalibrator.applyTouch(
                    anchors = Anchors.defaultFor(2400, 1080),
                    target = AnchorTarget.JOYSTICK,
                    screenX = 400f, screenY = 820f,
                    displayWidth = 2400, displayHeight = 1080
                )
            )
            val calibratedPlan = detector.planDodge(analysis, 2400, 1080)
            assertFalse(
                "with calibrated anchors the same threat must produce a gesture",
                calibratedPlan.isEmpty
            )
            assertEquals(4, calibratedPlan.strokes.size)
        } finally {
            detector.close()
        }
    }

    @Test
    fun `an undetected player falls back to the calibrated anchor, not a hard-coded centre`() {
        val detector = ScreenThreatDetector(80, 48, 2400, 1080)
        try {
            // Deliberately calibrate far from the screen centre.
            val calibrated = AnchorCalibrator.applyTouch(
                anchors = Anchors.defaultFor(2400, 1080),
                target = AnchorTarget.PLAYER,
                screenX = 1800f, screenY = 300f,
                displayWidth = 2400, displayHeight = 1080
            )
            assertTrue(calibrated.calibrated)
            detector.setAnchors(calibrated)

            val undetected = com.example.vision.nativebridge.VisionResult(
                FloatArray(64),   // playerVisible == 0
                IntArray(14)
            )
            val (x, y, detected) = detector.resolvePlayerPosition(undetected, 2400, 1080)

            assertFalse("no detection this frame", detected)
            assertEquals(
                "the collider must come from the calibration",
                calibrated.playerX * 2400f, x, 1f
            )
            assertEquals(calibrated.playerY * 1080f, y, 1f)
            assertTrue(
                "must not have snapped to the screen centre",
                kotlin.math.abs(x - 1200f) > 300f
            )
        } finally {
            detector.close()
        }
    }

    @Test
    fun `a detected player wins over the anchor`() {
        val detector = ScreenThreatDetector(80, 48, 2400, 1080)
        try {
            detector.setAnchors(
                AnchorCalibrator.applyTouch(
                    anchors = Anchors.defaultFor(2400, 1080),
                    target = AnchorTarget.PLAYER,
                    screenX = 1800f, screenY = 300f,
                    displayWidth = 2400, displayHeight = 1080
                )
            )
            val detected = com.example.vision.nativebridge.VisionResult(
                FloatArray(64).also {
                    it[9] = 1f   // playerVisible
                    it[4] = 640f  // playerX
                    it[5] = 700f  // playerY
                },
                IntArray(14)
            )
            val (x, y, isDetected) = detector.resolvePlayerPosition(detected, 2400, 1080)
            assertTrue(isDetected)
            assertEquals(640f, x, 0.01f)
            assertEquals(700f, y, 0.01f)
        } finally {
            detector.close()
        }
    }
}
