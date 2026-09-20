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

            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(50L, 500L))
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
}
