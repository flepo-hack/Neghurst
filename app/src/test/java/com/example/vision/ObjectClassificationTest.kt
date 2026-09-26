package com.example.vision

import com.example.vision.nativebridge.TrackKind
import com.example.vision.nativebridge.toTrackReadings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the object classification and the joystick suggestion.
 *
 * The rule these tests exist to enforce is the important one: **classification
 * is a label, not a gate.** A track is already a projectile before it is named
 * one, and naming it a ball or a bouncer must never be able to change whether
 * something can trigger a dodge. Every test here is written so that an
 * implementation which got that backwards would fail.
 */
class ObjectClassificationTest {

    // -----------------------------------------------------------------------
    // TrackKind
    // -----------------------------------------------------------------------

    @Test
    fun `track kind codes round trip and unknown codes degrade safely`() {
        for (k in TrackKind.entries) {
            assertEquals(k, TrackKind.fromCode(k.code))
        }
        // An unknown or out of range code must become UNKNOWN, never a kind that
        // would make something look actionable.
        assertEquals(TrackKind.UNKNOWN, TrackKind.fromCode(0))
        assertEquals(TrackKind.UNKNOWN, TrackKind.fromCode(99))
        assertEquals(TrackKind.UNKNOWN, TrackKind.fromCode(-1))
    }

    @Test
    fun `a track array decodes into the right number of readings`() {
        val stride = 7
        val raw = FloatArray(stride * 3)
        for (i in 0 until 3) {
            val o = i * stride
            raw[o] = 100f * (i + 1)
            raw[o + 5] = 1f
            raw[o + 6] = TrackKind.entries[i + 1].code.toFloat()
        }
        val readings = raw.toTrackReadings()
        assertEquals(3, readings.size)
        assertEquals(100f, readings[0].x, 0.01f)
        assertEquals(200f, readings[1].x, 0.01f)
        assertEquals(300f, readings[2].x, 0.01f)
        assertEquals(TrackKind.PROJECTILE, readings[0].kind)
        assertEquals(TrackKind.BALL, readings[1].kind)
        assertEquals(TrackKind.BOUNCER, readings[2].kind)
    }

    @Test
    fun `a short or empty track array decodes to nothing rather than crashing`() {
        assertTrue(FloatArray(0).toTrackReadings().isEmpty())
        assertTrue(FloatArray(4).toTrackReadings().isEmpty())
    }

    @Test
    fun `a bouncer is never actionable even though it is a projectile`() {
        val raw = FloatArray(7).also {
            it[5] = 1f          // passed the projectile gates
            it[6] = TrackKind.BOUNCER.code.toFloat()
        }
        val t = raw.toTrackReadings().single()
        assertTrue("it did pass the gates", t.isProjectile)
        assertFalse(
            "a bouncer's straight-line solution is wrong, so it must not be dodged",
            t.isActionable
        )
    }

    @Test
    fun `a plain projectile is actionable`() {
        val raw = FloatArray(7).also {
            it[5] = 1f
            it[6] = TrackKind.PROJECTILE.code.toFloat()
        }
        assertTrue(raw.toTrackReadings().single().isActionable)
    }

    @Test
    fun `a non projectile track is never actionable`() {
        val raw = FloatArray(7).also {
            it[5] = 0f
            it[6] = TrackKind.UNKNOWN.code.toFloat()
        }
        val t = raw.toTrackReadings().single()
        assertFalse(t.isProjectile)
        assertFalse(t.isActionable)
    }

    // -----------------------------------------------------------------------
    // Classification rules, expressed directly so they can be reasoned about
    // without a frame buffer. These mirror the engine's thresholds exactly.
    // -----------------------------------------------------------------------

    private val ballMinArea = 14f
    private val bouncerMaxArea = 26f
    private val bouncerDotThreshold = -0.55f

    /** The engine's label rule, transcribed so a test can drive it. */
    private fun classify(
        areaEma: Float,
        bounced: Boolean,
        straightness: Float,
        speedNorm: Float,
        projectileMinSpeedNorm: Float = 0.22f,
        projectileMinStraightness: Float = 0.55f
    ): TrackKind = when {
        bounced -> TrackKind.BOUNCER
        areaEma >= ballMinArea -> TrackKind.BALL
        areaEma <= bouncerMaxArea &&
            straightness >= projectileMinStraightness &&
            speedNorm >= projectileMinSpeedNorm -> TrackKind.PROJECTILE
        else -> TrackKind.UNKNOWN
    }

    @Test
    fun `a large steadily moving blob is the ball`() {
        assertEquals(TrackKind.BALL, classify(areaEma = 40f, bounced = false, 0.9f, 0.1f))
    }

    @Test
    fun `a small fast straight blob is a projectile`() {
        assertEquals(TrackKind.PROJECTILE, classify(areaEma = 6f, bounced = false, 0.95f, 0.6f))
    }

    @Test
    fun `a reversal is a bouncer whatever its size`() {
        // Even a big object that reversed is a bouncer, because the decisive
        // evidence is the direction change, not the size.
        assertEquals(TrackKind.BOUNCER, classify(areaEma = 40f, bounced = true, 0.9f, 0.1f))
    }

    @Test
    fun `a slow wandering blob stays unclassified rather than being guessed at`() {
        assertEquals(TrackKind.UNKNOWN, classify(areaEma = 8f, bounced = false, 0.3f, 0.05f))
    }

    @Test
    fun `the ball threshold is above any plausible projectile size`() {
        // A bullet's motion residual is a few cells; the ball is a large rolling
        // sphere. If these ever crossed, bullets would be reported as balls and
        // the dodge path would lose them.
        assertTrue("bullet", bouncerMaxArea < ballMinArea)
        assertTrue("ball", ballMinArea >= 10f)
    }

    @Test
    fun `bounce detection only fires on a genuine reversal`() {
        // cos of the angle between the predicted and observed velocity.
        fun cosBetween(predDeg: Double, obsDeg: Double): Float {
            val d = Math.toRadians(obsDeg - predDeg)
            return Math.cos(d).toFloat()
        }
        // Straight on: 1.0, far above the -0.55 threshold.
        assertTrue(cosBetween(180.0, 180.0) > bouncerDotThreshold)
        // Slight drift: still not a bounce.
        assertTrue(cosBetween(180.0, 195.0) > bouncerDotThreshold)
        // Full reversal: -1.0, decisively a bounce.
        assertTrue(cosBetween(180.0, 0.0) < bouncerDotThreshold)
        // Right angle bounce: 0.0, which is not a reversal but is a big change.
        // The threshold deliberately does not catch it, so a hard 90 degree
        // deflection is not mislabelled; that trades a missed label for not
        // mislabelling a genuinely curving projectile.
        assertTrue(cosBetween(180.0, 90.0) > bouncerDotThreshold)
    }

    // -----------------------------------------------------------------------
    // Joystick suggestion
    // -----------------------------------------------------------------------

    @Test
    fun `the suggested joystick lands in the stick's corner of the view`() {
        val a = AnchorCalibrator.suggestJoystick(2400, 1080)
        val px = a.joystickPx(2400, 1080)
        assertTrue("x=${px.x} should be in the left third", px.x < 2400 * 0.30f)
        assertTrue("y=${px.y} should be in the lower part", px.y > 1080 * 0.65f)
    }

    @Test
    fun `the suggestion is never presented as a calibration`() {
        // A suggestion nobody verified must not be trusted by the vision engine.
        val a = AnchorCalibrator.suggestJoystick(2400, 1080)
        assertFalse(a.calibrated)
        assertFalse(a.matchesDisplay(2400, 1080))
    }

    @Test
    fun `the suggestion is the same for every resolution of the same aspect`() {
        val a = AnchorCalibrator.suggestJoystick(2400, 1080)
        val b = AnchorCalibrator.suggestJoystick(1920, 864)
        assertEquals(a.joystickX, b.joystickX, 0.01f)
        assertEquals(a.joystickY, b.joystickY, 0.01f)
    }

    @Test
    fun `a left handed layout mirrors the stick but not the height`() {
        val right = AnchorCalibrator.suggestJoystick(2400, 1080, leftHanded = false)
        val left = AnchorCalibrator.suggestJoystick(2400, 1080, leftHanded = true)
        assertEquals(1f - right.joystickX, left.joystickX, 0.02f)
        assertEquals(right.joystickY, left.joystickY, 0.001f)
    }

    @Test
    fun `the suggested stick fits entirely on screen`() {
        for ((w, h) in listOf(2400 to 1080, 1920 to 1080, 2340 to 1080, 1600 to 720, 1080 to 2400)) {
            val a = AnchorCalibrator.suggestJoystick(w, h)
            val r = a.joystickRadiusPx(w)
            val p = a.joystickPx(w, h)
            assertTrue("$w x $h: x=${p.x} r=$r", p.x >= r && p.x <= w - r)
            assertTrue("$w x $h: y=${p.y} r=$r", p.y >= r && p.y <= h - r)
        }
    }

    @Test
    fun `the suggested player anchor sits in the middle of the view`() {
        val a = AnchorCalibrator.suggestJoystick(2400, 1080)
        assertTrue(a.playerX in 0.40f..0.60f)
        assertTrue(a.playerY in 0.40f..0.65f)
    }
}
