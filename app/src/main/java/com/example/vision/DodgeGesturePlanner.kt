package com.example.vision

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turns a solved escape into an executable multi-stroke gesture.
 *
 * ## Why this is not a single swipe
 *
 * A `GestureDescription.StrokeDescription` interpolates one pointer along a
 * path over the stroke's duration. The previous implementation dispatched a
 * 15 ms straight line from the joystick centre to the target, which is a bug
 * twice over:
 *
 *  * **15 ms is below the platform touch slop.** The receiving game sees a tap
 *    at the joystick base, not a drag, so nothing moves at all.
 *  * **A single line leaves the stick immediately.** Even at 180 ms the pointer
 *    arrives at the target and stops, so the brawler walks a fraction of the
 *    distance and halts. A dodge that only nudges you is worse than no dodge,
 *    because it commits you to an input you cannot cancel.
 *
 * A virtual stick needs four distinct phases, and Android's gesture API
 * expresses exactly that through chained strokes with `willContinue`:
 *
 *  1. **Press** - land on the stick base and hold long enough for the game to
 *     spawn the stick and register a drag start.
 *  2. **Drag** - travel to the target over a short window. The duration is
 *     irrelevant to the resulting direction, but must be long enough that the
 *     motion is not filtered out as jitter.
 *  3. **Hold** - stay at the target while the brawler actually travels. This is
 *     the phase that makes the dodge a real movement instead of a twitch.
 *  4. **Release** - end the chain, which lifts the pointer and drops the stick.
 *
 * `GestureDescription` runs strokes back to back on a single pointer, so the
 * whole chain is one atomic gesture: the game cannot interleave another touch.
 */
object DodgeGesturePlanner {

    /** One chained stroke of the gesture. */
    data class Stroke(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        /** Offset from the start of the whole gesture. */
        val startTimeMs: Long,
        val durationMs: Long,
        val willContinue: Boolean
    )

    /** The full plan, ready to be translated into a `GestureDescription`. */
    data class Plan(
        val strokes: List<Stroke>,
        val totalDurationMs: Long,
        val endX: Float,
        val endY: Float,
        /** Set when the requested drag was clamped or the gesture was dropped. */
        val truncated: Boolean = false
    ) {
        val isEmpty: Boolean get() = strokes.isEmpty()
    }

    /**
     * Tunables for the four phases. All times in milliseconds.
     *
     * [pressMs] and [dragMs] are the two phases that delay the moment the stick
     * actually starts moving, and they are the entire latency budget for a
     * close range dodge. At the default 45 + 35 ms the stick does not begin to
     * move until 80 ms after dispatch, which is most of the window on a
     * point blank shot, so [urgent] shortens them for the cases that need it.
     */
    data class Timing(
        val pressMs: Long = 45L,
        val dragMs: Long = 35L,
        val releaseMs: Long = 16L,
        val minHoldMs: Long = 70L,
        val maxHoldMs: Long = 220L
    ) {
        init {
            require(pressMs > 0) { "pressMs must be positive" }
            require(dragMs > 0) { "dragMs must be positive" }
            require(releaseMs > 0) { "releaseMs must be positive" }
            require(minHoldMs > 0) { "minHoldMs must be positive" }
            require(maxHoldMs >= minHoldMs) { "maxHoldMs must be >= minHoldMs" }
        }

        /**
         * Timing for an imminent hit.
         *
         * Only [pressMs] and [dragMs] shrink, to just above [MIN_STROKE_MS], so
         * the stick starts moving after 38 ms instead of 80 ms. They cannot go
         * lower: below the platform touch slop the motion is delivered as a tap
         * and the dodge silently does nothing, which is the exact failure this
         * whole class exists to prevent.
         *
         * The hold window is deliberately left untouched. The hold is the only
         * phase that produces actual travel, and shortening it would buy nothing
         * that the press/drag change has not already bought: what an urgent dodge
         * needs is for movement to *start* sooner, and a longer hold only
         * overruns the impact, at which point the game stops listening anyway.
         */
        fun urgent(): Timing = copy(
            pressMs = 20L.coerceAtLeast(MIN_STROKE_MS),
            dragMs = 18L.coerceAtLeast(MIN_STROKE_MS)
        )

        /** Milliseconds before the stick starts to move. */
        fun onsetMs(): Long = pressMs + dragMs
    }

    /**
     * Android's own bound for a single stroke. A `StrokeDescription` shorter
     * than this is not guaranteed to be delivered as motion, which is exactly
     * the failure the previous 15 ms stroke had.
     */
    const val MIN_STROKE_MS: Long = 16L

    /**
     * Builds the chained press / drag / hold / release gesture.
     *
     * @param stickX stick centre, screen pixels.
     * @param stickY stick centre, screen pixels.
     * @param headingDeg escape heading, 0 = +X, 90 = screen down.
     * @param dragPx how far to pull the stick, normally a fraction of the
     *        stick's own radius. Clamped so the target stays inside the screen.
     * @param holdMs how long to keep the stick pulled, i.e. how long the
     *        brawler keeps travelling. Clamped into
     *        `[Timing.minHoldMs, Timing.maxHoldMs]`.
     * @param screenWidthPx / screenHeightPx used only for clamping.
     */
    fun plan(
        stickX: Float,
        stickY: Float,
        headingDeg: Float,
        dragPx: Float,
        holdMs: Long,
        screenWidthPx: Float,
        screenHeightPx: Float,
        timing: Timing = Timing()
    ): Plan {
        if (screenWidthPx <= 1f || screenHeightPx <= 1f) {
            return Plan(emptyList(), 0L, stickX, stickY, truncated = true)
        }

        val margin = min(screenWidthPx, screenHeightPx) * 0.02f
        val rad = Math.toRadians(headingDeg.toDouble())
        var drag = dragPx
        var targetX = stickX + (cos(rad) * drag).toFloat()
        var targetY = stickY + (sin(rad) * drag).toFloat()
        var truncated = false

        if (drag <= 0f) {
            // A zero length drag would be a tap, which is the original bug.
            return Plan(emptyList(), 0L, stickX, stickY, truncated = true)
        }

        val clampedX = targetX.coerceIn(margin, screenWidthPx - margin)
        val clampedY = targetY.coerceIn(margin, screenHeightPx - margin)
        if (clampedX != targetX || clampedY != targetY) {
            truncated = true
            targetX = clampedX
            targetY = clampedY
            // Keep the drag length honest after clamping, otherwise the
            // direction we report and the direction we execute diverge.
            drag = hypot(targetX - stickX, targetY - stickY)
        }
        if (drag < 1f) {
            return Plan(emptyList(), 0L, stickX, stickY, truncated = true)
        }

        val hold = holdMs.coerceIn(timing.minHoldMs, timing.maxHoldMs)
        val press = timing.pressMs.coerceAtLeast(MIN_STROKE_MS)
        val dragDur = timing.dragMs.coerceAtLeast(MIN_STROKE_MS)
        val release = timing.releaseMs.coerceAtLeast(MIN_STROKE_MS)

        val strokes = listOf(
            // 1. Press: a zero length path that simply holds the pointer down.
            Stroke(
                startX = stickX, startY = stickY,
                endX = stickX, endY = stickY,
                startTimeMs = 0L, durationMs = press, willContinue = true
            ),
            // 2. Drag: travel to the escape direction.
            Stroke(
                startX = stickX, startY = stickY,
                endX = targetX, endY = targetY,
                startTimeMs = press, durationMs = dragDur, willContinue = true
            ),
            // 3. Hold: stay at the target while the brawler travels. This is the
            //    phase that turns a twitch into an actual movement.
            Stroke(
                startX = targetX, startY = targetY,
                endX = targetX, endY = targetY,
                startTimeMs = press + dragDur, durationMs = hold, willContinue = true
            ),
            // 4. Release: ending the chain with willContinue = false lifts the
            //    pointer and drops the stick.
            Stroke(
                startX = targetX, startY = targetY,
                endX = targetX, endY = targetY,
                startTimeMs = press + dragDur + hold,
                durationMs = release, willContinue = false
            )
        )

        val total = press + dragDur + hold + release
        return Plan(strokes, total, targetX, targetY, truncated)
    }

    /**
     * Convenience wrapper that plans straight from a solved [CollisionSolver.Solution].
     */
    fun planFromSolution(
        solution: CollisionSolver.Solution,
        stickX: Float,
        stickY: Float,
        screenWidthPx: Float,
        screenHeightPx: Float,
        timing: Timing = Timing()
    ): Plan = plan(
        stickX = stickX,
        stickY = stickY,
        headingDeg = solution.escapeHeadingDeg,
        dragPx = solution.joystickDragPx,
        holdMs = solution.holdMs,
        screenWidthPx = screenWidthPx,
        screenHeightPx = screenHeightPx,
        timing = timing
    )

    /**
     * Total time the brawler is actually moving, i.e. the hold phase only.
     * Exposed so the HUD can show when a dodge is being clipped short.
     */
    fun effectiveTravelMs(plan: Plan): Long =
        plan.strokes.getOrNull(2)?.durationMs ?: 0L

    /** Rounds a plan's coordinates to the integer pixels the gesture API wants. */
    fun toIntegerPixels(plan: Plan): List<Stroke> = plan.strokes.map {
        it.copy(
            startX = it.startX.roundToInt().toFloat(),
            startY = it.startY.roundToInt().toFloat(),
            endX = it.endX.roundToInt().toFloat(),
            endY = it.endY.roundToInt().toFloat()
        )
    }
}
