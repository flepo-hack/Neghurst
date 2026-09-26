package com.example.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the coordinate mapping.
 *
 * The "player and joystick are in the wrong place" bug was fundamentally a
 * units problem: anchors were stored as raw pixels in a flat key, and the
 * detector silently replaced them with hard-coded fractions of the screen once
 * detection lapsed. These tests pin normalisation, display-geometry validity and
 * orientation handling, so that class of bug cannot come back quietly.
 */
class AnchorGeometryTest {

    private val landscapeW = 2400
    private val landscapeH = 1080

    @Test
    fun `a calibration round trips through normalised storage`() {
        val defaults = Anchors.defaultFor(landscapeW, landscapeH)
        val recorded = AnchorCalibrator.applyTouch(
            anchors = defaults,
            target = AnchorTarget.JOYSTICK,
            screenX = 600f,
            screenY = 800f,
            displayWidth = landscapeW,
            displayHeight = landscapeH
        )
        val restored = AnchorCalibrator.applyTouch(
            anchors = recorded.copy(calibrated = false),
            target = AnchorTarget.JOYSTICK,
            screenX = 600f,
            screenY = 800f,
            displayWidth = landscapeW,
            displayHeight = landscapeH
        )
        val px = restored.joystickPx(landscapeW, landscapeH)
        assertEquals(600f, px.x, 0.01f)
        assertEquals(800f, px.y, 0.01f)
    }

    @Test
    fun `anchors are stored as fractions so they survive a resolution change`() {
        val recorded = AnchorCalibrator.applyTouch(
            anchors = Anchors.defaultFor(landscapeW, landscapeH),
            target = AnchorTarget.PLAYER,
            screenX = 1200f,
            screenY = 540f,
            displayWidth = landscapeW,
            displayHeight = landscapeH
        )
        assertEquals(0.5f, recorded.playerX, 1e-3f)
        assertEquals(0.5f, recorded.playerY, 1e-3f)
    }

    @Test
    fun `an anchor taken in landscape is not reused in portrait`() {
        val landscape = AnchorCalibrator.applyTouch(
            anchors = Anchors.defaultFor(landscapeW, landscapeH),
            target = AnchorTarget.JOYSTICK,
            screenX = 400f, screenY = 820f,
            displayWidth = landscapeW, displayHeight = landscapeH
        )
        assertTrue(landscape.calibrated)
        assertTrue(landscape.matchesDisplay(landscapeW, landscapeH))

        val portrait = AnchorCalibrator.adaptToDisplay(landscape, 1080, 2400)
        assertFalse(
            "rotating must invalidate the calibration, not reinterpret it",
            portrait.calibrated
        )
        assertNotEquals(landscape.joystickX, portrait.joystickX)
    }

    @Test
    fun `a calibration for the same geometry is returned untouched`() {
        val recorded = AnchorCalibrator.applyTouch(
            anchors = Anchors.defaultFor(landscapeW, landscapeH),
            target = AnchorTarget.JOYSTICK,
            screenX = 400f, screenY = 820f,
            displayWidth = landscapeW, displayHeight = landscapeH
        )
        assertEquals(recorded, AnchorCalibrator.adaptToDisplay(recorded, landscapeW, landscapeH))
    }

    @Test
    fun `an uncalibrated default never claims to be calibrated`() {
        val defaults = Anchors.defaultFor(landscapeW, landscapeH)
        assertFalse(defaults.calibrated)
        assertFalse(defaults.matchesDisplay(landscapeW, landscapeH))
    }

    @Test
    fun `an anchor is kept far enough inside the screen to be usable`() {
        val recorded = AnchorCalibrator.applyTouch(
            anchors = Anchors.defaultFor(landscapeW, landscapeH),
            target = AnchorTarget.JOYSTICK,
            screenX = 0f, screenY = 0f,
            displayWidth = landscapeW, displayHeight = landscapeH
        )
        val r = recorded.joystickRadiusPx(landscapeW)
        val px = recorded.joystickPx(landscapeW, landscapeH)
        assertTrue("x=${px.x} must be at least one radius in", px.x >= r)
        assertTrue("y=${px.y} must be at least one radius in", px.y >= r)
    }

    @Test
    fun `a nonsensical display size is rejected instead of producing NaN anchors`() {
        val recorded = AnchorCalibrator.applyTouch(
            anchors = Anchors.defaultFor(landscapeW, landscapeH),
            target = AnchorTarget.PLAYER,
            screenX = 100f, screenY = 100f,
            displayWidth = 0, displayHeight = 0
        )
        assertTrue(recorded.playerX.isFinite())
        assertTrue(recorded.playerY.isFinite())
    }

    @Test
    fun `orientation is derived from the rotated display size`() {
        assertEquals(ScreenOrientation.LANDSCAPE, ScreenOrientation.of(2400, 1080))
        assertEquals(ScreenOrientation.PORTRAIT, ScreenOrientation.of(1080, 2400))
    }

    @Test
    fun `defaults are sane for both orientations and always uncalibrated`() {
        val landscape = Anchors.defaultFor(2400, 1080)
        val portrait = Anchors.defaultFor(1080, 2400)

        assertFalse(landscape.calibrated)
        assertFalse(portrait.calibrated)

        // The stick belongs in a lower region, the brawler near the middle.
        assertTrue(landscape.joystickY > 0.6f)
        assertTrue(portrait.joystickY > 0.6f)
        assertTrue(landscape.playerX in 0.35f..0.65f)
        assertTrue(portrait.playerX in 0.35f..0.65f)
    }

    @Test
    fun `the joystick radius is a fraction of width so it is resolution independent`() {
        val recorded = AnchorCalibrator.applyTouch(
            anchors = Anchors.defaultFor(landscapeW, landscapeH),
            target = AnchorTarget.JOYSTICK,
            screenX = 400f, screenY = 800f,
            displayWidth = landscapeW, displayHeight = landscapeH
        )
        assertTrue(
            "radius norm ${recorded.joystickRadiusNorm} must be a sane fraction",
            recorded.joystickRadiusNorm in 0.02f..0.45f
        )
        // The radius derives from the display's short edge, so it lands on the
        // same normalised value on any display of the same aspect class.
        val same = AnchorCalibrator.applyTouch(
            anchors = Anchors.defaultFor(1920, 864),
            target = AnchorTarget.JOYSTICK,
            screenX = 320f, screenY = 640f,
            displayWidth = 1920, displayHeight = 864
        )
        assertEquals(recorded.joystickRadiusNorm, same.joystickRadiusNorm, 0.01f)
    }

    @Test
    fun `describe reports the state so the UI cannot imply a lock that does not exist`() {
        val defaults = Anchors.defaultFor(landscapeW, landscapeH)
        assertTrue(AnchorCalibrator.describe(defaults).contains("DEFAULT"))

        val recorded = AnchorCalibrator.applyTouch(
            anchors = defaults, target = AnchorTarget.JOYSTICK,
            screenX = 408f, screenY = 842f,
            displayWidth = landscapeW, displayHeight = landscapeH
        )
        val text = AnchorCalibrator.describe(recorded)
        assertTrue(text.contains("LOCKED"))
        assertTrue("expected the joystick percentage in: $text", text.contains("Joy 17%"))
    }
}
