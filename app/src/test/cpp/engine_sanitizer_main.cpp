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

    std::printf("    %dx%d grid %dx%d: player %d/%d, threats %d, "
                "max tracks %d, blobs %d, enemies %d\n",
                c.w, c.h, gridW, gridH, playerSeen, frames, threats,
                maxTracks, maxBlobs, maxEnemies);
}

}  // namespace

int main() {
    rendera::EngineConfig cfg;
    cfg.gridW = 200;
    cfg.gridH = 112;
    cfg.playerAnchorX = 0.5f;
    cfg.playerAnchorY = 0.6f;
    cfg.playerAnchorLocked = true;

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

    if (failures == 0) {
        std::printf("OK: engine ran clean under ASan/UBSan.\n");
        return 0;
    }
    std::printf("%d failure(s).\n", failures);
    return 1;
}
