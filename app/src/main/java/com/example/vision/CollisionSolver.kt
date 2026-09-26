package com.example.vision

import com.example.model.ThreatLevel
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure-Kotlin collision geometry. No Android, no allocation in the hot path.
 *
 * This is the single authority for "will I be hit, and what do I do about it".
 * The native engine implements the identical formulation, and this
 * implementation is what the unit tests pin the maths to, so the two cannot
 * silently disagree about signs, units or conventions.
 *
 * Units are explicit: positions and velocities are **pixels**, radii are
 * **pixels**, and every caller supplied size is normalised (a fraction of the
 * screen) so one profile behaves identically on any device.
 *
 * Heading convention, used everywhere in this project: degrees where 0 = +X
 * (screen right) and 90 = +Y (screen **down**), matching `atan2(y, x)`.
 */
object CollisionSolver {

    /** A projectile as seen by the solver. */
    data class Projectile(
        val x: Float,
        val y: Float,
        val vx: Float,
        val vy: Float,
        /** 0..1, how constant the velocity has been. */
        val confidence: Float = 1f
    ) {
        val speed: Float get() = hypot(vx, vy)
    }

    /** A world position the escape must not run into. */
    data class AvoidPoint(val x: Float, val y: Float)

    /**
     * What to actually do about a detected threat.
     *
     * Note the split between the three lengths, which is the part that is easy
     * to get wrong: dragging the joystick does **not** move the brawler a
     * matching distance. The drag only sets a *direction*; the brawler then
     * travels `characterSpeed * holdDuration`. So the plan is a direction, a
     * drag radius and a hold time, and the "did it work" question is answered by
     * [requiredTravelPx] against [expectedTravelPx].
     */
    data class Solution(
        val hasThreat: Boolean = false,
        val severity: ThreatLevel = ThreatLevel.SAFE,
        val timeToImpactSec: Float = 0f,
        val projectile: Projectile? = null,
        val missDistancePx: Float = 0f,
        /** 0 = +X, 90 = screen down. */
        val escapeHeadingDeg: Float = 0f,
        val escapeDirX: Float = 0f,
        val escapeDirY: Float = 0f,
        /** How far to drag the joystick, already clamped to the stick radius. */
        val joystickDragPx: Float = 0f,
        /** How long to hold the stick down, already clamped to a sane window. */
        val holdMs: Long = 0L,
        /** Perpendicular displacement needed to clear the projectile. */
        val requiredTravelPx: Float = 0f,
        /** Displacement the brawler can actually achieve within the hold. */
        val expectedTravelPx: Float = 0f,
        /** False when the brawler physically cannot get clear in time. */
        val escapeIsSufficient: Boolean = true
    ) {
        val timeToImpactMs: Long get() = (timeToImpactSec * 1000f).roundToInt().toLong()
    }

    /**
     * Seconds until [p] passes closest to `(px, py)`, or -1 when the closest
     * approach is behind the projectile or outside [horizonSec].
     *
     * `r = player - projectile`; a positive dot product means the player lies
     * ahead of the projectile along its velocity, which is the only case that
     * can ever be a collision.
     */
    fun timeToClosestApproach(
        px: Float,
        py: Float,
        p: Projectile,
        horizonSec: Float
    ): Float {
        val vSq = p.vx * p.vx + p.vy * p.vy
        if (vSq < 1e-6f) return -1f
        val rx = px - p.x
        val ry = py - p.y
        val dot = rx * p.vx + ry * p.vy
        if (dot <= 0f) return -1f
        val t = dot / vSq
        return if (t in 0f..horizonSec) t else -1f
    }

    /** Distance between the player and the projectile's position at [tSec]. */
    fun missDistanceAt(px: Float, py: Float, p: Projectile, tSec: Float): Float =
        hypot(px - (p.x + p.vx * tSec), py - (p.y + p.vy * tSec))

    /**
     * Solves a whole frame.
     *
     * The escape heading is chosen by scoring a full circle of candidate
     * headings rather than only the two perpendicular ones. That distinction
     * matters: a step orthogonal to the flight line leaves the time of closest
     * approach unchanged, so **both** perpendicular directions produce exactly
     * the same miss distance. A `+/-90 degree` rule is therefore a coin flip
     * between "straight into the enemy" and "into a wall", and it is the
     * reason the previous version felt random. The score breaks that tie on
     * reachable, safe ground.
     */
    fun solve(
        playerX: Float,
        playerY: Float,
        playerRadiusPx: Float,
        projectiles: List<Projectile>,
        screenWidthPx: Float,
        screenHeightPx: Float,
        reactionHorizonSec: Float = 0.42f,
        lethalTtiSec: Float = 0.17f,
        imminentTtiSec: Float = 0.29f,
        safetyMarginPx: Float = playerRadiusPx * 0.35f,
        joystickRadiusPx: Float = screenWidthPx * 0.13f,
        joystickDragFactor: Float = 0.92f,
        characterSpeedPxPerSec: Float = screenWidthPx * 0.67f,
        minHoldMs: Long = 70L,
        maxHoldMs: Long = 220L,
        borderMarginXPx: Float = screenWidthPx * 0.06f,
        borderMarginYPx: Float = screenHeightPx * 0.10f,
        enemies: List<AvoidPoint> = emptyList(),
        enemyAvoidRadiusPx: Float = screenWidthPx * 0.11f,
        candidateCount: Int = 24
    ): Solution {
        if (projectiles.isEmpty() || playerRadiusPx <= 0f) return Solution()

        var best: Projectile? = null
        var bestTti = Float.MAX_VALUE
        var bestMiss = Float.MAX_VALUE

        for (p in projectiles) {
            if (p.speed < 1f) continue
            val t = timeToClosestApproach(playerX, playerY, p, reactionHorizonSec)
            if (t < 0f) continue
            val miss = missDistanceAt(playerX, playerY, p, t)
            if (miss >= playerRadiusPx) continue
            if (t < bestTti) {
                bestTti = t
                bestMiss = miss
                best = p
            }
        }

        val projectile = best ?: return Solution()

        val severity = when {
            bestTti <= lethalTtiSec -> ThreatLevel.LETHAL
            bestTti <= imminentTtiSec -> ThreatLevel.IMMINENT_DANGER
            else -> ThreatLevel.WARNING
        }

        // Perpendicular displacement needed to turn a hit into a miss.
        val requiredTravel = (playerRadiusPx + safetyMarginPx).coerceAtLeast(1f)

        val heading = chooseEscapeHeading(
            playerX = playerX,
            playerY = playerY,
            projectile = projectile,
            stepPx = requiredTravel,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            borderMarginXPx = borderMarginXPx,
            borderMarginYPx = borderMarginYPx,
            enemies = enemies,
            enemyAvoidRadiusPx = enemyAvoidRadiusPx,
            candidateCount = candidateCount
        )

        // Dodge late, because every millisecond held is a millisecond the
        // brawler is exposed, but leave a margin for the dispatch itself.
        val availableMs = (bestTti * 1000f * 0.78f).roundToInt()
        val holdMs = availableMs.toLong().coerceIn(minHoldMs, maxHoldMs)

        val expectedTravel = if (characterSpeedPxPerSec > 1f) {
            characterSpeedPxPerSec * (holdMs / 1000f)
        } else {
            0f
        }

        val dragPx = (joystickRadiusPx * joystickDragFactor.coerceIn(0.1f, 1f))
            .coerceAtLeast(1f)

        return Solution(
            hasThreat = true,
            severity = severity,
            timeToImpactSec = bestTti,
            projectile = projectile,
            missDistancePx = bestMiss,
            escapeHeadingDeg = heading,
            escapeDirX = cos(Math.toRadians(heading.toDouble())).toFloat(),
            escapeDirY = sin(Math.toRadians(heading.toDouble())).toFloat(),
            joystickDragPx = dragPx,
            holdMs = holdMs,
            requiredTravelPx = requiredTravel,
            expectedTravelPx = expectedTravel,
            escapeIsSufficient = expectedTravel >= requiredTravel
        )
    }

    /**
     * Scores every heading on a circle and returns the best one.
     *
     * For a step `d`, the perpendicular offset from the flight line afterwards
     * is `|perp + d * (u . n)|`, with `n` the flight-line normal and `u` the
     * heading. Because `n` is orthogonal to the velocity, the time of closest
     * approach is unchanged, so maximising this maximises the real miss
     * distance. The remaining terms are pure feasibility.
     */
    fun chooseEscapeHeading(
        playerX: Float,
        playerY: Float,
        projectile: Projectile,
        stepPx: Float,
        screenWidthPx: Float,
        screenHeightPx: Float,
        borderMarginXPx: Float = screenWidthPx * 0.06f,
        borderMarginYPx: Float = screenHeightPx * 0.10f,
        enemies: List<AvoidPoint> = emptyList(),
        enemyAvoidRadiusPx: Float = screenWidthPx * 0.11f,
        candidateCount: Int = 24
    ): Float {
        val vLen = projectile.speed
        if (vLen < 1e-3f) return 0f
        val uhx = projectile.vx / vLen
        val uhy = projectile.vy / vLen
        val nx = -uhy
        val ny = uhx

        val rx = playerX - projectile.x
        val ry = playerY - projectile.y
        val perpSigned = rx * nx + ry * ny

        val n = max(4, candidateCount)
        var bestScore = -Float.MAX_VALUE
        var bestDeg = 0f

        for (i in 0 until n) {
            val ang = 2.0 * Math.PI * i / n
            val dx = cos(ang).toFloat()
            val dy = sin(ang).toFloat()

            var score = abs(perpSigned + stepPx * (dx * nx + dy * ny))

            // The destination must stay on screen with margin. The player keeps
            // moving for the whole hold, so the reach is scaled accordingly.
            val reach = (stepPx + screenWidthPx * 0.10f).coerceAtLeast(stepPx)
            val tx = playerX + dx * reach
            val ty = playerY + dy * reach
            if (min(tx, screenWidthPx - tx) < borderMarginXPx ||
                min(ty, screenHeightPx - ty) < borderMarginYPx
            ) {
                score -= screenWidthPx * 0.30f
            }

            // `dx * uhx + dy * uhy` is how much of the step runs ALONG the
            // projectile's velocity. Negative means back toward where the
            // projectile is coming from, i.e. into its path, so it is ADDED, which
            // penalises it.
            //
            // It is weighted against `stepPx`, not against the screen width, so it
            // stays a tie-breaker. Scaling it by the screen made it larger than the
            // clearance term itself, and the planner would then abandon the
            // perpendicular entirely and settle 45 degrees off it, which is exactly
            // the "escape" the scoring is supposed to be refining.
            score += (dx * uhx + dy * uhy) * stepPx * 0.25f

            for (e in enemies) {
                val ex = e.x - playerX
                val ey = e.y - playerY
                val along = ex * dx + ey * dy
                if (along < 0f || along > reach) continue
                val lateral = abs(ex * ny - ey * nx)
                if (lateral < enemyAvoidRadiusPx) {
                    score -= (enemyAvoidRadiusPx - lateral) * 1.5f
                }
            }

            if (score > bestScore) {
                bestScore = score
                bestDeg = ((Math.toDegrees(ang) + 360.0) % 360.0).toFloat()
            }
        }
        return bestDeg
    }

    /** Trajectory heading in degrees, 0 = +X, 90 = screen down. */
    fun trajectoryHeadingDeg(p: Projectile): Float =
        ((Math.toDegrees(atan2(p.vy, p.vx).toDouble()) + 360.0) % 360.0).toFloat()

    /** Smallest angle between two headings, in `[0, 180]`. */
    fun angleBetweenDeg(a: Float, b: Float): Float {
        val diff = ((a - b) % 360f + 360f) % 360f
        return if (diff > 180f) 360f - diff else diff
    }

    /** True when the two headings are within [toleranceDeg] of perpendicular. */
    fun isPerpendicular(a: Float, b: Float, toleranceDeg: Float = 20f): Boolean =
        abs(angleBetweenDeg(a, b) - 90f) <= toleranceDeg
}
