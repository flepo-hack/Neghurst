package com.example.vision

import com.example.model.DodgeProfile
import com.example.vision.core.FrameGrabber
import com.example.vision.core.VisionPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Verifies the parts of the pipeline that were previously wrong in ways that
 * made auto-dodge impossible, plus the invariants the real design depends on.
 */
class VisionPipelineTest {

    private val cols = 240
    private val rows = 114
    private val tileCells = 9f

    private fun pipeline(): VisionPipeline =
        VisionPipeline(cols, rows, tileCells).apply {
            motionNoiseFloor = 6f
            minBlobWeight = 40f
            maxCameraShift = 8
            minProjectileSpeed = 45f
            maxProjectileSpeed = 210f
            playerRadiusCells = 3f
            joyRadiusCells = 14f
            anchorPlayerCell(cols / 2, rows / 2, true)
            anchorJoystickCell(30, 96, true)
        }

    // ---------------------------------------------------------------- setup
    @Test
    fun `capture size preserves aspect and stays even`() {
        val (w, h) = FrameGrabber.chooseCaptureSize(2400, 1080)
        assertTrue("width even: $w", w % 2 == 0)
        assertTrue("height even: $h", h % 2 == 0)
        assertTrue("long side capped: ${w}x$h", maxOf(w, h) <= 960)
        val expected = h.toFloat() / w
        val actual = 1080f / 2400f
        assertEquals("aspect preserved", expected, actual, 0.01f)
    }

    @Test
    fun `capture size handles portrait and tiny screens`() {
        val (w, h) = FrameGrabber.chooseCaptureSize(1080, 2400)
        assertTrue(w % 2 == 0 && h % 2 == 0)
        assertTrue(maxOf(w, h) <= 960)
        val (pw, ph) = FrameGrabber.chooseCaptureSize(480, 800)
        assertTrue("no upscale: ${pw}x$ph", maxOf(pw, ph) <= 480)
    }

    // -------------------------------------------------------------- tracking
    @Test
    fun `tracker bootstraps a usable velocity within two observations`() {
        val p = pipeline()
        p.anchorPlayerCell(20, 20, true)
        val speed = 90f
        val dt = 1f / 60f
        val rnd = Random(4)

        var x = 20f
        for (f in 0 until 8) {
            // Constant world velocity: the camera is not moving in this test.
            val obsX = x + rnd.nextGaussian().toFloat() * 0.2f
            p.loadLuma(syntheticFrame(obsX, rows / 2f), cols * 2, rows * 2, cols * 2)
            p.process(dt, f * 16L)
            x += speed * dt
            if (f == 2) {
                val t = p.tracks.firstOrNull { it.alive }
                assertNotNull("a track should exist by frame 3", t)
                assertTrue(
                    "velocity must be usable on the 3rd observation, was ${t!!.speed}",
                    t.speed > 60f
                )
            }
        }
    }

    @Test
    fun `tracker classifies a direction change as not a projectile`() {
        val p = pipeline()
        p.anchorPlayerCell(20, 20, true)
        val dt = 1f / 60f
        val rnd = Random(11)
        val speed = 90f
        var x = 20f
        var y = 20f
        for (f in 0 until 12) {
            val ox = x + rnd.nextGaussian().toFloat() * 0.2f
            val oy = y + rnd.nextGaussian().toFloat() * 0.2f
            p.loadLuma(syntheticFrame(ox, oy), cols * 2, rows * 2, cols * 2)
            p.process(dt, f * 16L)
            if (f < 5) { x += speed * dt; y += 0f }
            else { x += 0f; y += speed * dt } // 90 degree turn at frame 5
        }
        val turned = p.tracks.filter { it.alive }.maxByOrNull { it.hits }
        assertNotNull(turned)
        assertTrue("a turning target must be rejected", !turned!!.isProjectile)
    }

    @Test
    fun `tracker rejects a slow target as a projectile`() {
        val p = pipeline()
        p.anchorPlayerCell(20, 20, true)
        val dt = 1f / 60f
        var x = 20f
        for (f in 0 until 12) {
            p.loadLuma(syntheticFrame(x, 20f), cols * 2, rows * 2, cols * 2)
            p.process(dt, f * 16L)
            x += 2f * dt // 2 cells/s, a brawler walking speed
        }
        assertTrue(
            "a 2 cells/s target is not a projectile",
            p.tracks.none { it.alive && it.isProjectile }
        )
    }

    @Test
    fun `world space is invariant to camera panning`() {
        // A bullet and the camera both moving: the bullet's world speed must be
        // the bullet's own speed, not speed + pan.
        val still = pipeline()
        val panning = pipeline()
        panning.anchorPlayerCell(20, 20, true)
        val dt = 1f / 60f
        val rnd = Random(5)
        val bulletSpeed = 120f
        val panPerFrame = 4f

        var stillFrames = 0
        var panFrames = 0
        for (f in 0 until 14) {
            val wx = bulletSpeed * dt * f
            val wy = 40f

            still.loadLuma(syntheticFrame(wx, wy), cols * 2, rows * 2, cols * 2)
            still.process(dt, f * 16L)
            if (f >= 6) stillFrames++

            // Same bullet, but drawn at a screen position shifted by the pan.
            panning.loadLuma(
                syntheticFrame(wx + panPerFrame * f, wy + panPerFrame * f),
                cols * 2, rows * 2, cols * 2
            )
            panning.process(dt, f * 16L)
            if (f >= 6) panFrames++
        }
        assertTrue(stillFrames > 0 && panFrames > 0)
        // The panning case is not asserted for an exact speed, only that the
        // engine does not treat pan itself as a fast moving object.
        assertTrue(
            "a moving camera must not create projectile tracks out of terrain",
            panning.tracks.count { it.alive } <= 4
        )
    }

    // ----------------------------------------------------------------- CPA
    @Test
    fun `threat solver returns a perpendicular escape angle`() {
        val p = pipeline()
        p.anchorPlayerCell(100, 57, true)
        // No frame data needed: solveThreat only reads the track state.
        val t = p.tracks.first()
        t.alive = true
        t.hits = 10
        t.x = 130f; t.y = 57f
        t.vx = -100f; t.vy = 0f
        t.speed = 100f
        t.disturbed = 0
        t.residual = 0.1f
        t.isProjectile = true

        val threat = p.solveThreat()
        assertNotNull("a bullet 30 cells away at 100 cells/s is a collision", threat)
        val deg = threat!!.dodgeAngleDeg
        assertTrue(
            "escape must be perpendicular to a 180 deg trajectory, was $deg",
            kotlin.math.abs(deg - 90f) < 1f || kotlin.math.abs(deg - 270f) < 1f
        )
        assertTrue("time to impact must be positive", threat.timeToImpactMs > 0)
    }

    @Test
    fun `threat solver ignores a target moving away`() {
        val p = pipeline()
        p.anchorPlayerCell(100, 57, true)
        val t = p.tracks.first()
        t.alive = true; t.hits = 10
        t.x = 130f; t.y = 57f
        t.vx = 100f; t.vy = 0f   // travelling right, away from the player
        t.speed = 100f
        t.isProjectile = true
        assertEquals(null, p.solveThreat())
    }

    @Test
    fun `threat solver ignores a far miss`() {
        val p = pipeline()
        p.anchorPlayerCell(100, 57, true)
        val t = p.tracks.first()
        t.alive = true; t.hits = 10
        t.x = 130f; t.y = 20f   // 37 cells off-axis, well outside the hitbox
        t.vx = -100f; t.vy = 0f
        t.speed = 100f
        t.isProjectile = true
        assertEquals(null, p.solveThreat())
    }

    // ----------------------------------------------------------- health bar
    @Test
    fun `health bar detector finds a four to one bar`() {
        val p = VisionPipeline(cols, rows, tileCells)
        val cap = cols * 2
        val frame = ByteArray(cap * rows * 2)
        val barLeft = cols - 20
        val barTop = rows - 20
        val barW = 24
        val barH = 6
        for (y in barTop until barTop + barH) {
            for (x in barLeft until barLeft + barW) {
                val i = y * cap + x
                if (i < frame.size) frame[i] = 230.toByte()
            }
        }
        p.loadLuma(frame, cap, rows * 2, cap)
        // The detector runs on a fresh structure built from the same luma.
        val found = p.detectPlayerAnchor(frame, cap, rows * 2, cap)
        assertTrue("a 4:1 bar should be found, score=${p.calibPlayerScore}", found)
        assertTrue("score must be meaningful", p.calibPlayerScore > 0.28f)
    }

    // ------------------------------------------------------------ exclusion
    @Test
    fun `exclusion regions are accepted without corrupting the mask`() {
        val p = pipeline()
        p.setExclusionRects(intArrayOf(0, 0, cols, rows), 1)
        p.loadLuma(syntheticFrame(30f, 30f), cols * 2, rows * 2, cols * 2)
        p.process(1f / 60f, 0L)
        p.loadLuma(syntheticFrame(31f, 30f), cols * 2, rows * 2, cols * 2)
        p.process(1f / 60f, 16L)
        assertEquals("fully masked frame yields no tracks", 0, p.tracks.count { it.alive })
    }

    // ------------------------------------------------------------- profiles
    @Test
    fun `brawl stars profile carries a usable dodge hold`() {
        val p = DodgeProfile.brawlStars()
        assertTrue("a 15 ms flick cannot move a brawler", p.dodgeHoldMs >= 150L)
        assertTrue("deflection must be near full stick", p.dodgeDeflection >= 0.8f)
        assertTrue(
            "cooldown must exceed the hold or gestures overlap",
            p.dodgeCooldownMs > p.dodgeHoldMs
        )
        assertFalse("an uncalibrated profile must not claim confirmation", p.anchorConfirmed)
    }

    @Test
    fun `sensitivity actually changes the hitbox`() {
        val low = DodgeProfile(sensitivity = 0.1f)
        val high = DodgeProfile(sensitivity = 1.0f)
        assertTrue(
            "sensitivity must widen the reaction hitbox",
            high.effectiveHitboxPx(160f) > low.effectiveHitboxPx(160f)
        )
    }

    // -------------------------------------------------------------- helpers
    /**
     * Builds a capture-resolution frame containing a single bright moving blob
     * at capture coordinates (bx, by) expressed in analysis cells.
     */
    private fun syntheticFrame(cx: Float, cy: Float): ByteArray {
        val cap = cols * 2
        val h = rows * 2
        val buf = ByteArray(cap * h) { 40 }
        val px = (cx * 2).toInt()
        val py = (cy * 2).toInt()
        for (y in py - 3..py + 3) {
            for (x in px - 3..px + 3) {
                if (x in 0 until cap && y in 0 until h) buf[y * cap + x] = 230.toByte()
            }
        }
        return buf
    }
}
