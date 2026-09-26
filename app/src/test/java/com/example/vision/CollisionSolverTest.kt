package com.example.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the collision geometry.
 *
 * These are plain JUnit tests with no Robolectric dependency on purpose: the
 * solver is pure maths, and keeping it Android-free means the conventions
 * (heading degrees, sign of the closest-approach test, units) are pinned by tests
 * that run in milliseconds and cannot be broken by a platform change.
 *
 * Heading convention under test everywhere: 0 = +X (screen right),
 * 90 = +Y (screen **down**).
 */
class CollisionSolverTest {

    private val screenW = 1920f
    private val screenH = 1080f

    // -----------------------------------------------------------------------
    // Closest point of approach
    // -----------------------------------------------------------------------

    @Test
    fun `time to closest approach for a head-on shot is distance over speed`() {
        // Projectile 500 px to the right, travelling left at 500 px/s.
        val p = CollisionSolver.Projectile(x = 900f, y = 400f, vx = -500f, vy = 0f)
        val t = CollisionSolver.timeToClosestApproach(400f, 400f, p, horizonSec = 2f)
        // 500 px at 500 px/s = 1.0 s
        assertEquals(1.0f, t, 1e-3f)
    }

    @Test
    fun `closest approach is rejected when the player is behind the projectile`() {
        val p = CollisionSolver.Projectile(x = 100f, y = 400f, vx = 500f, vy = 0f)
        val t = CollisionSolver.timeToClosestApproach(400f, 400f, p, horizonSec = 2f)
        assertEquals(-1f, t, 1e-6f)
    }

    @Test
    fun `closest approach is rejected when it is outside the horizon`() {
        val p = CollisionSolver.Projectile(x = 100f, y = 400f, vx = 1f, vy = 0f)
        val t = CollisionSolver.timeToClosestApproach(400f, 400f, p, horizonSec = 0.42f)
        assertEquals(-1f, t, 1e-6f)
    }

    @Test
    fun `closest approach is rejected for a stationary projectile`() {
        val p = CollisionSolver.Projectile(x = 400f, y = 400f, vx = 0f, vy = 0f)
        assertEquals(
            -1f,
            CollisionSolver.timeToClosestApproach(400f, 400f, p, horizonSec = 1f),
            1e-6f
        )
    }

    @Test
    fun `an off-line projectile is detected as a miss`() {
        // Passing 400 px above the player, so it never gets close.
        val p = CollisionSolver.Projectile(x = 900f, y = 400f, vx = -1000f, vy = 0f)
        val t = CollisionSolver.timeToClosestApproach(400f, 800f, p, horizonSec = 2f)
        assertTrue("should still have a closest approach in time", t > 0f)
        assertTrue(
            "and the miss distance must exceed the hitbox",
            CollisionSolver.missDistanceAt(400f, 800f, p, t) > 55f
        )
    }

    // -----------------------------------------------------------------------
    // Severity
    // -----------------------------------------------------------------------

    @Test
    fun `an impact inside the lethal window is classified lethal`() {
        // 45 px away at 500 px/s = 90 ms to impact.
        val p = CollisionSolver.Projectile(x = 445f, y = 400f, vx = -500f, vy = 0f)
        val s = CollisionSolver.solve(
            playerX = 400f, playerY = 400f, playerRadiusPx = 55f,
            projectiles = listOf(p),
            screenWidthPx = screenW, screenHeightPx = screenH
        )
        assertTrue(s.hasThreat)
        assertEquals(com.example.model.ThreatLevel.LETHAL, s.severity)
        // JUnit has no Long-delta overload, so compare with an explicit bound.
        assertTrue(
            "tti was ${s.timeToImpactMs}ms, expected about 90ms",
            kotlin.math.abs(s.timeToImpactMs - 90L) <= 6L
        )
    }

    @Test
    fun `an impact just inside the imminent window is imminent, not lethal`() {
        // 280 px at 1000 px/s = 280 ms, which is inside imminentTtiSec (290 ms)
        // and outside lethalTtiSec (170 ms).
        val p = CollisionSolver.Projectile(x = 680f, y = 400f, vx = -1000f, vy = 0f)
        val s = CollisionSolver.solve(
            playerX = 400f, playerY = 400f, playerRadiusPx = 55f,
            projectiles = listOf(p),
            screenWidthPx = screenW, screenHeightPx = screenH
        )
        assertTrue(s.hasThreat)
        assertEquals(
            com.example.model.ThreatLevel.IMMINENT_DANGER,
            s.severity
        )
    }

    @Test
    fun `an impact just inside the horizon is only a warning`() {
        // 380 px at 1000 px/s = 380 ms: inside the 420 ms horizon, but well past
        // both the imminent and lethal windows, so a warning at most.
        val p = CollisionSolver.Projectile(x = 780f, y = 400f, vx = -1000f, vy = 0f)
        val s = CollisionSolver.solve(
            playerX = 400f, playerY = 400f, playerRadiusPx = 55f,
            projectiles = listOf(p),
            screenWidthPx = screenW, screenHeightPx = screenH,
            reactionHorizonSec = 0.42f
        )
        assertTrue("380 ms must be inside the horizon", s.hasThreat)
        assertEquals(com.example.model.ThreatLevel.WARNING, s.severity)
    }

    @Test
    fun `an impact beyond the reaction horizon is not reported at all`() {
        // 800 ms away. The planner must not spend a gesture on something the
        // brawler cannot react to.
        val p = CollisionSolver.Projectile(x = 1200f, y = 400f, vx = -1000f, vy = 0f)
        val s = CollisionSolver.solve(
            playerX = 400f, playerY = 400f, playerRadiusPx = 55f,
            projectiles = listOf(p),
            screenWidthPx = screenW, screenHeightPx = screenH,
            reactionHorizonSec = 0.42f
        )
        assertFalse("beyond the horizon there is nothing to dodge", s.hasThreat)
        assertEquals(com.example.model.ThreatLevel.SAFE, s.severity)
    }

    @Test
    fun `no solution when nothing is incoming`() {
        val s = CollisionSolver.solve(
            playerX = 400f, playerY = 400f, playerRadiusPx = 55f,
            projectiles = emptyList(),
            screenWidthPx = screenW, screenHeightPx = screenH
        )
        assertFalse(s.hasThreat)
        assertEquals(0f, s.timeToImpactSec, 0f)
    }

    @Test
    fun `a zero radius player never reports a threat`() {
        val p = CollisionSolver.Projectile(x = 445f, y = 400f, vx = -500f, vy = 0f)
        val s = CollisionSolver.solve(
            playerX = 400f, playerY = 400f, playerRadiusPx = 0f,
            projectiles = listOf(p),
            screenWidthPx = screenW, screenHeightPx = screenH
        )
        assertFalse("a zero hitbox must not swallow every projectile", s.hasThreat)
    }

    // -----------------------------------------------------------------------
    // Escape heading
    // -----------------------------------------------------------------------

    @Test
    fun `escape from a horizontal shot is perpendicular`() {
        val p = CollisionSolver.Projectile(x = 445f, y = 400f, vx = -500f, vy = 0f)
        val s = CollisionSolver.solve(
            playerX = 400f, playerY = 400f, playerRadiusPx = 55f,
            projectiles = listOf(p),
            screenWidthPx = screenW, screenHeightPx = screenH
        )
        val heading = s.escapeHeadingDeg
        assertTrue(
            "escape $heading must be perpendicular to a 180 degree trajectory",
            CollisionSolver.isPerpendicular(heading, 180f, toleranceDeg = 20f)
        )
    }

    @Test
    fun `escape from a vertical shot is perpendicular`() {
        // 45 px below the player, travelling up at 500 px/s, so impact is at
        // 90 ms. It has to be close enough to be inside the reaction horizon or
        // the solver never runs and the assertion would pass vacuously.
        // vy < 0 is heading 270, so perpendicular is 0 or 180.
        val p = CollisionSolver.Projectile(x = 400f, y = 445f, vx = 0f, vy = -500f)
        val s = CollisionSolver.solve(
            playerX = 400f, playerY = 400f, playerRadiusPx = 55f,
            projectiles = listOf(p),
            screenWidthPx = screenW, screenHeightPx = screenH
        )
        assertTrue(s.hasThreat)
        assertEquals(
            "trajectory heading must be 270 for a shot going up",
            270f, CollisionSolver.trajectoryHeadingDeg(p), 0.01f
        )
        assertTrue(
            "escape ${s.escapeHeadingDeg} must be perpendicular to 270",
            CollisionSolver.isPerpendicular(s.escapeHeadingDeg, 270f, toleranceDeg = 20f)
        )
    }

    @Test
    fun `both perpendicular directions give the same separation, so the tie is broken on safety`() {
        // A shot straight down the screen with the player hugging the bottom
        // edge: the naive "+/-90" rule has one side off screen, so the planner
        // must pick the other one rather than a coin flip.
        val p = CollisionSolver.Projectile(x = 960f, y = 200f, vx = 0f, vy = 2000f)
        val heading = CollisionSolver.chooseEscapeHeading(
            playerX = 960f,
            playerY = 1000f, // 80 px from the bottom of a 1080 tall screen
            projectile = p,
            stepPx = 60f,
            screenWidthPx = screenW,
            screenHeightPx = screenH
        )
        val dirY = kotlin.math.sin(Math.toRadians(heading.toDouble())).toFloat()
        assertTrue(
            "escape must not run into the bottom edge (heading $heading, dirY $dirY)",
            dirY <= 0f
        )
    }

    @Test
    fun `escape avoids stepping onto an enemy`() {
        val p = CollisionSolver.Projectile(x = 900f, y = 400f, vx = -500f, vy = 0f)
        val playerX = 400f
        val playerY = 400f
        val enemyOffset = 150f

        // The shot travels along -X, so the escape goes up or down. An enemy
        // placed to the RIGHT would sit off that path and correctly not change
        // anything, so it has to be placed below the player to be a real test.
        val enemy = CollisionSolver.AvoidPoint(playerX, playerY + enemyOffset)
        val blocked = CollisionSolver.chooseEscapeHeading(
            playerX = playerX, playerY = playerY, projectile = p,
            stepPx = 60f,
            screenWidthPx = screenW, screenHeightPx = screenH,
            enemies = listOf(enemy),
            enemyAvoidRadiusPx = 220f
        )
        val unblocked = CollisionSolver.chooseEscapeHeading(
            playerX = playerX, playerY = playerY, projectile = p,
            stepPx = 60f,
            screenWidthPx = screenW, screenHeightPx = screenH,
            enemies = emptyList(),
            enemyAvoidRadiusPx = 220f
        )
        assertTrue(
            "the enemy must change the chosen heading (blocked=$blocked unblocked=$unblocked)",
            blocked != unblocked
        )
    }

    @Test
    fun `escape never leaves the screen`() {
        // Player in the top-left corner, shot coming from below-right.
        val p = CollisionSolver.Projectile(x = 1400f, y = 900f, vx = -800f, vy = -800f)
        // A projectile that is genuinely on its way in, so the planner runs.
        val heading = CollisionSolver.chooseEscapeHeading(
            playerX = 120f, playerY = 120f,
            projectile = p,
            stepPx = 90f,
            screenWidthPx = screenW, screenHeightPx = screenH
        )
        val rad = Math.toRadians(heading.toDouble())
        val tx = 120f + (kotlin.math.cos(rad) * 90f).toFloat()
        val ty = 120f + (kotlin.math.sin(rad) * 90f).toFloat()
        assertTrue("x=$tx must stay on screen", tx in 0f..screenW)
        assertTrue("y=$ty must stay on screen", ty in 0f..screenH)
    }

    @Test
    fun `escape heading is always a normalised degree value`() {
        val p = CollisionSolver.Projectile(x = 900f, y = 400f, vx = -500f, vy = 300f)
        val heading = CollisionSolver.chooseEscapeHeading(
            playerX = 400f, playerY = 400f, projectile = p,
            stepPx = 60f,
            screenWidthPx = screenW, screenHeightPx = screenH
        )
        assertTrue("heading $heading must be in [0,360)", heading >= 0f && heading < 360f)
    }

    // -----------------------------------------------------------------------
    // Timing and feasibility
    // -----------------------------------------------------------------------

    @Test
    fun `hold time is clamped into a dispatchable window`() {
        val fast = CollisionSolver.Projectile(x = 410f, y = 400f, vx = -5000f, vy = 0f)
        val s = CollisionSolver.solve(
            playerX = 400f, playerY = 400f, playerRadiusPx = 55f,
            projectiles = listOf(fast),
            screenWidthPx = screenW, screenHeightPx = screenH
        )
        assertTrue("hold ${s.holdMs}ms must respect the minimum", s.holdMs >= 70L)
    }

    @Test
    fun `an impossible dodge is reported as insufficient rather than silently faked`() {
        // 20 ms from impact: no character speed can cover the clearance in time.
        val p = CollisionSolver.Projectile(x = 410f, y = 400f, vx = -500f, vy = 0f)
        val s = CollisionSolver.solve(
            playerX = 400f, playerY = 400f, playerRadiusPx = 200f,
            projectiles = listOf(p),
            screenWidthPx = screenW, screenHeightPx = screenH,
            characterSpeedPxPerSec = 1f
        )
        assertTrue(s.hasThreat)
        assertFalse("must admit the dodge is not enough", s.escapeIsSufficient)
    }

    @Test
    fun `the earliest of several threats is the one that matters`() {
        val slow = CollisionSolver.Projectile(x = 1000f, y = 400f, vx = -400f, vy = 0f)
        val fast = CollisionSolver.Projectile(x = 460f, y = 400f, vx = -900f, vy = 0f)
        val s = CollisionSolver.solve(
            playerX = 400f, playerY = 400f, playerRadiusPx = 55f,
            projectiles = listOf(slow, fast),
            screenWidthPx = screenW, screenHeightPx = screenH
        )
        assertNotNull(s.projectile)
        assertEquals(900f, s.projectile!!.speed, 1f)
    }

    // -----------------------------------------------------------------------
    // Conventions
    // -----------------------------------------------------------------------

    @Test
    fun `trajectory heading follows atan2 with screen-down as positive y`() {
        assertEquals(0f, CollisionSolver.trajectoryHeadingDeg(
            CollisionSolver.Projectile(0f, 0f, 100f, 0f)
        ), 0.01f)
        assertEquals(90f, CollisionSolver.trajectoryHeadingDeg(
            CollisionSolver.Projectile(0f, 0f, 0f, 100f)
        ), 0.01f)
        assertEquals(180f, CollisionSolver.trajectoryHeadingDeg(
            CollisionSolver.Projectile(0f, 0f, -100f, 0f)
        ), 0.01f)
        assertEquals(270f, CollisionSolver.trajectoryHeadingDeg(
            CollisionSolver.Projectile(0f, 0f, 0f, -100f)
        ), 0.01f)
    }

    @Test
    fun `angle between headings is the smallest separation and stays in range`() {
        assertEquals(0f, CollisionSolver.angleBetweenDeg(10f, 10f), 0.01f)
        assertEquals(90f, CollisionSolver.angleBetweenDeg(0f, 90f), 0.01f)
        // 350 and 10 are 20 degrees apart, not 340.
        assertEquals(20f, CollisionSolver.angleBetweenDeg(350f, 10f), 0.01f)
        assertEquals(180f, CollisionSolver.angleBetweenDeg(0f, 180f), 0.01f)
    }

    @Test
    fun `isPerpendicular accepts exactly ninety degrees and rejects a reversal`() {
        assertTrue(CollisionSolver.isPerpendicular(0f, 90f))
        assertTrue(CollisionSolver.isPerpendicular(0f, 270f))
        assertFalse("a 180 degree turn is not perpendicular", CollisionSolver.isPerpendicular(0f, 180f))
        assertFalse("the same heading is not perpendicular", CollisionSolver.isPerpendicular(45f, 45f))
    }
}
