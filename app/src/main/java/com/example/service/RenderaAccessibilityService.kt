package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.Configuration
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.accessibility.AccessibilityEvent
import com.example.vision.DodgeGesturePlanner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The "hands" of the system: turns solved escape plans into real touches.
 *
 * ## Why the previous gesture did nothing
 *
 * It dispatched a single 15 ms `StrokeDescription` from the stick centre to the
 * target. Two independent reasons make that useless:
 *
 *  * 15 ms is under the platform touch slop, so the game receives a *tap* at the
 *    stick base rather than a drag, and the brawler never moves.
 *  * Even with a sane duration, one straight line releases the stick the instant
 *    it arrives, so the brawler travels a fraction of the distance and stops. A
 *    dodge that only nudges you is worse than no dodge, because it commits you
 *    to an input you cannot cancel.
 *
 * The fix is a chained press / drag / hold / release gesture built by
 * [DodgeGesturePlanner]. The hold phase is what turns a twitch into an actual
 * movement, and the whole chain is one atomic gesture so the game cannot
 * interleave another touch.
 *
 * ## Also fixed here
 *
 *  * `resources.displayMetrics` is not the display the game is on; for a service
 *    in multi-window it reports the service's own window. Coordinates now come
 *    from [DisplayManager].
 *  * `dispatchGesture` returns false when a gesture is already running. The old
 *    code ignored that and counted a dodge anyway, so under a burst of threats
 *    the app silently stopped dodging while still reporting successes.
 *  * `onAccessibilityEvent` stays a no-op and the service config no longer
 *    subscribes to `typeWindowContentChanged`, which was flooding the service
 *    with thousands of events per second from the game.
 */
class RenderaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RenderaAccessibility"

        /**
         * The system drops a `GestureDescription` dispatched while a previous one
         * is still running, and offers no queue. One in-flight gesture at a time,
         * separated by a short gap, is the only reliable pattern.
         */
        private const val GESTURE_GAP_MS = 24L

        @Volatile
        var instance: RenderaAccessibilityService? = null
            private set

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        private val _foregroundPackage = MutableStateFlow("")
        val foregroundPackage: StateFlow<String> = _foregroundPackage.asStateFlow()

        /** True once the service is bound and can accept gestures. */
        fun isAvailable(): Boolean = instance != null

        /**
         * True when [targetPackage] is the app currently in the foreground.
         *
         * Detection over the launcher or a settings screen is pure noise: the
         * tracker latches onto scrolling thumbnails and then the first real
         * frame is read as a scene change, which dumps the tracks. An empty
         * [targetPackage] means "no target configured", in which case this
         * reports true and the caller falls back to its own check.
         */
        fun isTargetInForeground(targetPackage: String): Boolean {
            // Either of these is "unknown", not "not foreground". Reporting
            // unknown as false is the trap: the service can connect while the
            // game is already focused, in which case no typeWindowStateChanged
            // event ever fires for it, and the overlay would reset its history
            // forever and never detect anything.
            if (targetPackage.isEmpty()) return true
            val current = _foregroundPackage.value
            if (current.isEmpty()) return true
            if (isSystemUi(current)) return true
            return current == targetPackage
        }

        /**
         * System UI (volume panel, notification shade, IME, permission dialogs)
         * takes focus constantly and steals it back immediately. Treating it as
         * "the game is no longer in front" would reset the detector mid-fight on
         * a single volume key press, so it is deliberately not a target change.
         */
        private fun isSystemUi(pkg: String): Boolean =
            pkg == "com.android.systemui" ||
                pkg == "android" ||
                pkg.startsWith("com.google.android.inputmethod") ||
                pkg.startsWith("com.android.inputmethod")

        /**
         * True when nothing is in flight, i.e. a dispatch would be accepted right
         * now. Callers should still treat a `false` return from [dispatch] as the
         * authoritative answer.
         */
        fun isIdle(): Boolean = instance?.isIdle() ?: false

        /**
         * Dispatches a planned dodge.
         *
         * @return true when the gesture was accepted by the system. A false
         *         result means nothing was sent and the caller must not count it.
         */
        fun dispatch(plan: DodgeGesturePlanner.Plan, onResult: ((Boolean) -> Unit)? = null): Boolean {
            val service = instance
            if (service == null) {
                Log.w(TAG, "dispatch: accessibility service is not connected")
                onResult?.invoke(false)
                return false
            }
            if (plan.isEmpty) {
                Log.w(TAG, "dispatch: refusing to send an empty gesture plan")
                onResult?.invoke(false)
                return false
            }
            return service.send(plan, onResult)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * True while a gesture is in flight or within the mandatory inter-gesture
     * gap, so the vision thread can skip work it knows cannot be delivered.
     */
    private val gestureBusy = AtomicBoolean(false)

    private var displayWidth = 0
    private var displayHeight = 0
    private var displayRotation = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnected.value = true
        refreshDisplayGeometry()
        Log.i(TAG, "Accessibility service connected; display ${displayWidth}x$displayHeight")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The only thing read from an event is which package owns the focused
        // window, and the service subscribes to `typeWindowStateChanged` alone
        // precisely so this stays cheap. Nothing inspects window content.
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrEmpty()) _foregroundPackage.value = pkg
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
            _isServiceConnected.value = false
            _foregroundPackage.value = ""
        }
        gestureBusy.set(false)
        Log.i(TAG, "Accessibility service destroyed")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshDisplayGeometry()
    }

    fun isIdle(): Boolean = !gestureBusy.get()

    fun currentDisplayWidth(): Int = displayWidth
    fun currentDisplayHeight(): Int = displayHeight
    fun currentDisplayRotation(): Int = displayRotation

    /**
     * Resolves the real size and rotation of the display the game renders into.
     *
     * `resources.displayMetrics` reports the *service's* window, which is wrong
     * in split screen and in freeform windows, and it does not follow rotation.
     * The accessibility `DisplayManager` is the reliable source.
     */
    private fun refreshDisplayGeometry() {
        try {
            val dm = getSystemService(DisplayManager::class.java)
            val display: Display? = dm?.getDisplay(Display.DEFAULT_DISPLAY)
                ?: dm?.getDisplays()?.firstOrNull()
            if (display != null) {
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                val rotated = display.rotation == Surface.ROTATION_90 ||
                    display.rotation == Surface.ROTATION_270
                val w = if (rotated) metrics.height else metrics.width
                val h = if (rotated) metrics.width else metrics.height
                if (w > 0 && h > 0) {
                    displayWidth = w
                    displayHeight = h
                }
                displayRotation = display.rotation
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Display geometry lookup failed; using window metrics", t)
        }
        if (displayWidth <= 0 || displayHeight <= 0) {
            val metrics = resources.displayMetrics
            displayWidth = metrics.widthPixels
            displayHeight = metrics.heightPixels
        }
    }

    private fun send(plan: DodgeGesturePlanner.Plan, onResult: ((Boolean) -> Unit)?): Boolean {
        if (!gestureBusy.compareAndSet(false, true)) {
            Log.w(TAG, "send: a gesture is already in flight, skipping this one")
            onResult?.invoke(false)
            return false
        }

        return try {
            val builder = GestureDescription.Builder()
            for (stroke in plan.strokes) {
                val path = Path().apply {
                    moveTo(stroke.startX, stroke.startY)
                    lineTo(stroke.endX, stroke.endY)
                }
                builder.addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        stroke.startTimeMs,
                        stroke.durationMs.coerceAtLeast(DodgeGesturePlanner.MIN_STROKE_MS),
                        stroke.willContinue
                    )
                )
            }
            val gesture = builder.build()

            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    release()
                    Log.d(TAG, "Dodge gesture completed, ${plan.totalDurationMs}ms total")
                    onResult?.invoke(true)
                }

                override fun onCancelled(description: GestureDescription?) {
                    release()
                    Log.w(TAG, "Dodge gesture cancelled by the system")
                    onResult?.invoke(false)
                }
            }, null)

            if (!accepted) {
                // The system refused it outright, e.g. another gesture is
                // already running. Do not leave the flag stuck.
                release()
                Log.w(TAG, "dispatchGesture refused the dodge gesture")
                onResult?.invoke(false)
            }
            accepted
        } catch (t: Throwable) {
            release()
            Log.e(TAG, "Failed to dispatch dodge gesture", t)
            onResult?.invoke(false)
            false
        }
    }

    private fun release() {
        // Hold the flag across the mandatory gap so back to back gestures do not
        // collide, then clear it on the main thread.
        mainHandler.postDelayed({ gestureBusy.set(false) }, GESTURE_GAP_MS)
    }
}
