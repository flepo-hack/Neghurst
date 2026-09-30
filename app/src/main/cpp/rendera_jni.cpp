// JNI surface for the Rendera native vision engine.
//
// The contract is deliberately narrow and honest:
//   * frames arrive as one direct ByteBuffer of interleaved RGBA, copied once
//     from the MediaProjection frame into a reusable ring, so no Bitmap and no
//     jbyteArray copy is ever involved;
//   * results come back in a single primitive float array plus one IntArray,
//     written into caller supplied buffers, so the hot path allocates nothing.

#include <jni.h>

#include <unwind.h>
#include <fcntl.h>
#include <sys/prctl.h>
#include <unistd.h>

#include <cstdio>

#include <algorithm>
#include <csignal>
#include <cstdio>
#include <cstdlib>
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
constexpr jint kProjectileFloats = 5;  // x, y, vx, vy, speed

// Enemy marks the escape planner must avoid walking into. Without them on the
// always-on path the whole enemy-avoidance term is dead outside the tests.
constexpr jint kMaxEnemies = 4;
constexpr jint kEnemyFloats = 2;  // x, y

constexpr jint kOutFloatCount =
    kSolutionFloats + kMaxProjectiles * kProjectileFloats +
    kMaxEnemies * kEnemyFloats;  // 24 + 40 + 8 = 72
constexpr jint kOutIntCount = 14;

/** Floats per track in nativeCopyTracks: x, y, vx, vy, speedNorm, isProjectile, kind. */
constexpr jint kTrackFloats = 7;

// ---------------------------------------------------------------------------
// Native crash capture.
//
// A signal kills the process outright. It never reaches a Kotlin catch block and
// never reaches Thread.setDefaultUncaughtExceptionHandler, so a segfault in
// this library is indistinguishable from the app being killed by the system: the
// log shows a session record and nothing else. That is exactly the report this
// exists to turn into an answer, so the handler records the signal, the faulting
// address, the real thread name and id, and a short frame list.
namespace {

// Set by nativeSetCrashFile before any engine exists. Owned, because the
// handler reads it long after the JNI frame that set it is gone.
char* gCrashPath = nullptr;

struct UnwindState {
    void* frames[24];
    int count = 0;
};

_Unwind_Reason_Code unwindFrame(_Unwind_Context* ctx, void* arg) {
    auto* st = static_cast<UnwindState*>(arg);
    if (st->count >= 24) return _URC_END_OF_STACK;
    const uintptr_t ip = _Unwind_GetIP(ctx);
    if (ip == 0) return _URC_END_OF_STACK;
    st->frames[st->count++] = reinterpret_cast<void*>(ip);
    return _URC_NO_REASON;
}

void crashHandler(int sig, siginfo_t* info, void* ctx) {
    if (gCrashPath != nullptr && gCrashPath[0] != '\0') {
        int fd = ::open(gCrashPath, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (fd >= 0) {
            // The real thread name. The previous version wrote a literal
            // "capture", which is a claim rather than a measurement and would
            // have misdirected the whole investigation.
            char name[32] = {0};
            prctl(PR_GET_NAME, name, 0, 0, 0);

            char buf[2048];

            // Unwind a short frame list. libunwind, not backtrace(): the latter
            // is a glibc function and does not exist in the NDK's libc, which
            // this file has to build against. Not async-signal-safe in the
            // strict sense, and that is the deliberate trade: without the PCs a
            // report can only give an address, and an address alone cannot be
            // turned into a line of code. Written with write() immediately,
            // before anything else can fault, and only on a path that is
            // already unwinding.
            UnwindState state;
            _Unwind_Backtrace(unwindFrame, &state);

            // Read the module map once, then translate every frame into
            // "library+offset" before writing it.
            //
            // The previous version wrote raw PCs plus a truncated maps dump, and
            // the truncation always cut off before librendera_native.so, so the
            // report carried a fault address and nothing that could turn it into
            // a line of code. Reading and scanning a static buffer is not
            // async-signal-safe in the strict sense; that is the trade, and it is
            // worth it because the alternative is a report nobody can act on.
            static char maps[32768];
            int mapLen = 0;
            const int mf = ::open("/proc/self/maps", O_RDONLY);
            if (mf >= 0) {
                mapLen = static_cast<int>(
                    ::read(mf, maps, sizeof(maps) - 1));
                ::close(mf);
                if (mapLen < 0) mapLen = 0;
            }
            if (mapLen >= 0 && mapLen < static_cast<int>(sizeof(maps))) {
                maps[mapLen] = '\0';
            }

            int n = std::snprintf(buf, sizeof(buf),
                "{\"type\":\"native-crash\",\"signal\":%d,\"code\":%d,"
                "\"addr\":\"%p\",\"thread\":\"%s\",\"tid\":%d,"
                "\"build\":\"1.0\",\"frames\":[",
                sig, info != nullptr ? info->si_code : 0,
                info != nullptr ? info->si_addr : nullptr, name,
                static_cast<int>(::gettid()));

            for (int i = 0; i < state.count && n > 0 &&
                            n < static_cast<int>(sizeof(buf)) - 160; ++i) {
                // Find the mapping containing this PC, and emit the module's
                // base and the offset within it. Both ends of the subtraction
                // come from the map, so no arithmetic on a possibly-wrapped
                // pointer is involved.
                const uintptr_t pc = reinterpret_cast<uintptr_t>(state.frames[i]);
                uintptr_t base = 0;
                const char* module = "?";
                for (int off = 0; off < mapLen; ) {
                    char* line = maps + off;
                    char* nl = static_cast<char*>(std::memchr(line, '\n', mapLen - off));
                    if (nl == nullptr) nl = maps + mapLen;
                    uintptr_t lo = 0, hi = 0;
                    if (std::sscanf(line, "%lx-%lx", &lo, &hi) == 2 &&
                        pc >= lo && pc < hi) {
                        const char* slash = nullptr;
                        for (char* q = line; q < nl; ++q) {
                            if (*q == '/') { slash = q; break; }
                        }
                        if (slash != nullptr) {
                            // The base of the executable segment, which is what
                            // the file offset in the map refers to. Adding the
                            // in-file offset is what llvm-symbolizer wants.
                            unsigned long long fileOff = 0;
                            std::sscanf(line + 17, "%llx", &fileOff);
                            base = lo - fileOff;
                            module = slash;
                        }
                        break;
                    }
                    off = static_cast<int>(nl - maps) + 1;
                }
                const uintptr_t off = base != 0 ? pc - base : pc;
                n += std::snprintf(buf + n, sizeof(buf) - n,
                                   "%s{\"pc\":\"%p\",\"off\":\"0x%lx\","
                                   "\"mod\":\"%s\"}",
                                   i == 0 ? "" : ",", state.frames[i],
                                   static_cast<unsigned long>(off), module);
            }
            if (n > 0 && n < static_cast<int>(sizeof(buf)) - 4) {
                n += std::snprintf(buf + n, sizeof(buf) - n, "]}\n");
            }
            if (n > 0) {
                ssize_t ignored = ::write(fd, buf, static_cast<size_t>(n));
                (void)ignored;
            }
            // The full map alongside, for anything the scan above missed.
            if (mapLen > 0) {
                const char* header = "--- /proc/self/maps ---\n";
                ssize_t ignored = ::write(fd, header, std::strlen(header));
                ignored = ::write(fd, maps, static_cast<size_t>(mapLen));
                (void)ignored;
            }
            ::close(fd);
        }
    }
    // Re-raise with the default handler so the process still dies the way the
    // system expects, and the tombstone is still produced.
    struct sigaction dfl;
    std::memset(&dfl, 0, sizeof(dfl));
    dfl.sa_handler = SIG_DFL;
    sigaction(sig, &dfl, nullptr);
    raise(sig);
}

void installCrashHandler() {
    if (gCrashPath == nullptr || gCrashPath[0] == '\0') return;
    static bool installed = false;
    if (installed) return;
    installed = true;
    struct sigaction sa;
    std::memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crashHandler;
    sa.sa_flags = SA_SIGINFO | SA_RESTART;
    sigemptyset(&sa.sa_mask);
    for (int sig : {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE}) {
        sigaction(sig, &sa, nullptr);
    }
}

}  // namespace

/**
 * Names the file the signal handler writes to, and installs the handler.
 *
 * Must be called before the engine is created. A path of null disables the
 * handler, which is what a host without a writable files directory gets rather
 * than a handler that writes to a bad path on every fault.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_vision_nativebridge_NativeVisionEngine_nativeSetCrashFile(
        JNIEnv* env, jobject, jstring path) {
    delete[] gCrashPath;
    gCrashPath = nullptr;
    if (path == nullptr) return;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return;
    const size_t len = std::strlen(chars);
    gCrashPath = new (std::nothrow) char[len + 1];
    if (gCrashPath != nullptr) std::memcpy(gCrashPath, chars, len + 1);
    env->ReleaseStringUTFChars(path, chars);
    installCrashHandler();
}

struct Session {
    std::unique_ptr<rendera::VisionEngine> engine;
    // Frame staging deliberately does not live here. The capture thread copies
    // each frame into a pool of direct buffers on the Kotlin side, and the vision
    // thread hands one of those straight to nativeProcessRgba, so the bytes are
    // already in a form JNI can address without a second copy. The capture
    // geometry is not duplicated here because there is nothing to keep in sync.
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
    // Installed first, so a fault in engine construction is still recorded.
    installCrashHandler();

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
    constexpr jsize kExpected = 53;
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
    c.alignSearchRadius             = static_cast<int>(p[29]);
    c.alignSearchCoarseStep         = static_cast<int>(p[30]);
    c.sceneChangeBlobFraction       = p[31];
    c.maxTracks                     = static_cast<int>(p[32]);
    c.maxObservations               = static_cast<int>(p[33]);
    c.ballMinArea                   = static_cast<int>(p[34]);
    c.bouncerDotThreshold           = p[35];
    c.kindMinHitsBeforeLabelling    = static_cast<int>(p[36]);
    c.trackGatePixels               = p[37];
    c.trackProcessPos               = p[38];
    c.trackProcessVel               = p[39];
    c.trackMeasureNoise             = p[40];
    c.trackMaxMisses                = static_cast<int>(p[41]);
    c.trackMinHitsForProjectile     = static_cast<int>(p[42]);
    c.projectileMinSpeedNorm        = p[43];
    c.projectileMinStraightness     = p[44];
    c.playerRadiusNorm              = p[45];
    c.projectileRadiusNorm          = p[46];
    c.reactionHorizonSec            = p[47];
    c.lethalTtiSec                  = p[48];
    c.imminentTtiSec                = p[49];
    c.escapeCandidateCount          = static_cast<int>(p[50]);
    c.escapeStepNorm                = p[51];
    c.characterSpeedNorm            = p[52];

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
 *   outF/jfloatOut  : kOutFloatCount floats, see NativeVisionEngine.kt.
 *                     24 solution floats, then up to 8 projectiles of 5 floats
 *                     (x, y, vx, vy, speed) nearest the brawler first.
 *   outI/jintOut    : kOutIntCount ints, see NativeVisionEngine.kt.
 *
 * Returns 1 when a threat was solved, 0 otherwise. Returns -1 on a bad handle.
 */
/**
 * Serialises the engine's current state into the caller's buffers.
 *
 * Shared by the YUV and RGBA entry points. Duplicating this would be a real
 * hazard rather than a tidy-up: the two copies would have to agree byte for
 * byte with what the Kotlin side reads at fixed indices, and nothing would catch
 * it if one were edited.
 */
static void writeResults(JNIEnv* env, rendera::VisionEngine* e,
                         jfloatArray outF, jintArray outI) {
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
    // against a burst instead of a single shot. When there are more than the
    // block holds, the ones nearest the brawler survive: those are the ones a
    // dodge can still act on.
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

    // Enemy marks, nearest the brawler first, for the escape planner.
    const auto& allEnemies = e->enemies();
    std::vector<const rendera::EnemyMark*> marks;
    marks.reserve(allEnemies.size());
    for (const auto& em : allEnemies) marks.push_back(&em);
    // Distinct parameter names: both sort lambdas used a/b, which made the two
    // different types indistinguishable to the static member check.
    std::sort(marks.begin(), marks.end(),
              [px, py](const rendera::EnemyMark* ma, const rendera::EnemyMark* mb) {
                  const float da = (ma->x - px) * (ma->x - px) + (ma->y - py) * (ma->y - py);
                  const float db = (mb->x - px) * (mb->x - px) + (mb->y - py) * (mb->y - py);
                  if (da != db) return da < db;
                  return ma->area < mb->area;
              });
    if (marks.size() > static_cast<size_t>(kMaxEnemies)) {
        marks.resize(static_cast<size_t>(kMaxEnemies));
    }
    for (size_t i = 0; i < marks.size(); ++i) {
        const int o = kSolutionFloats + kMaxProjectiles * kProjectileFloats +
            static_cast<int>(i) * kEnemyFloats;
        f[o + 0] = marks[i]->x;
        f[o + 1] = marks[i]->y;
    }

    if (outF != nullptr && env->GetArrayLength(outF) >= kOutFloatCount) {
        env->SetFloatArrayRegion(outF, 0, kOutFloatCount, f);
    }
    if (outI != nullptr && env->GetArrayLength(outI) >= kOutIntCount) {
        env->SetIntArrayRegion(outI, 0, kOutIntCount, i32);
    }

}

/**
 * Feeds one interleaved RGBA frame, the format a MediaProjection virtual
 * display produces.
 *
 * The reader is created PRIVATE and the pixels come out of the image's
 * HardwareBuffer, because `ImageReader` refuses to hand out an image whose
 * format differs from the reader's and a virtual display mirrors the composed
 * display, which is RGBA_8888. It throws naming the reader's format, so the
 * message reads as if that format were invalid.
 *
 * The capacity check is the only thing standing between a short buffer and a
 * wild read, so it is not optional.
 */
JNIEXPORT jint JNICALL
Java_com_example_vision_nativebridge_NativeVisionEngine_nativeProcessRgba(
        JNIEnv* env, jobject thiz, jlong handle,
        jobject rgbaBuf, jint stride,
        jint fullW, jint fullH, jlong ptsNanos,
        jfloatArray outF, jintArray outI) {
    rendera::VisionEngine* e = asEngine(handle);
    if (e == nullptr) return -1;
    if (rgbaBuf == nullptr || fullW <= 0 || fullH <= 0) return 0;

    auto* src = static_cast<uint8_t*>(env->GetDirectBufferAddress(rgbaBuf));
    const jlong cap = env->GetDirectBufferCapacity(rgbaBuf);
    if (src == nullptr || stride < fullW * 4) return 0;
    const jlong needed = static_cast<jlong>(stride) * fullH;
    if (cap > 0 && cap < needed) return 0;

    if (!e->ingestRgba(src, stride, fullW, fullH, static_cast<uint64_t>(ptsNanos))) {
        return 0;
    }
    e->process(static_cast<uint64_t>(ptsNanos));
    writeResults(env, e, outF, outI);
    return e->threat().valid ? 1 : 0;
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
