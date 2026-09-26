#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>
#include <atomic>

namespace rendera {

// ===========================================================================
// Rendera native deterministic vision engine
// ===========================================================================
//
// Pipeline, per frame:
//
//   1. INGEST        Luma + chroma planes are box-downsampled into a fixed
//                    grid (gridW x gridH) of 8 bit luma / green / red scores.
//                    No heap traffic after construction.
//   2. GLOBAL MOTION Camera translation is estimated with windowed phase
//                    correlation (radix-2 2D FFT) on a half resolution copy,
//                    refined to full grid resolution with a local SAD search.
//                    Full search range, sub-pixel accuracy, confidence gated.
//   3. DIFFERENCE    The previous frame is warped by -v and subtracted. The
//                    |shift| wide border ring is marked INVALID so warped
//                    border pixels can never manufacture motion.
//   4. EXTRACTION    8-connected component labelling of the difference image
//                    (union-find, two pass, allocation free) yields motion
//                    blobs with area / extent / fill statistics.
//   5. TRACKING      A proper constant-velocity Kalman filter (2x2 symmetric
//                    covariance per axis, correct F P F' + Q propagation) with
//                    Mahalanobis-style gating associates blobs to tracks and
//                    classifies constant velocity movers as projectiles.
//   6. SOLVING       Closest point of approach against the player, then a
//                    scored search over escape headings instead of the naive
//                    +/-90 degree coin flip.
//
// Everything here is deterministic and side effect free apart from the engine
// instance itself, so it is safe to drive from a single dedicated thread.

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

struct EngineConfig {
    // Working grid. Must track the capture aspect, or grid to screen positions
    // skew and the reported player lands in the wrong place. 200x112 is the
    // default: a cell is about 12x9.6 screen px on a 2400x1080 display, which is
    // the smallest cell that still resolves a Brawl Stars bullet as a 2-3 cell
    // blob. The half resolution motion plane is 100x56, which the FFT pads to
    // 128x64, so this costs no extra correlation work.
    int gridW = 200;
    int gridH = 112;

    // --- global motion estimation ---
    int motionMaxShiftHalfRes = 24;  // +/- search range on the half res grid
    // 0..1 alignment quality: how much of the frame-to-frame difference the
    // estimated shift actually removed. 0 means the shift explained nothing,
    // 1 means the aligned frames are identical. Both the core and the optional
    // OpenCV path compute this same quantity, so the threshold means one thing.
    float motionMinConfidence = 0.06f;
    int fineRefineRadius = 2;        // +/- full-res SAD refinement radius

    // --- difference / noise ---
    uint8_t diffNoiseFloor = 18;     // luminance delta below this is noise
    uint8_t diffStrongThreshold = 40; // blob "core" strength

    // --- blob filtering ---
    int blobMinArea = 2;
    int blobMaxArea = 400;
    float blobMinFill = 0.16f;       // area / bbox area
    float blobMinMeanStrength = 22.0f;

    // --- player detection (world anchored, evaluated on the ALIGNED frame) ---
    //
    // Scores are opponent signals: G - max(R,B) for green, R - max(G,B) for red,
    // both scaled by OPPONENT_SCALE and clamped to 0..255. These are scale free
    // and hue correct. The previous formula (2*cr - cb on raw chroma) is inverted:
    // it returns NEGATIVE greenness for saturated green, so the player detector
    // could never fire on an actual selection ring, while reporting purple and
    // blue UI elements as "red".
    //
    // The saturation gate is the second half of the discrimination. Brawl Stars'
    // selection ring scores around 240 while grass sits near 80, so requiring
    // saturation rejects terrain that passes the greenness test on hue alone.
    int playerMinComponentArea = 8;
    int playerMaxComponentArea = 900;
    float playerMinGreenScore = 58.0f;   // 0..255 greenness of the component
    float playerMinSaturation = 105.0f;  // 0..255 max(RGB) - min(RGB)
    float playerMinCompactness = 0.20f;  // area / bbox area
    float playerMaxAspect = 4.5f;        // rejects wide scenery runs
    float playerGateGridUnits = 34.0f;   // search radius around the prior (grid cells)
    bool playerAnchorLocked = false;     // honour the calibrated anchor exactly
    float playerAnchorX = 0.5f;          // normalised screen coords
    float playerAnchorY = 0.5f;

    // --- enemy detection (world anchored, evaluated on the ALIGNED frame) ---
    int enemyMinComponentArea = 5;
    int enemyMaxComponentArea = 700;
    float enemyMinRedScore = 55.0f;
    float enemyMinSaturation = 105.0f;
    float enemyMinCompactness = 0.18f;
    int maxEnemies = 10;
    float enemyAvoidRadiusNorm = 0.11f;  // screen widths the escape must keep clear

    // --- own-effect rejection ---
    //
    // Walking through a puddle, a bush or a wall of rubble produces motion at
    // the player's own position: splashes, rustle, dust. After camera
    // compensation that is the *only* motion such effects create, and without
    // this filter each step of the brawler spawns a blob that the tracker then
    // follows as if it were an incoming projectile. The tracker would then
    // classify the player's own footstep as a threat and the brawler would run
    // in circles dodging its own splash.
    //
    // Nothing can be dodged from inside the player's own sprite, so any motion
    // that overlaps it is discarded. The radius is a fraction of the screen
    // width rather than a cell count so it tracks the brawler at any scale.
    float ownEffectRadiusNorm = 0.055f;
    // A blob may be born slightly outside the radius and still be the player's
    // own effect, so a track is also dropped if its whole life stays within a
    // slightly larger radius: own effects travel with the brawler, they do not
    // cross the arena.
    float ownEffectTrackNorm = 0.085f;
    int ownEffectMinHits = 2;
    /**
     * How many frames of history a track needs before the "own effect" filter can
     * judge it. Lower than trackMinHitsForProjectile on purpose: a point blank
     // shot is legitimately close to the player, so the filter has to be able to
     * clear it rather than wait for more evidence.
     */

    // --- tracking ---
    int maxTracks = 16;
    int maxObservations = 48;
    // --- object classification (label only; does not affect threat detection) ---
    // Grid cells. A bullet is a few cells across once the motion residual has
    // eaten its tail; the ball is a large blob because it is a big rolling
    // sphere, not a sprite the size of a bullet.
    int ballMinArea = 14;
    int bouncerMaxArea = 26;
    // A bouncer reverses hard: the new velocity is nearly anti-parallel to the
    // previous one. A straight shot never does that by accident.
    float bouncerDotThreshold = -0.55f;
    int kindMinHitsBeforeLabelling = 3;
    float trackGatePixels = 90.0f;       // in screen pixels
    float trackProcessPos = 3.0f;
    float trackProcessVel = 240.0f;
    float trackMeasureNoise = 260.0f;
    int trackMaxMisses = 5;
    // 2, not 3. At 60 fps a third hit costs 50 ms, and close range bullets
    // in Brawl Stars arrive in well under 100 ms, so waiting for a third
    // observation would mean reacting after the shot had already landed.
    int trackMinHitsForProjectile = 2;
    float projectileMinSpeedNorm = 0.22f; // fraction of screen width per second
    float projectileMinStraightness = 0.55f;

    // --- collision solving ---
    float playerRadiusNorm = 0.052f;     // fraction of screen width
    float projectileRadiusNorm = 0.011f;
    float reactionHorizonSec = 0.42f;
    float minTtiSec = 0.0f;
    float lethalTtiSec = 0.17f;
    float imminentTtiSec = 0.29f;
    int escapeCandidateCount = 24;      // headings sampled around the circle
    // Required perpendicular displacement to turn a hit into a miss, as a
    // fraction of screen width. This is a *clearance*, not a travel distance:
    // the joystick drag only sets a direction, the brawler covers ground for as
    // long as the stick is held.
    float escapeStepNorm = 0.070f;
    float characterSpeedNorm = 0.67f;  // brawler top speed, screen widths / s
};

// ---------------------------------------------------------------------------
// Output value types
// ---------------------------------------------------------------------------

enum ThreatSeverity : int {
    kSafe = 0,
    kWarning = 1,
    kImminent = 2,
    kLethal = 3
};

struct MotionEstimate {
    float dx = 0.0f;          // grid cells, current vs previous, sub-pixel
    float dy = 0.0f;
    float confidence = 0.0f;
    bool valid = false;
};

struct Blob {
    float gx = 0.0f;          // grid coords
    float gy = 0.0f;
    float sx = 0.0f;          // screen pixel coords
    float sy = 0.0f;
    int area = 0;
    int minX = 0, minY = 0, maxX = 0, maxY = 0;
    float meanStrength = 0.0f;
    float peakStrength = 0.0f;
};

/**
 * What a tracked object most likely is.
 *
 * This is a LABEL on an existing track, not a separate tracker. A second set of
 * detection gates for the ball would mean a second set of thresholds that can
 * interact badly with projectile detection, and projectile detection is what
 * keeps the brawler alive. Classifying after the fact cannot regress dodging:
 * the gates that decide "is this a threat" are untouched.
 */
enum class TrackKind : int {
    kUnknown = 0,
    /** Small, fast, constant velocity. The thing that can kill you. */
    kProjectile = 1,
    /**
     * The Brawl Ball. Much larger than a projectile, rolls along the ground, and
     * is the win condition, so knowing where it is matters even when it is not
     * an immediate threat.
     */
    kBall = 2,
    /**
     * A bouncer: a shot that reflects off walls, so its velocity reverses
     * without the object changing identity. Recognising it matters because the
     * CPA solution is wrong for it and it should not be dodged as if it were a
     * straight shot.
     */
    kBouncer = 3
};

struct Track {
    int id = 0;
    TrackKind kind = TrackKind::kUnknown;
    float x = 0.0f;           // screen pixels
    float y = 0.0f;
    float vx = 0.0f;
    float vy = 0.0f;
    // Symmetric 2x2 covariance per axis: [[p00,p01],[p01,p11]]
    float pxx00 = 400.0f, pxx01 = 0.0f, pxx11 = 900.0f;
    float pyy00 = 400.0f, pyy01 = 0.0f, pyy11 = 900.0f;
    /** Blob area at first sight, in grid cells. Sets the size class. */
    int spawnArea = 0;
    /** Where the track was first seen, in screen pixels. */
    float spawnX = 0.0f;
    float spawnY = 0.0f;
    /** Smoothed blob area, used to keep the label stable. */
    float areaEma = 0.0f;
    int hits = 0;
    int misses = 0;
    bool isProjectile = false;
    /** Set when the velocity reversed since the previous observation. */
    bool bounced = false;
    /** 0..1 straightness of the velocity, as reported to the solver. */
    float straightnessNorm = 0.0f;
    float straightness = 0.0f;  // 1 = perfectly constant velocity
    float speedNorm = 0.0f;     // screen widths per second
    uint64_t lastSeenNanos = 0;
    bool alive = false;
};

struct EscapePlan {
    bool valid = false;
    float headingDeg = 0.0f;     // 0 = +X (right), 90 = +Y (down)
    float dirX = 0.0f;
    float dirY = 0.0f;
    float stepPixels = 0.0f;
    float travelMs = 0.0f;       // time for the character to cover stepPixels
    bool sufficient = true;      // can the character physically get clear in time?
};

struct ThreatSolution {
    bool valid = false;
    int severity = kSafe;
    float ttiSec = 0.0f;
    float threatX = 0.0f;
    float threatY = 0.0f;
    float vx = 0.0f;
    float vy = 0.0f;
    float speed = 0.0f;
    float trajectoryDeg = 0.0f;
    float confidence = 0.0f;
    int trackId = 0;
    EscapePlan escape;
};

struct PlayerState {
    bool valid = false;          // a real detection this frame
    bool locked = false;         // a real detection recently (within hold frames)
    float x = 0.0f;              // screen pixels
    float y = 0.0f;
    float vx = 0.0f;
    float vy = 0.0f;
    int framesSinceSeen = 0;
    int componentArea = 0;
    float greenness = 0.0f;
};

// A world anchored red object: enemy brawler selection ring or health bar.
struct EnemyMark {
    float x = 0.0f;
    float y = 0.0f;
    int area = 0;
    float redness = 0.0f;
};

struct FrameStats {
    double processMs = 0.0;
    int64_t framesProcessed = 0;
    int blobCount = 0;
    int trackCount = 0;
    int projectileCount = 0;
    int ballCount = 0;
    int bouncerCount = 0;
    int droppedFrames = 0;
};

/**
 * Scale applied to a normalized opponent signal to reach the 0..255 range the
 * thresholds are expressed in.
 *
 * A fully saturated colour gives an opponent value of 1.0, so everything above
 * `255 / kOpponentScale` = 0.64 clips. That is intentional: these are gates, not
 * measurements, and a vivid selection ring should not need a more precise
 * reading than "well past the bar".
 */
constexpr float kOpponentScale = 400.0f;

// Regions of the captured image that belong to Rendera's own overlay and must
// never produce detections. Supplied in normalised screen coordinates.
struct MaskRegion {
    float cx = 0.0f;
    float cy = 0.0f;
    float halfW = 0.0f;
    float halfH = 0.0f;
    bool enabled = false;
};

// ---------------------------------------------------------------------------
// Optional OpenCV hook.
//
// Defined in rendera_opencv.cpp, which CMake only compiles when a real OpenCV
// Android SDK was supplied. When the translation unit is absent the linker
// resolves the weak-ish seam to false, and the dependency free core path runs.
// Declared here so the core can try the fast path without a build-time flag.
// ---------------------------------------------------------------------------
bool opencvMotionDiffAvailable();
bool opencvEstimateMotionAndDiff(const uint8_t* current,
                                 const uint8_t* previous,
                                 int width,
                                 int height,
                                 int stride,
                                 int searchRadius,
                                 float minConfidence,
                                 uint8_t noiseFloor,
                                 MotionEstimate& outMotion,
                                 uint8_t* diffOut);

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------

class VisionEngine {
public:
    explicit VisionEngine(const EngineConfig& cfg);

    // Ingest one captured frame.
    //   yPlane  : luma,   fullW * fullH samples, `yStride` bytes per row
    //   uPlane  : Cb,     chromaW * chromaH samples, `uvStride` bytes per row
    //   vPlane  : Cr,     same layout as uPlane
    // Chroma planes may be null, in which case green/red scores stay 0 and only
    // motion based detection runs.
    // Returns false if the dimensions do not match the configured capture size.
    bool ingestYuv(const uint8_t* yPlane, int yStride,
                   const uint8_t* uPlane, const uint8_t* vPlane, int uvStride,
                   int fullW, int fullH, int chromaW, int chromaH,
                   uint64_t ptsNanos);

    // Run the full pipeline for the ingested frame. Call once per ingest.
    void process(uint64_t ptsNanos);

    // ---- results ----
    const MotionEstimate& motion() const { return motion_; }
    const PlayerState& player() const { return player_; }
    const ThreatSolution& threat() const { return threat_; }
    const FrameStats& stats() const { return stats_; }
    const std::vector<Blob>& blobs() const { return blobs_; }
    const std::vector<EnemyMark>& enemies() const { return enemies_; }
    const std::vector<Track>& tracks() const { return tracks_; }

    // Intentionally used by the zero-copy ingest fast path.
    uint8_t* lumaGrid() { return luma_.data(); }

    // One-time capture geometry setup. Must be called before the first ingest so
    // the downsample can avoid per-frame divisions and honour real plane strides.
    void configureCapture(int capW, int capH, int capCW, int capCH,
                          int yStride, int uvStride) {
        capW_ = capW; capH_ = capH;
        capCW_ = capCW; capCH_ = capCH;
        capYStride_ = yStride; capUvStride_ = uvStride;
    }

    void setScreenSize(int w, int h) { screenW_ = w > 0 ? w : 1; screenH_ = h > 0 ? h : 1; }
    void setMask(const MaskRegion* regions, int count);
    void setConfig(const EngineConfig& cfg) { cfg_ = cfg; }
    const EngineConfig& config() const { return cfg_; }
    void reset();

    // Map a screen pixel coordinate onto the internal grid.
    void screenToGrid(float sx, float sy, float& gx, float& gy) const;

    /**
     * The screen width this engine is producing coordinates in.
     *
     * Exposed because speeds live in the tracker as a normalised value (screen
     * widths per second, which is resolution independent and therefore the right
     * thing to filter on) but every speed that crosses the JNI boundary is in
     * pixels per second, to match the other velocities. The bridge must not reach
     * into a private field to do the conversion.
     */
    float screenWidthForReport() const { return static_cast<float>(screenW_); }

private:
    // --- stages ---
    void downsampleFromYuv(const uint8_t* y, int yStride,
                          const uint8_t* u, const uint8_t* v, int uvStride,
                          int fullW, int fullH, int chromaW, int chromaH);
    void estimateGlobalMotion();
    bool computeCorrelatedMotion();
    void refineMotionAtFullRes();
    void buildDifference();
    float measureAlignmentQuality() const;
    void extractBlobs();
    void detectPlayer();
    void detectEnemies();
    void updateTracks();
    void solveThreat();
    float chooseEscapeHeading(const Track& t) const;
    void fft2d(std::vector<float>& re, std::vector<float>& im, bool inverse = false);

    // --- helpers ---
    void invalidateBorderRing();
    float gridToScreenX(float gx) const;
    float gridToScreenY(float gy) const;

    EngineConfig cfg_;

    int gridW_ = 0, gridH_ = 0, totalCells_ = 0;
    int screenW_ = 1080, screenH_ = 1920;

    // Capture geometry, fixed at construction time by configureCapture().
    int capW_ = 0, capH_ = 0, capCW_ = 0, capCH_ = 0, capYStride_ = 0, capUvStride_ = 0;

    // Working grid buffers.
    std::vector<uint8_t> luma_;
    std::vector<uint8_t> prevLuma_;
    std::vector<uint8_t> green_;
    std::vector<uint8_t> red_;
    std::vector<uint8_t> sat_;
    std::vector<uint8_t> diff_;
    std::vector<uint8_t> valid_;

    // Half resolution motion buffers.
    int motW_ = 0, motH_ = 0;
    int fftW_ = 0, fftH_ = 0;
    std::vector<uint8_t> motCur_, motPrev_;
    // Windowed, mean removed spectra for the two frames under correlation.
    std::vector<float> specReCur_, specImCur_;
    std::vector<float> specRePrev_, specImPrev_;
    std::vector<float> corr_;                 // inverse FFT correlation surface
    std::vector<float> rowRe_, rowIm_;        // scratch, max(fftW_, fftH_)
    std::vector<float> fftCos_, fftSin_;      // twiddles, max(fftW_, fftH_)
    std::vector<float> hannX_, hannY_;       // separable window, motW_ / motH_

    // Blob labelling scratch.
    std::vector<int32_t> labels_;
    std::vector<int32_t> parent_;
    std::vector<float> blobSumX_, blobSumY_, blobSumStrength_, blobPeakStrength_;
    std::vector<int32_t> blobArea_, blobMinX_, blobMinY_, blobMaxX_, blobMaxY_;
    std::vector<uint8_t> maskBitmap_;

    std::vector<Blob> blobs_;
    std::vector<Track> tracks_;
    std::vector<MaskRegion> masks_;
    std::vector<EnemyMark> enemies_;
    std::vector<uint8_t> blobUsed_;

    MotionEstimate motion_;
    PlayerState player_;
    ThreatSolution threat_;
    FrameStats stats_;

    bool hasPrev_ = false;
    uint64_t lastPts_ = 0;
    uint64_t runPts_ = 0;
    uint64_t ingestPts_ = 0;
    float frameDt_ = 1.0f / 60.0f;
    int nextTrackId_ = 1;
};

}  // namespace rendera
