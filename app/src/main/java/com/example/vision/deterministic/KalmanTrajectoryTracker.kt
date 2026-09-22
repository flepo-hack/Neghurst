package com.example.vision.deterministic

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Deterministic 2D Kalman Filter and Kinematic Trajectory Predictor.
 *
 * State Vector: x = [x, y, vx, vy]^T
 * Measurement Vector: z = [x, y]^T
 *
 * Distinguishes constant-velocity hostile projectiles from erratically accelerating players:
 * - Constant velocity & stable linear trajectory -> Projectile (bullet, rocket, bottle, bolt)
 * - Variable acceleration & directional shifts -> Player / Bot
 * - Predicts future positions (t + 100ms, t + 200ms, t + 300ms) for deterministic collision checks.
 */
class KalmanTrajectoryTracker {

    class TrackedTarget(
        var id: Int = 0,
        // State estimate: [x, y, vx, vy]
        var x: Float = 0f,
        var y: Float = 0f,
        var vx: Float = 0f,
        var vy: Float = 0f,

        // Covariance diagonal elements P:
        var p00: Float = 100f, // Var(x)
        var p11: Float = 100f, // Var(y)
        var p22: Float = 250f, // Var(vx)
        var p33: Float = 250f, // Var(vy)

        var speed: Float = 0f,
        var trajectoryConsistency: Float = 0.5f,
        var isProjectile: Boolean = false,
        var hits: Int = 1,
        var misses: Int = 0,
        var lastSeenTimestamp: Long = 0L
    ) {
        // Measurement noise variance
        private val rVar = 16f
        // Process noise variance
        private val qPos = 2.0f
        private val qVel = 45.0f

        /**
         * Kalman Time-Update (Prediction Step)
         */
        fun predict(dtSec: Float) {
            val dt = dtSec.coerceIn(0.008f, 0.050f)

            // State extrapolation: x = x + vx * dt, y = y + vy * dt
            x += vx * dt
            y += vy * dt

            // Covariance extrapolation: P = F * P * F^T + Q
            p00 += (2f * dt * p00 + dt * dt * p22) + qPos
            p11 += (2f * dt * p11 + dt * dt * p33) + qPos
            p22 += qVel
            p33 += qVel
        }

        /**
         * Kalman Measurement-Update (Correction Step)
         */
        fun update(measX: Float, measY: Float, dtSec: Float) {
            val dt = dtSec.coerceIn(0.008f, 0.050f)

            // Innovation (Residual)
            val yx = measX - x
            val yy = measY - y

            // Innovation Covariance: S = H * P * H^T + R
            val s00 = p00 + rVar
            val s11 = p11 + rVar

            // Kalman Gain: K = P * H^T * S^-1
            val k00 = p00 / s00
            val k11 = p11 / s11
            val k20 = (p22 * dt) / s00
            val k31 = (p33 * dt) / s11

            // State Correction
            x += k00 * yx
            y += k11 * yy

            if (hits == 1 && dt > 0.001f) {
                // Initialize velocity directly from initial displacement to avoid slow ramp-up
                vx = yx / dt
                vy = yy / dt
            } else {
                vx += k20 * yx
                vy += k31 * yy
            }

            // Covariance Correction: P = (I - K * H) * P
            p00 *= (1f - k00)
            p11 *= (1f - k11)
            p22 *= (1f - (k20 / dt.coerceAtLeast(0.001f)).coerceIn(0f, 0.8f))
            p33 *= (1f - (k31 / dt.coerceAtLeast(0.001f)).coerceIn(0f, 0.8f))

            speed = hypot(vx, vy)
            hits++
            misses = 0

            // Trajectory linearity check: does velocity stay constant?
            val instantVx = yx / dt
            val instantVy = yy / dt
            val velDev = hypot(instantVx - vx, instantVy - vy)
            val consistencyFactor = (1.0f - (velDev / (speed + 80f))).coerceIn(0.1f, 1.0f)
            trajectoryConsistency = trajectoryConsistency * 0.7f + consistencyFactor * 0.3f

            // Projectile classification threshold:
            // High speed (> 180 px/s) + high consistency (> 0.65)
            isProjectile = (speed > 180f && trajectoryConsistency > 0.62f) || (speed > 320f && hits >= 2)
        }

        /**
         * Extrapolates position forward in time: pos(t + futureDt)
         */
        fun predictPositionAt(futureSec: Float): Pair<Float, Float> {
            return Pair(x + vx * futureSec, y + vy * futureSec)
        }
    }

    private var nextTrackId = 1
    private val tracks = ArrayList<TrackedTarget>(16)

    fun getActiveTracks(): List<TrackedTarget> = tracks

    /**
     * Ingests detected moving centroids from the motion difference image D
     * and associates them with existing Kalman tracks.
     */
    fun processObservations(
        observedX: FloatArray,
        observedY: FloatArray,
        observedCount: Int,
        nowTimestamp: Long,
        dtSec: Float
    ) {
        // Step 1: Predict all existing tracks
        for (track in tracks) {
            track.predict(dtSec)
        }

        val maxGateDistanceSq = 180f * 180f
        val matchedObs = BooleanArray(observedCount)

        // Step 2: Associate observations with closest track (Nearest Neighbor Gating)
        for (track in tracks) {
            var bestDistSq = Float.MAX_VALUE
            var bestObsIdx = -1

            for (i in 0 until observedCount) {
                if (matchedObs[i]) continue
                val dx = observedX[i] - track.x
                val dy = observedY[i] - track.y
                val distSq = dx * dx + dy * dy

                if (distSq < maxGateDistanceSq && distSq < bestDistSq) {
                    bestDistSq = distSq
                    bestObsIdx = i
                }
            }

            if (bestObsIdx >= 0) {
                matchedObs[bestObsIdx] = true
                track.update(observedX[bestObsIdx], observedY[bestObsIdx], dtSec)
                track.lastSeenTimestamp = nowTimestamp
            } else {
                track.misses++
            }
        }

        // Step 3: Spawn new tracks for unassociated observations
        for (i in 0 until observedCount) {
            if (!matchedObs[i] && tracks.size < 12) {
                val newTrack = TrackedTarget(
                    id = nextTrackId++,
                    x = observedX[i],
                    y = observedY[i],
                    vx = 0f,
                    vy = 0f,
                    lastSeenTimestamp = nowTimestamp
                )
                tracks.add(newTrack)
            }
        }

        // Step 4: Prune dead tracks (missed > 6 frames or age > 250ms)
        tracks.removeAll { it.misses > 6 || (nowTimestamp - it.lastSeenTimestamp > 280L) }
    }

    fun reset() {
        tracks.clear()
        nextTrackId = 1
    }
}
