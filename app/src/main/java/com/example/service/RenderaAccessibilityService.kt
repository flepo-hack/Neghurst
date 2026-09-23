package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RenderaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RenderaAccessibility"

        @Volatile
        var instance: RenderaAccessibilityService? = null
            private set

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        fun isAvailable(): Boolean = instance != null

        fun executeDodgeGesture(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            durationMs: Long,
            onResult: ((Boolean) -> Unit)? = null
        ): Boolean {
            val service = instance ?: run {
                Log.w(TAG, "Cannot execute gesture: Accessibility Service is not connected!")
                onResult?.invoke(false)
                return false
            }
            return service.dispatchJoystickStroke(startX, startY, endX, endY, durationMs, onResult)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnected.value = true
        Log.i(TAG, "Rendera Accessibility Service connected successfully.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We do not require reading UI elements unless relevant for screen transitions
    }

    override fun onInterrupt() {
        Log.w(TAG, "Rendera Accessibility Service interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
            _isServiceConnected.value = false
        }
        Log.i(TAG, "Rendera Accessibility Service destroyed.")
    }

    fun dispatchJoystickStroke(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
        onResult: ((Boolean) -> Unit)? = null
    ): Boolean {
        return try {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }

            // 15ms duration for zero-latency dynamic dodge stroke
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(15L, 500L))
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()

            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Dodge gesture completed from ($startX,$startY) to ($endX,$endY)")
                    onResult?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "Dodge gesture cancelled!")
                    onResult?.invoke(false)
                }
            }, null)

            dispatched
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch dodge gesture", e)
            onResult?.invoke(false)
            false
        }
    }

    /**
     * Executes ultra-fast 15 ms dodge swipe in the direction of the given angle in radians.
     * Can be invoked via JNI or directly from vision physics loop.
     */
    fun executeDodge(angleRad: Float, onResult: ((Boolean) -> Unit)? = null): Boolean {
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.toFloat()
        val screenH = metrics.heightPixels.toFloat()
        val isLandscape = screenW > screenH

        val startX = if (isLandscape) 0.20f * screenW else 0.25f * screenW
        val startY = if (isLandscape) 0.78f * screenH else 0.80f * screenH
        val radius = 150f

        val targetX = (startX + (kotlin.math.cos(angleRad.toDouble()).toFloat() * radius)).coerceIn(10f, screenW - 10f)
        val targetY = (startY + (kotlin.math.sin(angleRad.toDouble()).toFloat() * radius)).coerceIn(10f, screenH - 10f)

        return dispatchJoystickStroke(startX, startY, targetX, targetY, 15L, onResult)
    }
}
