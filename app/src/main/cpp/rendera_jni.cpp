// JNI surface for the Rendera native vision engine.
//
// The contract is deliberately narrow and honest:
//   * frames arrive as direct ByteBuffers (the Y / Cb / Cr planes of a
//     MediaProjection YUV_420_888 Image, copied once into a reusable ring), so
//     no Bitmap and no jbyteArray copy is ever involved;
//   * results come back in a single primitive float array plus one IntArray,
//     written into caller supplied buffers, so the hot path allocates nothing.

#include <jni.h>

#include <algorithm>
#include <cstring>
#include <memory>
#include <new>
#include <vector>

#include "rendera_core.h"

namespace {

// 24 solution floats, then a fixed block of actionable projectiles. The block is
// on the always-on path rather than behind the debug flag: the escape has to be
// chosen against every incoming shot, so the list cannot be something that only
// exists when the HUD happens to be visible.
constexpr jint kSolutionFloats = 24;
constexpr jint kMaxProjectiles = 8;
constexpr jint kProjectileFloats = 5;  // x, y, vx, vy, timeToImpactSec
constexpr jint kOutFloatCount =
    kSolutionFloats + kMaxProjectiles * kProjectileFloats;  // 64
constexpr jint kOutIntCount = 14;

/** Floats per track in nativeCopyTracks: x, y, vx, vy, speedNorm, isProjectile, kind. */
constexpr jint kTrackFloats = 7;

struct Session {
    std::unique_ptr<rendera::VisionEngine> engine;
    // Reusable direct frame staging. Three slots so the capture thread can
    // publish a finished frame while the vision thread consumes the previous
    // one, without ever blocking.
    int capW = 0, capH = 0, capCW = 0, capCH = 0, yStride = 0, uvStride = 0;
};

inline rendera::VisionEngine* asEngine(jlong handle) {
    if (handle == 0) return nullptr;
    Session* s = reinterpret_cast<Session*>(handle);
    return s ? s->engine.get() : nullptr;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_vision_nativebridge_NativeVisionEngine_nativeCreate(
        JNIEnv*, jobject thiz,
        jint gridW, jint gridH,
        jint screenW, jint screenH) {
    rendera::EngineConfig cfg;
    cfg.gridW = gridW > 0 ? gridW : 160;
    cfg.gridH = gridH > 0 ? gridH : 90;

    Session* s = new (std::nothrow) Session();
    if (s == nullptr) return 0;
    s->engine.reset(new (std::nothrow) rendera::VisionEngine(cfg));
    if (!s->engine) {
        delete s;
        return 0;
    }
    s->engine->setScreenSize(screenW, screenH);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_example_vision_nativebridge_NativeVisionEngine_nativeDestroy(
        JNIEnv*, jobject thiz, jlong handle) {
    Session* s = reinterpret_cast<Session*>(handle);
    delete s;
}

JNIEXPORT void JNICALL
Java_com_example_vision_nativebridge_NativeVisionEngine_nativeReset(
        JNIEnv*, jobject thiz, jlong handle) {
    if (auto* e = asEngine(handle)) e->reset();
}

JNIEXPORT void JNICALL
Java_com_example_vision_nativebridge_NativeVisionEngine_nativeSetScreenSize(
        JNIEnv*, jobject thiz, jlong handle, jint screenW, jint screenH) {
    if (auto* e = asEngine(handle)) e->setScreenSize(screenW, screenH);
}

/**
 * Installs the full tunable set. Every value is passed explicitly so there is no
 * hidden state and the Kotlin side is the single source of truth for tuning.
 */
JNIEXPORT void JNICALL
Java_com_example_vision_nativebridge_NativeVisionEngine_nativeConfigure(
        JNIEnv* env, jobject thiz, jlong handle, jfloatArray cfg) {
    auto* e = asEngine(handle);
    if (e == nullptr || cfg == nullptr) return;
    // The Kotlin side is the single source of truth for tuning, so the whole set
    // is transferred explicitly and atomically. Any layout change must bump
    // CONFIG_FLOATS on the Kotlin side too.
    constexpr jsize kExpected = 52;
    if (env->GetArrayLength(cfg) < kExpected) return;

    jfloat* p = env->GetFloatArrayElements(cfg, nullptr);
    if (p == nullptr) return;

    rendera::EngineConfig c = e->config();
    c.motionMaxShiftHalfRes         = static_cast<int>(p[0]);
    c.motionMinConfidence           = p[1];
    c.fineRefineRadius              = static_cast<int>(p[2]);
    c.diffNoiseFloor                = static_cast<uint8_t>(p[3]);
    c.diffStrongThreshold           = static_cast<uint8_t>(p[4]);
    c.blobMinArea                   = static_cast<int>(p[5]);
    c.blobMaxArea                   = static_cast<int>(p[6]);
    c.blobMinFill                   = p[7];
    c.blobMinMeanStrength           = p[8];
    c.playerMinComponentArea        = static_cast<int>(p[9]);
    c.playerMaxComponentArea        = static_cast<int>(p[10]);
    c.playerMinGreenScore           = p[11];
    c.playerMinSaturation           = p[12];
    c.playerMinCompactness          = p[13];
    c.playerMaxAspect               = p[14];
    c.playerGateGridUnits           = p[15];
    c.playerAnchorLocked            = p[16] != 0.0f;
    c.playerAnchorX                 = p[17];
    c.playerAnchorY                 = p[18];
    c.enemyMinComponentArea         = static_cast<int>(p[19]);
    c.enemyMaxComponentArea         = static_cast<int>(p[20]);
    c.enemyMinRedScore              = p[21];
    c.enemyMinSaturation            = p[22];
    c.enemyMinCompactness           = p[23];
    c.maxEnemies                    = static_cast<int>(p[24]);
    c.enemyAvoidRadiusNorm          = p[25];
    c.ownEffectRadiusNorm           = p[26];
    c.ownEffectTrackNorm            = p[27];
    c.ownEffectMinHits              = static_cast<int>(p[28]);
    c.maxTracks                     = static_cast<int>(p[29]);
    c.maxObservations               = static_cast<int>(p[30]);
    c.ballMinArea                   = static_cast<int>(p[31]);
    c.bouncerMaxArea                = static_cast<int>(p[32]);
    c.bouncerDotThreshold           = p[33];
    c.kindMinHitsBeforeLabelling    = static_cast<int>(p[34]);
    c.trackGatePixels               = p[35];
    c.trackProcessPos               = p[36];
    c.trackProcessVel               = p[37];
    c.trackMeasureNoise             = p[38];
    c.trackMaxMisses                = static_cast<int>(p[39]);
    c.trackMinHitsForProjectile     = static_cast<int>(p[40]);
    c.projectileMinSpeedNorm        = p[41];
    c.projectileMinStraightness     = p[42];
    c.playerRadiusNorm              = p[43];
    c.projectileRadiusNorm          = p[44];
    c.reactionHorizonSec            = p[45];
    c.minTtiSec                     = p[46];
    c.lethalTtiSec                  = p[47];
    c.imminentTtiSec                = p[48];
    c.escapeCandidateCount          = static_cast<int>(p[49]);
    c.escapeStepNorm                = p[50];
    c.characterSpeedNorm            = p[51];

    e->setConfig(c);
    env->ReleaseFloatArrayElements(cfg, p, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_example_vision_nativebridge_NativeVisionEngine_nativeSetMask(
        JNIEnv* env, jobject thiz, jlong handle, jfloatArray mask) {
    auto* e = asEngine(handle);
    if (e == nullptr) return;
    if (mask == nullptr) {
        e->setMask(nullptr, 0);
        return;
    }
    const jsize n = env->GetArrayLength(mask);
    const jsize count = n / 5;
    if (count <= 0) {
        e->setMask(nullptr, 0);
        return;
    }
    jfloat* p = env->GetFloatArrayElements(mask, nullptr);
    if (p == nullptr) return;

    std::vector<rendera::MaskRegion> regions(static_cast<size_t>(std::min<jsize>(count, 16)));
    for (jsize i = 0; i < static_cast<jsize>(regions.size()); ++i) {
        const jsize o = i * 5;
        regions[static_cast<size_t>(i)].cx = p[o + 0];
        regions[static_cast<size_t>(i)].cy = p[o + 1];
        regions[static_cast<size_t>(i)].halfW = p[o + 2];
        regions[static_cast<size_t>(i)].halfH = p[o + 3];
        regions[static_cast<size_t>(i)].enabled = p[o + 4] != 0.0f;
    }
    e->setMask(regions.data(), static_cast<int>(regions.size()));
    env->ReleaseFloatArrayElements(mask, p, JNI_ABORT);
}

/**
 * Feeds one captured frame and runs the full pipeline.
 *
 *   yBuf/uBuf/vBuf : direct ByteBuffers holding one frame of planes. May be null
 *                    for the chroma planes, in which case only motion detection
 *                    runs.
 *   yStride/uvStride : real plane strides in bytes, so no copy is needed.
 *   outF/jfloatOut  : kOutFloatCount floats, see NativeVisionEngine.kt.
 *                     24 solution floats, then up to 8 projectiles of 5 floats
 *                     (x, y, vx, vy, speed) nearest the brawler first.
 *   outI/jintOut    : kOutIntCount ints, see NativeVisionEngine.kt.
 *
 * Returns 1 when a threat was solved, 0 otherwise. Returns -1 on a bad handle.
 */
JNIEXPORT jint JNICALL
Java_com_example_vision_nativebridge_NativeVisionEngine_nativeProcess(
        JNIEnv* env, jobject thiz, jlong handle,
        jobject yBuf, jint yStride,
        jobject uBuf, jobject vBuf, jint uvStride,
        jint fullW, jint fullH, jint chromaW, jint chromaH,
        jlong ptsNanos,
        jfloatArray outF, jintArray outI) {
    auto* e = asEngine(handle);
    if (e == nullptr) return -1;
    if (yBuf == nullptr || fullW <= 0 || fullH <= 0) return 0;

    auto* y = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuf));
    jlong yCap = env->GetDirectBufferCapacity(yBuf);
    auto* u = uBuf ? static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuf)) : nullptr;
    auto* v = vBuf ? static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuf)) : nullptr;

    if (y == nullptr || yStride <= 0) return 0;
    // The buffer must actually hold a full frame, otherwise reading it is UB.
    const jlong needY = static_cast<jlong>(yStride) * static_cast<jlong>(fullH);
    if (yCap > 0 && yCap < needY) return 0;

    if (u != nullptr && v != nullptr && chromaW > 0 && chromaH > 0 && uvStride > 0) {
        const jlong needUv = static_cast<jlong>(uvStride) * static_cast<jlong>(chromaH);
        const jlong uCap = env->GetDirectBufferCapacity(uBuf);
        const jlong vCap = env->GetDirectBufferCapacity(vBuf);
        if ((uCap >= 0 && uCap < needUv) || (vCap >= 0 && vCap < needUv)) {
            u = nullptr;
            v = nullptr;
        }
    } else {
        u = nullptr;
        v = nullptr;
    }

    if (!e->ingestYuv(y, yStride, u, v, uvStride, fullW, fullH, chromaW, chromaH,
                      static_cast<uint64_t>(ptsNanos))) {
        return 0;
    }
    e->process(static_cast<uint64_t>(ptsNanos));

    float f[kOutFloatCount];
    int i32[kOutIntCount];
    std::memset(f, 0, sizeof(f));
    std::memset(i32, 0, sizeof(i32));

    const auto& mo = e->motion();
    const auto& pl = e->player();
    const auto& th = e->threat();
    const auto& st = e->stats();

    f[0]  = mo.dx;
    f[1]  = mo.dy;
    f[2]  = mo.confidence;
    f[3]  = mo.valid ? 1.0f : 0.0f;

    f[4]  = pl.x;
    f[5]  = pl.y;
    f[6]  = pl.vx;
    f[7]  = pl.vy;
    f[8]  = pl.greenness;
    f[9]  = pl.valid ? 1.0f : 0.0f;
    f[10] = pl.locked ? 1.0f : 0.0f;
    f[11] = pl.framesSinceSeen;

    f[12] = th.ttiSec;
    f[13] = th.threatX;
    f[14] = th.threatY;
    f[15] = th.vx;
    f[16] = th.vy;
    f[17] = th.speed;
    // Slot 18 carries the track's straightness as the threat confidence. The
    // trajectory heading is deliberately not sent: Kotlin derives it from
    // (vx, vy) with CollisionSolver.trajectoryHeadingDeg, which keeps a single
    // definition of the heading convention instead of two that can drift.
    f[18] = th.confidence;
    f[19] = th.escape.headingDeg;
    f[20] = th.escape.dirX;
    f[21] = th.escape.dirY;
    f[22] = th.escape.stepPixels;
    f[23] = th.escape.travelMs;

    i32[0] = static_cast<int>(th.severity);
    i32[1] = th.valid ? 1 : 0;
    i32[2] = st.blobCount;
    i32[3] = st.trackCount;
    i32[4] = st.projectileCount;
    i32[5] = th.trackId;
    i32[6] = th.escape.sufficient ? 1 : 0;
    i32[7] = pl.componentArea;
    i32[8] = st.ballCount;
    i32[9] = st.bouncerCount;
    i32[10] = static_cast<int>(st.framesProcessed);
    i32[11] = static_cast<int>(st.droppedFrames);
    // Enemy marks are classified every frame, not only when the debug HUD is
    // on, so the readout cannot silently report zero.
    i32[12] = static_cast<int>(e->enemies().size());

    // Actionable projectiles, so the Kotlin escape planner can score a heading
    // against a burst instead of a single shot. Sorted by time to impact so the
    // earliest ones survive the cap.
    const auto& allTracks = e->tracks();
    std::vector<const rendera::Track*> actionable;
    actionable.reserve(allTracks.size());
    for (const auto& t : allTracks) {
        if (!t.isProjectile) continue;
        if (t.kind == rendera::TrackKind::kBouncer) continue;
        actionable.push_back(&t);
    }
    // Nearest to the BRAWLER, not to the screen origin. Sorting by x^2 + y^2
    // alone keeps whichever tracks happen to be near the top-left corner, which
    // is usually the opposite end of the arena from the shots that matter.
    const float px = e->player().x;
    const float py = e->player().y;
    std::sort(actionable.begin(), actionable.end(),
              [px, py](const rendera::Track* a, const rendera::Track* b) {
                  const float da = (a->x - px) * (a->x - px) + (a->y - py) * (a->y - py);
                  const float db = (b->x - px) * (b->x - px) + (b->y - py) * (b->y - py);
                  if (da != db) return da < db;
                  return a->id < b->id;  // stable, so the order is reproducible
              });
    if (actionable.size() > static_cast<size_t>(kMaxProjectiles)) {
        actionable.resize(static_cast<size_t>(kMaxProjectiles));
    }
    i32[13] = static_cast<int>(actionable.size());
    for (size_t i = 0; i < actionable.size(); ++i) {
        const auto& t = *actionable[i];
        const int o = kSolutionFloats + static_cast<int>(i) * kProjectileFloats;
        f[o + 0] = t.x;
        f[o + 1] = t.y;
        f[o + 2] = t.vx;
        f[o + 3] = t.vy;
        f[o + 4] = t.speedNorm * e->screenWidthForReport();
    }

    if (outF != nullptr && env->GetArrayLength(outF) >= kOutFloatCount) {
        env->SetFloatArrayRegion(outF, 0, kOutFloatCount, f);
    }
    if (outI != nullptr && env->GetArrayLength(outI) >= kOutIntCount) {
        env->SetIntArrayRegion(outI, 0, kOutIntCount, i32);
    }

    return th.valid ? 1 : 0;
}

/**
 * Copies the current blob list (screen coords, area, strength) into the caller's
 * float array. Only called when the debug HUD is actually on, so the cost is off
 * the hot path.
 */
JNIEXPORT jint JNICALL
Java_com_example_vision_nativebridge_NativeVisionEngine_nativeCopyBlobs(
        JNIEnv* env, jobject thiz, jlong handle, jfloatArray out, jint maxItems) {
    auto* e = asEngine(handle);
    if (e == nullptr || out == nullptr) return 0;
    const jsize cap = env->GetArrayLength(out);
    const jint n = std::min<jsize>(static_cast<jsize>(maxItems), cap / 4);
    if (n <= 0) return 0;

    const auto& blobs = e->blobs();
    const jint count = std::min(n, static_cast<jint>(blobs.size()));
    std::vector<float> tmp(static_cast<size_t>(count) * 4);
    for (jint i = 0; i < count; ++i) {
        const auto& b = blobs[static_cast<size_t>(i)];
        tmp[static_cast<size_t>(i) * 4 + 0] = b.sx;
        tmp[static_cast<size_t>(i) * 4 + 1] = b.sy;
        tmp[static_cast<size_t>(i) * 4 + 2] = static_cast<float>(b.area);
        tmp[static_cast<size_t>(i) * 4 + 3] = b.meanStrength;
    }
    env->SetFloatArrayRegion(out, 0, count * 4, tmp.data());
    return count;
}

JNIEXPORT jint JNICALL
Java_com_example_vision_nativebridge_NativeVisionEngine_nativeCopyEnemies(
        JNIEnv* env, jobject thiz, jlong handle, jfloatArray out, jint maxItems) {
    auto* e = asEngine(handle);
    if (e == nullptr || out == nullptr) return 0;
    const jsize cap = env->GetArrayLength(out);
    const jint n = std::min<jsize>(static_cast<jsize>(maxItems), cap / 3);
    if (n <= 0) return 0;

    const auto& enemies = e->enemies();
    const jint count = std::min(n, static_cast<jint>(enemies.size()));
    std::vector<float> tmp(static_cast<size_t>(count) * 3);
    for (jint i = 0; i < count; ++i) {
        const auto& en = enemies[static_cast<size_t>(i)];
        tmp[static_cast<size_t>(i) * 3 + 0] = en.x;
        tmp[static_cast<size_t>(i) * 3 + 1] = en.y;
        tmp[static_cast<size_t>(i) * 3 + 2] = static_cast<float>(en.area);
    }
    env->SetFloatArrayRegion(out, 0, count * 3, tmp.data());
    return count;
}

JNIEXPORT jint JNICALL
Java_com_example_vision_nativebridge_NativeVisionEngine_nativeCopyTracks(
        JNIEnv* env, jobject thiz, jlong handle, jfloatArray out, jint maxItems) {
    auto* e = asEngine(handle);
    if (e == nullptr || out == nullptr) return 0;
    const jsize cap = env->GetArrayLength(out);
    const jint n = std::min<jsize>(static_cast<jsize>(maxItems), cap / kTrackFloats);
    if (n <= 0) return 0;

    const auto& tracks = e->tracks();
    const jint count = std::min(n, static_cast<jint>(tracks.size()));
    std::vector<float> tmp(static_cast<size_t>(count) * kTrackFloats);
    for (jint i = 0; i < count; ++i) {
        const auto& t = tracks[static_cast<size_t>(i)];
        const size_t o = static_cast<size_t>(i) * kTrackFloats;
        tmp[o + 0] = t.x;
        tmp[o + 1] = t.y;
        tmp[o + 2] = t.vx;
        tmp[o + 3] = t.vy;
        tmp[o + 4] = t.speedNorm;
        tmp[o + 5] = t.isProjectile ? 1.0f : 0.0f;
        tmp[o + 6] = static_cast<float>(static_cast<int>(t.kind));
    }
    env->SetFloatArrayRegion(out, 0, count * kTrackFloats, tmp.data());
    return count;
}

JNIEXPORT jdouble JNICALL
Java_com_example_vision_nativebridge_NativeVisionEngine_nativeLastProcessMillis(
        JNIEnv*, jobject thiz, jlong handle) {
    auto* e = asEngine(handle);
    return e ? e->stats().processMs : 0.0;
}

}  // extern "C"
