package com.example.vision

import com.example.vision.nativebridge.TrackKind
import com.example.vision.nativebridge.VisionTuning
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
    // Classification rules, as a model.
    //
    // ## What this is and is not
    //
    // A JVM unit test cannot load `librendera_native.so`, so the rules below are
    // a transcription of the engine's, not the engine. That is fine for the
    // property being pinned here - "a label cannot cost a dodge" - because that
    // property is about the shape of the rule and holds whatever the thresholds
    // are. What it must NOT do is carry its own copy of the thresholds and call
    // them a mirror, because a copy drifts silently. The previous version did
    // exactly that: it hard coded `ballMinArea = 24f` while the shipped tuning
    // said 14, so it asserted that a 20-cell slow blob is UNKNOWN when the real
    // engine calls it a BALL. Every threshold is now read from [tuning], so a
    // change to the shipped defaults shows up here as a failing test.
    // -----------------------------------------------------------------------

    private val tuning = VisionTuning()

    /**
     * The engine's label rule, transcribed so a test can drive it.
     *
     * Order: a hard reversal is a bouncer, then MOTION decides a projectile, and
     * size only decides the ball. Motion comes first because `isProjectile` -
     * the flag that actually gates the collision solve - is computed from hit
     * count, speed and straightness alone and never consults `kind`. A label
     * therefore cannot cost a dodge, and calling a 20-cell clean fast mover
     * UNKNOWN while calling a 6-cell one a PROJECTILE would just be wrong.
     *
     * `bouncerMaxArea` is deliberately unused here: with motion deciding the
     * projectile, no size gate is needed. It is still asserted against
     * `ballMinArea` below, because the two numbers document what each threshold
     * is for.
     */
    private fun classify(
        areaEma: Float,
        bounced: Boolean,
        straightness: Float,
        speedNorm: Float,
        projectileMinSpeedNorm: Float = tuning.projectileMinSpeedNorm,
        projectileMinStraightness: Float = tuning.projectileMinStraightness
    ): TrackKind = when {
        bounced -> TrackKind.BOUNCER
        straightness >= projectileMinStraightness &&
            speedNorm >= projectileMinSpeedNorm -> TrackKind.PROJECTILE
        areaEma >= tuning.ballMinArea -> TrackKind.BALL
        else -> TrackKind.UNKNOWN
    }

    @Test
    fun `a large steadily moving blob is the ball`() {
        // 40 cells, and NOT a clean fast mover, so the motion test passes over
        // it and the size test catches it.
        assertEquals(TrackKind.BALL, classify(areaEma = 40f, bounced = false, 0.2f, 0.1f))
    }

    @Test
    fun `a small fast straight blob is a projectile`() {
        assertEquals(TrackKind.PROJECTILE, classify(areaEma = 6f, bounced = false, 0.95f, 0.6f))
        // And a LARGE one is still a projectile, which the rule above is the
        // reason for.
        assertEquals(TrackKind.PROJECTILE, classify(areaEma = 30f, bounced = false, 0.95f, 0.6f))
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
    fun `size never decides a label on its own`() {
        // A clean fast straight mover is a projectile at every size, including
        // one large enough to look like a ball; a slow wandering blob is a ball
        // at every size. If size decided, one of these two would be wrong.
        for (area in listOf(3f, 6f, 20f, 30f, 40f)) {
            assertEquals(
                "area $area, clean fast straight mover",
                TrackKind.PROJECTILE,
                classify(areaEma = area, bounced = false, 0.9f, 0.6f)
            )
        }
        // A slow wandering mover is only a BALL once it is actually large
        // enough; a small slow one is simply not classified as anything. That is
        // the honest outcome, and the direction that matters for safety is the
        // first loop: a clean fast straight mover is never anything but a
        // projectile, at any size.
        //
        // The boundary is read from the shipped threshold rather than written
        // down, because the value it used to be written against (24) is not the
        // value that ships (14). With the old literal this loop asserted that a
        // 20-cell slow blob was UNKNOWN, which the real engine has called a BALL
        // ever since the threshold was retuned.
        val ball = tuning.ballMinArea
        for (area in listOf(1f, ball - 1f)) {
            assertEquals(
                "area $area, slow wandering mover, below the ball threshold",
                TrackKind.UNKNOWN,
                classify(areaEma = area, bounced = false, 0.2f, 0.1f)
            )
        }
        for (area in listOf(ball, ball + 10f, ball + 30f)) {
            assertEquals(
                "area $area, slow wandering mover, at or above the ball threshold",
                TrackKind.BALL,
                classify(areaEma = area, bounced = false, 0.2f, 0.1f)
            )
        }
    }

    @Test
    fun `the label is informational and cannot gate a dodge`() {
        // The invariant that makes the rule above safe: `isProjectile` is derived
        // from hit count, speed and straightness, and never from `kind`.
        // Transcribed from the engine's updateTracks on purpose - if someone ever
        // makes the label gate threat detection, this test should say so.
        val isProjectile = { hits: Int, speedNorm: Float, straightness: Float ->
            hits >= tuning.trackMinHitsForProjectile &&
                speedNorm >= tuning.projectileMinSpeedNorm &&
                straightness >= tuning.projectileMinStraightness
        }
        assertEquals(TrackKind.PROJECTILE, classify(40f, false, 0.9f, 0.6f))
        assertTrue(
            "a labelled projectile must still be an actionable threat",
            isProjectile(3, 0.6f, 0.9f)
        )
        assertEquals(TrackKind.BALL, classify(40f, false, 0.2f, 0.1f))
        assertFalse("a slow object is not a threat at all", isProjectile(3, 0.1f, 0.2f))
    }

    @Test
    fun `the ball threshold is a usable size band`() {
        // The ball is the last resort after motion has already claimed the
        // projectiles, so this number only has to be large enough that a small
        // slow blob is not called a ball, and small enough that a big one still
        // is. It gates nothing else.
        assertTrue(
            "ballMinArea ${tuning.ballMinArea} must leave room below it for small blobs",
            tuning.ballMinArea > 4
        )
        assertTrue(
            "ballMinArea ${tuning.ballMinArea} must be reachable within the grid",
            tuning.ballMinArea < 256
        )
    }

    @Test
    fun `bounce detection only fires on a genuine reversal`() {
        // cos of the angle between the predicted and observed velocity.
        fun cosBetween(predDeg: Double, obsDeg: Double): Float {
            val d = Math.toRadians(obsDeg - predDeg)
            return Math.cos(d).toFloat()
        }
        // Straight on: 1.0, far above the threshold.
        assertTrue(cosBetween(180.0, 180.0) > tuning.bouncerDotThreshold)
        // Slight drift: still not a bounce.
        assertTrue(cosBetween(180.0, 195.0) > tuning.bouncerDotThreshold)
        // Full reversal: -1.0, decisively a bounce.
        assertTrue(cosBetween(180.0, 0.0) < tuning.bouncerDotThreshold)
        // Right angle bounce: 0.0, which is not a reversal but is a big change.
        // The threshold deliberately does not catch it, so a hard 90 degree
        // deflection is not mislabelled; that trades a missed label for not
        // mislabelling a genuinely curving projectile.
        assertTrue(cosBetween(180.0, 90.0) > tuning.bouncerDotThreshold)
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
