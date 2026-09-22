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

#ifdef __cplusplus
extern "C" {
#endif

// JNI Function prototypes
JNIEXPORT jlong JNICALL
Java_com_example_vision_nativebridge_NativeRenderaPipeline_nativeInit(
    JNIEnv* env,
    jobject thiz,
    jint gridWidth,
    jint gridHeight
);

JNIEXPORT jint JNICALL
Java_com_example_vision_nativebridge_NativeRenderaPipeline_nativeProcessFrame(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jbyteArray grayscaleBytes,
    jfloat screenWidth,
    jfloat screenHeight,
    jfloatArray outThreatData
);

JNIEXPORT void JNICALL
Java_com_example_vision_nativebridge_NativeRenderaPipeline_nativeReset(
    JNIEnv* env,
    jobject thiz,
    jlong handle
);

JNIEXPORT void JNICALL
Java_com_example_vision_nativebridge_NativeRenderaPipeline_nativeDestroy(
    JNIEnv* env,
    jobject thiz,
    jlong handle
);

#ifdef __cplusplus
}
#endif
