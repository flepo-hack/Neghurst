package com.example.vision

import com.example.vision.nativebridge.VisionResult
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the multi-threat escape and the continuous re-evaluation policy.
 *
 * Both exist because of a specific observed failure: the brawler "walked into
 * ammo while dodging". There were two independent causes and both are pinned
 * here.
 *
 *  * **The escape was chosen against one shot.** A shotgun spread is several
 *    pellets at once, and the heading that clears the nearest one frequently
 *    walks into the second.
 *  * **The decision layer went blind for the whole dodge.** A wall-clock
 *    cooldown refused everything for 320 ms after a dispatch, and 640 ms when it
 *    thought the threat was already answered, which is longer than the gesture
 *    itself. Any projectile arriving in that window was ignored.
 */
class MultiThreatEscapeTest {

    private val screenW = 1920f
    private val screenH = 1080f
    private val playerRadius = 100f
    private val playerX = 960f
    private val playerY = 540f

    // -----------------------------------------------------------------------
    // Multi-threat scoring
    // -----------------------------------------------------------------------

    @Test
    fun `a single threat still escapes perpendicular`() {
        val s = CollisionSolver.solve(
            playerX = playerX, playerY = playerY, playerRadiusPx = playerRadius,
            projectiles = listOf(
                CollisionSolver.Projectile(1100f, 540f, -900f, 0f)
            ),
            screenWidthPx = screenW, screenHeightPx = screenH
        )
        assertTrue(s.hasThreat)
        assertTrue(
            "single-threat behaviour must be exactly perpendicular, got ${s.escapeHeadingDeg}",
            CollisionSolver.isPerpendicular(s.escapeHeadingDeg, 180f, toleranceDeg = 10f)
        )
    }

    @Test
    fun `a tight burst is answered with a single perpendicular step`() {
        // Three pellets arriving as a spread: the escape must clear all three,
        // not just the nearest.
        // Spread is plus/minus 20 px. That matters: a perpendicular step of
        // 1.35 * radius has to leave at least `radius` from the outer pellet, so
        // the spread has to be under 0.35 * radius. A wider spread is genuinely
        // not fully clearable by any single step, which the next test covers.
        val burst = listOf(
            CollisionSolver.Projectile(1150f, 520f, -900f, 0f),
            CollisionSolver.Projectile(1150f, 540f, -900f, 0f),
            CollisionSolver.Projectile(1150f, 560f, -900f, 0f)
        )
        val s = CollisionSolver.solve(
            playerX = playerX, playerY = playerY, playerRadiusPx = playerRadius,
            projectiles = burst,
            screenWidthPx = screenW, screenHeightPx = screenH
        )
        assertTrue(s.hasThreat)
        assertEquals("all three pellets are live", 3, s.projectilesConsidered)
        assertTrue(
            "a perpendicular step must clear the whole spread, cleared=${s.projectilesCleared}",
            s.projectilesCleared == 3
        )
        assertFalse("so it is not a partial escape", s.partialEscape)
    }

    @Test
    fun `fire from both sides is fully answered by going perpendicular`() {
        // One shot from each side. A perpendicular step moves off both flight
        // lines at once, so this IS fully clearable and the planner must say so
        // rather than reporting a partial dodge.
        val s = CollisionSolver.solve(
            playerX = playerX, playerY = playerY, playerRadiusPx = playerRadius,
            projectiles = listOf(
                CollisionSolver.Projectile(1150f, 540f, -900f, 0f),
                CollisionSolver.Projectile(770f, 540f, 900f, 0f)
            ),
            screenWidthPx = screenW, screenHeightPx = screenH
        )
        assertTrue(s.hasThreat)
        assertEquals(2, s.projectilesConsidered)
        assertEquals("a perpendicular step clears both", 2, s.projectilesCleared)
        assertFalse(s.partialEscape)
    }

    @Test
    fun `fire from two perpendicular directions is only partly answerable and says so`() {
        // One from the left, one straight down from above. No heading is
        // perpendicular to both, so no single step clears both. The planner has
        // to report that instead of claiming success, because a caller that
        // believes it is safe will stop re-evaluating.
        val s = CollisionSolver.solve(
            playerX = playerX, playerY = playerY, playerRadiusPx = playerRadius,
            projectiles = listOf(
                CollisionSolver.Projectile(1150f, 540f, -900f, 0f),
                CollisionSolver.Projectile(960f, 250f, 0f, 900f)
            ),
            screenWidthPx = screenW, screenHeightPx = screenH
        )
        assertTrue(s.hasThreat)
        assertEquals(2, s.projectilesConsidered)
        assertTrue(
            "no single step can clear both, cleared=${s.projectilesCleared}",
            s.partialEscape
        )
        assertTrue("but it must still save the nearer one", s.projectilesCleared >= 1)
    }

    @Test
    fun `a spread too wide for any single step reports a partial escape`() {
        // Spread plus/minus 40 px against a 100 px hitbox: a perpendicular step
        // of 135 px leaves only 95 px, which is inside the hitbox. Genuinely not
        // fully clearable.
        val s = CollisionSolver.solve(
            playerX = playerX, playerY = playerY, playerRadiusPx = playerRadius,
            projectiles = listOf(
                CollisionSolver.Projectile(1150f, 500f, -900f, 0f),
                CollisionSolver.Projectile(1150f, 540f, -900f, 0f),
                CollisionSolver.Projectile(1150f, 580f, -900f, 0f)
            ),
            screenWidthPx = screenW, screenHeightPx = screenH
        )
        assertEquals(3, s.projectilesConsidered)
        assertTrue("a 40 px spread cannot be fully dodged", s.partialEscape)
        assertTrue("but most of it can", s.projectilesCleared >= 2)
    }

    @Test
    fun `the heading is chosen to clear the most shots, not merely the first`() {
        // A narrow shot from straight left, plus a wide set from up-left. The
        // heading that clears only the first is not perpendicular to the second,
        // so a single-threat planner would pick the wrong one.
        val s = CollisionSolver.solve(
            playerX = playerX, playerY = playerY, playerRadiusPx = playerRadius,
            projectiles = listOf(
                CollisionSolver.Projectile(1120f, 540f, -1200f, 0f),
                CollisionSolver.Projectile(900f, 300f, 0f, 600f),
                CollisionSolver.Projectile(820f, 360f, 0f, 700f)
            ),
            screenWidthPx = screenW, screenHeightPx = screenH
        )
        assertTrue(s.hasThreat)
        assertTrue(
            "expected several shots to be cleared, got ${s.projectilesCleared}/${s.projectilesConsidered}",
            s.projectilesCleared >= 2
        )
    }

    @Test
    fun `a non collision shot is not counted as a threat at all`() {
        val s = CollisionSolver.solve(
            playerX = playerX, playerY = playerY, playerRadiusPx = playerRadius,
            projectiles = listOf(
                CollisionSolver.Projectile(1150f, 540f, -900f, 0f),
                CollisionSolver.Projectile(1150f, 200f, -900f, 0f)  // 340 px off line
            ),
            screenWidthPx = screenW, screenHeightPx = screenH
        )
        assertTrue(s.hasThreat)
        assertEquals("only the on-line shot counts", 1, s.projectilesConsidered)
    }

    @Test
    fun `a stationary projectile is ignored`() {
        val s = CollisionSolver.solve(
            playerX = playerX, playerY = playerY, playerRadiusPx = playerRadius,
            projectiles = listOf(CollisionSolver.Projectile(playerX, playerY, 0f, 0f)),
            screenWidthPx = screenW, screenHeightPx = screenH
        )
        assertFalse(s.hasThreat)
    }

    // -----------------------------------------------------------------------
    // Escape never walks into an enemy
    // -----------------------------------------------------------------------

    @Test
    fun `the escape will not close the distance to an enemy behind it`() {
        // The natural perpendicular to a shot from the right is straight down,
        // and an enemy sits there. The escape must go the other way.
        val enemy = CollisionSolver.AvoidPoint(playerX, playerY + 300f)
        val s = CollisionSolver.solve(
            playerX = playerX, playerY = playerY, playerRadiusPx = playerRadius,
            projectiles = listOf(CollisionSolver.Projectile(1150f, 540f, -900f, 0f)),
            screenWidthPx = screenW, screenHeightPx = screenH,
            enemies = listOf(enemy),
            enemyAvoidRadiusPx = 420f
        )
        assertTrue(s.hasThreat)
        val destY = playerY +
            kotlin.math.sin(Math.toRadians(s.escapeHeadingDeg.toDouble())).toFloat() * s.requiredTravelPx
        val enemyY = playerY + 300f
        assertTrue(
            "escape must not move towards the enemy at y=$enemyY, landed at y=$destY",
            destY < enemyY
        )
        assertTrue(
            "and must remain a valid perpendicular escape, got ${s.escapeHeadingDeg}deg",
            CollisionSolver.isPerpendicular(s.escapeHeadingDeg, 180f, toleranceDeg = 25f)
        )
    }

    @Test
    fun `the escape is not penalised for moving away from an enemy`() {
        // An enemy behind the brawler. Stepping further from it is correct and
        // must not be scored as a cost, otherwise the planner is pushed toward
        // the enemy purely by the penalty term.
        val enemy = CollisionSolver.AvoidPoint(playerX, playerY - 300f)
        val away = CollisionSolver.solve(
            playerX = playerX, playerY = playerY, playerRadiusPx = playerRadius,
            projectiles = listOf(CollisionSolver.Projectile(1150f, 540f, -900f, 0f)),
            screenWidthPx = screenW, screenHeightPx = screenH,
            enemies = listOf(enemy),
            enemyAvoidRadiusPx = 420f
        )
        val ignored = CollisionSolver.solve(
            playerX = playerX, playerY = playerY, playerRadiusPx = playerRadius,
            projectiles = listOf(CollisionSolver.Projectile(1150f, 540f, -900f, 0f)),
            screenWidthPx = screenW, screenHeightPx = screenH
        )
        // Both perpendiculars are equally far from an enemy that is exactly
        // behind, so the choice must not flip between them.
        assertEquals(
            "an enemy directly behind must not change the plan",
            0f,
            kotlin.math.abs(away.escapeHeadingDeg - ignored.escapeHeadingDeg),
            0.001f
        )
    }

    @Test
    fun `enemies are avoided even when that means a non perpendicular escape`() {
        // An enemy directly below, so the natural perpendicular (downwards) is
        // blocked and the other direction must be chosen.
        val s = CollisionSolver.solve(
            playerX = playerX, playerY = playerY, playerRadiusPx = playerRadius,
            projectiles = listOf(CollisionSolver.Projectile(1150f, 540f, -900f, 0f)),
            screenWidthPx = screenW, screenHeightPx = screenH,
            enemies = listOf(CollisionSolver.AvoidPoint(playerX, playerY + 300f)),
            enemyAvoidRadiusPx = 420f
        )
        val heading = s.escapeHeadingDeg
        val dy = kotlin.math.sin(Math.toRadians(heading.toDouble()))
        assertTrue("must not head downwards into the enemy, heading=$heading", dy <= 0.0)
    }

    @Test
    fun `an enemy beside the brawler biases the escape slightly but never off perpendicular`() {
        // The lateral distance used to be measured against the projectile's
        // flight-line normal, which made it the SAME for every candidate heading.
        // An enemy standing beside the brawler therefore scored as blocking all of
        // them equally, and with the raised penalty weight that could push the
        // escape onto a heading with zero clearance. Placing the enemy off to one
        // side is the only arrangement that exposes it.
        val sideways = CollisionSolver.AvoidPoint(playerX + 200f, playerY)
        val withEnemy = CollisionSolver.solve(
            playerX = playerX, playerY = playerY, playerRadiusPx = playerRadius,
            projectiles = listOf(CollisionSolver.Projectile(1150f, 540f, -900f, 0f)),
            screenWidthPx = screenW, screenHeightPx = screenH,
            enemies = listOf(sideways),
            enemyAvoidRadiusPx = 200f
        )
        assertTrue(withEnemy.hasThreat)

        // A small bias AWAY from a nearby enemy is intended and correct: it
        // costs a few pixels of clearance and keeps the brawler from drifting
        // toward an enemy mid-dodge. What must not happen is a large swing, or
        // the escape ending up on the wrong side of the projectile.
        assertTrue(
            "sideways enemy distorted the heading to ${withEnemy.escapeHeadingDeg}deg",
            CollisionSolver.isPerpendicular(
                withEnemy.escapeHeadingDeg, 180f, toleranceDeg = 20f
            )
        )
        val headingRad = Math.toRadians(withEnemy.escapeHeadingDeg.toDouble())
        val clearance = abs(
            withEnemy.requiredTravelPx * sin(headingRad).toFloat()
        )
        assertTrue(
            "clearance fell to $clearance px, inside the ${playerRadius}px hitbox",
            clearance > playerRadius
        )
        // And it must be biased away from the enemy, i.e. leftward for an enemy
        // on the right.
        assertTrue(
            "escape should lean away from an enemy on its right",
            cos(headingRad).toFloat() < 0.2f
        )
    }

    @Test
    fun `the escape stays off the map edge even with nowhere else to go`() {
        // Player pinned in the top-left corner with a shot incoming.
        val s = CollisionSolver.solve(
            playerX = 140f, playerY = 140f, playerRadiusPx = 60f,
            // 160 px away at 707 px/s is 0.23 s, inside the reaction horizon. A
            // shot further out has no plan, because there is nothing to dodge yet.
            projectiles = listOf(CollisionSolver.Projectile(300f, 300f, -500f, -500f)),
            screenWidthPx = screenW, screenHeightPx = screenH
        )
        assertTrue(s.hasThreat)
        val tx = 140f + kotlin.math.cos(Math.toRadians(s.escapeHeadingDeg.toDouble())).toFloat() * s.requiredTravelPx
        val ty = 140f + kotlin.math.sin(Math.toRadians(s.escapeHeadingDeg.toDouble())).toFloat() * s.requiredTravelPx
        assertTrue("x=$tx must stay on screen", tx > 0f && tx < screenW)
        assertTrue("y=$ty must stay on screen", ty > 0f && ty < screenH)
    }

    // -----------------------------------------------------------------------
    // Continuous re-evaluation
    // -----------------------------------------------------------------------

    /** Minimal analysis, so the state machine can be driven directly. */
    private fun analysis(
        heading: Float,
        cleared: Int,
        considered: Int,
        trackId: Int = 1,
        threatX: Float = 1150f,
        threatY: Float = 540f
    ): ScreenThreatDetector.Analysis {
        val raw = VisionResult(
            FloatArray(64).also {
                it[13] = threatX; it[14] = threatY
                it[15] = -900f; it[16] = 0f
            },
            IntArray(14).also { it[1] = 1; it[5] = trackId }
        )
        val sol = CollisionSolver.Solution(
            hasThreat = true,
            escapeHeadingDeg = heading,
            projectilesCleared = cleared,
            projectilesConsidered = considered
        )
        // `threat` MUST be non-null: `hasDodgeableThreat` is
        // `threat != null && escape.hasThreat`, so a null threat returns before
        // the state machine does anything and the test passes without covering
        // it. This exact trap silently disarmed the first draft of this file.
        return ScreenThreatDetector.Analysis(
            raw = raw, playerX = 960f, playerY = 540f,
            playerDetected = true, playerFromAnchor = false,
            threat = com.example.model.ThreatVector(
                threatX = threatX, threatY = threatY,
                velocityX = -900f, velocityY = 0f,
                speed = 900f, threatAngleDeg = 180f,
                dodgeAngleDeg = heading,
                dodgeDirX = 0f, dodgeDirY = 0f,
                threatLevel = com.example.model.ThreatLevel.WARNING,
                timeToImpactMs = 100L, confidence = 1f
            ),
            escape = sol, processMillis = 0.0,
            blobCount = 0, projectileCount = considered, enemyCount = 0
        )
    }

    @Test
    fun `a new threat is answered immediately even right after a dispatch`() {
        var idle = false
        val st = DodgeDecisionState(canDispatch = { idle })

        idle = true
        assertTrue("first threat must dispatch", st.shouldDispatch(analysis(90f, 1, 1, trackId = 1), 0L))

        // Still idle, and a different projectile shows up 5 ms later. The old
        // cooldown would have refused this for another 315 ms.
        assertTrue(
            "a new threat must not wait out a cooldown",
            st.shouldDispatch(analysis(90f, 1, 1, trackId = 2, threatX = 1100f), 5L)
        )
    }

    @Test
    fun `the same threat with the same heading is not repeated`() {
        val st = DodgeDecisionState(canDispatch = { true })
        val a = analysis(90f, 1, 1, trackId = 7)
        assertTrue(st.shouldDispatch(a, 0L))
        assertFalse(
            "repeating an identical dodge wastes a gesture and cancels the one in flight",
            st.shouldDispatch(analysis(92f, 1, 1, trackId = 7), 16L)
        )
        assertEquals(1, st.dispatchedCount)
        assertEquals(1, st.suppressedCount)
    }

    @Test
    fun `a materially different heading is dispatched even for the same threat`() {
        val st = DodgeDecisionState(canDispatch = { true })
        assertTrue(st.shouldDispatch(analysis(90f, 1, 1, trackId = 7), 0L))
        // The situation changed and the escape must be corrected. This is the
        // case the old cooldown made impossible: the brawler was already holding
        // the stick the wrong way.
        assertTrue(
            "a 90 degree change of plan must be dispatched",
            st.shouldDispatch(analysis(0f, 2, 3, trackId = 7), 20L)
        )
    }

    @Test
    fun `while a gesture is in flight nothing is dispatched but the threat is not forgotten`() {
        var idle = true
        val st = DodgeDecisionState(canDispatch = { idle })
        assertTrue(st.shouldDispatch(analysis(90f, 1, 1), 0L))

        idle = false
        assertFalse(
            "the system refuses overlapping gestures, so we must not try",
            st.shouldDispatch(analysis(0f, 2, 2), 10L)
        )
        assertEquals(1, st.dispatchedCount)

        // Gesture completes; the situation still needs a different answer.
        idle = true
        assertTrue(
            "once the stick is free the pending plan must be delivered",
            st.shouldDispatch(analysis(0f, 2, 2), 200L)
        )
    }

    @Test
    fun `a refused dispatch is retried rather than counted as handled`() {
        val st = DodgeDecisionState(canDispatch = { true })
        assertTrue(st.shouldDispatch(analysis(90f, 1, 1), 0L))
        st.onDispatchFailed()
        assertTrue(
            "nothing was sent, so the next frame must try again",
            st.shouldDispatch(analysis(90f, 1, 1), 16L)
        )
        assertEquals(2, st.dispatchedCount)
    }

    @Test
    fun `no threat means no dispatch`() {
        val st = DodgeDecisionState(canDispatch = { true })
        val raw = VisionResult(FloatArray(64), IntArray(14))
        val a = ScreenThreatDetector.Analysis(
            raw = raw, playerX = 960f, playerY = 540f,
            playerDetected = true, playerFromAnchor = false,
            threat = null, escape = CollisionSolver.Solution(), processMillis = 0.0,
            blobCount = 0, projectileCount = 0, enemyCount = 0
        )
        assertFalse(st.shouldDispatch(a, 0L))
        assertFalse(a.hasDodgeableThreat)
    }

    @Test
    fun `reset clears the commitment so a fresh match is not silenced`() {
        val st = DodgeDecisionState(canDispatch = { true })
        assertTrue(st.shouldDispatch(analysis(90f, 1, 1), 0L))
        st.reset()
        assertTrue(
            "after a reset the same plan must be dispatchable again",
            st.shouldDispatch(analysis(90f, 1, 1), 5L)
        )
    }

    @Test
    fun `a threat key is stable while the threat is stationary and changes when it moves`() {
        val st = DodgeDecisionState(canDispatch = { true })
        val a = analysis(90f, 1, 1, trackId = 3, threatX = 1150f, threatY = 540f)
        val k1 = st.trackKeyFor(a)
        val k2 = st.trackKeyFor(analysis(90f, 1, 1, trackId = 3, threatX = 1152f, threatY = 541f))
        val k3 = st.trackKeyFor(analysis(90f, 1, 1, trackId = 3, threatX = 1500f, threatY = 300f))
        assertEquals("small motion keeps the identity", k1, k2, 0.001f)
        assertTrue("a moved threat is a different one", k1 != k3)
    }
}
