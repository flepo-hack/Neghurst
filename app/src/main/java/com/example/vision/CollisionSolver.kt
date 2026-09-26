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
        val escapeIsSufficient: Boolean = true,
        /** How many incoming projectiles this heading actually avoids. */
        val projectilesCleared: Int = 0,
        /** How many incoming projectiles are considered. */
        val projectilesConsidered: Int = 0,
        /** True when the chosen heading was worse than some other one. */
        val partialEscape: Boolean = false
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

        // Everything on a collision course, not just the earliest. A shotgun
        // spread or a Rosa volley is several pellets at once, and dodging only
        // the nearest one walks the brawler into the rest.
        val live = projectiles.filter { p ->
            p.speed >= 1f &&
                timeToClosestApproach(playerX, playerY, p, reactionHorizonSec) >= 0f &&
                missDistanceAt(
                    playerX, playerY, p,
                    timeToClosestApproach(playerX, playerY, p, reactionHorizonSec)
                ) < playerRadiusPx
        }
        if (live.isEmpty()) return Solution()

        // The one that decides the severity and the hold time.
        var best: Projectile? = null
        var bestTti = Float.MAX_VALUE
        var bestMiss = Float.MAX_VALUE
        for (p in live) {
            val t = timeToClosestApproach(playerX, playerY, p, reactionHorizonSec)
            if (t >= 0f && t < bestTti) {
                bestTti = t
                bestMiss = missDistanceAt(playerX, playerY, p, t)
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
            candidateCount = candidateCount,
            playerRadiusPx = playerRadiusPx,
            reactionHorizonSec = reactionHorizonSec,
            additionalThreats = live
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

        val destX = playerX + cos(Math.toRadians(heading.toDouble())).toFloat() * requiredTravel
        val destY = playerY + sin(Math.toRadians(heading.toDouble())).toFloat() * requiredTravel
        var cleared = 0
        for (p in live) {
            val t = timeToClosestApproach(destX, destY, p, reactionHorizonSec)
            if (t < 0f || missDistanceAt(destX, destY, p, t) >= playerRadiusPx) cleared++
        }

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
            escapeIsSufficient = expectedTravel >= requiredTravel,
            projectilesCleared = cleared,
            projectilesConsidered = live.size,
            // Reporting a dodge as successful when a pellet is still going to
            // land is the single most damaging kind of quiet lie here: the
            // caller would believe it was safe and stop re-evaluating.
            partialEscape = cleared < live.size
        )
    }

    /**
     * Scores every heading against **every** live threat and returns the best.
     *
     * A single-threat score is not enough for a burst. A shotgun spread gives
     * several pellets on a collision course at once, and the heading that
     * clears the nearest one very often walks straight into the second. The
     * primary objective is therefore the number of pellets avoided, weighted by
     * how soon each one arrives, and the geometric clearance is the tie break
     * among headings that avoid the same number.
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
        candidateCount: Int = 24,
        playerRadiusPx: Float = 0f,
        reactionHorizonSec: Float = 0.42f,
        additionalThreats: List<Projectile>? = null
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

        // Every threat on a collision course. The caller's `projectile` is always
        // in here, so a single-threat caller behaves exactly as it always did.
        val threats = buildList {
            add(projectile)
            if (additionalThreats != null) {
                for (p in additionalThreats) {
                    if (p === projectile) continue
                    if (p.speed < 1f) continue
                    if (timeToClosestApproach(playerX, playerY, p, reactionHorizonSec) >= 0f) {
                        add(p)
                    }
                }
            }
        }
        val hitR = if (playerRadiusPx > 0f) playerRadiusPx else stepPx

        for (i in 0 until n) {
            val ang = 2.0 * Math.PI * i / n
            val dx = cos(ang).toFloat()
            val dy = sin(ang).toFloat()

            // --- primary objective: how many incoming shots does this clear? ---
            // A shot counts as cleared when, from the destination, it either has
            // no collision course any more or its closest approach lands outside
            // the hitbox. Each cleared shot is worth more the sooner it was going
            // to arrive, so saving the lethal pellet outranks a distant one.
            //
            // Skipped for a single threat, where the geometry below is already
            // the exact answer.
            var clearedCount = 0
            var deferredCount = 0
            var urgencyWeight = 0f
            if (threats.size > 1) {
                val destX = playerX + dx * stepPx
                val destY = playerY + dy * stepPx
                for (p in threats) {
                    val t = timeToClosestApproach(destX, destY, p, reactionHorizonSec)
                    if (t >= 0f && missDistanceAt(destX, destY, p, t) >= hitR) {
                        // Genuinely avoided: it still arrives, but it misses.
                        clearedCount++
                        // 1/t weights by how urgent it was: a shot 100 ms out is
                        // worth ten times one 1000 ms out.
                        urgencyWeight += 1f / (0.05f + maxOf(t, 0.05f))
                    } else if (t < 0f) {
                        // Deferred, not avoided. The heading pushed it past the
                        // reaction horizon, so there will be another chance to
                        // deal with it. Counting this as a clear would let the
                        // planner score "just wait" equal to "actually dodge", and
                        // the first thing to expire is the window the next dodge
                        // needs.
                        deferredCount++
                        urgencyWeight += 0.25f / (0.05f + reactionHorizonSec)
                    }
                }
            }

            // --- secondary: geometric clearance for the primary threat ---
            //
            // clearedCount dominates at 1000 per shot, so a heading that saves
            // the brawler always beats one that does not, however good its
            // geometry. The geometric term is weighted 0.5, and that ratio to the
            // "along the flight line" term below is load bearing: collapsing it to
            // a negligible weight lets the along term win every single-threat
            // comparison, and the escape then runs *along* the bullet's path
            // instead of away from it.
            // A real clear is worth 1000; a deferral 120. The ratio is chosen so
            // that even the worst possible heading, which defers all eight
            // tracked projectiles, scores 960 and can never outrank a heading
            // that actually saves the brawler. At 400 per deferral the eight of
            // them reached 3200 and "just wait" beat "dodge".
            var score = clearedCount * 1000f + deferredCount * 120f + urgencyWeight
            score += abs(perpSigned + stepPx * (dx * nx + dy * ny)) * 0.5f

            // The destination must stay on screen with margin. The player keeps
            // moving for the whole hold, so the reach is scaled accordingly.
            val reach = (stepPx + screenWidthPx * 0.10f).coerceAtLeast(stepPx)
            val tx = playerX + dx * reach
            val ty = playerY + dy * reach
            if (min(tx, screenWidthPx - tx) < borderMarginXPx ||
                min(ty, screenHeightPx - ty) < borderMarginYPx
            ) {
                // Walking into the border is a death. 1600 sits strictly between
                // one clear (1000) and two (2000), so a heading that saves the
                // brawler from a burst is still rejected if it is off the map,
                // while a genuine single clear still beats a border.
                score -= 1600f
            }

            // An earlier version added a term here preferring headings that run
            // *along* the projectile's velocity, on the theory that staying ahead
            // of it is better than moving away from its line. Measurement showed
            // it is strictly a cost: it pulled the escape 15-30 degrees off
            // perpendicular, and every degree off perpendicular is a degree of
            // real miss distance given up for a benefit that has no geometric
            // meaning. The perpendicular maximises clearance, so it stands alone.

            for (e in enemies) {
                val ex = e.x - playerX
                val ey = e.y - playerY

                // Repel, not just block. The lateral test below stops a heading
                // that walks INTO an enemy, but with nothing to block it happily
                // chose to step TOWARD one: with a symmetric choice available and
                // an enemy 300 px behind, it picked the direction that closed
                // the gap. Approaching an enemy is punished in proportion to how
                // close it is and how directly we close on it.
                //
                // Weighted by the avoid radius and capped well below a real
                // multi-shot clear (1000) or the map border (1600), so this can
                // only ever break the tie between the two equally valid
                // perpendiculars. It can never trade a saved brawler for a
                // comfortable distance from an enemy.
                val eDist = hypot(ex, ey)
                if (eDist > 1e-3f) {
                    val approach = (dx * ex + dy * ey) / eDist
                    val proximity =
                        (1f - (eDist - enemyAvoidRadiusPx) / enemyAvoidRadiusPx).coerceIn(0f, 1f)
                    // Scaled by the distance the step actually CLOSES, not by the
                    // avoid radius. Scaling by the radius made the term worth up
                    // to 63, which outweighed the clearance term and tilted the
                    // escape 45 degrees off perpendicular for a sideways enemy.
                    // At 0.15 the term is at most 11 for a 135 px step: enough to
                    // break the up/down tie, where the two perpendiculars score
                    // identically, and far too small to trade real clearance.
                    score -= approach * stepPx * 0.15f * proximity
                }

                // `along` is the enemy's distance down our escape path.
                val along = ex * dx + ey * dy
                if (along < 0f || along > reach) continue
                // `lateral` must be the enemy's distance from OUR PATH, so it is
                // the cross product of the enemy's offset with the HEADING. The
                // previous version crossed it with the flight-line normal instead,
                // which made `lateral` a constant for every heading: an enemy
                // standing beside the brawler was scored as blocking all of them
                // equally, and once the penalty weight was raised the planner could
                // be pushed onto a heading with zero clearance, i.e. into the shot.
                val lateral = abs(ex * dy - ey * dx)
                if (lateral < enemyAvoidRadiusPx) {
                    // Running into an enemy is a death, so this has to dominate
                    // the geometric tie break. It is expressed in the same
                    // units as `avoidedCount * 1000`, and a full overlap costs
                    // 3000, i.e. it can outvote clearing two shots.
                    score -= (enemyAvoidRadiusPx - lateral) * 15f
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
