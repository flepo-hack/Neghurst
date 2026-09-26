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
    //
    // `*Score` values are opponent signals (G - max(R,B) and R - max(G,B)) scaled
    // to 0..255, so they are hue correct and scale free. The saturation gate is
    // the second half of the discrimination: Brawl Stars' selection ring scores
    // about 240 while grass of the same hue sits near 80, so hue alone cannot
    // separate them.
    val playerMinComponentArea: Int = 8,
    val playerMaxComponentArea: Int = 900,
    val playerMinGreenScore: Float = 58f,
    val playerMinSaturation: Float = 105f,
    val playerMinCompactness: Float = 0.20f,
    val playerMaxAspect: Float = 4.5f,
    val playerGateGridUnits: Float = 34f,
    val playerAnchorLocked: Boolean = false,
    val playerAnchorX: Float = 0.5f,
    val playerAnchorY: Float = 0.5f,

    // --- enemy detection ---
    val enemyMinComponentArea: Int = 5,
    val enemyMaxComponentArea: Int = 700,
    val enemyMinRedScore: Float = 55f,
    val enemyMinSaturation: Float = 105f,
    val enemyMinCompactness: Float = 0.18f,
    val maxEnemies: Int = 10,
    val enemyAvoidRadiusNorm: Float = 0.11f,

    // --- tracking ---

    // --- own-effect rejection ---
    //
    // Motion on top of the brawler is a splash, a rustle or dust, not a
    // projectile, and the brawler used to dodge its own footsteps. Exposed here
    // because on an unfamiliar map the first thing to need tuning is how far
    // "on top of the brawler" reaches.
    val ownEffectRadiusNorm: Float = 0.055f,
    val ownEffectTrackNorm: Float = 0.085f,
    val ownEffectMinHits: Int = 2,
    val maxTracks: Int = 16,
    val maxObservations: Int = 48,
    // --- object classification (label only; does not affect threat detection) ---
    val ballMinArea: Int = 14,
    val bouncerMaxArea: Int = 26,
    val bouncerDotThreshold: Float = -0.55f,
    val kindMinHitsBeforeLabelling: Int = 3,
    val trackGatePixels: Float = 90f,
    val trackProcessPos: Float = 3f,
    val trackProcessVel: Float = 240f,
    val trackMeasureNoise: Float = 260f,
    val trackMaxMisses: Int = 5,
    // 2, not 3. At 60 fps a third observation costs 50 ms, and close range
    // bullets in Brawl Stars arrive in well under 100 ms.
    val trackMinHitsForProjectile: Int = 2,
    val projectileMinSpeedNorm: Float = 0.22f,
    val projectileMinStraightness: Float = 0.55f,

    // --- collision solving ---
    val playerRadiusNorm: Float = 0.052f,
    val projectileRadiusNorm: Float = 0.011f,
    val reactionHorizonSec: Float = 0.42f,
    /**
     * A shot closer than this in seconds is not treated as a threat yet. At zero
     * a shot already on top of the player counts, which is the right default,
     * but a small positive value suppresses the degenerate case of reacting to a
     * projectile that is already inside the brawler's own sprite.
     */
    val minTtiSec: Float = 0f,
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
        dst[i++] = diffNoiseFloor
        dst[i++] = diffStrongThreshold
        dst[i++] = blobMinArea.toFloat()
        dst[i++] = blobMaxArea.toFloat()
        dst[i++] = blobMinFill
        dst[i++] = blobMinMeanStrength
        dst[i++] = playerMinComponentArea.toFloat()
        dst[i++] = playerMaxComponentArea.toFloat()
        dst[i++] = playerMinGreenScore
        dst[i++] = playerMinSaturation
        dst[i++] = playerMinCompactness
        dst[i++] = playerMaxAspect
        dst[i++] = playerGateGridUnits
        dst[i++] = if (playerAnchorLocked) 1f else 0f
        dst[i++] = playerAnchorX
        dst[i++] = playerAnchorY
        dst[i++] = enemyMinComponentArea.toFloat()
        dst[i++] = enemyMaxComponentArea.toFloat()
        dst[i++] = enemyMinRedScore
        dst[i++] = enemyMinSaturation
        dst[i++] = enemyMinCompactness
        dst[i++] = maxEnemies.toFloat()
        dst[i++] = enemyAvoidRadiusNorm
        dst[i++] = ownEffectRadiusNorm
        dst[i++] = ownEffectTrackNorm
        dst[i++] = ownEffectMinHits.toFloat()
        dst[i++] = maxTracks.toFloat()
        dst[i++] = maxObservations.toFloat()
        dst[i++] = ballMinArea.toFloat()
        dst[i++] = bouncerMaxArea.toFloat()
        dst[i++] = bouncerDotThreshold
        dst[i++] = kindMinHitsBeforeLabelling.toFloat()
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
        dst[i++] = minTtiSec
        dst[i++] = lethalTtiSec
        dst[i++] = imminentTtiSec
        dst[i++] = escapeCandidateCount.toFloat()
        dst[i++] = escapeStepNorm
        dst[i++] = characterSpeedNorm
        check(i == NativeVisionEngine.CONFIG_FLOATS) {
            "writeInto produced $i floats but the native side reads " +
                "${NativeVisionEngine.CONFIG_FLOATS}"
        }
    }
}

/**
 * What a tracked object most likely is.
 *
 * This is a label applied to a track after it has already passed the projectile
 * gates, so it describes what something is without changing whether it is
 * treated as a threat. Only [kProjectile] objects can trigger a dodge.
 */
enum class TrackKind(val code: Int) {
    /** Not enough history yet to say. */
    UNKNOWN(0),

    /** Small, fast, constant velocity: the thing that can kill you. */
    PROJECTILE(1),

    /** The Brawl Ball: large, rolling, and the win condition. */
    BALL(2),

    /**
     * A wall bouncer. Its straight-line closest-approach is wrong, so it is
     * reported but deliberately not acted on.
     */
    BOUNCER(3);

    companion object {
        fun fromCode(code: Int): TrackKind = when (code) {
            1 -> PROJECTILE
            2 -> BALL
            3 -> BOUNCER
            else -> UNKNOWN
        }
    }
}

/** One incoming shot, as reported by [VisionResult.projectiles]. */
class Projectile(val x: Float, val y: Float, val vx: Float, val vy: Float, val speed: Float)

/** One tracked object, as returned by [NativeVisionEngine.readTracks]. */
class TrackReading(private val f: FloatArray, private val offset: Int) {
    val x: Float get() = f[offset]
    val y: Float get() = f[offset + 1]
    val vx: Float get() = f[offset + 2]
    val vy: Float get() = f[offset + 3]
    val speedNorm: Float get() = f[offset + 4]
    val isProjectile: Boolean get() = f[offset + 5] > 0.5f
    val kind: TrackKind get() = TrackKind.fromCode(f[offset + 6].toInt())

    /**
     * How dangerous this specific object is, in screen pixels. Only projectiles
     * are dangerous; a bouncer's straight-line solution is meaningless.
     */
    val isActionable: Boolean get() = isProjectile && kind != TrackKind.BOUNCER
}

/** Decodes a [NativeVisionEngine.readTracks] array into [TrackReading]s. */
fun FloatArray.toTrackReadings(): List<TrackReading> {
    val stride = NativeVisionEngine.TRACK_FLOATS
    if (size < stride) return emptyList()
    return List(size / stride) { TrackReading(this, it * stride) }
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

    /** Tracks classified as the Brawl Ball. */
    val ballCount: Int get() = i[8]

    /** Tracks classified as wall-bouncing shots. */
    val bouncerCount: Int get() = i[9]

    /** Enemy marks found in the world-anchored frame. Counted every frame. */
    val enemyCount: Int get() = i[12]

    /**
     * Every actionable projectile the engine is tracking, nearest to the brawler
     * first.
     *
     * Eagerly copied into a fresh list, NOT a `by lazy` view over the engine's
     * arrays. Those arrays are reused every frame, so a lazy read would return
     * whatever the last written frame happened to contain, and this object is
     * held across frames and read from another thread. Copying a handful of
     * structs is far cheaper than a cross-frame mix-up in the threat the system
     * believes it is dodging.
     *
     * On the always-on path rather than behind the debug flag, because the escape
     * heading has to be chosen against a whole burst, not a single shot.
     */
    val projectiles: List<Projectile> = run {
        val n = i[13].coerceIn(0, MAX_PROJECTILES)
        ArrayList<Projectile>(n).apply {
            for (k in 0 until n) {
                val o = SOLUTION_FLOATS + k * PROJECTILE_FLOATS
                if (o + PROJECTILE_FLOATS - 1 >= f.size) break
                add(
                    Projectile(
                        x = f[o],
                        y = f[o + 1],
                        vx = f[o + 2],
                        vy = f[o + 3],
                        speed = f[o + 4]
                    )
                )
            }
        }
    }

    companion object {
        /**
         * Wire order of the tuning fields, positionally matching the
         * `EngineConfig` declaration in `rendera_core.h`.
         *
         * `nativeConfigure` assigns `p[N]` positionally, so a field inserted on
         * either side without the other silently misapplies every knob after the
         * insertion point. Declaring the order here lets the unit tests assert by
         * field NAME instead of by a literal index that rots, and
         * `.github/scripts/check_cpp.py` compares this list against the C++
         * declaration so a divergence fails CI instead of production.
         */
        val WIRE_ORDER: List<String> = listOf(
        "motionMaxShiftHalfRes",
        "motionMinConfidence",
        "fineRefineRadius",
        "diffNoiseFloor",
        "diffStrongThreshold",
        "blobMinArea",
        "blobMaxArea",
        "blobMinFill",
        "blobMinMeanStrength",
        "playerMinComponentArea",
        "playerMaxComponentArea",
        "playerMinGreenScore",
        "playerMinSaturation",
        "playerMinCompactness",
        "playerMaxAspect",
        "playerGateGridUnits",
        "playerAnchorLocked",
        "playerAnchorX",
        "playerAnchorY",
        "enemyMinComponentArea",
        "enemyMaxComponentArea",
        "enemyMinRedScore",
        "enemyMinSaturation",
        "enemyMinCompactness",
        "maxEnemies",
        "enemyAvoidRadiusNorm",
        "ownEffectRadiusNorm",
        "ownEffectTrackNorm",
        "ownEffectMinHits",
        "maxTracks",
        "maxObservations",
        "ballMinArea",
        "bouncerMaxArea",
        "bouncerDotThreshold",
        "kindMinHitsBeforeLabelling",
        "trackGatePixels",
        "trackProcessPos",
        "trackProcessVel",
        "trackMeasureNoise",
        "trackMaxMisses",
        "trackMinHitsForProjectile",
        "projectileMinSpeedNorm",
        "projectileMinStraightness",
        "playerRadiusNorm",
        "projectileRadiusNorm",
        "reactionHorizonSec",
        "minTtiSec",
        "lethalTtiSec",
        "imminentTtiSec",
        "escapeCandidateCount",
        "escapeStepNorm",
        "characterSpeedNorm",
        )

        /** Index of [field] on the wire, or -1 if it is not exported. */
        fun wireIndexOf(field: String): Int = WIRE_ORDER.indexOf(field)

        /** Floats before the projectile block. Must match `kSolutionFloats`. */
        const val SOLUTION_FLOATS = 24
        const val MAX_PROJECTILES = 8
        const val PROJECTILE_FLOATS = 5
    }

    /** Frames the engine has processed since the last reset. */
    val framesProcessed: Int get() = i[10]

    /** Frames dropped by the capture ring since the last reset. */
    val droppedFrames: Int get() = i[11]

    val timeToImpactMs: Long get() = (timeToImpactSec * 1000f).toLong()
}
