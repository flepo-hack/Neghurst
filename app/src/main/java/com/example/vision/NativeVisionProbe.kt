package com.example.vision

import com.example.vision.nativebridge.NativeVisionEngine

/**
 * Single place that answers "is the compiled vision engine actually in this
 * build?".
 *
 * The previous code had no such check. `NativeRenderaPipeline` swallowed the
 * `UnsatisfiedLinkError` in an `init` block, logged a warning nobody read, and
 * the service then reported a healthy-looking loop with `fps 0` and no detections
 * forever. That is the difference between a bug the user can act on and a bug
 * that looks like a broken game.
 *
 * Surfaced in the UI so a build without the NDK output fails loudly.
 */
object NativeVisionProbe {
    private val available: Boolean by lazy { NativeVisionEngine.isNativeSupported() }

    fun isNativeAvailable(): Boolean = available

    /** One line suitable for a status card. */
    fun describe(): String = if (available) {
        "Native engine loaded (librendera_native.so)."
    } else {
        "Native engine MISSING. This APK was built without the NDK output, " +
            "so nothing can be detected. Rebuild with the Android NDK installed."
    }
}
