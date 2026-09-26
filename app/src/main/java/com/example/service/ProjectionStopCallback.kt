package com.example.service

import android.media.projection.MediaProjection
import android.os.Handler
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Holder for the API 34+ `MediaProjection.Callback`.
 *
 * `MediaProjection.Callback` only exists from API 34. Declaring a subclass in
 * the main service class would make that class fail verification (and crash the
 * process) on the minSdk 24..33 devices Rendera still supports, so the subclass
 * lives here in its own class that is only loaded from inside a version check.
 */
@RequiresApi(34)
internal class ProjectionStopCallback(
    private val onStop: () -> Unit
) : MediaProjection.Callback() {
    override fun onStop() {
        Log.w("RenderaOverlayService", "MediaProjection revoked by the system")
        onStop()
    }

    companion object {
        private val main = Handler(android.os.Looper.getMainLooper())

        fun post(callback: MediaProjection.Callback, action: () -> Unit) {
            val cb = ProjectionStopCallback(action)
            callback.registerCallback(cb, main)
        }
    }
}
