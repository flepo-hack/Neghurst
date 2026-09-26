// Optional OpenCV accelerated kernels.
//
// Compiled by CMakeLists.txt ONLY when a real OpenCV Android SDK is available
// (see RENDERA_OPENCV_SDK). The dependency free core in rendera_core.cpp is
// complete on its own and performs the same stages, so the default build has no
// external dependencies and always compiles.
//
// ---------------------------------------------------------------------------
// A note on APK size, because the two options are routinely confused:
//
//   * Adding `org.opencv:opencv` (the AAR on Maven Central) inflates the APK by
//     roughly 70 MB, because it ships libopencv_java4.so for four ABIs.
//   * That AAR does NOT export the C++ `cv::` symbols. OpenCV's Android builds
//     are compiled with -fvisibility=hidden, so only the JNI bridge is exported.
//     NDK code here therefore cannot call `cv::*` when the AAR is used, no
//     matter how the include paths are arranged.
//   * Calling `cv::*` from NDK requires the OpenCV **Android SDK** (headers
//     plus static libraries), which is what RENDERA_OPENCV_SDK points at.
//
// So the APK size win and the C++ API are two different products. This file
// provides the second. The size route is separately available from Kotlin by
// adding the AAR and using its Java API.
//
// What OpenCV accelerates here, and what it deliberately does not:
//
//   * Accelerated: windowed phase correlation and the motion compensated
//     absolute difference. Both are textbook OpenCV strengths and both are pure
//     per-pixel / per-row work over the whole grid.
//   * Not accelerated: the Kalman recursion, the CPA solve and the escape
//     heading scoring. These run over at most 16 objects, are numerically
//     delicate, and are the parts where clarity and correctness matter far more
//     than throughput. Leaving them in the core also keeps a single source of
//     truth for the maths the Kotlin unit tests pin down.
//
// Behavioural contract with the core it replaces: identical `outMotion` and
// identical `diffOut` bytes, including the confidence gating. If this path
// cannot reach the confidence bar it returns false, and the caller falls back to
// the core so there is exactly one definition of "no usable motion".

#include "rendera_core.h"

#ifndef RENDERA_WITH_OPENCV
#error "rendera_opencv.cpp must only be compiled when RENDERA_WITH_OPENCV is defined"
#endif

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>

namespace rendera {
namespace {

/**
 * Separable Hann window with a raised floor, materialised as a full H x W image.
 *
 * A pure Hann window weights the border to zero, which throws away exactly the
 * pixels that carry the camera motion signal, so a floor is added. The window
 * must be the same size as the images: `phaseCorrelate` rejects a mismatched
 * window outright with CV_Error(StsBadArg), which would unwind straight through
 * the JNI frame and abort the process.
 */
cv::Mat buildWindow(int width, int height) {
    cv::Mat window(height, width, CV_32F);
    for (int y = 0; y < height; ++y) {
        const float wy = 0.30f + 0.70f * (0.5f - 0.5f * std::cos(
            2.0f * 3.14159265358979323846f * y / static_cast<float>(height)));
        float* row = window.ptr<float>(y);
        for (int x = 0; x < width; ++x) {
            const float wx = 0.30f + 0.70f * (0.5f - 0.5f * std::cos(
                2.0f * 3.14159265358979323846f * x / static_cast<float>(width)));
            row[x] = wx * wy;
        }
    }
    return window;
}

}  // namespace

bool opencvMotionDiffAvailable() { return true; }

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
    if (current == nullptr || previous == nullptr || diffOut == nullptr) return false;
    if (width <= 0 || height <= 0 || stride <= 0) return false;

    try {
        // Wrap the caller's memory without copying. Mat holds a reference, so the
        // data stays zero copy for the lifetime of these Mats.
        cv::Mat curMat(height, width, CV_8UC1, const_cast<uint8_t*>(current), stride);
        cv::Mat prevMat(height, width, CV_8UC1, const_cast<uint8_t*>(previous), stride);

        cv::Mat window = buildWindow(width, height);

        // Mean removal and windowing in one pass. Converting straight into the
        // windowed form keeps this to a single traversal of the frame.
        cv::Mat curF, prevF;
        curMat.convertTo(curF, CV_32FC1);
        prevMat.convertTo(prevF, CV_32FC1);
        curF -= cv::mean(curF);
        prevF -= cv::mean(prevF);
        cv::multiply(curF, window, curF);
        cv::multiply(prevF, window, prevF);

        // phaseCorrelate(a, b) reports the shift p such that b(x) == a(x + p).
        // With a = previous and b = current that is exactly the core's
        // convention, prev(x + dx) ~= curr(x), so no sign flip is needed.
        const cv::Point2d shift = cv::phaseCorrelate(prevF, curF, window);
        // A magnitude test rather than std::isfinite: it cannot be folded away
        // by any fast-math setting, and it rejects NaN and infinity both.
        constexpr double kMaxShift = 1e30;
        if (!(shift.x > -kMaxShift && shift.x < kMaxShift)) return false;
        if (!(shift.y > -kMaxShift && shift.y < kMaxShift)) return false;
        if (searchRadius > 0 &&
            (std::fabs(shift.x) > static_cast<double>(searchRadius) ||
             std::fabs(shift.y) > static_cast<double>(searchRadius))) {
            return false;
        }

        // Motion compensated absolute difference: warp the previous frame so it
        // lines up with the current one, then subtract. World-anchored terrain
        // cancels to zero; only independently moving objects survive.
        //
        // warpAffine's matrix maps source to destination, i.e. dst(x) = src(M^-1 x),
        // and the core samples prev(x + shiftX, y + shiftY), so the translation
        // column is the negated shift.
        const int ix = static_cast<int>(std::lround(shift.x));
        const int iy = static_cast<int>(std::lround(shift.y));
        cv::Mat translation = (cv::Mat_<double>(2, 3) << 1.0, 0.0, static_cast<double>(-ix),
                               0.0, 1.0, static_cast<double>(-iy));

        cv::Mat aligned;
        cv::warpAffine(prevMat, aligned, translation, cv::Size(width, height),
                       cv::INTER_NEAREST, cv::BORDER_REPLICATE);
        cv::absdiff(curMat, aligned, aligned);
        if (!aligned.isContinuous()) aligned = aligned.clone();

        // Confidence: the same quantity the core computes in
        // VisionEngine::measureAlignmentQuality, namely the fraction of the
        // frame-to-frame difference the estimated shift removed. Ratio based, so
        // it is scale free, and identical in both implementations, so
        // `motionMinConfidence` has exactly one meaning.
        cv::Mat unaligned, curPlain;
        cv::absdiff(curMat, prevMat, unaligned);
        // `curPlain` is the unwindowed float copy; reusing it avoids a third
        // full-frame conversion.
        cv::Mat alignedF, unalignedF;
        aligned.convertTo(alignedF, CV_32FC1);
        unaligned.convertTo(unalignedF, CV_32FC1);
        curMat.convertTo(curPlain, CV_32FC1);

        // The core samples the interior only, skipping anything whose shifted
        // coordinate leaves the frame. Reproduce that here, otherwise the two
        // implementations disagree exactly when the shift is large, because
        // warpAffine's BORDER_REPLICATE band would be counted as agreement.
        const int pad = std::min(width, height) / 4;
        const int step = std::max(1, std::min(width, height) / 48);
        double alignedSum = 0.0;
        double rawSum = 0.0;
        int64_t n = 0;
        for (int y = pad; y < height - pad; y += step) {
            const float* ra = alignedF.ptr<float>(y);
            const float* rr = unalignedF.ptr<float>(y);
            const float* rc = curPlain.ptr<float>(y);
            for (int x = pad; x < width - pad; x += step) {
                alignedSum += std::abs(static_cast<double>(rc[x] - ra[x]));
                rawSum += std::abs(static_cast<double>(rc[x] - rr[x]));
                ++n;
            }
        }
        double quality = 0.0;
        if (n == 0) {
            return false;
        }
        const double rawMean = rawSum / static_cast<double>(n);
        const double alignedMean = alignedSum / static_cast<double>(n);
        quality = (rawMean <= 1e-6) ? 1.0 : (1.0 - alignedMean / rawMean);
        quality = std::max(0.0, std::min(1.0, quality));

        if (minConfidence > 0.0f && quality < static_cast<double>(minConfidence)) {
            // A shift that does not explain the frame means the scene has no
            // usable global motion. Hand the decision back to the core rather
            // than committing to a wrong shift.
            return false;
        }

        // Apply the same noise floor the core applies, so `diffOut` really is
        // interchangeable with the core's output. Without this the two paths
        // would feed the blob extractor different pixel populations and the
        // tuning knob would only work on one of them.
        const uint8_t floor = noiseFloor;
        for (int y = 0; y < height; ++y) {
            const uint8_t* srcRow = aligned.ptr<uint8_t>(y);
            uint8_t* dstRow = diffOut + static_cast<size_t>(y) * width;
            for (int x = 0; x < width; ++x) {
                dstRow[x] = srcRow[x] > floor ? srcRow[x] : 0;
            }
        }

        outMotion.dx = static_cast<float>(ix);
        outMotion.dy = static_cast<float>(iy);
        outMotion.confidence = static_cast<float>(quality);
        outMotion.valid = true;
        return true;
    } catch (const cv::Exception& e) {
        // Any OpenCV failure (a bad window, a failed allocation) must degrade to
        // the core path, never propagate across the JNI boundary.
        (void)e;
        return false;
    }
}

}  // namespace rendera
