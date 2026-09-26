package com.example.vision.nativebridge

/**
 * Complete tunable set for the native pipeline.
 *
 * Every distance and radius that used to be a hard-coded pixel constant is
 * expressed either as a fraction of the screen or as a real quantity, so the
 * same profile behaves identically on a 720p phone and on a 1440p one.
 *
 * The field order in [writeInto] is load bearing: it is the wire format shared
 * with `nativeConfigure`. Adding a field means adding it to the end of both.
 */
data class VisionTuning(
    // --- global motion estimation ---
    val motionMaxShiftHalfRes: Int = 24,
    val motionMinConfidence: Float = 0.06f,
    val fineRefineRadius: Int = 2,

    // --- difference / noise ---
    val diffNoiseFloor: Int = 18,
    val diffStrongThreshold: Int = 40,

    // --- blob filtering (grid cells) ---
    val blobMinArea: Int = 2,
    val blobMaxArea: Int = 400,
    val blobMinFill: Float = 0.16f,
    val blobMinMeanStrength: Float = 22f,

    // --- player detection ---
    val playerMinComponentArea: Int = 6,
    val playerMaxComponentArea: Int = 900,
    val playerMinGreenScore: Float = 70f,
    val playerMinCompactness: Float = 0.22f,
    val playerMaxAspect: Float = 4.5f,
    val playerGateGridUnits: Float = 46f,
    val playerAnchorLocked: Boolean = false,
    val playerAnchorX: Float = 0.5f,
    val playerAnchorY: Float = 0.5f,

    // --- enemy detection ---
    val enemyMinComponentArea: Int = 4,
    val enemyMaxComponentArea: Int = 700,
    val enemyMinRedScore: Float = 64f,
    val enemyMinCompactness: Float = 0.18f,
    val maxEnemies: Int = 10,
    val enemyAvoidRadiusNorm: Float = 0.11f,

    // --- tracking ---
    val maxTracks: Int = 16,
    val maxObservations: Int = 48,
    val trackGatePixels: Float = 90f,
    val trackProcessPos: Float = 3f,
    val trackProcessVel: Float = 240f,
    val trackMeasureNoise: Float = 260f,
    val trackMaxMisses: Int = 5,
    val trackMinHitsForProjectile: Int = 3,
    val projectileMinSpeedNorm: Float = 0.22f,
    val projectileMinStraightness: Float = 0.55f,

    // --- collision solving ---
    val playerRadiusNorm: Float = 0.052f,
    val projectileRadiusNorm: Float = 0.011f,
    val reactionHorizonSec: Float = 0.42f,
    val lethalTtiSec: Float = 0.17f,
    val imminentTtiSec: Float = 0.29f,
    val escapeCandidateCount: Int = 24,
    /** Required perpendicular clearance, as a fraction of screen width. */
    val escapeStepNorm: Float = 0.070f,
    /** Brawler top speed, screen widths per second. */
    val characterSpeedNorm: Float = 0.67f
) {
    fun writeInto(dst: FloatArray) {
        require(dst.size >= NativeVisionEngine.CONFIG_FLOATS) {
            "config buffer must hold at least ${NativeVisionEngine.CONFIG_FLOATS} floats, " +
                "got ${dst.size}"
        }
        var i = 0
        dst[i++] = motionMaxShiftHalfRes.toFloat()
        dst[i++] = motionMinConfidence
        dst[i++] = fineRefineRadius.toFloat()
        dst[i++] = diffNoiseFloor.toFloat()
        dst[i++] = diffStrongThreshold.toFloat()
        dst[i++] = blobMinArea.toFloat()
        dst[i++] = blobMaxArea.toFloat()
        dst[i++] = blobMinFill
        dst[i++] = blobMinMeanStrength
        dst[i++] = playerMinComponentArea.toFloat()
        dst[i++] = playerMaxComponentArea.toFloat()
        dst[i++] = playerMinGreenScore
        dst[i++] = playerMinCompactness
        dst[i++] = playerMaxAspect
        dst[i++] = playerGateGridUnits
        dst[i++] = if (playerAnchorLocked) 1f else 0f
        dst[i++] = playerAnchorX
        dst[i++] = playerAnchorY
        dst[i++] = enemyMinComponentArea.toFloat()
        dst[i++] = enemyMaxComponentArea.toFloat()
        dst[i++] = enemyMinRedScore
        dst[i++] = enemyMinCompactness
        dst[i++] = maxEnemies.toFloat()
        dst[i++] = enemyAvoidRadiusNorm
        dst[i++] = maxTracks.toFloat()
        dst[i++] = maxObservations.toFloat()
        dst[i++] = trackGatePixels
        dst[i++] = trackProcessPos
        dst[i++] = trackProcessVel
        dst[i++] = trackMeasureNoise
        dst[i++] = trackMaxMisses.toFloat()
        dst[i++] = trackMinHitsForProjectile.toFloat()
        dst[i++] = projectileMinSpeedNorm
        dst[i++] = projectileMinStraightness
        dst[i++] = playerRadiusNorm
        dst[i++] = projectileRadiusNorm
        dst[i++] = reactionHorizonSec
        dst[i++] = lethalTtiSec
        dst[i++] = imminentTtiSec
        dst[i++] = escapeCandidateCount.toFloat()
        dst[i++] = escapeStepNorm
        dst[i] = characterSpeedNorm
    }
}

/**
 * A normalised region of the captured screen that must be ignored by detection.
 * Rendera's own overlay is captured by MediaProjection just like the game is,
 * so its pixels have to be excluded explicitly.
 */
data class ScreenRegion(
    val centerX: Float,
    val centerY: Float,
    val halfWidth: Float,
    val halfHeight: Float,
    val enabled: Boolean = true
)

/**
 * Severity of a solved collision, mirroring the native `rendera::ThreatSeverity`
 * wire codes.
 *
 * This enum exists only at the JNI boundary. Everything above the boundary uses
 * [com.example.model.ThreatLevel], and [toModel] is the single conversion, so
 * the two can never be confused for one another at a call site.
 */
enum class ThreatSeverity(val code: Int) {
    SAFE(0),
    WARNING(1),
    IMMINENT_DANGER(2),
    LETHAL(3);

    fun toModel(): com.example.model.ThreatLevel = when (this) {
        SAFE -> com.example.model.ThreatLevel.SAFE
        WARNING -> com.example.model.ThreatLevel.WARNING
        IMMINENT_DANGER -> com.example.model.ThreatLevel.IMMINENT_DANGER
        LETHAL -> com.example.model.ThreatLevel.LETHAL
    }

    companion object {
        fun fromCode(code: Int): ThreatSeverity = when (code) {
            3 -> LETHAL
            2 -> IMMINENT_DANGER
            1 -> WARNING
            // An out of range code degrades to SAFE, never to a scary level.
            else -> SAFE
        }
    }
}

/**
 * Immutable snapshot of one native pipeline run.
 *
 * This is constructed from the native output buffers, which are reused, so the
 * values are copied out by value and the object stays valid after the next
 * frame.
 */
class VisionResult(
    f: FloatArray,
    i: IntArray
) {
    // --- global motion ---
    val motionDxGrid: Float = f[0]
    val motionDyGrid: Float = f[1]
    val motionConfidence: Float = f[2]
    val motionValid: Boolean = f[3] > 0.5f

    // --- player ---
    val playerX: Float = f[4]
    val playerY: Float = f[5]
    val playerVx: Float = f[6]
    val playerVy: Float = f[7]
    val playerGreenness: Float = f[8]
    val playerVisible: Boolean = f[9] > 0.5f
    val playerLocked: Boolean = f[10] > 0.5f
    val playerFramesSinceSeen: Int = f[11].toInt()
    val playerComponentArea: Int = i[7]

    // --- threat ---
    val timeToImpactSec: Float = f[12]
    val threatX: Float = f[13]
    val threatY: Float = f[14]
    val threatVx: Float = f[15]
    val threatVy: Float = f[16]
    val threatSpeed: Float = f[17]
    /** 0..1 straightness of the tracked projectile's velocity. */
    val threatConfidence: Float = f[18]
    val escapeHeadingDeg: Float = f[19]
    val escapeDirX: Float = f[20]
    val escapeDirY: Float = f[21]
    val escapeStepPixels: Float = f[22]
    val escapeTravelMs: Float = f[23]

    val severityRaw: Int = i[0]
    val threatValid: Boolean = i[1] != 0
    val blobCount: Int = i[2]
    val trackCount: Int = i[3]
    val projectileCount: Int = i[4]
    val threatTrackId: Int = i[5]
    val escapeSufficient: Boolean = i[6] != 0

    val severity: ThreatSeverity get() = ThreatSeverity.fromCode(severityRaw)

    val timeToImpactMs: Long get() = (timeToImpactSec * 1000f).toLong()
}
