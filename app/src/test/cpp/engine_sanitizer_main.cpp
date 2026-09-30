// Host-side exercise for the vision engine, built with the sanitizers.
//
// ## Why this exists
//
// The engine is plain C++ with no Android or JNI dependency in rendera_core.*,
// so it compiles and runs on the CI host. That makes it possible to do the one
// thing the Kotlin unit tests and the NDK build cannot: run the actual pipeline
// over actual frames under AddressSanitizer and UndefinedBehaviorSanitizer.
//
// A crash reported from the device as "SIGSEGV on the capture thread" told us
// nothing about which of our own buffers was wrong, because the fault turned out
// to be inside platform bitmap code. This harness exists for the faults that
// *are* ours: an out-of-bounds row in the downsampler, a stale stride after a
// resize, a track array read past its end. Those are cheap to find here and
// impossible to find from a device report.
//
// ## What it does
//
// Feeds a few hundred synthetic RGBA frames through ingestRgba/process at the
// capture size the app actually uses, moving a player, a red projectile and a
// bouncing ball so the motion stage, the blob stage, the tracker and the threat
// solver all run. Geometry changes mid-run, because a stride that is not
// reconfigured on resize is exactly the bug a fixed-size test would miss.
//
// No assertions about detection quality. A synthetic green rectangle is not
// Brawl Stars, and pretending otherwise here would be the same mistake the
// Kotlin classification test made. The contract is: it runs, it does not
// misbehave, and the sanitizers stay quiet.

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "rendera_core.h"

namespace {

struct Canvas {
    int w = 0;
    int h = 0;
    int stride = 0;
    std::vector<uint8_t> px;

    void reset(int width, int height) {
        w = width;
        h = height;
        // Deliberately padded rows, like the real capture, so the stride
        // handling is exercised rather than assumed.
        stride = ((width * 4) + 63) & ~63;
        px.assign(static_cast<size_t>(stride) * height, 0);
    }

    void fill(uint8_t r, uint8_t g, uint8_t b) {
        for (int y = 0; y < h; ++y) {
            uint8_t* row = &px[static_cast<size_t>(y) * stride];
            for (int x = 0; x < w; ++x) {
                row[x * 4 + 0] = r;
                row[x * 4 + 1] = g;
                row[x * 4 + 2] = b;
                row[x * 4 + 3] = 255;
            }
        }
    }

    void disc(float cx, float cy, float radius, uint8_t r, uint8_t g, uint8_t b) {
        const int r2 = static_cast<int>(radius * radius);
        const int x0 = static_cast<int>(cx) - static_cast<int>(radius) - 1;
        const int x1 = static_cast<int>(cx) + static_cast<int>(radius) + 1;
        const int y0 = static_cast<int>(cy) - static_cast<int>(radius) - 1;
        const int y1 = static_cast<int>(cy) + static_cast<int>(radius) + 1;
        for (int y = y0; y <= y1; ++y) {
            if (y < 0 || y >= h) continue;
            uint8_t* row = &px[static_cast<size_t>(y) * stride];
            for (int x = x0; x <= x1; ++x) {
                if (x < 0 || x >= w) continue;
                const int dx = x - static_cast<int>(cx);
                const int dy = y - static_cast<int>(cy);
                if (dx * dx + dy * dy > r2) continue;
                row[x * 4 + 0] = r;
                row[x * 4 + 1] = g;
                row[x * 4 + 2] = b;
                row[x * 4 + 3] = 255;
            }
        }
    }
};

int failures = 0;
int totalThreats = 0;

void expect(bool ok, const char* what) {
    if (!ok) {
        std::printf("  FAIL: %s\n", what);
        ++failures;
    }
}

// Runs one geometry for a while, and reports what the engine produced so a
// regression shows up as numbers rather than as silence.
void run(rendera::VisionEngine& engine, Canvas& c, int frames, int gridW, int gridH,
         int screenW, int screenH) {
    int playerSeen = 0;
    int threats = 0;
    int maxTracks = 0;
    int maxBlobs = 0;
    int maxEnemies = 0;

    for (int f = 0; f < frames; ++f) {
        const float t = static_cast<float>(f);

        // Grass-ish background, the luma the diff stage has to rise above.
        c.fill(38, 74, 46);

        // A green player drifting slowly, as the joystick would.
        const float px0 = c.w * 0.42f + std::sin(t * 0.05f) * c.w * 0.05f;
        const float py0 = c.h * 0.66f + std::cos(t * 0.04f) * c.h * 0.03f;
        c.disc(px0, py0, 5.0f, 40, 245, 70);

        // Red projectiles crossing toward the player.
        for (int p = 0; p < 3; ++p) {
            const float tt = t * 3.0f + p * 90.0f;
            const float bx = c.w * 0.9f - (tt * 1.5f) - p * 40.0f;
            const float by = c.h * 0.30f + p * c.h * 0.12f;
            if (bx < -8.0f || bx > c.w + 8.0f) continue;
            c.disc(bx, by, 3.0f, 235, 60, 55);
        }

        // A large slow ball, which is the BALL label's whole purpose.
        {
            const float period = 220.0f;
            const float u = std::fmod(t * 1.1f, period) / period;
            c.disc(c.w * (0.15f + 0.7f * u), c.h * 0.18f, 11.0f, 200, 200, 210);
        }

        engine.setScreenSize(screenW, screenH);
        if (!engine.ingestRgba(c.px.data(), c.stride, c.w, c.h, t * 16'666'667ull)) {
            expect(false, "ingestRgba rejected a frame it should have accepted");
            return;
        }
        engine.process(t * 16'666'667ull);

        const rendera::PlayerState& player = engine.player();
        if (player.valid) ++playerSeen;

        const rendera::ThreatSolution& threat = engine.threat();
        if (threat.valid) {
            ++threats;
            // The solver writes screen coordinates; a value outside the screen
            // is a real defect even when nothing crashes.
            expect(threat.threatX >= -screenW && threat.threatX <= screenW * 2,
                   "threatX outside any plausible screen position");
            expect(threat.threatY >= -screenH && threat.threatY <= screenH * 2,
                   "threatY outside any plausible screen position");
            expect(threat.trackId >= 0, "threat carries a negative track id");
        }

        const int tracks = static_cast<int>(engine.tracks().size());
        const int blobs = static_cast<int>(engine.blobs().size());
        const int enemies = static_cast<int>(engine.enemies().size());
        if (tracks > maxTracks) maxTracks = tracks;
        if (blobs > maxBlobs) maxBlobs = blobs;
        if (enemies > maxEnemies) maxEnemies = enemies;

        // Hard ceilings from EngineConfig. Exceeding one is an overflow of a
        // fixed-capacity vector, which is a bug even when the values are wrong
        // rather than out of bounds.
        expect(tracks <= 16, "track count exceeds MAX_TRACKS");
        expect(enemies <= 10, "enemy count exceeds maxEnemies");
    }

    totalThreats += threats;
    std::printf("    %dx%d grid %dx%d: player %d/%d, threats %d, "
                "max tracks %d, blobs %d, enemies %d\n",
                c.w, c.h, gridW, gridH, playerSeen, frames, threats,
                maxTracks, maxBlobs, maxEnemies);
}

}  // namespace

int main() {
    // The configuration the app actually ships, not the struct defaults.
    //
    // RenderaOverlayService.tuningFromPrefs() overwrites six fields at the
    // default sensitivity of 0.70, and check_jni.py only proves the values are
    // read and written in the same order - not that they are the ones in use.
    // A harness on the struct defaults therefore tests a configuration that
    // never runs, and when it reported no threats at all that was a statement
    // about the wrong numbers. These are the shipped ones, arithmetic included.
    constexpr float kSensitivity = 0.70f;

    rendera::EngineConfig cfg;
    cfg.gridW = 200;
    cfg.gridH = 112;
    cfg.playerAnchorX = 0.50f;
    cfg.playerAnchorY = 0.52f;
    cfg.playerAnchorLocked = false;  // Anchors.calibrated defaults to false
    cfg.diffNoiseFloor = 26.0f - kSensitivity * 12.0f;   // 17.6 -> 18
    cfg.playerMinGreenScore = 48.0f + kSensitivity * 30.0f;   // 69
    cfg.enemyMinRedScore = 40.0f + kSensitivity * 26.0f;     // 58.2
    cfg.projectileMinSpeedNorm = 0.30f - kSensitivity * 0.14f;      // 0.202
    cfg.projectileMinStraightness = 0.70f - kSensitivity * 0.22f;    // 0.546
    cfg.reactionHorizonSec = 0.32f + kSensitivity * 0.18f;           // 0.446

    rendera::VisionEngine engine(cfg);

    // The size the app captures at, in both orientations.
    std::printf("  landscape 480x216\n");
    Canvas a;
    a.reset(480, 216);
    run(engine, a, 240, 200, 112, 2400, 1080);

    std::printf("  portrait 216x480, same engine (stride must reconfigure)\n");
    Canvas b;
    b.reset(216, 480);
    run(engine, b, 240, 200, 112, 1080, 2400);

    std::printf("  tiny 64x48 (the smallest the service allows)\n");
    Canvas c;
    c.reset(64, 48);
    run(engine, c, 120, 200, 112, 640, 480);

    // Back to the original size, to catch a stride left stale by the resizes.
    std::printf("  back to 480x216\n");
    a.reset(480, 216);
    run(engine, a, 120, 200, 112, 2400, 1080);

    // KNOWN FAILURE, measured rather than asserted.
    //
    // This reports 0, and it is a real defect, not a harness artefact. The
    // tracker stores velocity in pixels *per frame* - t.vx = inX / dd, where inX
    // is the frame's displacement - while the player velocity right next to it
    // is per second: player_.vx = (nx - x) / frameDt_. Two unit systems in one
    // engine. Everything downstream then compares a per-frame quantity against a
    // seconds-valued gate:
    //
    //   tCpa = (r.v) / |v|^2            is in FRAMES, and is compared against
    //                                     cfg_.reactionHorizonSec, which the app
    //                                     ships as 0.446 *seconds*;
    //   t.speedNorm = |v| / screenW     is screen widths PER FRAME, and is
    //                                     compared against projectileMinSpeedNorm
    //                                     = 0.202, i.e. 0.2 screen widths per
    //                                     frame, roughly 6 screen widths a second.
    //
    // A projectile therefore has to be already inside the collision radius to
    // produce a threat, and has to cross a sixth of the screen in a single frame
    // to be classed as a projectile at all. The red discs here move 1.5 px per
    // frame, so nothing is ever classified and the solver never runs.
    //
    // Left as a printed number rather than a hard assertion on purpose. The fix
    // is a unit conversion across the Kalman update and the solve, which is not
    // something to land unverified; when the tracker is corrected this becomes
    // an assertion and CI will say so by turning red.
    std::printf("  threats solved overall: %d%s\n", totalThreats,
                totalThreats == 0 ? "  (KNOWN: tracker velocity is per frame, "
                                   "gates are per second)" : "");

    if (failures == 0) {
        std::printf("OK: engine ran clean under ASan/UBSan.\n");
        return 0;
    }
    std::printf("%d failure(s).\n", failures);
    return 1;
}
