package com.example.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the gesture shape.
 *
 * The regression this file exists to prevent is very specific and very easy to
 * reintroduce: a single short swipe. A `StrokeDescription` shorter than the
 * platform touch slop is delivered as a *tap*, and a single line releases the
 * stick the instant it arrives, so the brawler twitches instead of dodging.
 * Every test here therefore checks the *chained four-phase* structure, not just
 * the endpoints.
 */
class DodgeGesturePlannerTest {

    private val stickX = 300f
    private val stickY = 760f
    private val screenW = 1920f
    private val screenH = 1080f
    private val dragPx = 150f

    // -----------------------------------------------------------------------
    // Structure
    // -----------------------------------------------------------------------

    @Test
    fun `a plan is four chained strokes press drag hold release`() {
        val plan = DodgeGesturePlanner.plan(
            stickX = stickX, stickY = stickY,
            headingDeg = 90f, dragPx = dragPx, holdMs = 140L,
            screenWidthPx = screenW, screenHeightPx = screenH
        )
        assertFalse(plan.isEmpty)
        assertEquals(4, plan.strokes.size)

        val press = plan.strokes[0]
        val drag = plan.strokes[1]
        val hold = plan.strokes[2]
        val release = plan.strokes[3]

        // 1. press: stationary, holds the pointer down
        assertEquals(stickX, press.startX, 0.01f)
        assertEquals(stickY, press.startY, 0.01f)
        assertEquals(stickX, press.endX, 0.01f)
        assertEquals(stickY, press.endY, 0.01f)
        assertTrue(press.willContinue)
        assertEquals(0L, press.startTimeMs)

        // 2. drag: starts where the press ended, travels the escape direction
        assertEquals(press.startTimeMs + press.durationMs, drag.startTimeMs)
        assertEquals(stickX, drag.startX, 0.01f)
        assertEquals(stickY, drag.startY, 0.01f)
        assertTrue(drag.willContinue)

        // 3. hold: stays at the target, this is the phase that moves the brawler
        assertEquals(drag.startTimeMs + drag.durationMs, hold.startTimeMs)
        assertEquals(drag.endX, hold.startX, 0.01f)
        assertEquals(drag.endY, hold.startY, 0.01f)
        assertEquals(hold.startX, hold.endX, 0.01f)
        assertEquals(hold.startY, hold.endY, 0.01f)
        assertTrue(hold.willContinue)
        assertEquals(140L, hold.durationMs)

        // 4. release: ends the chain, which lifts the pointer
        assertEquals(hold.startTimeMs + hold.durationMs, release.startTimeMs)
        assertFalse("the last stroke must end the chain", release.willContinue)
    }

    @Test
    fun `stroke start times tile the gesture with no gaps or overlaps`() {
        val plan = DodgeGesturePlanner.plan(
            stickX, stickY, 45f, dragPx, 120L, screenW, screenH
        )
        for (i in 1 until plan.strokes.size) {
            assertEquals(
                "stroke $i must begin exactly when stroke ${i - 1} ends",
                plan.strokes[i - 1].startTimeMs + plan.strokes[i - 1].durationMs,
                plan.strokes[i].startTimeMs
            )
        }
        val last = plan.strokes.last()
        assertEquals(plan.totalDurationMs, last.startTimeMs + last.durationMs)
    }

    @Test
    fun `no stroke is ever shorter than the platform minimum`() {
        val plan = DodgeGesturePlanner.plan(
            stickX, stickY, 0f, dragPx, 70L, screenW, screenH
        )
        for (s in plan.strokes) {
            assertTrue(
                "stroke of ${s.durationMs}ms is below the $DodgeGesturePlanner.MIN_STROKE_MS floor",
                s.durationMs >= DodgeGesturePlanner.MIN_STROKE_MS
            )
        }
    }

    @Test
    fun `the hold phase is a real phase, not a formality`() {
        val plan = DodgeGesturePlanner.plan(
            stickX, stickY, 0f, dragPx, 200L, screenW, screenH
        )
        assertEquals("the brawler must actually travel", 200L, DodgeGesturePlanner.effectiveTravelMs(plan))
    }

    // -----------------------------------------------------------------------
    // Direction
    // -----------------------------------------------------------------------

    @Test
    fun `heading ninety degrees drags straight down the screen`() {
        val plan = DodgeGesturePlanner.plan(stickX, stickY, 90f, dragPx, 100L, screenW, screenH)
        val drag = plan.strokes[1]
        assertEquals(stickX, drag.endX, 0.5f)
        assertEquals(stickY + dragPx, drag.endY, 0.5f)
    }

    @Test
    fun `heading zero degrees drags straight right`() {
        val plan = DodgeGesturePlanner.plan(stickX, stickY, 0f, dragPx, 100L, screenW, screenH)
        val drag = plan.strokes[1]
        assertEquals(stickX + dragPx, drag.endX, 0.5f)
        assertEquals(stickY, drag.endY, 0.5f)
    }

    @Test
    fun `the target sits exactly the requested distance from the base`() {
        val cx = 960f
        val cy = 540f
        for (deg in intArrayOf(0, 37, 90, 143, 180, 225, 270, 315)) {
            val plan = DodgeGesturePlanner.plan(cx, cy, deg.toFloat(), dragPx, 100L, screenW, screenH)
            val drag = plan.strokes[1]
            val dist = kotlin.math.hypot(drag.endX - cx, drag.endY - cy)
            assertTrue(
                "heading $deg produced a $dist px drag, expected ~$dragPx",
                kotlin.math.abs(dist - dragPx) < 1.5f
            )
        }
    }

    // -----------------------------------------------------------------------
    // Refusals and clamping
    // -----------------------------------------------------------------------

    @Test
    fun `a zero length drag is refused rather than degenerating into a tap`() {
        val plan = DodgeGesturePlanner.plan(stickX, stickY, 90f, 0f, 100L, screenW, screenH)
        assertTrue("a zero drag would be a tap, which is the original bug", plan.isEmpty)
    }

    @Test
    fun `an absurd screen size is refused instead of producing garbage coordinates`() {
        val plan = DodgeGesturePlanner.plan(stickX, stickY, 90f, dragPx, 100L, 0f, 0f)
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `the target is clamped on screen and the drag length is corrected`() {
        // Base is 30 px from the left edge; a 400 px drag to the left cannot fit.
        val baseX = 30f
        val plan = DodgeGesturePlanner.plan(
            stickX = baseX, stickY = 540f,
            headingDeg = 180f, dragPx = 400f, holdMs = 100L,
            screenWidthPx = screenW, screenHeightPx = screenH
        )
        assertTrue("clamping must be reported, not hidden", plan.truncated)
        val drag = plan.strokes[1]
        assertTrue("target ${drag.endX} must stay on screen", drag.endX >= 0f)

        // After clamping, the executed drag length is the real distance to the
        // target, never the requested one. Reporting a length the gesture does
        // not perform is exactly the kind of quiet lie this code used to tell.
        val actual = kotlin.math.hypot(drag.endX - baseX, drag.endY - 540f)
        assertTrue("actual drag $actual must be shorter than the request", actual < 400f)
        assertTrue("actual drag $actual must be positive", actual > 0f)

        // And the hold phase must sit on the same point the drag arrived at.
        assertEquals(drag.endX, plan.strokes[2].startX, 0.001f)
        assertEquals(drag.endY, plan.strokes[2].startY, 0.001f)
    }

    @Test
    fun `hold time is clamped into the configured window`() {
        val short = DodgeGesturePlanner.plan(stickX, stickY, 0f, dragPx, 5L, screenW, screenH)
        assertEquals(70L, DodgeGesturePlanner.effectiveTravelMs(short))

        val long = DodgeGesturePlanner.plan(stickX, stickY, 0f, dragPx, 5000L, screenW, screenH)
        assertEquals(220L, DodgeGesturePlanner.effectiveTravelMs(long))
    }

    @Test
    fun `invalid timing is rejected at construction instead of producing a bad gesture`() {
        var threw = false
        try {
            DodgeGesturePlanner.Timing(pressMs = 0L)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a zero press duration must be rejected", threw)
    }

    // -----------------------------------------------------------------------
    // Bridging from the solver
    // -----------------------------------------------------------------------

    @Test
    fun `planFromSolution carries the solver's heading and hold through`() {
        val solution = CollisionSolver.Solution(
            hasThreat = true,
            escapeHeadingDeg = 270f,
            joystickDragPx = 140f,
            holdMs = 150L
        )
        val plan = DodgeGesturePlanner.planFromSolution(
            solution, stickX, stickY, screenW, screenH
        )
        assertEquals(4, plan.strokes.size)
        assertEquals(150L, DodgeGesturePlanner.effectiveTravelMs(plan))
        val drag = plan.strokes[1]
        // 270 degrees is straight up, so y decreases.
        assertTrue("270 degrees must drag upward", drag.endY < stickY)
        assertEquals(stickX, drag.endX, 0.5f)
    }

    @Test
    fun `integer rounding does not change the stroke count or the chain`() {
        val plan = DodgeGesturePlanner.plan(
            stickX = 300.4f, stickY = 760.6f, headingDeg = 47f,
            dragPx = 133.7f, holdMs = 111L, screenWidthPx = screenW, screenHeightPx = screenH
        )
        val rounded = DodgeGesturePlanner.toIntegerPixels(plan)
        assertEquals(plan.strokes.size, rounded.size)
        for (i in 1 until rounded.size) {
            assertEquals(
                "stroke $i start must still chain",
                rounded[i - 1].startTimeMs + rounded[i - 1].durationMs,
                rounded[i].startTimeMs
            )
            assertEquals(rounded[i].startX, rounded[i].startX.roundToInt().toFloat(), 0.001f)
        }
    }

    private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()
}
