#include "rendera_core.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>

namespace rendera {
namespace {

constexpr float kPi = 3.14159265358979323846f;

inline int nextPow2(int v) {
    int p = 1;
    while (p < v) p <<= 1;
    return p;
}

inline float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

inline int ufFind(std::vector<int32_t>& parent, int a) {
    while (parent[a] != a) {
        parent[a] = parent[parent[a]];
        a = parent[a];
    }
    return a;
}

inline void ufUnion(std::vector<int32_t>& parent, int a, int b) {
    const int ra = ufFind(parent, a);
    const int rb = ufFind(parent, b);
    if (ra == rb) return;
    // Lowest label wins so a root is always the component's minimum label.
    if (ra < rb) parent[rb] = ra;
    else parent[ra] = rb;
}

// In place iterative radix-2 Cooley-Tukey. `n` must be a power of two and
// `n` must divide the twiddle table length so `tw = k * (len / n)` indexes it.
void fftRadix2(float* re, float* im, int n, bool inverse,
               const std::vector<float>& cosT, const std::vector<float>& sinT) {
    const int tableLen = static_cast<int>(cosT.size());

    for (int i = 1, j = 0; i < n; ++i) {
        int bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) {
            std::swap(re[i], re[j]);
            std::swap(im[i], im[j]);
        }
    }
    for (int len = 2; len <= n; len <<= 1) {
        const int half = len >> 1;
        const int twStep = tableLen / len;
        for (int i = 0; i < n; i += len) {
            int tw = 0;
            for (int k = 0; k < half; ++k, tw += twStep) {
                const float wr = cosT[tw];
                const float wi = inverse ? sinT[tw] : -sinT[tw];
                const int a = i + k;
                const int b = a + half;
                const float tr = re[b] * wr - im[b] * wi;
                const float ti = re[b] * wi + im[b] * wr;
                re[b] = re[a] - tr;
                im[b] = im[a] - ti;
                re[a] += tr;
                im[a] += ti;
            }
        }
    }
    if (inverse) {
        const float inv = 1.0f / static_cast<float>(n);
        for (int i = 0; i < n; ++i) {
            re[i] *= inv;
            im[i] *= inv;
        }
    }
}

}  // namespace

// ---------------------------------------------------------------------------
// OpenCV seam fallback.
//
// When CMake did not find an OpenCV Android SDK it does not compile
// rendera_opencv.cpp, so these two symbols have to exist for the linker.
// Reporting "unavailable" makes the core take its own stages.
// ---------------------------------------------------------------------------
#if !defined(RENDERA_WITH_OPENCV)
bool opencvMotionDiffAvailable() { return false; }

bool opencvEstimateMotionAndDiff(const uint8_t* current,
                                 const uint8_t* previous,
                                 int width,
                                 int height,
                                 int stride,
                                 int searchRadius,
                                 float minConfidence,
                                 uint8_t noiseFloor,
                                 MotionEstimate& outMotion,
                                 uint8_t* diffOut) {
    (void)current; (void)previous; (void)width; (void)height; (void)stride;
    (void)searchRadius; (void)minConfidence; (void)noiseFloor;
    (void)outMotion; (void)diffOut;
    return false;
}
#endif

// ===========================================================================
// Construction
// ===========================================================================

VisionEngine::VisionEngine(const EngineConfig& cfg) : cfg_(cfg) {
    gridW_ = std::max(16, cfg_.gridW);
    gridH_ = std::max(16, cfg_.gridH);
    totalCells_ = gridW_ * gridH_;

    luma_.assign(static_cast<size_t>(totalCells_), 0);
    prevLuma_.assign(static_cast<size_t>(totalCells_), 0);
    green_.assign(static_cast<size_t>(totalCells_), 0);
    red_.assign(static_cast<size_t>(totalCells_), 0);
    sat_.assign(static_cast<size_t>(totalCells_), 0);
    diff_.assign(static_cast<size_t>(totalCells_), 0);
    valid_.assign(static_cast<size_t>(totalCells_), 0);

    // Half resolution plane for the coarse correlation, padded to a power of
    // two for the FFT. Half resolution keeps the correlation cost well inside
    // the frame budget while still covering +/- (fftW/4) cells at full res.
    motW_ = std::max(16, gridW_ / 2);
    motH_ = std::max(16, gridH_ / 2);
    fftW_ = nextPow2(motW_);
    fftH_ = nextPow2(motH_);

    motCur_.assign(static_cast<size_t>(motW_) * motH_, 0);
    motPrev_.assign(static_cast<size_t>(motW_) * motH_, 0);

    const size_t specSize = static_cast<size_t>(fftW_) * fftH_;
    specReCur_.assign(specSize, 0.0f);
    specImCur_.assign(specSize, 0.0f);
    specRePrev_.assign(specSize, 0.0f);
    specImPrev_.assign(specSize, 0.0f);
    corr_.assign(specSize, 0.0f);

    const int scratch = std::max(fftW_, fftH_);
    rowRe_.assign(static_cast<size_t>(scratch), 0.0f);
    rowIm_.assign(static_cast<size_t>(scratch), 0.0f);

    // Twiddle table. Every transform length used here divides the table length,
    // so a single table indexed by (k * tableLen / len) serves rows and columns.
    fftCos_.assign(static_cast<size_t>(scratch), 1.0f);
    fftSin_.assign(static_cast<size_t>(scratch), 0.0f);
    for (int i = 0; i < scratch; ++i) {
        const float a = -2.0f * kPi * static_cast<float>(i) / static_cast<float>(scratch);
        fftCos_[static_cast<size_t>(i)] = std::cos(a);
        fftSin_[static_cast<size_t>(i)] = std::sin(a);
    }

    // Separable Hann window, with a raised floor. A pure Hann window weights
    // the border to zero, which throws away exactly the pixels that carry the
    // camera motion signal; the floor keeps them usable.
    hannX_.assign(static_cast<size_t>(motW_), 0.0f);
    hannY_.assign(static_cast<size_t>(motH_), 0.0f);
    for (int x = 0; x < motW_; ++x) {
        hannX_[static_cast<size_t>(x)] =
            0.30f + 0.70f * (0.5f - 0.5f * std::cos(2.0f * kPi * static_cast<float>(x) /
                                                     static_cast<float>(motW_)));
    }
    for (int y = 0; y < motH_; ++y) {
        hannY_[static_cast<size_t>(y)] =
            0.30f + 0.70f * (0.5f - 0.5f * std::cos(2.0f * kPi * static_cast<float>(y) /
                                                     static_cast<float>(motH_)));
    }

    labels_.assign(static_cast<size_t>(totalCells_), -1);
    parent_.assign(static_cast<size_t>(totalCells_), 0);
    maskBitmap_.assign(static_cast<size_t>(totalCells_), 0);

    const size_t maxLabels = static_cast<size_t>(totalCells_);
    blobArea_.assign(maxLabels, 0);
    blobMinX_.assign(maxLabels, 0);
    blobMinY_.assign(maxLabels, 0);
    blobMaxX_.assign(maxLabels, 0);
    blobMaxY_.assign(maxLabels, 0);
    blobSumX_.assign(maxLabels, 0.0f);
    blobSumY_.assign(maxLabels, 0.0f);
    blobSumStrength_.assign(maxLabels, 0.0f);
    blobPeakStrength_.assign(maxLabels, 0.0f);

    blobs_.reserve(static_cast<size_t>(std::max(1, cfg_.maxObservations)));
    tracks_.reserve(static_cast<size_t>(std::max(1, cfg_.maxTracks)));
    enemies_.reserve(static_cast<size_t>(std::max(1, cfg_.maxEnemies)));
    blobUsed_.assign(static_cast<size_t>(std::max(1, cfg_.maxObservations)), 0);
    masks_.assign(8, MaskRegion());
}

void VisionEngine::setMask(const MaskRegion* regions, int count) {
    const int n = std::max(0, std::min(count, 16));
    masks_.assign(static_cast<size_t>(n), MaskRegion());
    for (int i = 0; i < n; ++i) masks_[static_cast<size_t>(i)] = regions[i];

    std::fill(maskBitmap_.begin(), maskBitmap_.end(), 0);
    for (const MaskRegion& r : masks_) {
        if (!r.enabled) continue;
        const int x0 = static_cast<int>(std::floor((r.cx - r.halfW) * gridW_));
        const int x1 = static_cast<int>(std::ceil((r.cx + r.halfW) * gridW_));
        const int y0 = static_cast<int>(std::floor((r.cy - r.halfH) * gridH_));
        const int y1 = static_cast<int>(std::ceil((r.cy + r.halfH) * gridH_));
        const int cx0 = std::max(0, x0);
        const int cx1 = std::min(gridW_, x1);
        const int cy0 = std::max(0, y0);
        const int cy1 = std::min(gridH_, y1);
        for (int y = cy0; y < cy1; ++y) {
            uint8_t* row = maskBitmap_.data() + static_cast<size_t>(y) * gridW_;
            for (int x = cx0; x < cx1; ++x) row[x] = 1;
        }
    }
}

void VisionEngine::reset() {
    std::fill(luma_.begin(), luma_.end(), 0);
    std::fill(prevLuma_.begin(), prevLuma_.end(), 0);
    std::fill(green_.begin(), green_.end(), 0);
    std::fill(red_.begin(), red_.end(), 0);
    std::fill(sat_.begin(), sat_.end(), 0);
    std::fill(diff_.begin(), diff_.end(), 0);
    std::fill(valid_.begin(), valid_.end(), 0);
    std::fill(motCur_.begin(), motCur_.end(), 0);
    std::fill(motPrev_.begin(), motPrev_.end(), 0);
    hasPrev_ = false;
    lastPts_ = 0;
    runPts_ = 0;
    ingestPts_ = 0;
    frameDt_ = 1.0f / 60.0f;
    motion_ = MotionEstimate();
    player_ = PlayerState();
    threat_ = ThreatSolution();
    tracks_.clear();
    blobs_.clear();
    enemies_.clear();
    nextTrackId_ = 1;
    stats_ = FrameStats();
}

float VisionEngine::gridToScreenX(float gx) const {
    return gx * static_cast<float>(screenW_) / static_cast<float>(gridW_);
}

float VisionEngine::gridToScreenY(float gy) const {
    return gy * static_cast<float>(screenH_) / static_cast<float>(gridH_);
}

void VisionEngine::screenToGrid(float sx, float sy, float& gx, float& gy) const {
    gx = sx * static_cast<float>(gridW_) / static_cast<float>(screenW_);
    gy = sy * static_cast<float>(gridH_) / static_cast<float>(screenH_);
}

// ===========================================================================
// Stage 1: ingest + box downsample
// ===========================================================================

void VisionEngine::downsampleFromYuv(const uint8_t* y, int yStride,
                                     const uint8_t* u, const uint8_t* v, int uvStride,
                                     int fullW, int fullH, int chromaW, int chromaH) {
    if (capW_ <= 0 || capH_ <= 0 || y == nullptr) return;

    // Bin origins are recomputed per call because the capture geometry can
    // change on rotation. Integer steps keep the gather branch free.
    const int stepX = std::max(1, fullW / gridW_);
    const int stepY = std::max(1, fullH / gridH_);
    const int cStepX = std::max(1, chromaW / std::max(1, gridW_));
    const int cStepY = std::max(1, chromaH / std::max(1, gridH_));
    const bool haveChroma = (u != nullptr && v != nullptr && chromaW > 0 && chromaH > 0);

    for (int gy = 0; gy < gridH_; ++gy) {
        int sy = gy * stepY;
        if (sy >= fullH) sy = fullH - 1;
        const uint8_t* yRow = y + static_cast<size_t>(sy) * yStride;
        const int outRow = gy * gridW_;

        for (int gx = 0; gx < gridW_; ++gx) {
            int sx = gx * stepX;
            if (sx >= fullW) sx = fullW - 1;
            luma_[static_cast<size_t>(outRow + gx)] = yRow[sx];
        }

        if (haveChroma) {
            int cy = gy * cStepY;
            if (cy >= chromaH) cy = chromaH - 1;
            const uint8_t* uRow = u + static_cast<size_t>(cy) * uvStride;
            const uint8_t* vRow = v + static_cast<size_t>(cy) * uvStride;
            for (int gx = 0; gx < gridW_; ++gx) {
                int cx = gx * cStepX;
                if (cx >= chromaW) cx = chromaW - 1;
                const size_t ci = static_cast<size_t>(outRow + gx);

                // BT.601 studio-swing YUV -> normalised RGB. MediaProjection
                // hands us video-range chroma, so the 16/219 and 128/224 offsets
                // matter; skipping them skews every recovered channel.
                // Reuse the luma already written to the grid for this cell: the
                // source column index is scoped to the luma loop above.
                const float yp =
                    (static_cast<float>(luma_[ci]) - 16.0f) * (1.0f / 219.0f);
                const float cb = (static_cast<float>(uRow[cx]) - 128.0f) * (1.0f / 224.0f);
                const float cr = (static_cast<float>(vRow[cx]) - 128.0f) * (1.0f / 224.0f);

                // Deliberately NOT clamped to 0..1 here, only the final 0..255
                // score is. Measured over the real Brawl Stars palette the two
                // forms agree, but clamping per channel is not safe in
                // principle: a pixel whose R and G both exceed 1.0 (a vivid
                // green ring is exactly that) would clamp to equality, and
                // G - max(R, B) would collapse to zero, silently rejecting the
                // brightest greens in the game. A difference of out of gamut
                // values is harmless; a difference of clamped values is not.
                const float r = yp + 1.402f * cr;
                const float g = yp - 0.344136f * cb - 0.714136f * cr;
                const float b = yp + 1.772f * cb;

                const float mx = std::max(r, std::max(g, b));
                const float mn = std::min(r, std::min(g, b));

                // Opponent signals, not raw chroma. gOpponent = G - max(R, B) is
                // positive only for a genuinely green pixel, and it is hue
                // correct, which the previous (2*cr - cb) form was not.
                const float gOpp = g - std::max(r, b);
                const float rOpp = r - std::max(g, b);

                green_[ci] = static_cast<uint8_t>(clampf(gOpp * kOpponentScale, 0.0f, 255.0f));
                red_[ci] = static_cast<uint8_t>(clampf(rOpp * kOpponentScale, 0.0f, 255.0f));
                // Saturation is what separates Brawl Stars' vivid selection ring
                // (~240) from grass of the same hue (~80).
                sat_[ci] = static_cast<uint8_t>(clampf((mx - mn) * kOpponentScale, 0.0f, 255.0f));
            }
        } else {
            std::memset(green_.data() + outRow, 0, static_cast<size_t>(gridW_));
            std::memset(red_.data() + outRow, 0, static_cast<size_t>(gridW_));
            std::memset(sat_.data() + outRow, 0, static_cast<size_t>(gridW_));
        }
    }
}

bool VisionEngine::ingestYuv(const uint8_t* yPlane, int yStride,
                             const uint8_t* uPlane, const uint8_t* vPlane, int uvStride,
                             int fullW, int fullH, int chromaW, int chromaH,
                             uint64_t ptsNanos) {
    if (yPlane == nullptr || fullW <= 0 || fullH <= 0) return false;

    if (capW_ != fullW || capH_ != fullH || capYStride_ != yStride ||
        capUvStride_ != uvStride) {
        // Geometry changed (rotation, or a resolution switch). Reconfigure and
        // drop history, otherwise the difference stage would compare buffers
        // that do not correspond to the same screen.
        configureCapture(fullW, fullH, chromaW, chromaH, yStride, uvStride);
        hasPrev_ = false;
    }

    const bool hadPrevious = hasPrev_;
    std::memcpy(prevLuma_.data(), luma_.data(), static_cast<size_t>(totalCells_));
    downsampleFromYuv(yPlane, yStride, uPlane, vPlane, uvStride,
                      fullW, fullH, chromaW, chromaH);
    if (!hadPrevious) {
        // Mirror the first frame into the history. Differencing against the
        // zero-initialised buffer would light up the entire frame on the first
        // call and hand the tracker a screen-sized "projectile".
        std::memcpy(prevLuma_.data(), luma_.data(), static_cast<size_t>(totalCells_));
    }
    hasPrev_ = true;
    ingestPts_ = ptsNanos;
    return true;
}

// ===========================================================================
// Stage 2: global motion estimation by phase correlation
// ===========================================================================

void VisionEngine::estimateGlobalMotion() {
    motion_ = MotionEstimate();
    if (!hasPrev_) return;

    // --- build half resolution planes ---
    const int mStepX = std::max(1, gridW_ / motW_);
    const int mStepY = std::max(1, gridH_ / motH_);
    for (int my = 0; my < motH_; ++my) {
        const int gy = std::min(gridH_ - 1, my * mStepY);
        const int rowIn = gy * gridW_;
        const int rowOut = my * motW_;
        for (int mx = 0; mx < motW_; ++mx) {
            const int gx = std::min(gridW_ - 1, mx * mStepX);
            motCur_[static_cast<size_t>(rowOut + mx)] = luma_[static_cast<size_t>(rowIn + gx)];
        }
    }
    // motPrev_ is NOT touched here: it still holds the previous frame, and the
    // correlation consumes it. Overwriting it first would correlate the current
    // frame against itself, which always peaks at (0,0) and silently disables
    // camera motion compensation entirely.
    //
    // The previous plane advances on EVERY exit path below, including the early
    // rejects, so a low confidence frame cannot leave the history several frames
    // stale. That is why the work is delegated rather than inlined.
    computeCorrelatedMotion();

    std::memcpy(motPrev_.data(), motCur_.data(), motCur_.size());
}

bool VisionEngine::computeCorrelatedMotion() {
    // --- window, mean remove, forward 2D FFT for both frames ---
    auto loadAndTransform = [&](const std::vector<uint8_t>& src,
                                std::vector<float>& re, std::vector<float>& im) {
        double mean = 0.0;
        const size_t total = motCur_.size();
        for (size_t i = 0; i < total; ++i) mean += static_cast<double>(src[i]);
        mean /= static_cast<double>(total > 0 ? total : 1);
        const float fm = static_cast<float>(mean);

        std::fill(re.begin(), re.end(), 0.0f);
        std::fill(im.begin(), im.end(), 0.0f);
        for (int y = 0; y < motH_; ++y) {
            const float wy = hannY_[static_cast<size_t>(y)];
            const int srcRow = y * motW_;
            const int dstRow = y * fftW_;
            for (int x = 0; x < motW_; ++x) {
                re[static_cast<size_t>(dstRow + x)] =
                    (static_cast<float>(src[static_cast<size_t>(srcRow + x)]) - fm) *
                    wy * hannX_[static_cast<size_t>(x)];
            }
        }
        fft2d(re, im);
    };

    loadAndTransform(motCur_, specReCur_, specImCur_);
    loadAndTransform(motPrev_, specRePrev_, specImPrev_);

    // --- cross power spectrum conj(G) * F, whose inverse transform peaks at the
    //     translation (dx, dy) that maps previous onto current ---
    for (size_t i = 0; i < corr_.size(); ++i) {
        const float ar = specReCur_[i], ai = specImCur_[i];
        const float br = specRePrev_[i], bi = specImPrev_[i];
        float xr = ar * br + ai * bi;
        float xi = bi * ar - ai * br;   // conj(a) * b
        const float mag = std::sqrt(xr * xr + xi * xi);
        if (mag > 1e-9f) {
            xr /= mag;
            xi /= mag;
        } else {
            xr = 0.0f;
            xi = 0.0f;
        }
        specReCur_[i] = xr;
        specImCur_[i] = xi;
    }
    fft2d(specReCur_, specImCur_, true);
    std::memcpy(corr_.data(), specReCur_.data(), corr_.size() * sizeof(float));

    // --- peak search, restricted to physically reachable shifts ---
    const int limX = std::max(1, std::min(cfg_.motionMaxShiftHalfRes, fftW_ / 2 - 1));
    const int limY = std::max(1, std::min(cfg_.motionMaxShiftHalfRes, fftH_ / 2 - 1));

    // Iterate SIGNED shifts and wrap each into its DFT bin. Scanning bins
    // [0, lim] instead would make every negative shift unreachable, which would
    // mean any leftward or upward camera pan reported as "no motion".
    float bestVal = -std::numeric_limits<float>::infinity();
    int bestX = 0, bestY = 0;
    double sumAbs = 0.0;
    int64_t counted = 0;
    for (int sy = -limY; sy <= limY; ++sy) {
        const int ny = (sy % fftH_ + fftH_) % fftH_;
        const size_t rowBase = static_cast<size_t>(ny) * fftW_;
        for (int sx = -limX; sx <= limX; ++sx) {
            const int nx = (sx % fftW_ + fftW_) % fftW_;
            const float v = corr_[rowBase + static_cast<size_t>(nx)];
            sumAbs += std::fabs(v);
            ++counted;
            if (v > bestVal) {
                bestVal = v;
                bestX = sx;
                bestY = sy;
            }
        }
    }
    const float meanAbs =
        counted > 0 ? static_cast<float>(sumAbs / static_cast<double>(counted)) : 0.0f;
    if (meanAbs <= 1e-7f || bestVal <= 0.0f) return false;

    // Peak sharpness is used only to reject a degenerate, essentially flat
    // spectrum, where "the peak" means nothing. The real quality gate is
    // measureAlignmentQuality(), which is evaluated after the shift has been
    // refined.
    const float peakRatio = bestVal / meanAbs;
    if (peakRatio < 4.0f) return false;

    // Parabolic sub-pixel refinement on the wrapped surface.
    auto sampleAt = [&](int x, int y) -> float {
        if (x < 0) x += fftW_;
        if (x >= fftW_) x -= fftW_;
        if (y < 0) y += fftH_;
        if (y >= fftH_) y -= fftH_;
        return corr_[static_cast<size_t>(y) * fftW_ + static_cast<size_t>(x)];
    };
    const float c0 = sampleAt(bestX, bestY);
    const float cL = sampleAt(bestX - 1, bestY);
    const float cR = sampleAt(bestX + 1, bestY);
    const float cU = sampleAt(bestX, bestY - 1);
    const float cD = sampleAt(bestX, bestY + 1);
    const float denX = cL - 2.0f * c0 + cR;
    const float denY = cU - 2.0f * c0 + cD;
    const float subX = (std::fabs(denX) > 1e-7f) ? 0.5f * (cL - cR) / denX : 0.0f;
    const float subY = (std::fabs(denY) > 1e-7f) ? 0.5f * (cU - cD) / denY : 0.0f;

    // Half resolution to full grid resolution, using the actual gather steps
    // rather than a hard-coded 2x, so odd grid dimensions cannot skew the scale.
    const float scaleX = static_cast<float>(gridW_) / static_cast<float>(motW_);
    const float scaleY = static_cast<float>(gridH_) / static_cast<float>(motH_);
    motion_.dx = clampf((static_cast<float>(bestX) + clampf(subX, -0.5f, 0.5f)) * scaleX,
                        -static_cast<float>(gridW_), static_cast<float>(gridW_));
    motion_.dy = clampf((static_cast<float>(bestY) + clampf(subY, -0.5f, 0.5f)) * scaleY,
                        -static_cast<float>(gridH_), static_cast<float>(gridH_));
    motion_.confidence = 0.0f;
    motion_.valid = true;
    return true;
}

void VisionEngine::fft2d(std::vector<float>& re, std::vector<float>& im, bool inverse) {
    for (int y = 0; y < fftH_; ++y) {
        const size_t off = static_cast<size_t>(y) * fftW_;
        fftRadix2(&re[off], &im[off], fftW_, inverse, fftCos_, fftSin_);
    }
    for (int x = 0; x < fftW_; ++x) {
        for (int y = 0; y < fftH_; ++y) {
            rowRe_[static_cast<size_t>(y)] = re[static_cast<size_t>(y) * fftW_ + static_cast<size_t>(x)];
            rowIm_[static_cast<size_t>(y)] = im[static_cast<size_t>(y) * fftW_ + static_cast<size_t>(x)];
        }
        fftRadix2(rowRe_.data(), rowIm_.data(), fftH_, inverse, fftCos_, fftSin_);
        for (int y = 0; y < fftH_; ++y) {
            re[static_cast<size_t>(y) * fftW_ + static_cast<size_t>(x)] = rowRe_[static_cast<size_t>(y)];
            im[static_cast<size_t>(y) * fftW_ + static_cast<size_t>(x)] = rowIm_[static_cast<size_t>(y)];
        }
    }
}

void VisionEngine::refineMotionAtFullRes() {
    if (!motion_.valid) return;
    const int baseX = static_cast<int>(std::lround(motion_.dx));
    const int baseY = static_cast<int>(std::lround(motion_.dy));
    const int r = std::max(0, cfg_.fineRefineRadius);
    if (r == 0) return;

    // Sample the interior, well away from the warping bands, so the clamp
    // regions next to the frame border cannot bias the refinement.
    const int mx0 = std::max(r + 1, gridW_ / 4);
    const int mx1 = std::min(gridW_ - r - 2, gridW_ * 3 / 4);
    const int my0 = std::max(r + 1, gridH_ / 4);
    const int my1 = std::min(gridH_ - r - 2, gridH_ * 3 / 4);
    if (mx1 <= mx0 || my1 <= my0) return;

    int bestDx = 0, bestDy = 0;
    int64_t bestSad = std::numeric_limits<int64_t>::max();
    bool evaluated = false;

    for (int dy = -r; dy <= r; ++dy) {
        const int py = baseY + dy;
        for (int dx = -r; dx <= r; ++dx) {
            const int px = baseX + dx;
            // The sample is prevLuma_[y + py][x + px], so the guard has to cover
            // the SAMPLED index, not the shift. mx0/mx1 already keep the column
            // interior; this keeps the shifted coordinate itself in range.
            if (py < 0 || py >= gridH_) continue;
            if (px + mx0 < 0 || px + mx1 >= gridW_) continue;

            int64_t sad = 0;
            for (int y = my0; y <= my1; y += 2) {
                const size_t rowOff = static_cast<size_t>(y) * gridW_;
                const size_t prevRowOff = static_cast<size_t>(y + py) * gridW_;
                for (int x = mx0; x <= mx1; x += 2) {
                    const int c = luma_[rowOff + static_cast<size_t>(x)] & 0xFF;
                    const int p = prevLuma_[prevRowOff + static_cast<size_t>(x + px)] & 0xFF;
                    sad += (c > p) ? (c - p) : (p - c);
                }
            }
            evaluated = true;
            if (sad < bestSad) {
                bestSad = sad;
                bestDx = dx;
                bestDy = dy;
            }
        }
    }

    // (0,0) is always in the candidate set, so the search cannot come out worse
    // than the coarse answer. If nothing could be evaluated (the coarse shift was
    // already at the frame edge) the coarse answer stands.
    if (!evaluated) return;
    motion_.dx = static_cast<float>(baseX + bestDx);
    motion_.dy = static_cast<float>(baseY + bestDy);
}

// ===========================================================================
// Stage 3: motion compensated difference + border invalidation
// ===========================================================================

float VisionEngine::measureAlignmentQuality() const {
    // Compare the mean absolute difference with and without the estimated
    // shift. Ratio based, so it is scale free and behaves the same on a bright
    // snow map and a dark one. Sampled on a coarse lattice, so the cost is
    // negligible next to the correlation itself.
    const int sx = static_cast<int>(std::lround(motion_.dx));
    const int sy = static_cast<int>(std::lround(motion_.dy));
    const int step = std::max(1, std::min(gridW_, gridH_) / 48);

    double alignedSum = 0.0;
    double rawSum = 0.0;
    int64_t n = 0;
    for (int y = 0; y < gridH_; y += step) {
        const size_t rowOff = static_cast<size_t>(y) * gridW_;
        const int py = y + sy;
        if (py < 0 || py >= gridH_) continue;
        const size_t prevRowOff = static_cast<size_t>(py) * gridW_;
        for (int x = 0; x < gridW_; x += step) {
            const int px = x + sx;
            if (px < 0 || px >= gridW_) continue;
            const int c = luma_[rowOff + static_cast<size_t>(x)] & 0xFF;
            const int pAligned = prevLuma_[prevRowOff + static_cast<size_t>(px)] & 0xFF;
            const int pRaw = prevLuma_[rowOff + static_cast<size_t>(x)] & 0xFF;
            alignedSum += std::abs(c - pAligned);
            rawSum += std::abs(c - pRaw);
            ++n;
        }
    }
    if (n == 0) return 0.0f;
    const double alignedMean = alignedSum / static_cast<double>(n);
    const double rawMean = rawSum / static_cast<double>(n);
    if (rawMean <= 1e-6) return 1.0f;  // nothing changed, alignment is trivially perfect
    const double q = 1.0 - (alignedMean / rawMean);
    return clampf(static_cast<float>(q), 0.0f, 1.0f);
}

void VisionEngine::invalidateBorderRing() {
    int pad = 0;
    if (motion_.valid) {
        pad = static_cast<int>(
                  std::ceil(std::max(std::fabs(motion_.dx), std::fabs(motion_.dy)))) +
              1;
    }
    pad = std::max(0, pad);
    for (int y = 0; y < gridH_; ++y) {
        uint8_t* row = valid_.data() + static_cast<size_t>(y) * gridW_;
        const bool edgeRow = (y < pad) || (y >= gridH_ - pad);
        for (int x = 0; x < gridW_; ++x) {
            row[x] = (edgeRow || x < pad || x >= gridW_ - pad) ? 0 : 1;
        }
    }
}

void VisionEngine::buildDifference() {
    const int sx = motion_.valid ? static_cast<int>(std::lround(motion_.dx)) : 0;
    const int sy = motion_.valid ? static_cast<int>(std::lround(motion_.dy)) : 0;
    const int floorLevel = static_cast<int>(cfg_.diffNoiseFloor);

    for (int y = 0; y < gridH_; ++y) {
        int py = y + sy;
        if (py < 0) py = 0;
        if (py >= gridH_) py = gridH_ - 1;
        const size_t rowOff = static_cast<size_t>(y) * gridW_;
        const size_t prevRowOff = static_cast<size_t>(py) * gridW_;
        for (int x = 0; x < gridW_; ++x) {
            int px = x + sx;
            if (px < 0) px = 0;
            if (px >= gridW_) px = gridW_ - 1;
            const int c = luma_[rowOff + static_cast<size_t>(x)] & 0xFF;
            const int p = prevLuma_[prevRowOff + static_cast<size_t>(px)] & 0xFF;
            const int d = (c > p) ? (c - p) : (p - c);
            diff_[rowOff + static_cast<size_t>(x)] =
                d > floorLevel ? static_cast<uint8_t>(d) : 0;
        }
    }
    invalidateBorderRing();
}

// ===========================================================================
// Stage 4: connected component blob extraction (two pass, allocation free)
// ===========================================================================

void VisionEngine::extractBlobs() {
    blobs_.clear();

    int32_t nextLabel = 0;
    for (int y = 0; y < gridH_; ++y) {
        const size_t rowOff = static_cast<size_t>(y) * gridW_;
        for (int x = 0; x < gridW_; ++x) {
            const size_t i = rowOff + static_cast<size_t>(x);
            if (diff_[i] == 0 || valid_[i] == 0 || maskBitmap_[i] != 0) {
                labels_[i] = -1;
                continue;
            }
            labels_[i] = nextLabel;
            parent_[static_cast<size_t>(nextLabel)] = nextLabel;
            if (x > 0 && labels_[i - 1] >= 0) {
                parent_[static_cast<size_t>(nextLabel)] = ufFind(parent_, labels_[i - 1]);
            }
            if (y > 0) {
                const size_t up = static_cast<size_t>(gridW_);
                if (labels_[i - up] >= 0) {
                    ufUnion(parent_, nextLabel, labels_[i - up]);
                }
                // NE is (x + 1, y - 1) => i - up + 1, valid while x < gridW_ - 1.
                if (x + 1 < gridW_ && labels_[i - up + 1] >= 0) {
                    ufUnion(parent_, nextLabel, labels_[i - up + 1]);
                }
                // NW is (x - 1, y - 1) => i - up - 1, valid while x > 0.
                if (x > 0 && labels_[i - up - 1] >= 0) {
                    ufUnion(parent_, nextLabel, labels_[i - up - 1]);
                }
            }
            ++nextLabel;
        }
    }
    if (nextLabel == 0) {
        stats_.blobCount = 0;
        return;
    }

    for (int l = 0; l < nextLabel; ++l) {
        blobArea_[static_cast<size_t>(l)] = 0;
        blobSumX_[static_cast<size_t>(l)] = 0.0f;
        blobSumY_[static_cast<size_t>(l)] = 0.0f;
        blobSumStrength_[static_cast<size_t>(l)] = 0.0f;
        blobPeakStrength_[static_cast<size_t>(l)] = 0.0f;
        blobMinX_[static_cast<size_t>(l)] = gridW_;
        blobMinY_[static_cast<size_t>(l)] = gridH_;
        blobMaxX_[static_cast<size_t>(l)] = -1;
        blobMaxY_[static_cast<size_t>(l)] = -1;
    }

    for (int y = 0; y < gridH_; ++y) {
        const size_t rowOff = static_cast<size_t>(y) * gridW_;
        for (int x = 0; x < gridW_; ++x) {
            const size_t i = rowOff + static_cast<size_t>(x);
            const int32_t lab = labels_[i];
            if (lab < 0) continue;
            const int root = ufFind(parent_, lab);
            labels_[i] = root;
            const size_t r = static_cast<size_t>(root);
            const float d = static_cast<float>(diff_[i]);
            blobArea_[r] += 1;
            blobSumX_[r] += static_cast<float>(x);
            blobSumY_[r] += static_cast<float>(y);
            blobSumStrength_[r] += d;
            if (d > blobPeakStrength_[r]) blobPeakStrength_[r] = d;
            if (x < blobMinX_[r]) blobMinX_[r] = x;
            if (x > blobMaxX_[r]) blobMaxX_[r] = x;
            if (y < blobMinY_[r]) blobMinY_[r] = y;
            if (y > blobMaxY_[r]) blobMaxY_[r] = y;
        }
    }

    const float spcX = static_cast<float>(screenW_) / static_cast<float>(gridW_);
    const float spcY = static_cast<float>(screenH_) / static_cast<float>(gridH_);

    for (int l = 0; l < nextLabel; ++l) {
        const size_t r = static_cast<size_t>(l);
        const int area = blobArea_[r];
        if (area < cfg_.blobMinArea || area > cfg_.blobMaxArea) continue;
        const int bw = blobMaxX_[r] - blobMinX_[r] + 1;
        const int bh = blobMaxY_[r] - blobMinY_[r] + 1;
        if (bw <= 0 || bh <= 0) continue;
        const float bboxArea = static_cast<float>(bw) * static_cast<float>(bh);
        const float fill = static_cast<float>(area) / bboxArea;
        if (fill < cfg_.blobMinFill) continue;
        const float meanStrength = blobSumStrength_[r] / static_cast<float>(area);
        if (meanStrength < cfg_.blobMinMeanStrength) continue;
        // A blob must also contain at least one strong pixel. Mean strength
        // alone lets a wide, faintly different patch (compression ringing along
        // a wall edge, for instance) through.
        if (blobPeakStrength_[r] < static_cast<float>(cfg_.diffStrongThreshold)) continue;

        Blob b;
        b.gx = blobSumX_[r] / static_cast<float>(area);
        b.gy = blobSumY_[r] / static_cast<float>(area);
        b.sx = b.gx * spcX;
        b.sy = b.gy * spcY;
        b.area = area;
        b.minX = blobMinX_[r];
        b.minY = blobMinY_[r];
        b.maxX = blobMaxX_[r];
        b.maxY = blobMaxY_[r];
        b.meanStrength = meanStrength;
        b.peakStrength = blobPeakStrength_[r];
        blobs_.push_back(b);
    }

    // Strongest first; the tracker gates on relative strength anyway.
    std::sort(blobs_.begin(), blobs_.end(), [](const Blob& a, const Blob& b) {
        return (static_cast<float>(a.area) * a.meanStrength) >
               (static_cast<float>(b.area) * b.meanStrength);
    });
    if (static_cast<int>(blobs_.size()) > cfg_.maxObservations) {
        blobs_.resize(static_cast<size_t>(cfg_.maxObservations));
    }
    stats_.blobCount = static_cast<int>(blobs_.size());
}

// ===========================================================================
// Stage 5: player detection on the WORLD ANCHORED frame
// ===========================================================================
//
// The player is anchored to the world, so once the camera motion is cancelled it
// produces essentially NO motion residual. It must therefore be located in the
// aligned frame by its saturated green signature, and gated by a temporal prior
// so a patch of green scenery can never drag the reported position away.

void VisionEngine::detectPlayer() {
    player_.framesSinceSeen++;

    float searchCx, searchCy, gate;
    if (cfg_.playerAnchorLocked) {
        searchCx = cfg_.playerAnchorX * static_cast<float>(gridW_);
        searchCy = cfg_.playerAnchorY * static_cast<float>(gridH_);
        // Scaled by the grid so the tuning field means the same thing at any
        // resolution, and deliberately wider than the temporal prior: with a
        // committed anchor there is no history to lean on, so the search window
        // has to tolerate the calibration being slightly off.
        gate = cfg_.playerGateGridUnits * 2.0f;
    } else if (player_.locked) {
        screenToGrid(player_.x, player_.y, searchCx, searchCy);
        gate = cfg_.playerGateGridUnits;
    } else {
        searchCx = static_cast<float>(gridW_) * 0.5f;
        searchCy = static_cast<float>(gridH_) * 0.55f;
        gate = static_cast<float>(gridW_) * 0.45f;
    }

    const int x0 = std::max(1, static_cast<int>(searchCx - gate));
    const int x1 = std::min(gridW_ - 2, static_cast<int>(searchCx + gate));
    const int y0 = std::max(1, static_cast<int>(searchCy - gate * 0.6f));
    const int y1 = std::min(gridH_ - 2, static_cast<int>(searchCy + gate * 0.6f));
    if (x1 <= x0 || y1 <= y0) return;

    const int thr = static_cast<int>(cfg_.playerMinGreenScore);
    const int satThr = static_cast<int>(cfg_.playerMinSaturation);
    int32_t nextLabel = 0;
    for (int y = y0; y <= y1; ++y) {
        const size_t rowOff = static_cast<size_t>(y) * gridW_;
        for (int x = x0; x <= x1; ++x) {
            const size_t i = rowOff + static_cast<size_t>(x);
            // Both gates: hue AND saturation. Grass has the right hue at low
            // saturation, and the selection ring has the hue at high saturation,
            // so requiring both is what separates them.
            if (green_[i] < thr || sat_[i] < satThr || maskBitmap_[i] != 0) {
                labels_[i] = -1;
                continue;
            }
            labels_[i] = nextLabel;
            parent_[static_cast<size_t>(nextLabel)] = nextLabel;
            if (x > x0 && labels_[i - 1] >= 0) {
                parent_[static_cast<size_t>(nextLabel)] = ufFind(parent_, labels_[i - 1]);
            }
            if (y > y0) {
                const size_t up = static_cast<size_t>(gridW_);
                if (labels_[i - up] >= 0) {
                    ufUnion(parent_, nextLabel, labels_[i - up]);
                }
                // NE is (x + 1, y - 1) => i - up + 1, valid while x + 1 <= x1.
                if (x + 1 <= x1 && labels_[i - up + 1] >= 0) {
                    ufUnion(parent_, nextLabel, labels_[i - up + 1]);
                }
                // NW is (x - 1, y - 1) => i - up - 1, valid while x - 1 >= x0.
                if (x > x0 && labels_[i - up - 1] >= 0) {
                    ufUnion(parent_, nextLabel, labels_[i - up - 1]);
                }
            }
            ++nextLabel;
        }
    }
    if (nextLabel == 0) return;

    for (int l = 0; l < nextLabel; ++l) {
        const size_t r = static_cast<size_t>(l);
        blobArea_[r] = 0;
        blobSumX_[r] = 0.0f;
        blobSumY_[r] = 0.0f;
        blobSumStrength_[r] = 0.0f;
        blobMinX_[r] = gridW_;
        blobMinY_[r] = gridH_;
        blobMaxX_[r] = -1;
        blobMaxY_[r] = -1;
    }
    for (int y = y0; y <= y1; ++y) {
        const size_t rowOff = static_cast<size_t>(y) * gridW_;
        for (int x = x0; x <= x1; ++x) {
            const size_t i = rowOff + static_cast<size_t>(x);
            const int32_t lab = labels_[i];
            if (lab < 0) continue;
            const int root = ufFind(parent_, lab);
            labels_[i] = root;
            const size_t r = static_cast<size_t>(root);
            blobArea_[r] += 1;
            blobSumX_[r] += static_cast<float>(x);
            blobSumY_[r] += static_cast<float>(y);
            blobSumStrength_[r] += static_cast<float>(green_[i]);
            if (x < blobMinX_[r]) blobMinX_[r] = x;
            if (x > blobMaxX_[r]) blobMaxX_[r] = x;
            if (y < blobMinY_[r]) blobMinY_[r] = y;
            if (y > blobMaxY_[r]) blobMaxY_[r] = y;
        }
    }

    float bestScore = -std::numeric_limits<float>::infinity();
    int bestRoot = -1;
    float lockedBestDist = std::numeric_limits<float>::infinity();
    int lockedBestRoot = -1;
    for (int l = 0; l < nextLabel; ++l) {
        const size_t r = static_cast<size_t>(l);
        const int area = blobArea_[r];
        if (area < cfg_.playerMinComponentArea || area > cfg_.playerMaxComponentArea) continue;
        const int bw = blobMaxX_[r] - blobMinX_[r] + 1;
        const int bh = blobMaxY_[r] - blobMinY_[r] + 1;
        if (bw <= 0 || bh <= 0) continue;
        const float bboxArea = static_cast<float>(bw) * static_cast<float>(bh);
        const float compactness = static_cast<float>(area) / bboxArea;
        if (compactness < cfg_.playerMinCompactness) continue;  // reject foliage
        const float aspect = static_cast<float>(bw) / static_cast<float>(bh);
        if (aspect > cfg_.playerMaxAspect) continue;           // reject wide runs
        const float greenness = blobSumStrength_[r] / static_cast<float>(area);

        const float cxg = blobSumX_[r] / static_cast<float>(area);
        const float cyg = blobSumY_[r] / static_cast<float>(area);
        const float distCells = std::hypot(cxg - searchCx, cyg - searchCy);
        if (distCells >= gate) continue;

        // The distance term is what stops a large green bush from winning: a
        // distant candidate is scaled down hard, not merely disfavoured.
        float score = greenness * 0.6f + compactness * 120.0f;
        score *= 1.0f - 0.80f * (distCells / gate);

        if (cfg_.playerAnchorLocked) {
            // A calibration the user committed to is authoritative, so pick the
            // candidate NEAREST the anchor rather than the best scoring one. A
            // weighted score still lets a big vivid patch outvote the ring the
            // user actually locked onto.
            if (distCells < lockedBestDist) {
                lockedBestDist = distCells;
                lockedBestRoot = l;
            }
        }
        if (score > bestScore) {
            bestScore = score;
            bestRoot = l;
        }
    }

    if (cfg_.playerAnchorLocked && lockedBestRoot >= 0) bestRoot = lockedBestRoot;
    if (bestRoot < 0) {
        if (player_.framesSinceSeen > 12) player_.locked = false;
        return;
    }

    const size_t r = static_cast<size_t>(bestRoot);
    const float areaF = static_cast<float>(blobArea_[r]);
    const float detX = gridToScreenX(blobSumX_[r] / areaF);
    const float detY = gridToScreenY(blobSumY_[r] / areaF);
    const float greenness = blobSumStrength_[r] / areaF;

    // The green signature sits on the ground under the brawler, so the collidable
    // centre is slightly above it.
    const float footOffset = static_cast<float>(screenH_) * 0.012f;
    const float measX = detX;
    const float measY = detY - footOffset;

    float nx = measX;
    float ny = measY;
    if (player_.valid) {
        // Responsive but not jittery.
        nx = player_.x * 0.45f + measX * 0.55f;
        ny = player_.y * 0.45f + measY * 0.55f;
    }

    if (player_.valid && frameDt_ > 1e-4f) {
        player_.vx = (nx - player_.x) / frameDt_;
        player_.vy = (ny - player_.y) / frameDt_;
    } else {
        player_.vx = 0.0f;
        player_.vy = 0.0f;
    }
    player_.x = nx;
    player_.y = ny;
    player_.valid = true;
    player_.locked = true;
    player_.framesSinceSeen = 0;
    player_.componentArea = blobArea_[r];
    player_.greenness = greenness;
}

// ===========================================================================
// Stage 5b: enemy detection on the WORLD ANCHORED frame
// ===========================================================================
//
// Enemy selection rings and health bars are red. Like the player they are
// anchored to the world, so they are found in the aligned frame, not in the
// motion residual. They matter because walking into an enemy is a death, so the
// escape planner keeps clear of them.

void VisionEngine::detectEnemies() {
    enemies_.clear();

    const int thr = static_cast<int>(cfg_.enemyMinRedScore);
    const int satThr = static_cast<int>(cfg_.enemyMinSaturation);
    int32_t nextLabel = 0;
    for (int y = 1; y < gridH_ - 1; ++y) {
        const size_t rowOff = static_cast<size_t>(y) * gridW_;
        for (int x = 1; x < gridW_ - 1; ++x) {
            const size_t i = rowOff + static_cast<size_t>(x);
            // The red opponent signal is negative for green, so a selection ring
            // cannot be mistaken for an enemy even before the saturation gate.
            if (red_[i] < thr || sat_[i] < satThr || maskBitmap_[i] != 0) {
                labels_[i] = -1;
                continue;
            }
            labels_[i] = nextLabel;
            parent_[static_cast<size_t>(nextLabel)] = nextLabel;
            if (labels_[i - 1] >= 0) {
                parent_[static_cast<size_t>(nextLabel)] = ufFind(parent_, labels_[i - 1]);
            }
            if (labels_[i - static_cast<size_t>(gridW_)] >= 0) {
                ufUnion(parent_, nextLabel, labels_[i - static_cast<size_t>(gridW_)]);
            }
            if (labels_[i - static_cast<size_t>(gridW_) + 1] >= 0) {
                ufUnion(parent_, nextLabel, labels_[i - static_cast<size_t>(gridW_) + 1]);
            }
            if (labels_[i - static_cast<size_t>(gridW_) - 1] >= 0) {
                ufUnion(parent_, nextLabel, labels_[i - static_cast<size_t>(gridW_) - 1]);
            }
            ++nextLabel;
        }
    }
    if (nextLabel == 0) return;

    for (int l = 0; l < nextLabel; ++l) {
        const size_t r = static_cast<size_t>(l);
        blobArea_[r] = 0;
        blobSumX_[r] = 0.0f;
        blobSumY_[r] = 0.0f;
        blobSumStrength_[r] = 0.0f;
        blobMinX_[r] = gridW_;
        blobMinY_[r] = gridH_;
        blobMaxX_[r] = -1;
        blobMaxY_[r] = -1;
    }
    for (int y = 1; y < gridH_ - 1; ++y) {
        const size_t rowOff = static_cast<size_t>(y) * gridW_;
        for (int x = 1; x < gridW_ - 1; ++x) {
            const size_t i = rowOff + static_cast<size_t>(x);
            const int32_t lab = labels_[i];
            if (lab < 0) continue;
            const int root = ufFind(parent_, lab);
            labels_[i] = root;
            const size_t r = static_cast<size_t>(root);
            blobArea_[r] += 1;
            blobSumX_[r] += static_cast<float>(x);
            blobSumY_[r] += static_cast<float>(y);
            blobSumStrength_[r] += static_cast<float>(red_[i]);
            if (x < blobMinX_[r]) blobMinX_[r] = x;
            if (x > blobMaxX_[r]) blobMaxX_[r] = x;
            if (y < blobMinY_[r]) blobMinY_[r] = y;
            if (y > blobMaxY_[r]) blobMaxY_[r] = y;
        }
    }

    const float spcX = static_cast<float>(screenW_) / static_cast<float>(gridW_);
    const float spcY = static_cast<float>(screenH_) / static_cast<float>(gridH_);

    for (int l = 0; l < nextLabel; ++l) {
        const size_t r = static_cast<size_t>(l);
        const int area = blobArea_[r];
        if (area < cfg_.enemyMinComponentArea || area > cfg_.enemyMaxComponentArea) continue;
        const int bw = blobMaxX_[r] - blobMinX_[r] + 1;
        const int bh = blobMaxY_[r] - blobMinY_[r] + 1;
        if (bw <= 0 || bh <= 0) continue;
        const float compactness =
            static_cast<float>(area) / (static_cast<float>(bw) * static_cast<float>(bh));
        if (compactness < cfg_.enemyMinCompactness) continue;

        EnemyMark e;
        e.x = (blobSumX_[r] / static_cast<float>(area)) * spcX;
        e.y = (blobSumY_[r] / static_cast<float>(area)) * spcY;
        e.area = area;
        e.redness = blobSumStrength_[r] / static_cast<float>(area);
        enemies_.push_back(e);
    }
    if (static_cast<int>(enemies_.size()) > cfg_.maxEnemies) {
        enemies_.resize(static_cast<size_t>(cfg_.maxEnemies));
    }
}

// ===========================================================================
// Stage 6: constant velocity Kalman tracking
// ===========================================================================

void VisionEngine::updateTracks() {
    const float screenW = static_cast<float>(screenW_);
    const float d = frameDt_;
    const float gate = cfg_.trackGatePixels;
    const float gate2 = gate * gate;
    const float R = cfg_.trackMeasureNoise;

    // --- predict: P = F P F' + Q, F = [[1, dt], [0, 1]] ---
    for (Track& t : tracks_) {
        t.x += t.vx * d;
        t.y += t.vy * d;
        t.pxx00 += 2.0f * d * t.pxx01 + d * d * t.pxx11 + cfg_.trackProcessPos;
        t.pxx01 += d * t.pxx11;
        t.pxx11 += cfg_.trackProcessVel;
        t.pyy00 += 2.0f * d * t.pyy01 + d * d * t.pyy11 + cfg_.trackProcessPos;
        t.pyy01 += d * t.pyy11;
        t.pyy11 += cfg_.trackProcessVel;
    }

    if (blobUsed_.size() < blobs_.size()) blobUsed_.resize(blobs_.size());
    std::fill(blobUsed_.begin(), blobUsed_.end(), 0);

    // --- associate: nearest blob inside the gate, greedy over tracks ---
    for (Track& t : tracks_) {
        int best = -1;
        float bestD2 = gate2;
        for (size_t i = 0; i < blobs_.size(); ++i) {
            if (blobUsed_[i]) continue;
            const float bx = blobs_[i].sx - t.x;
            const float by = blobs_[i].sy - t.y;
            const float dd = bx * bx + by * by;
            if (dd < bestD2) {
                bestD2 = dd;
                best = static_cast<int>(i);
            }
        }
        if (best < 0) {
            t.misses++;
            continue;
        }
        blobUsed_[static_cast<size_t>(best)] = 1;

        const float inX = blobs_[static_cast<size_t>(best)].sx - t.x;
        const float inY = blobs_[static_cast<size_t>(best)].sy - t.y;
        const float dd = std::max(d, 1e-3f);

        // Scalar measurement update per axis: S = p00 + R, K = [p00/S, p01/S],
        // P' = P - K S K'.
        const float Sx = t.pxx00 + R;
        const float kx0 = t.pxx00 / Sx;
        const float kx1 = t.pxx01 / Sx;
        const float Sy = t.pyy00 + R;
        const float ky0 = t.pyy00 / Sy;
        const float ky1 = t.pyy01 / Sy;

        // Snapshot the prediction BEFORE any correction. The predict step above
        // already advanced the state by v*dt, so this IS the predicted velocity.
        // Taking it afterwards would compare the observation against the
        // corrected velocity, which by construction already absorbed part of the
        // very displacement being measured, and would score every brand new
        // track as perfectly straight.
        const float predictedVx = t.vx;
        const float predictedVy = t.vy;

        if (t.hits == 0) {
            // First measurement: seed velocity straight from the observed
            // displacement, which removes the multi-frame velocity ramp-up that
            // made real tracking never classify anything as a projectile.
            t.vx = inX / dd;
            t.vy = inY / dd;
        } else {
            t.x += kx0 * inX;
            t.y += ky0 * inY;
            t.vx += kx1 * inX;
            t.vy += ky1 * inY;
        }

        t.pxx00 = std::max(1.0f, t.pxx00 - kx0 * kx0 * Sx);
        t.pxx01 = t.pxx01 - kx1 * kx0 * Sx;
        t.pxx11 = std::max(1.0f, t.pxx11 - kx1 * kx1 * Sx);
        t.pyy00 = std::max(1.0f, t.pyy00 - ky0 * ky0 * Sy);
        t.pyy01 = t.pyy01 - ky1 * ky0 * Sy;
        t.pyy11 = std::max(1.0f, t.pyy11 - ky1 * ky1 * Sy);

        // Straightness: how closely the observed displacement matches what the
        // track PREDICTED, before any correction is applied. Comparing against
        // the post-update velocity would score 1.0 for a brand new track by
        // construction, because a first measurement seeds the velocity from the
        // very displacement being compared.
        const float obsVx = inX / dd, obsVy = inY / dd;
        const float dev = std::hypot(obsVx - predictedVx, obsVy - predictedVy);
        const float predSpeed = std::hypot(predictedVx, predictedVy);
        // A track with no predicted speed yet cannot be judged straight; a
        // high one can, because the deviation has to be small in absolute terms.
        const float tolerance = std::max(240.0f, predSpeed * 0.45f);
        const float straight = clampf(1.0f - dev / tolerance, 0.0f, 1.0f);
        t.straightness = t.straightness * 0.55f + straight * 0.45f;

        // A bouncer reverses: the newly observed direction is close to
        // anti-parallel to what was predicted. Measured against the PRE-update
        // velocity, since the Kalman gain has already absorbed part of the turn
        // by this point and would mask it.
        const float prevSpeed = std::hypot(predictedVx, predictedVy);
        const float obsSpeed = std::hypot(obsVx, obsVy);
        if (prevSpeed > 40.0f && obsSpeed > 40.0f) {
            const float cosang = (predictedVx * obsVx + predictedVy * obsVy) /
                                 (prevSpeed * obsSpeed);
            if (cosang < cfg_.bouncerDotThreshold) t.bounced = true;
        }

        const float area = static_cast<float>(blobs_[static_cast<size_t>(best)].area);
        t.areaEma = (t.hits <= 1) ? area : (t.areaEma * 0.6f + area * 0.4f);

        t.hits++;
        t.misses = 0;
        t.lastSeenNanos = runPts_;
        t.speedNorm = std::hypot(t.vx, t.vy) / screenW;
        t.straightnessNorm = t.straightness;
        t.isProjectile = (t.hits >= cfg_.trackMinHitsForProjectile) &&
                         (t.speedNorm >= cfg_.projectileMinSpeedNorm) &&
                         (t.straightness >= cfg_.projectileMinStraightness);
    }

    // --- spawn ---
    for (size_t i = 0; i < blobs_.size(); ++i) {
        if (blobUsed_[i]) continue;
        if (static_cast<int>(tracks_.size()) >= cfg_.maxTracks) break;

        // Discard the brawler's own effects: splashes from puddles, rustle from
        // bushes, dust off walls. They are born on top of the player and travel
        // with it, so they are undodgeable by construction and following them
        // only produces phantom threats.
        if (player_.valid) {
            const float ownR = cfg_.ownEffectRadiusNorm * screenW;
            const float ddx = blobs_[i].sx - player_.x;
            const float ddy = blobs_[i].sy - player_.y;
            if (ddx * ddx + ddy * ddy < ownR * ownR) continue;
        }

        Track t;
        t.id = nextTrackId_++;
        t.x = blobs_[i].sx;
        t.y = blobs_[i].sy;
        t.hits = 0;
        t.misses = 0;
        t.alive = true;
        t.spawnArea = blobs_[i].area;
        t.areaEma = static_cast<float>(blobs_[i].area);
        t.lastSeenNanos = runPts_;
        tracks_.push_back(t);
    }

    tracks_.erase(std::remove_if(tracks_.begin(), tracks_.end(),
                                 [&](const Track& t) { return t.misses > cfg_.trackMaxMisses; }),
                  tracks_.end());

    // A track that has never left the player's neighbourhood is the brawler's
    // own effect, whatever it looked like at birth. Real projectiles cross the
    // arena; splashes and rustle stay glued to the player. This is the cheaper
    // and more reliable of the two filters, because it uses the whole history
    // rather than one frame.
    if (player_.valid) {
        const float trackR = cfg_.ownEffectTrackNorm * screenW;
        const float trackR2 = trackR * trackR;
        tracks_.erase(std::remove_if(tracks_.begin(), tracks_.end(),
                                     [&](const Track& t) {
                                         if (t.hits < cfg_.ownEffectMinHits) return false;
                                         const float dx = t.x - player_.x;
                                         const float dy = t.y - player_.y;
                                         return dx * dx + dy * dy < trackR2;
                                     }),
                      tracks_.end());
    }

    // --- classify, only once a track has enough history to be sure -----------
    // Deliberately after the gates above: labelling must not be able to change
    // whether something is treated as a threat, only what it is called.
    stats_.ballCount = 0;
    stats_.bouncerCount = 0;
    for (Track& t : tracks_) {
        if (t.hits < cfg_.kindMinHitsBeforeLabelling) {
            t.kind = TrackKind::kUnknown;
            continue;
        }
        // A hard reversal is decisive: the object changed direction, so it is a
        // bouncer regardless of size.
        if (t.bounced) {
            t.kind = TrackKind::kBouncer;
        } else if (t.areaEma >= static_cast<float>(cfg_.ballMinArea)) {
            // Big and consistent. The ball is the only large, steadily moving
            // object in Brawl Ball mode.
            t.kind = TrackKind::kBall;
        } else if (t.areaEma <= static_cast<float>(cfg_.bouncerMaxArea) &&
                   t.straightness >= cfg_.projectileMinStraightness &&
                   t.speedNorm >= cfg_.projectileMinSpeedNorm) {
            t.kind = TrackKind::kProjectile;
        } else {
            t.kind = TrackKind::kUnknown;
        }
        if (t.kind == TrackKind::kBall) ++stats_.ballCount;
        if (t.kind == TrackKind::kBouncer) ++stats_.bouncerCount;
    }

    stats_.trackCount = static_cast<int>(tracks_.size());
    stats_.projectileCount = 0;
    for (const Track& t : tracks_) {
        if (t.isProjectile) ++stats_.projectileCount;
    }
}

// ===========================================================================
// Stage 7: collision solving and escape selection
// ===========================================================================

float VisionEngine::chooseEscapeHeading(const Track& t) const {
    const float screenW = static_cast<float>(screenW_);
    const float screenH = static_cast<float>(screenH_);
    // Required perpendicular clearance, not a travel distance. The joystick
    // drag sets a direction; the brawler covers ground for as long as the
    // stick is held, which the Kotlin side turns into a hold time.
    const float step = cfg_.escapeStepNorm * screenW;
    // The brawler keeps moving for the whole hold, so the reach used for the
    // border test must be larger than the clearance itself.
    const float reach = step + screenW * 0.10f;

    const float vLen = std::hypot(t.vx, t.vy);
    if (vLen < 1e-3f) return 0.0f;
    const float uhx = t.vx / vLen;
    const float uhy = t.vy / vLen;
    const float nx = -uhy;
    const float ny = uhx;

    const float rx = player_.x - t.x;
    const float ry = player_.y - t.y;
    const float perpSigned = rx * nx + ry * ny;

    float bestScore = -std::numeric_limits<float>::infinity();
    float bestDeg = 0.0f;
    const int n = std::max(4, cfg_.escapeCandidateCount);

    for (int i = 0; i < n; ++i) {
        const float ang = 2.0f * kPi * static_cast<float>(i) / static_cast<float>(n);
        const float dx = std::cos(ang);
        const float dy = std::sin(ang);

        // Because the normal is orthogonal to the velocity, the time of closest
        // approach is unchanged by the step, so the resulting miss distance is
        // exactly |perpSigned + step * (d . n)|. Maximising that maximises the
        // achievable separation.
        const float newPerp = std::fabs(perpSigned + step * (dx * nx + dy * ny));
        float score = newPerp;

        // The destination must stay on screen with margin, because walking into
        // the border is a death, not a dodge.
        const float tx = player_.x + dx * reach;
        const float ty = player_.y + dy * reach;
        const float marginX = std::min(tx, screenW - tx);
        const float marginY = std::min(ty, screenH - ty);
        if (marginX < screenW * 0.06f || marginY < screenH * 0.10f) {
            score -= screenW * 0.30f;
        }

        // How much of the step runs along the projectile's velocity. Negative
        // means back toward where the projectile is coming from, i.e. into its
        // path, so it is ADDED, which penalises it.
        //
        // Weighted against the clearance `step`, not the screen width, so it stays
        // a tie-breaker. Scaling it by the screen made it larger than the
        // clearance term itself, and the planner abandoned the perpendicular
        // entirely and settled 45 degrees off it.
        score += (dx * uhx + dy * uhy) * step * 0.25f;

        // Keep clear of enemy brawlers: stepping into one is a death, not a
        // dodge, so a heading that lands inside an enemy avoid radius is
        // penalised by the depth of the intrusion.
        for (const EnemyMark& e : enemies_) {
            const float avoid = cfg_.enemyAvoidRadiusNorm * screenW;
            const float ex = e.x - player_.x;
            const float ey = e.y - player_.y;
            const float along = ex * dx + ey * dy;
            if (along < 0.0f || along > reach) continue;  // not on our path
            const float lateral = std::fabs(ex * ny - ey * nx);
            if (lateral < avoid) {
                score -= (avoid - lateral) * 1.5f;
            }
        }

        if (score > bestScore) {
            bestScore = score;
            bestDeg = std::fmod(ang * 180.0f / kPi + 360.0f, 360.0f);
        }
    }
    return bestDeg;
}

void VisionEngine::solveThreat() {
    threat_ = ThreatSolution();
    if (!player_.valid) return;

    const float screenW = static_cast<float>(screenW_);
    const float effectiveR =
        (cfg_.playerRadiusNorm + cfg_.projectileRadiusNorm) * screenW;

    float bestTti = std::numeric_limits<float>::infinity();
    const Track* bestTrack = nullptr;

    for (const Track& t : tracks_) {
        if (!t.isProjectile) continue;
        // A bouncer reflects off walls, so the straight-line CPA is wrong and
        // dodging "away from it" can be worse than doing nothing. It is
        // reported, not acted on.
        if (t.kind == TrackKind::kBouncer) continue;
        const float vLen = std::hypot(t.vx, t.vy);
        if (vLen < 1e-3f) continue;

        // r = projectile -> player
        const float rx = player_.x - t.x;
        const float ry = player_.y - t.y;
        const float tCpa = (rx * t.vx + ry * t.vy) / (vLen * vLen);
        if (tCpa < cfg_.minTtiSec || tCpa > cfg_.reactionHorizonSec) continue;

        const float cpx = t.x + t.vx * tCpa;
        const float cpy = t.y + t.vy * tCpa;
        if (std::hypot(player_.x - cpx, player_.y - cpy) >= effectiveR) continue;

        if (tCpa < bestTti) {
            bestTti = tCpa;
            bestTrack = &t;
        }
    }
    if (bestTrack == nullptr) return;

    const Track& t = *bestTrack;
    threat_.valid = true;
    threat_.ttiSec = bestTti;
    threat_.threatX = t.x;
    threat_.threatY = t.y;
    threat_.vx = t.vx;
    threat_.vy = t.vy;
    threat_.speed = std::hypot(t.vx, t.vy);
    threat_.trajectoryDeg =
        std::fmod(std::atan2(t.vy, t.vx) * 180.0f / kPi + 360.0f, 360.0f);
    threat_.confidence = t.straightness;
    threat_.trackId = t.id;

    if (bestTti <= cfg_.lethalTtiSec) {
        threat_.severity = kLethal;
    } else if (bestTti <= cfg_.imminentTtiSec) {
        threat_.severity = kImminent;
    } else {
        threat_.severity = kWarning;
    }

    const float step = cfg_.escapeStepNorm * screenW;
    const float travelSec = step / (cfg_.characterSpeedNorm * screenW);
    const float heading = chooseEscapeHeading(t);
    const float rad = heading * kPi / 180.0f;

    threat_.escape.valid = true;
    threat_.escape.headingDeg = heading;
    threat_.escape.dirX = std::cos(rad);
    threat_.escape.dirY = std::sin(rad);
    threat_.escape.stepPixels = step;
    threat_.escape.travelMs = travelSec * 1000.0f;
    // If the brawler physically cannot cover the escape distance before impact,
    // a partial step still beats standing still, but the caller must know.
    threat_.escape.sufficient = travelSec <= bestTti;
}

// ===========================================================================
// Driver
// ===========================================================================

void VisionEngine::process(uint64_t ptsNanos) {
    const auto t0 = std::chrono::steady_clock::now();

    frameDt_ = (ingestPts_ > 0) ? static_cast<float>((ingestPts_ - lastPts_) * 1e-9)
                                : (1.0f / 60.0f);
    frameDt_ = clampf(frameDt_, 0.004f, 0.100f);
    lastPts_ = ingestPts_;
    runPts_ = ptsNanos;

    if (!hasPrev_) {
        ++stats_.framesProcessed;
        return;
    }

    // Motion estimation + compensated difference. When a real OpenCV SDK was
    // supplied these two stages are done together in one accelerated call;
    // otherwise the dependency free core stages run. Both paths produce the
    // same `motion_` and `diff_`, so nothing downstream can tell the difference.
    bool diffReady = false;
    if (opencvMotionDiffAvailable()) {
        diffReady = opencvEstimateMotionAndDiff(
            luma_.data(), prevLuma_.data(), gridW_, gridH_, gridW_,
            static_cast<int>(cfg_.motionMaxShiftHalfRes * 2), cfg_.motionMinConfidence,
            cfg_.diffNoiseFloor, motion_, diff_.data());
        if (diffReady) {
            // phaseCorrelate is already sub-pixel; the difference sampler wants
            // integer offsets, so quantise once here.
            motion_.dx = std::round(motion_.dx);
            motion_.dy = std::round(motion_.dy);
            invalidateBorderRing();
        } else {
            motion_ = MotionEstimate();
        }
    }
    if (!diffReady) {
        estimateGlobalMotion();
        refineMotionAtFullRes();
        if (motion_.valid) {
            // 0..1: the fraction of frame-to-frame difference the estimated
            // shift removed. Computed the same way the optional OpenCV path
            // computes it, so `motionMinConfidence` has one meaning in both.
            const float quality = measureAlignmentQuality();
            if (quality < cfg_.motionMinConfidence) {
                // A shift that does not actually explain the frame means the
                // scene has no usable global motion, not that the terrain moved.
                motion_.valid = false;
                motion_.dx = 0.0f;
                motion_.dy = 0.0f;
            } else {
                motion_.confidence = quality;
            }
        }
    }
    if (motion_.valid) {
        // Cap the accepted shift at the width of the invalid border ring, so
        // the ring can always cover the whole warped band. A shift larger than
        // the ring would leave edge-clamped samples inside the valid region,
        // which manufacture a full height stripe of false motion.
        const float cap = static_cast<float>(std::min(gridW_, gridH_) / 4);
        if (std::fabs(motion_.dx) > cap) motion_.dx = (motion_.dx < 0 ? -1.0f : 1.0f) * cap;
        if (std::fabs(motion_.dy) > cap) motion_.dy = (motion_.dy < 0 ? -1.0f : 1.0f) * cap;
    }
    buildDifference();

    extractBlobs();
    detectPlayer();
    detectEnemies();
    updateTracks();
    solveThreat();

    const auto t1 = std::chrono::steady_clock::now();
    stats_.processMs = std::chrono::duration<double, std::milli>(t1 - t0).count();
    ++stats_.framesProcessed;
}

}  // namespace rendera
