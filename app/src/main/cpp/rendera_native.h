#pragma once

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <vector>
#include <cmath>
#include <cstring>
#include <algorithm>

#define LOG_TAG "RenderaNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace rendera {

// ---------------------------------------------------------------------------
// Geometry handed to the engine by the Kotlin side. All lengths are in
// ANALYSIS GRID CELLS so that the engine never has to guess the screen scale.
// ---------------------------------------------------------------------------
struct Geometry {
    int cols = 0;
    int rows = 0;
    int captureW = 0;
    int captureH = 0;
    int screenW = 0;
    int screenH = 0;
    int playerCellX = -1;
    int playerCellY = -1;
    int joyCellX = -1;
    int joyCellY = -1;
    float playerRadiusCells = 3.0f;
    float joyRadiusCells = 12.0f;
    float projectileMinSpeedCellsPerSec = 22.0f;
    float projectileMaxSpeedCellsPerSec = 220.0f;
    bool playerLocked = false;
    bool joyLocked = false;
};

// ---------------------------------------------------------------------------
// Result slots produced by nativeProcessFrame(). Shared by the C++ writer and
// the Kotlin reader; the two must stay in sync.
// ---------------------------------------------------------------------------
enum ResultSlot {
    SLOT_THREAT = 0,        // 1.0 when a colliding projectile was found
    SLOT_THREAT_LEVEL,      // 0 none, 1 warning, 2 imminent, 3 lethal
    SLOT_DODGE_ANGLE_DEG,   // [0,360) or -1 when there is no dodge
    SLOT_THREAT_X,          // threat position, screen px
    SLOT_THREAT_Y,
    SLOT_THREAT_VX,         // threat velocity, screen px/s
    SLOT_THREAT_VY,
    SLOT_THREAT_SPEED,      // screen px/s
    SLOT_THREAT_TTI_MS,     // time to closest approach, ms
    SLOT_PLAYER_X,          // tracked player, screen px
    SLOT_PLAYER_Y,
    SLOT_PLAYER_CONFIDENCE, // 0..1, 1 only when a real detection confirmed it
    SLOT_PLAYER_DETECTED,   // 1 when a detection (not a fallback) was used
    SLOT_CAMERA_DX,         // per-frame camera motion, cells
    SLOT_CAMERA_DY,
    SLOT_CAMERA_MOVING,     // 0/1
    SLOT_PROJECTILE_COUNT,
    SLOT_ENEMY_COUNT,
    SLOT_TRACK_COUNT,
    SLOT_FRAMES_SEEN,
    SLOT_NOISE_FLOOR,       // measured per-frame noise floor, 0..255
    SLOT_CALIB_JOY_X,       // auto-calibration results, screen px (-1 if none)
    SLOT_CALIB_JOY_Y,
    SLOT_CALIB_PLAYER_X,
    SLOT_CALIB_PLAYER_Y,
    SLOT_CALIB_JOY_SCORE,   // 0..1 confidence of the joystick proposal
    SLOT_CALIB_PLAYER_SCORE,
    SLOT_SLOT_COUNT
};

constexpr int kResultSlots = SLOT_SLOT_COUNT;

// Per-track payload for the optional JNI callback.
struct TrackInfo {
    float x = 0.0f;
    float y = 0.0f;
    float vx = 0.0f;
    float vy = 0.0f;
    float speed = 0.0f;
    float consistency = 0.0f;
    int hits = 0;
    int isProjectile = 0;
};

constexpr int kMaxTracks = 24;
constexpr int kMaxEnemies = 16;
constexpr int kMaxObservations = 48;

struct FrameInput {
    const uint8_t* y = nullptr;   // captureW * captureH, 8-bit luma
    int yRowStride = 0;
    const uint8_t* cb = nullptr;  // (captureW/2) * (captureH/2)
    int cbRowStride = 0;
    const uint8_t* cr = nullptr;
    int crRowStride = 0;
    int captureW = 0;
    int captureH = 0;
    float dtSec = 0.016f;
};

struct FrameOutput {
    float threat = 0.0f;
    float threatLevel = 0.0f;
    float dodgeAngleDeg = -1.0f;
    float threatX = 0.0f;
    float threatY = 0.0f;
    float threatVx = 0.0f;
    float threatVy = 0.0f;
    float threatSpeed = 0.0f;
    float threatTtiMs = 0.0f;
    float playerX = 0.0f;
    float playerY = 0.0f;
    float playerConfidence = 0.0f;
    float playerDetected = 0.0f;
    int cameraDx = 0;
    int cameraDy = 0;
    int cameraMoving = 0;
    int projectileCount = 0;
    int enemyCount = 0;
    int trackCount = 0;
    long long framesSeen = 0;
    float noiseFloor = 0.0f;
    float calibJoyX = -1.0f;
    float calibJoyY = -1.0f;
    float calibPlayerX = -1.0f;
    float calibPlayerY = -1.0f;
    float calibJoyScore = 0.0f;
    float calibPlayerScore = 0.0f;
    float calibJoyRingCells = 0.0f;
    int enemyCount = 0;
    TrackInfo tracks[kMaxTracks];
    int enemyX[kMaxEnemies];
    int enemyY[kMaxEnemies];
};

} // namespace rendera

#ifdef __cplusplus
extern "C" {
#endif

// --- Lifecycle -------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_example_vision_nativebridge_NativeRenderaEngine_nativeInit(
        JNIEnv* env, jobject thiz,
        jint gridCols, jint gridRows);

/**
 * Frames processed since init. A non-zero value proves the .so is really the
 * code doing the work (and not the Kotlin fallback).
 */
JNIEXPORT jint JNICALL
Java_com_example_vision_nativebridge_NativeRenderaEngine_nativeProcessedFrames(
        JNIEnv* env, jobject thiz, jlong handle);

JNIEXPORT void JNICALL
Java_com_example_vision_nativebridge_NativeRenderaEngine_nativeSetGeometry(
        JNIEnv* env, jobject thiz, jlong handle,
        jint cols, jint rows,
        jint captureW, jint captureH,
        jint screenW, jint screenH,
        jint playerCellX, jint playerCellY,
        jint joyCellX, jint joyCellY,
        jfloat playerRadiusCells, jfloat joyRadiusCells,
        jfloat projectileMinSpeed, jfloat projectileMaxSpeed,
        jboolean playerLocked, jboolean joyLocked);

JNIEXPORT void JNICALL
Java_com_example_vision_nativebridge_NativeRenderaEngine_nativeSetExclusions(
        JNIEnv* env, jobject thiz, jlong handle,
        jintArray rects);

JNIEXPORT void JNICALL
Java_com_example_vision_nativebridge_NativeRenderaEngine_nativeSetTuning(
        JNIEnv* env, jobject thiz, jlong handle,
        jfloat motionNoiseFloor,
        jfloat minBlobWeight,
        jint maxCameraShiftCells,
        jfloat processNoisePos,
        jfloat processNoiseVel,
        jfloat measurementNoise);

JNIEXPORT void JNICALL
Java_com_example_vision_nativebridge_NativeRenderaEngine_nativeMarkAnchorConfirmed(
        JNIEnv* env, jobject thiz, jlong handle);

JNIEXPORT void JNICALL
Java_com_example_vision_nativebridge_NativeRenderaEngine_nativeReset(
        JNIEnv* env, jobject thiz, jlong handle);

JNIEXPORT void JNICALL
Java_com_example_vision_nativebridge_NativeRenderaEngine_nativeDestroy(
        JNIEnv* env, jobject thiz, jlong handle);

// --- Detection -------------------------------------------------------------

/**
 * Runs the full deterministic pipeline over one captured frame.
 * `luma` is a direct copy of the ImageReader Y plane at capture resolution and
 * is the ONLY buffer the engine reads for motion. `outResult` receives
 * kResultSlots floats, `outTracks` receives 8 floats per track.
 */
JNIEXPORT jboolean JNICALL
Java_com_example_vision_nativebridge_NativeRenderaEngine_nativeProcessFrame(
        JNIEnv* env, jobject thiz,
        jlong handle,
        jbyteArray luma,
        jint captureW, jint captureH, jint lumaRowStride,
        jfloat dtSec,
        jfloatArray outResult,
        jfloatArray outTracks);

// --- Auto-calibration ------------------------------------------------------
// These read the CURRENT frame only (no history) and return real proposals.

JNIEXPORT jboolean JNICALL
Java_com_example_vision_nativebridge_NativeRenderaEngine_nativeCalibrateJoystick(
        JNIEnv* env, jobject thiz, jlong handle,
        jbyteArray luma, jint captureW, jint captureH, jint lumaRowStride,
        jfloatArray outResult);

JNIEXPORT jboolean JNICALL
Java_com_example_vision_nativebridge_NativeRenderaEngine_nativeCalibratePlayer(
        JNIEnv* env, jobject thiz, jlong handle,
        jbyteArray luma, jbyteArray cb, jbyteArray cr,
        jint captureW, jint captureH, jint lumaRowStride,
        jfloatArray outResult);

JNIEXPORT jint JNICALL
Java_com_example_vision_nativebridge_NativeRenderaEngine_nativeVersion(
        JNIEnv* env, jobject thiz);

#ifdef __cplusplus
}
#endif
