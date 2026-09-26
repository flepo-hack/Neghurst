package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Executes the dodge input.
 *
 * Two things the previous implementation got wrong and that this one fixes:
 *
 *  1. `dispatchGesture` is a main-thread API. It used to be called from the
 *     detection thread (`Dispatchers.Default`), which makes the dispatch
 *     unreliable. Everything is now posted to the main looper.
 *
 *  2. A 15 ms flick does not move a brawler. Brawl Stars integrates the
 *     joystick over time, so a stroke that reaches full deflection only in its
 *     final millisecond produces a twitch. The gesture is now built as
 *     `ramp -> hold` (two linked strokes via `continueStroke`), so the stick
 *     stays deflected for `holdMs` and the character actually walks out of the
 *     bullet's path.
 */
class RenderaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RenderaAccessibility"

        /** How long the stick takes to travel from centre to full deflection. */
        const val RAMP_MS = 8L

        /** No stroke may be shorter than this or the system may drop it. */
        const val MIN_STROKE_MS = 12L
        const val MAX_STROKE_MS = 1200L

        @Volatile
        var instance: RenderaAccessibilityService? = null
            private set

        private val _connected = AtomicBoolean(false)
        val isConnected: Boolean get() = _connected.get()

        fun isAvailable(): Boolean = instance != null

        /**
         * Dispatches a dodge: press at the joystick, ramp to
         * `joyCenter + radius * unit(angle)`, hold for [holdMs], release.
         *
         * @return true when the gesture was accepted by the system.
         */
        fun executeDodge(
            joyX: Float,
            joyY: Float,
            angleDeg: Float,
            radiusPx: Float,
            holdMs: Long,
            onResult: ((Boolean) -> Unit)? = null
        ): Boolean {
            val svc = instance
            if (svc == null) {
                Log.w(TAG, "dodge requested but the accessibility service is not connected")
                onResult?.invoke(false)
                return false
            }
            return svc.dispatchDodge(joyX, joyY, angleDeg, radiusPx, holdMs, onResult)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Only one gesture may be in flight; a new request supersedes the wait. */
    private val pending = AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.set(true)
        Log.i(TAG, "Rendera accessibility service connected; gestures enabled")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Rendera never reads UI nodes; typeWindowStateChanged is configured
        // purely so the service stays enabled on aggressive OEM builds.
    }

    override fun onInterrupt() {
        Log.w(TAG, "accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
            _connected.set(false)
        }
        Log.i(TAG, "accessibility service destroyed")
    }

    /**
     * Must be called from any thread; the work is marshalled to the main looper.
     */
    fun dispatchDodge(
        joyX: Float,
        joyY: Float,
        angleDeg: Float,
        radiusPx: Float,
        holdMs: Long,
        onResult: ((Boolean) -> Unit)? = null
    ): Boolean {
        if (!pending.compareAndSet(false, true)) {
            // A dodge is already in flight. Report "not now" rather than
            // silently queueing work that would arrive after the danger passed.
            onResult?.invoke(false)
            return false
        }
        mainHandler.post {
            val ok = try {
                performDodge(joyX, joyY, angleDeg, radiusPx, holdMs, onResult)
            } catch (t: Throwable) {
                Log.e(TAG, "gesture dispatch threw", t)
                onResult?.invoke(false)
                false
            }
            if (!ok) pending.set(false)
        }
        return true
    }

    private fun performDodge(
        joyX: Float,
        joyY: Float,
        angleDeg: Float,
        radiusPx: Float,
        holdMs: Long,
        onResult: ((Boolean) -> Unit)?
    ): Boolean {
        val metrics = resources.displayMetrics
        val sw = metrics.widthPixels.toFloat()
        val sh = metrics.heightPixels.toFloat()
        if (sw <= 0f || sh <= 0f) {
            onResult?.invoke(false)
            return false
        }

        val rad = Math.toRadians(angleDeg.toDouble())
        val ux = kotlin.math.cos(rad).toFloat()
        val uy = kotlin.math.sin(rad).toFloat()

        val radius = radiusPx.coerceIn(minRadius(sw), maxRadius(sw, sh))
        val startX = joyX.coerceIn(1f, sw - 2f)
        val startY = joyY.coerceIn(1f, sh - 2f)

        // Keep the endpoint on the stick's deflection circle instead of clamping
        // each axis independently, which would change the dodge direction.
        val rawX = startX + ux * radius
        val rawY = startY + uy * radius
        val endX = clampToCircle(startX, startY, rawX, rawY, radius, sw, sh).first
        val endY = clampToCircle(startX, startY, rawX, rawY, radius, sw, sh).second

        val hold = holdMs.coerceIn(MIN_STROKE_MS.toLong(), MAX_STROKE_MS.toLong())
        val ramp = RAMP_MS.coerceAtMost(hold)

        val rampPath = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val rampStroke = GestureDescription.StrokeDescription(rampPath, 0L, ramp, true)
        val holdPath = Path().apply {
            moveTo(endX, endY)
            lineTo(endX, endY)
        }
        val holdStroke = rampStroke.continueStroke(holdPath, 0L, hold - ramp, false)

        val gesture = GestureDescription.Builder()
            .addStroke(rampStroke)
            .addStroke(holdStroke)
            .build()

        val accepted = try {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) {
                        pending.set(false)
                        onResult?.invoke(true)
                    }

                    override fun onCancelled(description: GestureDescription?) {
                        pending.set(false)
                        onResult?.invoke(false)
                    }
                },
                null
            )
        } catch (t: Throwable) {
            Log.w(TAG, "dispatchGesture rejected: ${t.message}")
            false
        }
        if (!accepted) {
            Log.w(TAG, "dispatchGesture returned false (another gesture in progress)")
        }
        return accepted
    }

    private fun minRadius(sw: Float): Float = sw * 0.06f
    private fun maxRadius(sw: Float, sh: Float): Float = min(sw, sh) * 0.20f

    private fun clampToCircle(
        cx: Float,
        cy: Float,
        px: Float,
        py: Float,
        radius: Float,
        sw: Float,
        sh: Float
    ): Pair<Float, Float> {
        var x = px
        var y = py
        // Pull back onto the circle if the raw endpoint left the screen.
        if (x < 0f || x > sw || y < 0f || y > sh) {
            val dx = x - cx
            val dy = y - cy
            val len = kotlin.math.hypot(dx, dy)
            if (len > 0.01f) {
                val s = radius / len
                x = cx + dx * s
                y = cy + dy * s
            }
        }
        return Pair(
            x.coerceIn(1f, sw - 2f),
            y.coerceIn(1f, sh - 2f)
        )
    }
}
