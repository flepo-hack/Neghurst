package com.example.vision

import kotlin.math.abs

/**
 * Decides whether a detected threat should be acted on *right now*.
 *
 * ## The bug this replaces
 *
 * The previous logic was a wall-clock cooldown: after a dodge it refused to
 * dispatch anything for 320 ms, and doubled that for a threat it thought it had
 * already answered. That is a blind window, and it is the direct cause of the
 * complaint that the brawler "walks into ammo while dodging".
 *
 * A dodge takes 80-350 ms of gesture time. For that whole span the analysis kept
 * running but the decision layer was mute, so:
 *
 *  * a **second** projectile arriving mid-dodge was ignored entirely;
 *  * a projectile the dodge did **not** clear was ignored, so the brawler kept
 *    holding the stick towards an escape that was not working;
 *  * a projectile that was going to land **during** the hold was ignored, which
 *    is exactly when re-aiming matters most.
 *
 * ## The rule
 *
 * The only hard gate is whether a gesture can physically be dispatched right now.
 * Everything else is per-threat bookkeeping, so a new threat is answered
 * immediately even while another dodge is in flight, and the same threat is only
 * re-answered when the new plan is genuinely different.
 *
 * This is a pure state machine with no Android dependency, so the whole policy is
 * unit testable, which the cooldown arithmetic never was.
 */
class DodgeDecisionState(
    /** A second gesture is refused while one is in flight. */
    private val canDispatch: () -> Boolean = { true },
    /**
     * Two headings count as the same escape when they differ by less than this.
     * A jitter of a few degrees from tracker noise is not a reason to spend a
     * gesture, but a genuinely different direction is.
     */
    private val headingToleranceDeg: Float = 20f,
    /**
     * Shortest gap between two dispatched gestures.
     *
     * Not a blanket cooldown: it only applies to a DIFFERENT plan. A new threat,
     * or a materially changed heading, still goes through immediately. What it
     * stops is re-spending a gesture every single frame on a plan that is
     * drifting a few degrees each frame, which is what happened without it: a
     * fast shot crosses the 90 px identity grid roughly every other frame, so
     * "same threat" was false most frames and a gesture was spent on each one.
     */
    private val minGapMs: Long = 140L
) {

    /** What we last committed to, so we can tell a repeat from a new plan. */
    data class Commitment(
        val trackKey: Float,
        val headingDeg: Float,
        val atMs: Long,
        val clearedCount: Int,
        val consideredCount: Int
    )

    var lastCommitment: Commitment? = null
        private set

    /** How many gestures were actually dispatched. For the HUD. */
    var dispatchedCount: Int = 0
        private set

    /** How many frames a live threat was seen but deliberately not answered. */
    var suppressedCount: Int = 0
        private set

    fun reset() {
        lastCommitment = null
    }

    /**
     * A stable identity for a threat across frames.
     *
     * The engine's track id is the right identity, but it renumbers whenever
     * tracks are pruned, so the nearest projectile's position is mixed in. A
     * threat that is still incoming does not move much between frames, so
     * rounding its position to a coarse grid gives an id that survives while it
     * matters and changes when it does not.
     */
    fun trackKeyFor(analysis: ScreenThreatDetector.Analysis): Float {
        val raw = analysis.raw.threatTrackId
        val gx = (analysis.raw.threatX / 90f)
        val gy = (analysis.raw.threatY / 90f)
        return raw.toFloat() * 1_000_003f + (gx.toInt() * 4096 + gy.toInt())
    }

    /**
     * @return true when the caller should dispatch a gesture now.
     */
    fun shouldDispatch(analysis: ScreenThreatDetector.Analysis, nowMs: Long): Boolean {
        if (!analysis.hasDodgeableThreat) return false

        // A live threat during a hold means the dodge is not enough, and the
        // only correct response is to correct the heading as soon as a gesture
        // can be delivered. Suppressing here is what let the brawler walk into
        // the shot it was already dodging.
        if (!canDispatch()) {
            suppressedCount++
            return false
        }

        val key = trackKeyFor(analysis)
        val heading = analysis.escape.escapeHeadingDeg
        val prev = lastCommitment

        if (prev == null) return commit(key, heading, nowMs, analysis)

        val sameThreat = prev.trackKey == key
        val sameHeading = abs(heading - prev.headingDeg) < headingToleranceDeg

        if (sameThreat && sameHeading) {
            // Already committed to this exact escape. Repeating it would spend a
            // gesture to achieve nothing and would cancel the movement already
            // in progress.
            suppressedCount++
            return false
        }

        // A different threat, or a materially different direction. If the plan
        // changed because the threat DRIFTED rather than because it is new, hold
        // it briefly rather than chasing it every frame; a genuinely new threat
        // skips this entirely.
        val drifted = sameThreat && abs(heading - prev.headingDeg) < 45f
        if (drifted && nowMs - prev.atMs < minGapMs) {
            suppressedCount++
            return false
        }

        return commit(key, heading, nowMs, analysis)
    }

    /**
     * Called when a dispatched gesture did not reach the system, so the plan is
     * retried next frame instead of being counted as handled.
     */
    fun onDispatchFailed() {
        lastCommitment = null
        // The commitment is rolled back, so the counter has to be too. Otherwise a
        // permanently unusable plan (uncalibrated anchors return an empty plan
        // every frame) inflates "dodges" forever while nothing was ever sent.
        if (dispatchedCount > 0) dispatchedCount--
    }

    /**
     * Records the intent to dodge. The caller must call [onDispatchFailed] if the
     * gesture does not actually go out, which also decrements the counter.
     */
    private fun commit(
        key: Float,
        heading: Float,
        nowMs: Long,
        analysis: ScreenThreatDetector.Analysis
    ): Boolean {
        lastCommitment = Commitment(
            trackKey = key,
            headingDeg = heading,
            atMs = nowMs,
            clearedCount = analysis.escape.projectilesCleared,
            consideredCount = analysis.escape.projectilesConsidered
        )
        dispatchedCount++
        return true
    }

    /** Human readable state for the HUD. */
    fun describe(): String {
        val c = lastCommitment
        return if (c == null) {
            "no dodge committed"
        } else {
            "last: ${c.headingDeg.toInt()}deg cleared ${c.clearedCount}/${c.consideredCount}"
        }
    }
}
