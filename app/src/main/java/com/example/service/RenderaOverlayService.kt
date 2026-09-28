package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.BuildConfig
import com.example.MainActivity
import com.example.R
import com.example.RenderaApp
import com.example.data.RenderaPreferences
import com.example.model.DetectionStats
import com.example.model.ThreatLevel
import com.example.ui.overlay.CalibrationOverlayView
import com.example.ui.overlay.RenderaReticleOverlay
import com.example.ui.overlay.TacticalHudView
import com.example.vision.AnchorCalibrator
import com.example.vision.AnchorTarget
import com.example.vision.Anchors
import com.example.vision.DodgeDecisionState
import com.example.vision.RenderaEventLog
import com.example.vision.ScreenThreatDetector
import com.example.vision.nativebridge.ScreenRegion
import com.example.vision.nativebridge.VisionTuning
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Orchestrates the whole system: screen capture, vision, dodging and the
 * on-screen UI.
 *
 * ## The four bugs that made this look completely dead
 *
 * 1. **No `MediaProjection.Callback` was ever registered.** From Android 14
 *    (API 34) onwards, `createVirtualDisplay()` throws `IllegalStateException`
 *    unless a callback has been registered first. `targetSdk` here is 36, so on
 *    any modern device the call threw, the `try/catch` swallowed it,
 *    `virtualDisplay` stayed null, the `ImageReader` never got a producer, and
 *    `acquireLatestImage()` returned null **forever**. No frames, no detection,
 *    no auto-calibration, nothing. That is why "auto calib does nothing" and the
 *    live feed was blank.
 *
 * 2. **The capture path was `RGBA_8888` + `Bitmap`.** It discarded three
 *    quarters of the data it copied, allocated a `Bitmap` every frame, and
 *    assumed the image plane limit was `rowStride * height` when it is usually
 *    smaller, so `copyPixelsFromBuffer` threw on real devices. Now `YUV_420_888`
 *    with a pooled direct-buffer ring. See [YuvFrameRing].
 *
 * 3. **The bubble opened the menu instead of toggling.** The long-press runnable
 *    was posted at 450 ms and only cancelled on `ACTION_UP`, but a *click* also
 *    opens the menu because `ACTION_CANCEL` returned false and let the touch
 *    stream be stolen, while the drag threshold was only 12 px on a 62 dp
 *    bubble, so ordinary finger jitter cancelled the toggle and started a drag.
 *    Both thresholds are now separate, and the view consumes the entire gesture.
 *
 * 4. **The manual calibration never reached the detector.** `LOCK &amp; ACTIVATE`
 *    wrote the anchors into a preferences object that the vision loop never read,
 *    and after 25 missed detections the detector replaced the player with a
 *    hard-coded `0.50 * screenWidth`. Anchors are now a single shared value in
 *    [RenderaPreferences] that the detector is explicitly told about.
 *
 * The dodge gesture itself was fixed in [RenderaAccessibilityService] and
 * [com.example.vision.DodgeGesturePlanner].
 */

/**
 * Everything the UI needs to describe what the engine is actually doing, published
 * as observable state rather than polled from a bare `var`.
 *
 * `capturing` is deliberately separate from `running`: the service can be alive
 * with the capture torn down, and showing "active" for that state is how the app
 * ends up looking healthy while detecting nothing.
 */
data class ServiceStatus(
    val running: Boolean = false,
    val capturing: Boolean = false,
    val armed: Boolean = false,
    val anchorsCalibrated: Boolean = false,
    val nativeAvailable: Boolean = false,
    val accessibilityReady: Boolean = false,
    val foregroundPackage: String = "",
    val targetPackage: String = "",
    val suppressedByBackground: Boolean = false,
    val fps: Int = 0,
    val visionMillis: Double = 0.0
)

class RenderaOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.example.RenderaOverlayService.START"
        const val ACTION_STOP = "com.example.RenderaOverlayService.STOP"
        const val ACTION_TOGGLE = "com.example.RenderaOverlayService.TOGGLE"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA_INTENT = "dataIntent"
        const val EXTRA_GAME_NAME = "gameName"
        const val EXTRA_PACKAGE_NAME = "packageName"

        /** Set when the bubble asks the Activity for a fresh capture grant. */
        const val EXTRA_NEEDS_CAPTURE = "needsCapture"

        private const val TAG = "RenderaOverlay"
        private const val NOTIFICATION_ID = 4711

        /**
         * Long edge of the captured image, in pixels.
         *
         * This is the real resolution limit of the whole pipeline: the engine
         * downsamples to a 200 wide grid, so the capture must be at least that
         * wide or the downsample throws information away. 640 gives a 2400x1080
         * display a 3.75x reduction, which leaves a Brawl Stars bullet about 7
         * capture pixels across and therefore 2-3 grid cells: enough to survive
         * the noise floor and the minimum blob area. YUV_420_888 requires even
         * dimensions on both axes.
         */
        private const val CAPTURE_LONG_EDGE_EVEN = 640

        private const val VISION_IDLE_SLEEP_MS = 4L
        private const val STATS_INTERVAL_MS = 1000L

        /**
         * How long the foreground app must disagree with the target before
         * dodging is suppressed. Long enough that no focus flap reaches it.
         */
        private const val BACKGROUND_CONFIRM_MS = 2500L

        /** The HUD is a readout, not an animation: ten updates a second is ample. */
        private const val HUD_MIN_INTERVAL_MS = 100L

        // ARGB colours whose top bit is set do not fit in a Kotlin Int literal:
        // 0xCC2A0845 is 3425306693, so `const val x = 0xCC2A0845` is inferred as
        // Long and every use as a colour fails to compile. These are `val` with an
        // explicit narrowing, which keeps the readable hex and the Int type.
        private val COLOR_IDLE: Int = 0xCC2A0845.toInt()
        private val COLOR_ARMED: Int = 0xE60F3822.toInt()
        private val COLOR_MENU: Int = 0xF01A0F2E.toInt()

        private val _status = MutableStateFlow(ServiceStatus())
        val status: StateFlow<ServiceStatus> = _status.asStateFlow()

        /** True while the service is alive. Observed state, not a bare var. */
        val isRunning: Boolean get() = _status.value.running
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: RenderaPreferences
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var visionJob: Job? = null

    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var imageReader: ImageReader? = null
    private var captureThread: android.os.HandlerThread? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val frameRing = YuvFrameRing(poolSize = 3)

    private var displayWidth = 0
    private var displayHeight = 0
    private var displayRotation = 0
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureConfiguredForRotation = -1

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    /** Held so it can be cancelled precisely on teardown. */
    private var bubbleLongPress: Runnable? = null
    private var menuView: View? = null
    private var menuX = 0
    private var menuY = 0
    private var hudView: TacticalHudView? = null
    private var reticleView: RenderaReticleOverlay? = null
    private var calibrationView: CalibrationOverlayView? = null

    private var lastDodgeAtMs = 0L

    private var statsFrames = 0
    private var statsWindowStartMs = 0L

    // Written from the vision thread, read from the main thread and from logging.
    @Volatile private var latestFps = 0
    @Volatile private var latestVisionMillis = 0.0
    @Volatile private var latestSeverity = ThreatLevel.SAFE
    @Volatile private var threatCount = 0
    @Volatile private var dodgeCount = 0
    @Volatile private var lastDodgeAngleDeg = 0f

    // The vision thread reads the detector while the main thread creates and
    // destroys it. Without volatile it can call process() on a closed engine.
    @Volatile private var detector: ScreenThreatDetector? = null

    // Cross-thread, main thread reads it for auto-detect.
    @Volatile private var latestAnalysis: ScreenThreatDetector.Analysis? = null

    /**
     * Per-threat dodge bookkeeping. Replaces the old wall-clock cooldown, which
     * went blind for the whole duration of a dodge and is the reason a second
     * projectile could land while the first was still being avoided.
     */
    private val dodgeState = DodgeDecisionState(
        canDispatch = { RenderaAccessibilityService.isIdle() }
    )

    // Capture and analysis counters. These are the only honest way to tell
    // "no threat found" apart from "no frames are arriving", which look
    // identical from the outside and were guessed at repeatedly.
    @Volatile private var framesReceived = 0L
    @Volatile private var framesRejected = 0L
    @Volatile private var framesAnalysed = 0L
    @Volatile private var lastFrameAtMs = 0L

    @Volatile private var autoDodgeArmed = false

    /** When the foreground app was first seen to be something other than the game. */
    @Volatile private var notInGameSinceMs = 0L

    /** Last HUD content key and when it was pushed, for the change/interval gate. */
    @Volatile private var lastHudKey: String = ""
    @Volatile private var lastHudAtMs = 0L
    @Volatile private var lastReticleAtMs = 0L
    @Volatile private var anchors: Anchors = Anchors.defaultFor(0, 0)

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = RenderaPreferences.get(this)
        statsWindowStartMs = SystemClock.elapsedRealtime()
        events.beginSession(
            mapOf(
                "build" to (BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"),
                "device" to (Build.MANUFACTURER + " " + Build.MODEL),
                "app" to packageName
            )
        )
        publishStatus(running = true, capturing = false, armed = false, anchorsOk = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                toggleAutoDodge()
                return START_NOT_STICKY
            }
            else -> {
                // Everything here can throw, and none of it is fatal. A service
                // that dies during onStartCommand gives the user a black screen
                // and no explanation, which is the "it crashes when I pick a game"
                // report. A failure here is reported into the status and as a
                // toast, and the service stays alive so the bubble still works.
                try {
                    startWithConsent(intent)
                } catch (t: Throwable) {
                    Log.e(TAG, "Start failed", t)
            events.error("start", t.message ?: "threw", t)
                    startFailure = "Start failed: ${t.javaClass.simpleName}: ${t.message}"
                    publishStatus(capturing = false, armed = false)
                    mainHandler.post { toast("Rendera could not start: ${t.message ?: t.javaClass.simpleName}") }
                }
            }
        }
        // NOT sticky on purpose. A restarted service is handed a null Intent, so
        // it has no MediaProjection consent token and could never capture again.
        // START_STICKY left it stuck in the foreground with a notification, no
        // bubble and no frames, which looks exactly like a hung app.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation invalidates the capture geometry and, importantly, the
        // calibration: a stick at (0.17, 0.76) in landscape is not the same
        // physical location in portrait.
        mainHandler.post { onGeometryChanged() }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopEverything()
        stopSelf()
    }

    private fun stopEverything() {
        visionJob?.cancel()
        visionJob = null
        releaseCapture()
        synchronized(detectorLock) {
            detector?.close()
            detector = null
        }
        mainHandler.post {
            removeCalibrationOverlay()
            removeHud()
            removeMenu()
            removeBubble()
        }
        autoDodgeArmed = false
        dodgeState.reset()
        publishStatus(running = false, capturing = false, armed = false, anchorsOk = false)
        // Close the session with a verdict summary, so the log always ends with
        // the number that matters: gestures sent, and how many worked.
        runCatching { events.flushOpen("service stopped") }
            .onSuccess {
                val text = runCatching { events.summaryText() }.getOrNull()
                if (text != null) Log.i(TAG, "\n" + text)
            }
            .onFailure { Log.w(TAG, "Could not close the event log", it) }
    }

    /**
     * The single source of truth for what the app is doing, pushed to Compose as
     * observable state.
     *
     * The status pill used to read a plain `var` during composition, so Compose
     * never recomposed on it: it showed whatever was true the first time the
     * screen was drawn and stayed there. That is the "Rendera stopped, nothing
     * happens" report - the UI was not wrong, it was frozen.
     */
    private fun publishStatus(
        running: Boolean = _status.value.running,
        capturing: Boolean = _status.value.capturing,
        armed: Boolean = autoDodgeArmed,
        anchorsOk: Boolean = anchors.calibrated
    ) {
        _status.value = ServiceStatus(
            running = running,
            capturing = capturing,
            armed = armed && capturing,
            anchorsCalibrated = anchorsOk,
            nativeAvailable = detector?.isNativeAvailable == true,
            accessibilityReady = RenderaAccessibilityService.isAvailable(),
            foregroundPackage = RenderaAccessibilityService.foregroundPackage.value,
            targetPackage = prefs.targetPackage.value,
            suppressedByBackground = shouldSuppressDodge(),
            fps = latestFps,
            visionMillis = latestVisionMillis
        )
    }

    // -----------------------------------------------------------------------
    // Foreground notification
    // -----------------------------------------------------------------------

    /**
     * Starts (or retargets) the service from a consent-carrying Intent.
     *
     * ## Why a second start must NOT re-acquire
     *
     * Picking a game after capture is already running sends a second
     * ACTION_START carrying the SAME consent token that has already been spent.
     * On Android 14+ that token is single use: re-acquiring with it invalidates
     * the live projection, the first projection's callback fires, and the app
     * tears itself down - which is exactly the reported "picking a game crashes
     * it, and afterwards it says no capture".
     *
     * So a second start is a RETARGET: update which app we watch, keep the
     * projection, and do not touch consent at all. A genuinely fresh capture
     * always comes from the Activity, which is the only place a consent dialog
     * can be shown.
     */
    private fun startWithConsent(intent: Intent?) {
        val gameName = intent?.getStringExtra(EXTRA_GAME_NAME) ?: "Universal"
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
        if (pkg.isNotEmpty() || gameName != "Universal") {
            prefs.setTarget(pkg, gameName)
        }

        val alreadyCapturing = mediaProjection != null && virtualDisplay != null && !captureEnded

        // startForeground MUST precede getMediaProjection() on API 29+, and it is
        // safe to call again.
        startInForeground()

        if (alreadyCapturing) {
            // Retarget only. The token in this Intent has already been spent.
            Log.i(TAG, "Retargeting to $pkg without re-acquiring the projection")
            captureEnded = false
            projectionStopHandled = false
            startFailure = null
            ensureDetector()
            if (bubbleView == null) showFloatingBubble()
            startVisionLoop()
            publishStatus(capturing = true)
            mainHandler.post { toast("Watching ${pkg.ifEmpty { "the foreground app" }}") }
            return
        }

        // No live projection. Either a first start, or one after the projection
        // ended, in which case this Intent's token is stale and consent has to be
        // requested again from the Activity.
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = try {
            intent?.getParcelableExtra(EXTRA_DATA_INTENT)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not read the consent Intent", t)
            null
        }

        if (resultCode == 0 || data == null) {
            startFailure = "No screen capture consent. Open Rendera and grant it."
            Log.w(TAG, startFailure!!)
            showBubbleOnly()
            publishStatus(capturing = false, armed = false)
            mainHandler.post { toast("Grant screen recording in Rendera first") }
            return
        }

        // Retire a stale projection from a previous session, but only if one is
        // actually live. `releaseCapture` is a no-op when there is nothing to
        // retire, which is what keeps the foreground state - and therefore the
        // ability to acquire a projection at all - intact.
        if (mediaProjection != null || virtualDisplay != null) {
            releaseCapture()
        } else if (resultCode != 0 && data != null) {
            // About to spend the token. If anything downstream then fails, the
            // next start must ask for a new grant rather than replay this one.
            consentTokenSpent = false
        }

        if (!setupCapture(resultCode, data)) {
            // setupCapture has already named the step in the status. The only
            // thing left is to make sure the user can actually recover.
            showBubbleOnly()
            mainHandler.post {
                toast(startFailure ?: "Screen capture failed. Tap the bubble to retry.")
            }
            return
        }
        consentTokenSpent = true

        captureEnded = false
        projectionStopHandled = false
        startFailure = null
        publishStatus(capturing = true)
        ensureDetector()
        showFloatingBubble()
        startVisionLoop()
        mainHandler.post {
            prefs.setAutoDodge(true)
            autoDodgeArmed = true
            publishStatus()
            refreshBubbleUi()
            if (!anchors.calibrated) {
                toast("Capturing. Long press the bubble to set the anchors.")
            }
        }
    }

    /**
     * A minimal, always-usable shell when capture could not start: the bubble
     * still appears and still opens the menu, so the user can reach the re-grant
     * path instead of being left with nothing on screen.
     */
    private fun showBubbleOnly() {
        if (bubbleView == null) showFloatingBubble()
    }

    /**
     * Phase 1 of the foreground handshake: an untyped `startForeground`.
     *
     * The ordering rules around `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` have
     * shifted across releases, and getting them wrong produces exactly the
     * symptom this whole file exists to fix: a foreground service with a
     * notification and no frames. Concretely:
     *
     *  * a VirtualDisplay may only be created while the service is in the
     *    foreground, so a plain `startForeground` must already have happened;
     *  * the typed variant validates against a live MediaProjection token, whose
     *    ordering relative to `startForeground` is not stable across API levels.
     *
     * Doing the untyped call first and upgrading to the typed one **after** the
     * token exists satisfies both constraints on every API level this app
     * supports (24..36). It is deliberately not "clean" enough to collapse into
     * one call.
     */
    private fun startInForeground() {
        val notification = buildNotification()
        // Untyped: always accepted, and satisfies the "foreground before
        // createVirtualDisplay" requirement.
        startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * Phase 2: upgrade the service to the media projection type, now that a
     * MediaProjection token exists. See [startInForeground] for why this is a
     * separate call.
     */
    private fun upgradeForegroundToMediaProjection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val notification = buildNotification()
        startForeground(
            NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, RenderaApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_desc))
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    // -----------------------------------------------------------------------
    // Capture
    // -----------------------------------------------------------------------

    /**
     * Registers the projection callback, then creates the reader and the virtual
     * display.
     *
     * Order matters and is the single most important thing in this file: from
     * API 34 the system rejects `createVirtualDisplay()` with
     * `IllegalStateException` unless `registerCallback()` ran first.
     */
    /**
     * Acquires a projection and points it at a frame reader.
     *
     * ## Why this reports a step and not just "failed"
     *
     * Every version of this collapsed every failure into a single "capture
     * could not start", and that message was useless: "no capture, please grant
     * screen recording" is what the user saw **after they had already granted
     * it**, and it sent every attempt at the problem looking for a missing
     * permission that was not missing. So each step is named, the exception is
     * recorded, and the step is put in the status and in the event log.
     *
     * ## Why the token is never reused
     *
     * A MediaProjection consent token is **single use**. Once a projection has
     * been created from it, re-acquiring with the same token fails on Android 14
     * and later. The Activity therefore hands the token over once and clears it,
     * and a second attempt is told plainly that a fresh grant is needed instead
     * of retrying with something that can no longer work.
     */
    private fun setupCapture(resultCode: Int, data: Intent?): Boolean {
        if (resultCode == 0 || data == null) {
            fail("no-consent", "No screen capture consent. Grant it in Rendera.")
            return false
        }
        if (resolveDisplayGeometry().not() || displayWidth < 16 || displayHeight < 16) {
            fail("geometry", "Screen size not resolved (${displayWidth}x$displayHeight).")
            return false
        }
        computeCaptureSize()
        if (captureWidth < 16 || captureHeight < 16) {
            fail("geometry", "Capture size invalid: ${captureWidth}x$captureHeight.")
            return false
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // The typed foreground call is not optional and is not best effort. A
        // MediaProjection may only be used while the service holds the
        // mediaProjection foreground type, and swallowing a failure here means
        // the next call throws with a message that points somewhere else entirely.
        try {
            upgradeForegroundToMediaProjection()
        } catch (t: Throwable) {
            fail("foreground",
                "Foreground media projection refused: ${t.message ?: t.javaClass.simpleName}", t)
            return false
        }

        val projection: MediaProjection = try {
            mpm.getMediaProjection(resultCode, data)
                ?: run { fail("get", "getMediaProjection returned null."); return false }
        } catch (t: Throwable) {
            // The overwhelmingly common cause here: the token was already spent
            // by an earlier attempt. Say that, instead of implying a missing
            // permission the user has already given.
            fail("token", "Consent token rejected: ${t.message ?: t.javaClass.simpleName}", t)
            return false
        }
        mediaProjection = projection

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped by the system or the user")
                mainHandler.post { onProjectionStopped() }
            }
        }
        mediaProjectionCallback = callback
        try {
            // Required before createVirtualDisplay on Android 14 and later.
            projection.registerCallback(callback, mainHandler)
        } catch (t: Throwable) {
            fail("callback", "registerCallback failed: ${t.message ?: t.javaClass.simpleName}", t)
            return false
        }

        frameRing.configure(captureWidth, captureHeight)
        val reader = createImageReader()
        if (reader == null) {
            fail("reader", "Could not create a frame reader at ${captureWidth}x$captureHeight.")
            return false
        }
        imageReader = reader

        virtualDisplay = try {
            projection.createVirtualDisplay(
                "RenderaVision",
                captureWidth,
                captureHeight,
                densityDpi(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )
        } catch (t: Throwable) {
            fail("display",
                "createVirtualDisplay refused at ${captureWidth}x$captureHeight: " +
                    "${t.message ?: t.javaClass.simpleName}", t)
            return false
        }

        startFailure = null
        captureEnded = false
        projectionStopHandled = false
        Log.i(TAG, "Capture started: ${captureWidth}x$captureHeight on ${displayWidth}x$displayHeight")
        return true
    }

    /**
     * Records which step failed, so the status and the event log name it rather
     * than saying "capture failed" and leaving the user to guess.
     */
    private fun fail(step: String, message: String, throwable: Throwable? = null) {
        startFailure = message
        Log.e(TAG, "Capture failed at '$step': $message", throwable)
        runCatching { events.error("capture:$step", message, throwable) }
        publishStatus(capturing = false, armed = false)
    }

    /**
     * Builds an ImageReader at the current capture size, wired to the capture
     * thread.
     *
     * Rebuildable on purpose. The reader's size is fixed at construction, so a
     * geometry change has to hand the VirtualDisplay a NEW surface; resizing the
     * display alone leaves the reader expecting the old dimensions and every
     * frame is then rejected. That is not a rare edge case: Brawl Stars forces
     * landscape, so the rotation fires almost immediately after the service
     * starts and killed the capture permanently.
     *
     * Returns null rather than throwing, because
     * `ImageReader.newInstance(0, 0, ...)` is an IllegalArgumentException and a
     * display that has not resolved yet would take the whole service down on
     * start.
     */
    private fun createImageReader(): ImageReader? {
        if (captureWidth < 16 || captureHeight < 16) {
            Log.e(TAG, "Capture size not resolved (${captureWidth}x$captureHeight)")
            events.error("capture", "display size not resolved: ${displayWidth}x$displayHeight")
            return null
        }
        return try {
            val reader = ImageReader.newInstance(
                captureWidth, captureHeight, android.graphics.ImageFormat.YUV_420_888, 2
            )
            val handler = captureHandler()
            reader.setOnImageAvailableListener({ r: ImageReader ->
                // Runs on the capture thread. Copy the planes out and hand the
                // image straight back; never hold it, it holds a buffer.
                var image: Image? = null
                try {
                    image = r.acquireLatestImage()
                    if (image != null) {
                        if (frameRing.publish(image)) framesReceived++ else framesRejected++
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Frame acquisition failed", t)
                    events.error("capture", "acquire: " + (t.message ?: "threw"), t)
                } finally {
                    try {
                        image?.close()
                    } catch (ignored: Throwable) {
                        // The image is being discarded either way.
                    }
                }
            }, handler)
            reader
        } catch (t: Throwable) {
            Log.e(TAG, "ImageReader creation failed for ${captureWidth}x$captureHeight", t)
            events.error("capture", "ImageReader ${captureWidth}x$captureHeight: " + (t.message ?: "threw"), t)
            null
        }
    }

    /** One HandlerThread for the plane copies, created on first use. */
    private fun captureHandler(): Handler {
        val existing = captureThread
        if (existing != null && existing.isAlive) return Handler(existing.looper)
        val thread = HandlerThread("RenderaCapture", android.os.Process.THREAD_PRIORITY_DISPLAY)
        captureThread = thread
        thread.start()
        return Handler(thread.looper)
    }

    /**
     * Retires the current capture, if there is one.
     *
     * Every step is conditional on there actually being something live, and that
     * matters more than it looks: `stopForeground` removes the foreground service
     * state that a MediaProjection legally requires. An unconditional teardown
     * that ran before a new projection was acquired therefore pulled the
     * foreground state out from under `createVirtualDisplay`, which then throws
     * `IllegalStateException` on Android 14 and later. The symptom was the app
     * reporting "started" and then, immediately, that recording had ended.
     *
     * `suppressProjectionCallback` makes a stop we asked for invisible to our
     * own `onStop` handler, which is otherwise told about a teardown it
     * initiated and treats it as an external failure.
     */
    private fun releaseCapture() {
        val hadProjection = mediaProjection != null || virtualDisplay != null

        try {
            virtualDisplay?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "virtualDisplay release failed", t)
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "imageReader close failed", t)
        }
        imageReader = null

        try {
            captureThread?.quitSafely()
        } catch (t: Throwable) {
            Log.w(TAG, "capture thread shutdown failed", t)
        }
        captureThread = null

        try {
            mediaProjectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterCallback failed", t)
        }
        mediaProjectionCallback = null

        if (hadProjection) {
            // This stop is ours. A late onStop must not be mistaken for the
            // system or the user revoking consent, which tears the overlay down.
            suppressProjectionCallback = true
            try {
                mediaProjection?.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "mediaProjection stop failed", t)
            }
            mediaProjection = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (t: Throwable) {
                    Log.w(TAG, "stopForeground failed", t)
                }
            }
        } else {
            // Nothing was live, so the foreground notification must stay: it is
            // the precondition for acquiring a projection at all.
            mediaProjection = null
        }
        suppressProjectionCallback = false
        frameRing.release()
    }

    private fun onProjectionStopped() {
        // A MediaProjection can report the end more than once, and each report
        // tears the overlay down. Handling the second one re-enters the teardown
        // and leaves a stale window behind.
        if (suppressProjectionCallback) {
            Log.i(TAG, "Projection stop we initiated; ignoring")
            return
        }
        if (projectionStopHandled) return
        projectionStopHandled = true
        mainHandler.post {
            captureEnded = true
            publishStatus(capturing = false, armed = false)
            visionJob?.cancel()
            visionJob = null
            releaseCapture()
            autoDodgeArmed = false
            prefs.setAutoDodge(false)
            dodgeState.reset()
            latestAnalysis = null
            lastHudKey = ""
            removeHud()
            closeMenu()
            removeCalibrationOverlay()
            // A restartable capture state, so the bubble is a real control again
            // rather than a decoration.
            showRestartNotice()
        }
    }

    /**
     * Replaces the bubble with a clearly actionable "capture ended" bubble.
     *
     * A long press cannot fix this, because a fresh consent grant needs an
     * Activity. Tapping opens the app, where the permission row does the work.
     */
    private fun showRestartNotice() {
        removeBubble()
        val sizePx = dp(72f).roundToInt()
        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12f).roundToInt()
            y = dp(140f).roundToInt()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBackground(COLOR_IDLE)
            elevation = dp(10f)
        }
        val label = TextView(this).apply {
            text = getString(R.string.bubble_capture_ended)
            setTextColor(0xFF0B0710.toInt())
            textSize = 9f
            gravity = Gravity.CENTER
        }
        root.addView(
            label,
            LinearLayout.LayoutParams(sizePx - dp(10f).roundToInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        root.setOnClickListener { requestCaptureGrant() }
        runCatching { windowManager.addView(root, params) }
            .onSuccess { bubbleView = root; bubbleParams = params }
            .onFailure { Log.e(TAG, "could not show the restart notice", it) }
    }

    /**
     * True once a consent token has been handed to `getMediaProjection`.
     *
     * A MediaProjection token is single use, so this is what distinguishes "the
     * user never granted anything" from "the grant was already spent by an
     * earlier attempt". Without it the second case reports the first, which is
     * why the user kept seeing "grant screen recording" after they had granted
     * it.
     */
    @Volatile private var consentTokenSpent = false

    /** Whether the capture was torn down and has not been re-armed. */
    @Volatile private var captureEnded = false
    @Volatile private var projectionStopHandled = false

    /** True while we are stopping a projection on purpose. */
    @Volatile private var suppressProjectionCallback = false

    /**
     * The learning record: what the engine saw, what it decided, and whether the
     * gesture worked. Without an outcome for each dodge, every tuning decision
     * in this project has been an argument from reasoning, and the reasoning has
     * been wrong often enough to be useless.
     */
    private val events: RenderaEventLog by lazy { RenderaEventLog(this) }

    /** When the last outcome was judged, so a new one can be allowed. */
    private var lastOutcomeAtMs = 0L

    /** Why the last start attempt failed, or null. Shown instead of a guess. */
    @Volatile private var startFailure: String? = null

    /**
     * Sends the user to the Activity, which is the only place a fresh
     * MediaProjection consent dialog can be shown. On Android 14+ the token is
     * single use, so this is not optional after any capture end.
     */
    /**
     * One compact line per interval, mirrored to a report file the user can read.
     *
     * The recurring problem with this project has been guessing which stage stopped.
     * Frames never arriving, frames arriving and being rejected, frames analysed
     * with no player lock, and a solved threat with no dispatch all look identical
     * from outside the app, and each was guessed at in turn. One line makes them
     * distinguishable, and the file means a report can be attached to a bug without a
     * cable. `adb logcat -s RenderaOverlay` shows the same lines.
     */
    /**
     * Appends to a plain text report in the app's own files directory, rotated so it
     * cannot grow without bound.
     */
    private fun appendReport(line: String) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val f = java.io.File(dir, "rendera-diagnostics.txt")
            if (f.length() > 256L * 1024L) f.writeText("")
            f.appendText("${System.currentTimeMillis()} $line\n")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not write the diagnostic report", t)
        }
    }

    private fun logDiagnostics() {
        val sinceFrame = if (lastFrameAtMs == 0L) -1L
            else SystemClock.elapsedRealtime() - lastFrameAtMs
        val suppressed = shouldSuppressDodge()
        val anchorsStale = anchors.calibrated &&
            anchors.calibratedForWidth != displayWidth
        val line = "Rendera state:" +
            " alive=${_status.value.running}" +
            " capture=${_status.value.capturing}" +
            " native=${detector?.isNativeAvailable == true}" +
            " armed=$autoDodgeArmed" +
            " anchors=${anchors.calibrated}" +
            " stale=$anchorsStale" +
            " fps=$latestFps" +
            " got=${framesReceived}" +
            " rejected=${framesRejected}" +
            " analysed=${framesAnalysed}" +
            " sinceFrameMs=$sinceFrame" +
            " engine=${"%.1f".format(latestVisionMillis)}ms" +
            " blobs=${latestAnalysis?.blobCount ?: -1}" +
            " proj=${latestAnalysis?.projectileCount ?: -1}" +
            " ball=${latestAnalysis?.ballCount ?: -1}" +
            " foes=${latestAnalysis?.enemyCount ?: -1}" +
            " player=${latestAnalysis?.playerDetected ?: false}" +
            " fromAnchor=${latestAnalysis?.playerFromAnchor ?: false}" +
            " suppBg=$suppressed" +
            " fgApp=${RenderaAccessibilityService.foregroundPackage.value}" +
            " target=${prefs.targetPackage.value}" +
            " a11y=${RenderaAccessibilityService.isAvailable()}" +
            " idle=${RenderaAccessibilityService.isIdle()}" +
            " dodges=$dodgeCount"
        Log.i(TAG, line)
        appendReport(line)
    }

    private fun requestCaptureGrant() {
        val open = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_NEEDS_CAPTURE, true)
        }
        runCatching { startActivity(open) }
            .onFailure { toast("Open Rendera to grant screen capture again") }
    }

    private fun densityDpi(): Int {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        return dm.densityDpi
    }

    /**
     * Resolves the real display size *including* rotation, from `DisplayManager`.
     * `resources.displayMetrics` reports this service's own window, which is
     * wrong whenever the game is in split screen or freeform.
     */
    /**
     * Resolves the real display size, rotation applied.
     *
     * @return true when a usable size was obtained. Callers must not proceed
     *         otherwise: a capture created against an unresolved size either
     *         throws or silently produces frames of the wrong dimensions.
     */
    private fun resolveDisplayGeometry(): Boolean {
        try {
            val dm = getSystemService(DisplayManager::class.java)
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
                ?: dm?.getDisplays()?.firstOrNull()
            if (display != null) {
                val size = realDisplaySize(display)
                if (size.first > 0 && size.second > 0) {
                    displayWidth = size.first
                    displayHeight = size.second
                }
                displayRotation = display.rotation
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DisplayManager lookup failed", t)
        }
        if (displayWidth <= 0 || displayHeight <= 0) {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            displayWidth = dm.widthPixels
            displayHeight = dm.heightPixels
        }
        return displayWidth >= 16 && displayHeight >= 16
    }

    /**
     * Real display size, with rotation already applied.
     *
     * `Display.getRealSize` reports width and height in the panel's own
     * orientation, so a rotated device comes back transposed. `getRealMetrics`
     * plus an explicit swap gives the same numbers as a logical point, without
     * depending on `android.util.Point`.
     */
    private fun realDisplaySize(display: android.view.Display): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val rotated = display.rotation == android.view.Surface.ROTATION_90 ||
            display.rotation == android.view.Surface.ROTATION_270
        // widthPixels/heightPixels, not width/height: the latter are Point's.
        return if (rotated) metrics.heightPixels to metrics.widthPixels
        else metrics.widthPixels to metrics.heightPixels
    }

    /**
     * Capture dimensions keep the display's aspect exactly, so grid coordinates
     * and screen pixels stay linearly related. The previous code computed
     * `height = (screenHeight / screenWidth) * captureWidth` and then
     * round-truncated, which skews the aspect and misplaces every position.
     */
    private fun computeCaptureSize() {
        val (w, h) = if (displayWidth >= displayHeight) {
            CAPTURE_LONG_EDGE_EVEN to
                ((CAPTURE_LONG_EDGE_EVEN.toLong() * displayHeight / displayWidth).toInt() and 1.inv())
                .coerceAtLeast(2)
        } else {
            ((CAPTURE_LONG_EDGE_EVEN.toLong() * displayWidth / displayHeight).toInt() and 1.inv())
                .coerceAtLeast(2) to CAPTURE_LONG_EDGE_EVEN
        }
        captureWidth = w
        captureHeight = h
        captureConfiguredForRotation = displayRotation
    }

    private fun onGeometryChanged() {
        val beforeW = displayWidth
        val beforeH = displayHeight
        runCatching { resolveDisplayGeometry() }
        if (beforeW == displayWidth && beforeH == displayHeight) return

        Log.i(TAG, "Display changed ${beforeW}x$beforeH -> ${displayWidth}x$displayHeight")
        computeCaptureSize()
        frameRing.configure(captureWidth, captureHeight)

        // The ImageReader's size is fixed when it is built, so a geometry change
        // has to hand the VirtualDisplay a NEW surface. Resizing the display
        // alone leaves the reader expecting the old size, and then every frame is
        // rejected and the capture is dead for the rest of the session.
        //
        // setSurface rather than release + createVirtualDisplay: it needs no new
        // user consent, which on Android 14+ cannot be obtained from a
        // background service at all.
        val newReader = createImageReader()
        if (newReader == null) {
            Log.e(TAG, "Could not rebuild the reader for the new size; capture stops here")
            events.error("capture", "reader rebuild failed at ${captureWidth}x$captureHeight")
        } else {
            val display = virtualDisplay
            if (display == null) {
                newReader.close()
            } else {
                try {
                    display.resize(captureWidth, captureHeight, densityDpi())
                    display.setSurface(newReader.surface)
                    val old = imageReader
                    imageReader = newReader
                    old?.close()
                    Log.i(TAG, "Capture surface swapped to ${captureWidth}x$captureHeight")
                } catch (t: Throwable) {
                    Log.e(TAG, "Could not swap the capture surface", t)
                    events.error("capture", "surface swap: " + (t.message ?: "threw"), t)
                    newReader.close()
                }
            }
        }

        // The calibration is only valid for the geometry it was taken on.
        anchors = prefs.anchorsFor(displayWidth, displayHeight)
        detector?.let {
            synchronized(detectorLock) {
                it.setDisplaySize(displayWidth, displayHeight)
                it.setAnchors(anchors)
                it.reset()
            }
            // The threat identity is a position on the old display, so a
            // commitment carried across a rotation would suppress the first real
            // dodge after it.
            dodgeState.reset()
        }
        pushMaskRegions()
        repositionOverlayViews()
        calibrationView?.let { view ->
            view.applyAnchors(anchors)
            view.setStatus(
                if (!anchors.calibrated) {
                    "Screen changed. Re-lock the anchors for this orientation."
                } else {
                    "Anchors re-applied to the new screen size."
                }
            )
        }
    }

    // -----------------------------------------------------------------------
    // Detector
    // -----------------------------------------------------------------------

    /**
     * Guards the native engine.
     *
     * The vision thread is inside `process()` (a JNI call) while the main thread
     * reconfigures the engine from `setAnchors`, `applyTuning`, `setMaskRegions`
     * and `reset`. Volatile gives visibility but no exclusion, so without this
     * lock the engine can be reset or re-tuned mid-frame. All the work under it
     * is a few microseconds of parameter setting, never a long analysis, so
     * contention is not a concern.
     */
    private val detectorLock = Any()

    private fun ensureDetector() {
        if (detector != null) return
        if (displayWidth <= 0 || displayHeight <= 0) runCatching { resolveDisplayGeometry() }
        val (gw, gh) = ScreenThreatDetector.gridForCapture(captureWidth, captureHeight)
        val d = ScreenThreatDetector(gw, gh, displayWidth, displayHeight)
        anchors = prefs.anchorsFor(displayWidth, displayHeight)
        synchronized(detectorLock) {
            d.setAnchors(anchors)
            d.applyTuning(tuningFromPrefs())
            detector = d
        }
        pushMaskRegions()

        if (!d.isNativeAvailable) {
            Log.e(TAG, "Native vision engine is NOT available in this build")
            mainHandler.post {
                toast("Vision engine missing from this build; detection is disabled")
            }
        } else {
            Log.i(TAG, "Vision engine ready on a ${gw}x$gh grid for ${displayWidth}x$displayHeight")
        }
    }

    private fun tuningFromPrefs(): VisionTuning {
        val sensitivity = prefs.sensitivity.value
        // Higher sensitivity means reacting to fainter and slower things, which
        // is a lower noise floor, a lower speed gate and a longer horizon.
        //
        // This is a LOWER bound, and the slider only moves it between 51 and 78
        // on the 0..255 opponent scale. Brawl Stars' selection ring measures
        // about 80 and a fully saturated green about 250, so the whole range
        // keeps the real player detectable while still rejecting grass, which
        // measures 47 on hue alone and 78 on saturation against a gate of 105.
        // The heavy discrimination against terrain is the saturation gate in the
        // engine, not this threshold.
        return VisionTuning(
            diffNoiseFloor = (26f - sensitivity * 12f).roundToInt().coerceIn(10, 26),
            playerMinGreenScore = 48f + sensitivity * 30f,
            enemyMinRedScore = 40f + sensitivity * 26f,
            projectileMinSpeedNorm = 0.30f - sensitivity * 0.14f,
            projectileMinStraightness = 0.70f - sensitivity * 0.22f,
            reactionHorizonSec = 0.32f + sensitivity * 0.18f,
            playerAnchorLocked = anchors.calibrated,
            playerAnchorX = anchors.playerX,
            playerAnchorY = anchors.playerY
        )
    }

    /**
     * Tells the engine which parts of the captured image are Rendera's own UI.
     *
     * MediaProjection captures every window on the display, including ours, so
     * without this the bubble, the mini menu and the HUD panel are all detected
     * as moving objects and the engine dodges at its own overlay.
     */
    private fun pushMaskRegions() {
        val d = detector ?: return
        if (displayWidth <= 0 || displayHeight <= 0) return
        val regions = ArrayList<ScreenRegion>(3)

        val bubble = bubbleView
        val bp = bubbleParams
        if (bubble != null && bp != null) {
            val size = if (bubble.width > 0) bubble.width else dp(64f).roundToInt()
            val half = size * 0.75f
            regions += ScreenRegion(
                centerX = (bp.x + size / 2f) / displayWidth,
                centerY = (bp.y + size / 2f) / displayHeight,
                halfWidth = half / displayWidth,
                halfHeight = half / displayHeight
            )
        }

        val hud = hudView
        if (hud != null) {
            val w = if (hud.width > 0) hud.width else dp(232f).roundToInt()
            val h = if (hud.height > 0) hud.height else dp(140f).roundToInt()
            val pad = dp(10f)
            regions += ScreenRegion(
                centerX = (displayWidth - w / 2f) / displayWidth,
                centerY = (h / 2f) / displayHeight,
                halfWidth = (w / 2f + pad) / displayWidth,
                halfHeight = (h / 2f + pad) / displayHeight
            )
        }

        // The mini menu is a large, bright, animated panel. Unmasked it is the
        // single most detectable object on screen, so the engine would classify
        // it as a projectile and dodge at the user's own menu.
        val menu = menuView
        if (menu != null) {
            val w = if (menu.width > 0) menu.width else dp(260f).roundToInt()
            val h = if (menu.height > 0) menu.height else dp(300f).roundToInt()
            val pad = dp(8f)
            regions += ScreenRegion(
                centerX = (menuX + w / 2f) / displayWidth,
                centerY = (menuY + h / 2f) / displayHeight,
                halfWidth = (w / 2f + pad) / displayWidth,
                halfHeight = (h / 2f + pad) / displayHeight
            )
        }

        // While the calibration overlay is up it covers the whole screen, so the
        // whole screen is masked. Detection is not wanted then anyway; the
        // overlay is used to place anchors by hand.
        if (calibrationView != null) {
            regions += ScreenRegion(0.5f, 0.5f, 0.5f, 0.5f)
        }

        // The joystick base is deliberately NOT masked: the brawler stands on top
        // of it, so masking the stick would blind player detection. The stick is
        // static, so it produces no motion residual anyway once the camera is
        // compensated. Only genuinely moving overlay pixels are masked above.
        synchronized(detectorLock) { d.setMaskRegions(regions) }
    }

    // -----------------------------------------------------------------------
    // Vision loop
    // -----------------------------------------------------------------------

    private fun startVisionLoop() {
        if (visionJob?.isActive == true) return
        visionJob = serviceScope.launch(Dispatchers.Default) {
            var lastAnchors: Anchors? = null
            while (isActive) {
                val frame = frameRing.take()
                if (frame == null) {
                    delay(VISION_IDLE_SLEEP_MS)
                    continue
                }
                try {
                    val d = detector
                    if (d != null && frameRing.width > 0) {
                        // Re-read anchors and tuning when the user changes them,
                        // without a listener per write.
                        val liveAnchors = prefs.anchors.value
                        if (lastAnchors != liveAnchors) {
                            synchronized(detectorLock) { d.setAnchors(liveAnchors) }
                            lastAnchors = liveAnchors
                        }

                        // Analyse unconditionally. The previous version gated
                        // this on the accessibility service's idea of the
                        // foreground app and called `d.reset()` on every frame
                        // when it disagreed. That gate can be wrong forever -
                        // the last TYPE_WINDOW_STATE_CHANGED the service sees
                        // may be our own MainActivity, and no event arrives for
                        // the game on some OEM builds - so detection went
                        // permanently dead with no error and no frame counter,
                        // which is exactly "it does not start and never works".
                        // Auto-detect also depends on this analysis, so it was
                        // dead for the same reason.
                        //
                        // A backgrounded app is now handled where it actually
                        // costs something: dodging, not analysing. See
                        // [shouldSuppressDodge].
                        val analysis = synchronized(detectorLock) { d.process(
                            yPlane = frame.y,
                            yStride = frame.yStride,
                            uPlane = frame.u,
                            vPlane = frame.v,
                            uvStride = frame.uvStride,
                            frameWidth = frame.width,
                            frameHeight = frame.height,
                            chromaWidth = frame.chromaWidth,
                            chromaHeight = frame.chromaHeight,
                            ptsNanos = System.nanoTime(),
                            screenWidth = displayWidth,
                            screenHeight = displayHeight,
                            collectDebug = prefs.debugOverlayEnabled.value
                        ) }
                        if (analysis != null) {
                            framesAnalysed++
                            lastFrameAtMs = SystemClock.elapsedRealtime()
                        }
                        latestAnalysis = analysis
                        if (analysis != null) onAnalysis(analysis, d)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Vision frame failed", t)
                    events.error("vision", t.message ?: "threw", t)
                } finally {
                    frame.recycle()
                    statsFrames++
                }

                if (SystemClock.elapsedRealtime() - statsWindowStartMs >= STATS_INTERVAL_MS) {
                    val elapsed = SystemClock.elapsedRealtime() - statsWindowStartMs
                    latestFps = (statsFrames * 1000L / elapsed).toInt()
                    statsFrames = 0
                    statsWindowStartMs = SystemClock.elapsedRealtime()
                    publishStats()
                    publishStatus()
                    logDiagnostics()
                }
            }
        }
    }

    /**
     * Whether dodging should be suppressed because the game is not in front.
     *
     * Deliberately used to gate the DISPATCH, never the analysis. Detecting is
     * cheap and a wrong "paused" is invisible - no frames, no detections, no
     * clue why - whereas a wrong "go ahead" at worst fires one gesture in a
     * launcher. So this fails open on every ambiguity and only suppresses after
     * a sustained, unambiguous disagreement.
     */
    private fun shouldSuppressDodge(): Boolean {
        if (!autoDodgeArmed) return false
        if (!RenderaAccessibilityService.isAvailable()) return false

        val target = prefs.targetPackage.value
        // No target chosen: the user never said what to watch, so never gate.
        if (target.isEmpty()) return false

        val now = SystemClock.elapsedRealtime()
        val foreground = RenderaAccessibilityService.foregroundPackage.value

        if (RenderaAccessibilityService.isForegroundAppUs(foreground) || foreground.isEmpty()) {
            // We are in front, or the service genuinely does not know. Either way
            // this is not evidence that the game left.
            notInGameSinceMs = 0L
            return false
        }

        if (foreground == target) {
            notInGameSinceMs = 0L
            return false
        }

        if (notInGameSinceMs == 0L) {
            notInGameSinceMs = now
            return false
        }
        // Require a sustained disagreement. Focus flaps constantly: a volume
        // panel, a notification, a permission dialog. One frame of evidence
        // must never disable the thing the user just armed.
        return now - notInGameSinceMs > BACKGROUND_CONFIRM_MS
    }

    private fun onAnalysis(analysis: ScreenThreatDetector.Analysis?, d: ScreenThreatDetector) {
        if (analysis == null) return
        latestAnalysis = analysis

        latestVisionMillis = analysis.processMillis
        val raw = analysis.raw
        if (analysis.escape.hasThreat) {
            latestSeverity = analysis.escape.severity
            threatCount++
            events.threat(
                trackId = raw.threatTrackId,
                x = raw.threatX,
                y = raw.threatY,
                vx = raw.threatVx,
                vy = raw.threatVy,
                ttiMs = analysis.escape.timeToImpactMs,
                severity = analysis.escape.severity.name
            )
            judgeOutcomes(analysis, raw.threatTrackId)
        } else {
            latestSeverity = ThreatLevel.SAFE
        }
        if (autoDodgeArmed && analysis.hasDodgeableThreat && displayWidth > 0 &&
            !shouldSuppressDodge()
        ) {
            maybeDodge(analysis, d)
        }
        publishHud(analysis, d)
    }

    /**
     * Judges the dodge that is still in flight.
     *
     * This is the number nothing in this project has ever had: for each threat we
     * acted on, did the threat stop being on a collision course afterwards, or
     * did it still arrive? Every tuning decision so far - the dodge distance, the
     * hold time, the escape weights - was argued from reasoning, and the
     * reasoning was wrong often enough to be worthless.
     *
     * A verdict is recorded when the same track either stops being a threat
     * (worked, or it missed on its own) or survives long enough that the dodge
     * plainly did not clear it (failed). Judging too eagerly would mark a
     * successful dodge as failed on the very next frame.
     */
    private fun judgeOutcomes(analysis: ScreenThreatDetector.Analysis, trackId: Int) {
        judgeCandidates(analysis, SystemClock.elapsedRealtime())
    }

    private fun judgeCandidates(analysis: ScreenThreatDetector.Analysis, now: Long): Int {
        // The escape planner's own verdict is the cheapest reliable signal: if the
        // heading it chose still leaves a shot on a collision course, the dodge
        // did not clear it.
        val esc = analysis.escape
        if (!esc.hasThreat) return 0
        val trackId = analysis.raw.threatTrackId
        // Only a track we actually planned an escape for can have an outcome.
        // Judging a threat we never acted on would credit or blame a dodge that
        // did not happen.
        if (!events.hasOpenDecision(trackId)) return 0
        // Give the gesture time to take effect before judging, and never judge
        // more than one per interval.
        if (now - lastDodgeAtMs < 260L) return 0
        if (now - lastOutcomeAtMs < 200L) return 0
        if (esc.escapeIsSufficient && !esc.partialEscape) {
            events.recordOutcome(
                trackId, worked = true, reason = "clear after the dodge",
                newTtiMs = esc.timeToImpactMs
            )
            return 1
        }
        events.recordOutcome(
            trackId, worked = false,
            reason = "still on a collision course: cleared ${esc.projectilesCleared}" +
                " of ${esc.projectilesConsidered}, sufficient=${esc.escapeIsSufficient}",
            newTtiMs = esc.timeToImpactMs
        )
        return 1
    }

    private fun maybeDodge(analysis: ScreenThreatDetector.Analysis, d: ScreenThreatDetector) {
        if (!RenderaAccessibilityService.isAvailable()) return
        if (displayWidth <= 0 || displayHeight <= 0) return

        // The only hard gate is whether a gesture can be physically delivered.
        // Everything else is per-threat bookkeeping, so a second projectile
        // arriving mid-dodge is answered as soon as the stick is free instead of
        // being swallowed by a cooldown.
        if (!dodgeState.shouldDispatch(analysis, SystemClock.elapsedRealtime())) return

        val esc = analysis.escape
        events.decide(
            trackId = analysis.raw.threatTrackId,
            headingDeg = esc.escapeHeadingDeg,
            dragPx = esc.joystickDragPx,
            holdMs = esc.holdMs,
            requiredTravelPx = esc.requiredTravelPx,
            expectedTravelPx = esc.expectedTravelPx,
            sufficient = esc.escapeIsSufficient,
            cleared = esc.projectilesCleared,
            considered = esc.projectilesConsidered,
            playerX = analysis.playerX,
            playerY = analysis.playerY,
            screenW = displayWidth,
            screenH = displayHeight
        )

        val plan = d.planDodge(analysis, displayWidth, displayHeight)
        if (plan.isEmpty) {
            // Refused: no usable plan. Forget the commitment so it is retried
            // once anchors are fixed rather than being treated as handled.
            dodgeState.onDispatchFailed()
            events.dispatched(analysis.raw.threatTrackId, accepted = false)
            Log.d(TAG, "Dodge suppressed: no usable plan (anchors calibrated=${anchors.calibrated})")
            return
        }

        val trackId = analysis.raw.threatTrackId
        var settled = false
        val accepted = RenderaAccessibilityService.dispatch(plan) { success ->
            if (settled) return@dispatch
            settled = true
            events.dispatched(trackId, success)
            if (success) {
                lastDodgeAtMs = SystemClock.elapsedRealtime()
                lastDodgeAngleDeg = esc.escapeHeadingDeg
                dodgeCount++
            }
        }
        if (!accepted) {
            // The system refused it, so nothing was sent. Forget the commitment
            // so the next frame tries again instead of assuming we handled it.
            dodgeState.onDispatchFailed()
            Log.d(TAG, "Dodge not dispatched (gesture busy or service down)")
        }
    }

    // -----------------------------------------------------------------------
    // Stats / HUD
    // -----------------------------------------------------------------------

    /**
     * One consolidated health snapshot per second.
     *
     * Everything here is measured, not hard coded. The previous version reported
     * `latencyMs = 0`, `threatsDetected = 0` and `dodgesExecuted = 0` forever,
     * which is worse than reporting nothing: a dashboard of zeros reads as
     * "working, nothing happening" rather than "this number was never wired up".
     */
    private fun publishStats() {
        val state = DetectionStats(
            isRunning = true,
            fps = latestFps,
            frameCount = frameRing.consumedCount,
            droppedFrames = frameRing.droppedCount,
            threatsDetected = threatCount,
            dodgesExecuted = dodgeCount,
            lastDodgeAngleDeg = lastDodgeAngleDeg,
            lastDodgeTimestamp = lastDodgeAtMs,
            currentThreatLevel = latestSeverity,
            visionMillis = latestVisionMillis,
            nativeVisionAvailable = detector?.isNativeAvailable == true,
            isJoystickCalibrated = anchors.calibrated,
            isPlayerCalibrated = anchors.calibrated,
            anchorsCalibrated = anchors.calibrated,
            anchorsCalibratedFor = "${anchors.calibratedForWidth}x${anchors.calibratedForHeight}",
            autoDodgeArmed = autoDodgeArmed,
            accessibilityReady = RenderaAccessibilityService.isAvailable(),
            gameInForeground = !shouldSuppressDodge(),
            activeGamePackage = prefs.targetPackage.value,
            latestTacticalAdvice = buildAdvice()
        )
        Log.i(TAG, "stats: $state")
    }

    /**
     * One line naming the FIRST thing that is wrong, in the order the stages
     * actually run.
     *
     * The previous version led with "Native vision engine missing from this
     * build" whenever `detector` was null, which is also true when the capture
     * simply never started. That single mislabelling is what sent the user - and
     * several sessions - looking for a missing library that was present and
     * loaded. Capture comes first because everything else depends on it, and the
     * three "no engine" cases are now distinguished from each other.
     */
    /** How long ago the last frame was analysed, phrased for a human. */
    private fun lastFrameAge(): String {
        val t = lastFrameAtMs
        if (t == 0L) return "capture has produced nothing"
        val age = (SystemClock.elapsedRealtime() - t) / 1000L
        return if (age < 2) "frames are arriving" else "no frame for ${age}s"
    }

    private fun buildAdvice(): String {
        // Truncated: the panel shows three lines and a full sentence here
        // overflows it, which is what "the text comes out" was.
        startFailure?.let { return it.take(70) }
        if (!_status.value.capturing) {
            return when {
                framesReceived > 0L -> "Capture stopped. Tap to retry."
                consentTokenSpent -> "Grant used up. Tap the bubble."
                else -> "No capture. Tap the bubble."
            }
        }
        val d = detector
            ?: return "Engine not ready."
        if (!d.isNativeAvailable) {
            return "Native engine did not load."
        }
        if (!RenderaAccessibilityService.isAvailable()) {
            return "Enable the accessibility service."
        }
        if (!anchors.calibrated) {
            return "Set the anchors first."
        }
        if (shouldSuppressDodge()) {
            return "${prefs.targetPackage.value} not in front."
        }
        if (!autoDodgeArmed) return "Paused. Tap the bubble to arm."
        // Armed but seeing nothing is a distinct state from armed and working,
        // and reporting both as "Armed" is why arming appeared to do nothing.
        if (framesAnalysed == 0L) {
            return "Armed, but no frames analysed. ${lastFrameAge()}."
        }
        if (latestAnalysis?.playerDetected != true) {
            return "Armed, no player lock. Using the anchor."
        }
        return "Armed. ${latestFps} fps, ${framesAnalysed} frames."
    }

    /**
     * Builds the HUD snapshot on the vision thread and applies it on the main
     * thread.
     *
     * `invalidate()`, `width` and `height` are all main-thread-only, and this is
     * called straight from `Dispatchers.Default`. Doing it inline was a
     * guaranteed `CalledFromWrongThreadException` the moment the HUD was on.
     */
    private fun publishHud(analysis: ScreenThreatDetector.Analysis, d: ScreenThreatDetector) {
        // Deliberately not gated on the panel existing: the reticle layer is a
        // separate full screen window, and returning early when the small panel
        // failed to add meant the reticles never got any data either.
        if (!prefs.debugOverlayEnabled.value) return
        val hud = hudView
        // The reticle layer is a separate full screen window with its own data
        // path, so it must be fed whether or not the small panel exists.

        val currentAnchors = anchors
        val playerRadius = synchronized(detectorLock) { d.currentTuning() }.playerRadiusNorm * displayWidth
        val joy = currentAnchors.joystickPx(displayWidth, displayHeight)
        val dragPx = currentAnchors.joystickRadiusPx(displayWidth)
        val tracks = d.debugTrackSnapshot()
        val enemies = d.debugEnemySnapshot()
        val esc = analysis.escape
        val hasThreat = analysis.threat != null

        // The entity list only feeds the small text panel's summary. The reticle
        // layer builds its own marks, so the panel being absent must not stop it.
        val entities = if (hud == null) emptyList() else hud.entitiesFor(
            playerX = analysis.playerX,
            playerY = analysis.playerY,
            playerRadius = playerRadius,
            playerLocked = analysis.playerDetected,
            joyX = joy.x,
            joyY = joy.y,
            joyRadius = dragPx,
            tracks = tracks,
            enemies = enemies,
            threatX = analysis.raw.threatX,
            threatY = analysis.raw.threatY,
            hasThreat = hasThreat,
            escapeX = joy.x + esc.escapeDirX * dragPx,
            escapeY = joy.y + esc.escapeDirY * dragPx,
            hasEscape = hasThreat
        )

        val now = SystemClock.elapsedRealtime()
        val snapshot = TacticalHudView.Snapshot(
            entities = entities,
            fps = latestFps,
            visionMillis = analysis.processMillis,
            droppedFrames = frameRing.droppedCount,
            playerLocked = analysis.playerDetected,
            playerFromAnchor = analysis.playerFromAnchor,
            projectiles = analysis.projectileCount,
            balls = analysis.raw.ballCount,
            bouncers = analysis.raw.bouncerCount,
            enemies = analysis.enemyCount,
            // Use the Kotlin solver's severity, not the native one. The Kotlin
            // solve produced the plan that is actually dispatched, so it is the
            // authoritative answer; reading the native value here could colour
            // the HUD SAFE while the tti next to it reads 90 ms.
            threatSeverity = esc.severity.name,
            timeToImpactMs = esc.timeToImpactMs,
            escapeHeadingDeg = esc.escapeHeadingDeg,
            escapeSufficient = esc.escapeIsSufficient,
            anchorsCalibrated = currentAnchors.calibrated,
            accessibilityReady = RenderaAccessibilityService.isAvailable(),
            gameForeground = !shouldSuppressDodge(),
            autoDodgeArmed = autoDodgeArmed,
            dodgePlan = dodgeState.describe(),
            note = buildAdvice()
        )

        // Throttled, and skipped when nothing visible changed. Posting an
        // invalidate per analysed frame is up to sixty a second, each one
        // re-laying out and re-drawing the panel - on its own enough to make
        // the overlay feel like it is fighting the game for frames.
        val key = snapshot.lines()
        if (key != lastHudKey || now - lastHudAtMs >= HUD_MIN_INTERVAL_MS) {
            lastHudKey = key
            lastHudAtMs = now
            if (hud != null) mainHandler.post { hudView?.update(snapshot) }
        publishReticles(analysis)
        }
    }

    // -----------------------------------------------------------------------
    // Floating bubble
    // -----------------------------------------------------------------------

    private fun showFloatingBubble() {
        if (bubbleView != null) return
        val sizePx = dp(64f).roundToInt()
        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12f).roundToInt()
            y = dp(80f).roundToInt()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBackground(COLOR_IDLE)
            elevation = dp(8f)
        }
        val icon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val label = TextView(this).apply {
            text = getString(R.string.bubble_label_paused)
            setTextColor(0xFF0B0710.toInt())
            textSize = 8f
        }
        root.addView(icon, LinearLayout.LayoutParams(sizePx - dp(14f).roundToInt(), sizePx - dp(26f).roundToInt()))
        root.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Touch handling is done here rather than with a click listener so the
        // three gestures stay distinguishable:
        //   tap        -> toggle armed
        //   long press -> open the menu
        //   drag       -> move the bubble
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        val tapSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        var downRawX = 0f
        var downRawY = 0f
        var downX = 0f
        var downY = 0f
        var dragging = false
        var longFired = false

        val longPressRunnable = Runnable {
            if (!dragging) {
                longFired = true
                triggerHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                // addView while a touch is still being dispatched to the bubble
                // is re-entrant and can throw on some OEM builds, so defer it.
                mainHandler.post { openMenu(params.x, params.y) }
            }
        }

        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x.toFloat()
                    downY = params.y.toFloat()
                    dragging = false
                    longFired = false
                    mainHandler.postDelayed(longPressRunnable, longPressMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    // A real touch slop, not the old 12 px constant: ordinary
                    // finger jitter on a 64 dp bubble used to exceed 12 px and
                    // cancel the tap.
                    if (!dragging && (abs(dx) > tapSlop || abs(dy) > tapSlop)) {
                        dragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (dragging) {
                        params.x = (downX + dx).roundToInt()
                        params.y = (downY + dy).roundToInt()
                        try {
                            windowManager.updateViewLayout(root, params)
                        } catch (t: Throwable) {
                            Log.w(TAG, "bubble drag update failed", t)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!dragging && !longFired) {
                        // A tap on a bubble with no capture is a request to fix
                        // it, not a request to arm something that cannot arm. The
                        // old behaviour silently toggled and nothing happened,
                        // which read as "tapping does nothing".
                        if (!_status.value.capturing) {
                            requestCaptureGrant()
                        } else {
                            toggleAutoDodge()
                        }
                    }
                    // Always consume UP so the gesture stream is not stolen.
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> {
                    // Multi-touch and anything unmodelled: drop the pending long
                    // press rather than letting it fire on a stale gesture.
                    mainHandler.removeCallbacks(longPressRunnable)
                    true
                }
            }
        }

        bubbleLongPress = longPressRunnable

        try {
            windowManager.addView(root, params)
            bubbleView = root
            bubbleParams = params
        } catch (t: Throwable) {
            Log.e(TAG, "Could not add the floating bubble", t)
            mainHandler.post { toast("Overlay permission is required") }
            stopSelf()
        }
    }

    private fun refreshBubbleUi() {
        val view = bubbleView ?: return
        val params = bubbleParams ?: return
        val color = if (autoDodgeArmed) COLOR_ARMED else COLOR_IDLE
        view.background = roundedBackground(color)
        val label = (view as? LinearLayout)?.getChildAt(1) as? TextView
        label?.text = getString(
            if (autoDodgeArmed) R.string.bubble_label_armed else R.string.bubble_label_paused
        )
        label?.setTextColor(if (autoDodgeArmed) 0xFF04140A.toInt() else 0xFF0B0710.toInt())
        view.invalidate()
        Log.d(TAG, "bubble state armed=$autoDodgeArmed params=$params")
    }

    private fun removeBubble() {
        // Cancel only OUR runnable. Wiping the whole main handler queue would
        // also drop the pending onProjectionStopped and onGeometryChanged
        // handlers, leaving a service whose projection was revoked stuck in the
        // foreground forever.
        bubbleLongPress?.let { mainHandler.removeCallbacks(it) }
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        bubbleParams = null
    }

    private fun repositionOverlayViews() {
        if (displayWidth <= 0 || displayHeight <= 0) return
        bubbleParams?.let { p ->
            p.x = p.x.coerceIn(0, (displayWidth - p.width).coerceAtLeast(0))
            p.y = p.y.coerceIn(0, (displayHeight - p.height).coerceAtLeast(0))
            bubbleView?.let { v -> runCatching { windowManager.updateViewLayout(v, p) } }
        }
    }

    // -----------------------------------------------------------------------
    // Mini menu
    // -----------------------------------------------------------------------

    private fun toggleAutoDodge() {
        autoDodgeArmed = !autoDodgeArmed
        prefs.setAutoDodge(autoDodgeArmed)
        dodgeState.reset()
        lastDodgeAtMs = 0L
        triggerHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        mainHandler.post {
            publishStatus()
            refreshBubbleUi()
            toast(if (autoDodgeArmed) "Auto-dodge ARMED" else "Auto-dodge PAUSED")
        }
        Log.i(TAG, "auto-dodge armed=$autoDodgeArmed")
    }

    private fun openMenu(anchorX: Int, anchorY: Int) {
        if (menuView != null) {
            closeMenu()
            return
        }
        // A fixed 260dp was too narrow on some densities and wasted space on
        // others, and a label that does not fit is drawn outside the panel.
        // Take the width from the screen, with a floor so the buttons stay
        // tappable.
        val w = ((displayWidth * 0.62f).toInt().coerceIn(dp(240f).roundToInt(), dp(420f).roundToInt()))
        val rowH = dp(46f).roundToInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            elevation = dp(12f)
            setPadding(0, 0, 0, dp(6f).roundToInt())
        }
        // A heading, so the panel is obviously a menu and not a stray rectangle.
        root.addView(TextView(this).apply {
            text = "RENDERA"
            setTextColor(0xFFEDE7FF.toInt())
            textSize = 11f
            letterSpacing = 0.2f
            setPadding(dp(14f).roundToInt(), dp(10f).roundToInt(), dp(14f).roundToInt(), dp(4f).roundToInt())
        })

        /**
         * One menu row.
         *
         * The label auto-sizes inside the view's own `apply`, so it is addressed
         * through the receiver rather than through the local it is defining -
         * `tv` is not in scope inside the very expression that creates it. A label
         * that does not fit is drawn outside the panel, which is what "the text
         * comes out" was.
         */
        fun addButton(label: String, onClick: () -> Unit) {
            val view = TextView(this).apply {
                text = label
                setTextColor(0xFFEDE7FF.toInt())
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14f).roundToInt(), 0, dp(10f).roundToInt(), 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setAutoSizeTextTypeUniformWithConfiguration(
                        10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP
                    )
                } else {
                    textSize = 12f
                }
                setOnClickListener { clicked ->
                    // Tear down after this dispatch completes, otherwise removing
                    // a view from inside its own click listener drops the rest of
                    // the gesture and can throw on OEM builds.
                    clicked.post { runClick(onClick) }
                }
            }
            // A hairline between rows so the buttons read as separate targets.
            view.setBackgroundColor(0x1AFFFFFF)
            root.addView(
                view,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowH)
            )
        }

        // One button, three jobs: arm, pause, or send the user to the Activity
        // when the capture has ended. A dead-end "capture ended" bubble with a
        // normal-looking arm button is how the app ended up looking functional
        // and doing nothing.
        addButton(
            getString(
                when {
                    captureEnded -> R.string.menu_recapture
                    autoDodgeArmed -> R.string.menu_pause
                    else -> R.string.menu_arm
                }
            )
        ) {
            if (captureEnded) requestCaptureGrant() else toggleAutoDodge()
        }
        addButton(getString(R.string.menu_calibrate)) {
            closeMenu()
            showCalibrationOverlay()
        }
        addButton(getString(R.string.menu_auto_detect)) {
            closeMenu()
            // Runs against the real game, with no overlay on top of it. The
            // overlay used to be the only entry point, which meant the engine
            // was looking at the overlay's own background.
            runAutoDetect()
        }
        addButton(getString(R.string.menu_hud)) {
            prefs.setDebugOverlayEnabled(!prefs.debugOverlayEnabled.value)
            toggleHud()
            closeMenu()
            toast(if (prefs.debugOverlayEnabled.value) "HUD ON" else "HUD OFF")
        }
        addButton(getString(R.string.menu_share_diagnostics)) {
            closeMenu()
            shareDiagnostics()
        }
        addButton(getString(R.string.menu_reset_calibration)) {
            prefs.clearCalibration()
            anchors = prefs.anchorsFor(displayWidth, displayHeight)
            synchronized(detectorLock) { detector?.setAnchors(anchors) }
            closeMenu()
            toast("Calibration cleared")
        }
        addButton(getString(R.string.menu_quit)) {
            closeMenu()
            stopEverything()
            stopSelf()
        }

        val status = TextView(this).apply {
            text = pendingMenuStatus ?: buildAdvice()
            pendingMenuStatus = null
            setTextColor(0xFF9C93B8.toInt())
            textSize = 10f
            // Bounded: an unbounded TextView here grows the panel and pushes the
            // buttons off screen, which is what "the text comes out" looks like.
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(14f).roundToInt(), dp(6f).roundToInt(), dp(14f).roundToInt(), dp(6f).roundToInt())
        }
        root.addView(
            status,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        val params = WindowManager.LayoutParams(
            w, LinearLayout.LayoutParams.WRAP_CONTENT, overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = anchorX.coerceIn(0, (displayWidth - w).coerceAtLeast(0))
            // dp() returns Float and params.y is Int, so the whole expression
            // has to be Float until the final conversion.
            y = (anchorY + dp(72f))
                .coerceIn(0f, (displayHeight - dp(400f)).coerceAtLeast(0f))
                .roundToInt()
        }

        menuX = params.x
        menuY = params.y
        try {
            windowManager.addView(root, params)
            menuView = root
            pushMaskRegions()
        } catch (t: Throwable) {
            Log.e(TAG, "Could not show the menu", t)
        }
    }

    /**
     * Hands the diagnostics to the user.
     *
     * The app cannot write to the repository itself. Doing that would need a
     * personal access token inside the APK, which is public the moment the APK is
     * uploaded, and it is not a trade worth making for a diagnostic. So the log
     * is written to a file the user chooses to share, and the repository side is a
     * workflow that reads it.
     *
     * The share target is `ShareCompat` free and plain `Intent.createChooser`, so
     * it works on every version without an extra dependency.
     */
    private fun shareDiagnostics() {
        try {
            val summary = runCatching { events.summaryText() }.getOrDefault("")
            val dir = getExternalFilesDir(null) ?: filesDir
            val out = java.io.File(dir, "rendera-diagnostics.txt")
            out.writeText(
                buildString {
                    appendLine(summary)
                    appendLine()
                    val log = events.file
                    if (log.exists()) append(log.readText())
                }
            )
            // Text only, deliberately. `ACTION_SEND` with `EXTRA_STREAM` built
            // from `Uri.fromFile` throws FileUriExposedException on Android 7 and
            // later, and fixing that properly means shipping a FileProvider for a
            // diagnostic. The summary is what a report needs, and the full log's
            // path is included in the text for anyone who wants the lot.
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Rendera diagnostics")
                putExtra(
                    Intent.EXTRA_TEXT,
                    summary + "\nFull log: " + out.absolutePath +
                        "\n(" + out.length() + " bytes)"
                )
            }
            startActivity(Intent.createChooser(share, "Send Rendera diagnostics"))
            Log.i(TAG, "Diagnostics written to ${out.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "Could not share diagnostics", t)
            runCatching { events.error("export", t.message ?: "threw", t) }
            mainHandler.post { toast("Could not export the diagnostics") }
        }
    }

    private fun runClick(action: () -> Unit) {
        try {
            action()
        } catch (t: Throwable) {
            Log.e(TAG, "Menu action failed", t)
        }
    }

    private fun closeMenu() {
        menuView?.let { runCatching { windowManager.removeView(it) } }
        menuView = null
        pushMaskRegions()
    }

    private fun removeMenu() = closeMenu()

    // -----------------------------------------------------------------------
    // HUD
    // -----------------------------------------------------------------------

    private fun toggleHud() {
        if (prefs.debugOverlayEnabled.value) {
            showHud()
        } else {
            removeHud()
            // `showHud` returns early when the panel already exists, so switching
            // it off has to clear both windows explicitly.
            removeReticles()
        }
    }

    private fun showHud() {
        if (hudView != null) return
        val view = TacticalHudView(this)
        val w = dp(232f).roundToInt()
        val params = WindowManager.LayoutParams(
            w, dp(140f).roundToInt(), overlayWindowType(),
            // FLAG_NOT_TOUCHABLE is essential: the panel must never steal a
            // touch from the game.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8f).roundToInt()
            y = dp(8f).roundToInt()
        }
        try {
            windowManager.addView(view, params)
            hudView = view
        } catch (t: Throwable) {
            Log.e(TAG, "Could not show the HUD panel", t)
            events.error("overlay", "hud panel: " + (t.message ?: "threw"), t)
        }
        showReticles()
        pushMaskRegions()
    }

    /**
     * The full screen reticle layer.
     *
     * Separate from the text panel because the panel is 232x140 in one corner
     * and the whole point is to see things WHERE THEY ARE. Without this the
     * overlay showed numbers and no picture, which reads as "it sees nothing".
     *
     * FLAG_NOT_TOUCHABLE is not optional: this window covers the game, and
     * without it every touch goes to the overlay instead of Brawl Stars.
     */
    private fun showReticles() {
        if (reticleView != null) return
        val view = RenderaReticleOverlay(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            windowManager.addView(view, params)
            reticleView = view
            Log.i(TAG, "Reticle overlay added")
        } catch (t: Throwable) {
            Log.e(TAG, "Could not show the reticle overlay", t)
        events.error("overlay", "reticle overlay: " + (t.message ?: "threw"), t)
        }
    }

    /**
     * Feeds the reticle layer. Throttled on the same clock as the panel, because
     * this window covers the game and redrawing it every frame is a frame budget
     * the game needs more.
     */
    private fun publishReticles(analysis: ScreenThreatDetector.Analysis) {
        val view = reticleView ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReticleAtMs < HUD_MIN_INTERVAL_MS) return
        lastReticleAtMs = now

        val d = detector ?: return
        val currentAnchors = anchors
        val joy = currentAnchors.joystickPx(displayWidth, displayHeight)
        val joyR = currentAnchors.joystickRadiusPx(displayWidth)
        val esc = analysis.escape
        val playerR = d.currentTuning().playerRadiusNorm * displayWidth
        val marks = RenderaReticleOverlay.marksFromTracks(
            tracks = d.debugTrackSnapshot(),
            playerX = analysis.playerX,
            playerY = analysis.playerY,
            playerRadius = playerR,
            playerDetected = analysis.playerDetected,
            playerFromAnchor = analysis.playerFromAnchor,
            joystickX = joy.x,
            joystickY = joy.y,
            joystickRadius = joyR,
            enemies = d.debugEnemySnapshot(),
            threatX = analysis.raw.threatX,
            threatY = analysis.raw.threatY,
            hasThreat = analysis.threat != null,
            escapeX = joy.x + esc.escapeDirX * joyR,
            escapeY = joy.y + esc.escapeDirY * joyR,
            hasEscape = analysis.threat != null,
            density = resources.displayMetrics.density
        )
        val frame = RenderaReticleOverlay.Frame(
            marks = marks,
            playerDetected = analysis.playerDetected,
            playerFromAnchor = analysis.playerFromAnchor,
            captureOk = _status.value.capturing,
            note = buildAdvice()
        )
        mainHandler.post { reticleView?.update(frame) }
    }

    private fun removeReticles() {
        reticleView?.let { runCatching { windowManager.removeView(it) } }
        reticleView = null
    }

    private fun removeHud() {
        hudView?.let { runCatching { windowManager.removeView(it) } }
        hudView = null
        removeReticles()
    }

    // -----------------------------------------------------------------------
    // Calibration overlay
    // -----------------------------------------------------------------------

    private fun showCalibrationOverlay() {
        if (calibrationView != null) return
        runCatching { resolveDisplayGeometry() }
        // Start from whatever the user last committed; if nothing is committed,
        // start from the game's actual HUD layout so the crosshair lands on the
        // stick to begin with instead of in a corner.
        val committed = prefs.currentAnchors()
        anchors = if (committed.calibrated && committed.matchesDisplay(displayWidth, displayHeight)) {
            committed
        } else {
            AnchorCalibrator.suggestJoystick(displayWidth, displayHeight)
        }

        // The callbacks call methods on the view they are handed to during
        // construction, so the view cannot be a `val` in its own initialiser.
        // They are built first and reach the view through a lateinit.
        lateinit var overlay: CalibrationOverlayView
        val calibrationCallbacks = object : CalibrationOverlayView.Callbacks {
                override fun onAnchorMoved(target: AnchorTarget, screenX: Float, screenY: Float) {
                    // The view has already clamped and normalised into the basis it
                    // was drawn in. Running that through AnchorCalibrator again,
                    // with the service's own display size, re-clamps against a
                    // possibly different rectangle and the reticle snaps away from
                    // where the user just put it. Take the view's state verbatim.
                    anchors = overlay.currentAnchors()
                    val stale = anchors.calibratedForWidth != displayWidth
                    overlay.setStatus(
                        "${target.name} at ${"%.2f".format(anchors.playerX)}, " +
                            "${"%.2f".format(anchors.playerY)}" +
                            if (stale) " - display size differs, re-check before locking" else ""
                    )
                }

                override fun onAutoDetectRequested() {
                    // The scrim here is nearly transparent, so the engine is
                    // genuinely still looking at the game underneath.
                    runAutoDetect()
                }

                override fun onCommitted(committed: Anchors) {
                    anchors = committed
                    prefs.setAnchors(committed)
                    events.calibration(
                        source = "manual", ok = true,
                        playerX = committed.playerX, playerY = committed.playerY,
                        joyX = committed.joystickX, joyY = committed.joystickY,
                        screenW = displayWidth, screenH = displayHeight
                    )
                    synchronized(detectorLock) {
                        detector?.setAnchors(committed)
                        detector?.applyTuning(tuningFromPrefs())
                    }
                    // New anchors mean a new escape geometry.
                    dodgeState.reset()
                    pushMaskRegions()
                    removeCalibrationOverlay()
                    publishStatus(armed = true, anchorsOk = true)
                    triggerHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    toast("Anchors locked for ${displayWidth}x$displayHeight")
                }

                override fun onCancelled() {
                    removeCalibrationOverlay()
                }

                override fun onTargetChanged(target: AnchorTarget) {
                    overlay.setStatus("Editing $target")
                }
        }

        val view = CalibrationOverlayView(
            context = this,
            displayWidthPx = displayWidth,
            displayHeightPx = displayHeight,
            anchors = anchors,
            callbacks = calibrationCallbacks
        )
        overlay = view

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager.addView(view, params)
            calibrationView = view
            pushMaskRegions()
            anchorCurrentOverlay(anchors)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not show the calibration overlay", t)
        }
    }

    /**
     * Seeds the player anchor from the live analysis.
     *
     * Reachable from the mini menu as well as the overlay, because the overlay
     * used to hide the game from the very engine it was asking.
     *
     * Accepts a held lock as well as a fresh detection: the green signature is
     * demanding, and it drops out for a frame whenever the brawler passes under
     * a bush, a fountain or an ability effect. The engine holds the lock across
     * exactly that, so refusing to use it made auto-detect fail for reasons that
     * have nothing to do with the player not being there.
     */
    private fun runAutoDetect() {
        val d = detector
        if (d == null) {
            mainHandler.post { toast("Vision engine is not running yet") }
            return
        }
        if (!d.isNativeAvailable) {
            mainHandler.post { toast("Vision engine unavailable; auto-detect skipped") }
            return
        }
        if (displayWidth <= 0 || displayHeight <= 0) {
            mainHandler.post { toast("Screen size unknown; rotate the device once") }
            return
        }

        val live = latestAnalysis
        if (live == null) {
            // Say WHY, with the frame counts, instead of a bare "no frame yet".
            // This button previously looked broken because every failure looked
            // identical from the menu.
            val why = "no frame analysed yet (got ${framesReceived}, " +
                "analysed ${framesAnalysed})"
            mainHandler.post {
                toast(why)
                openMenuWithStatus(why)
            }
            return
        }
        if (!live.playerDetected && !live.playerFromAnchor) {
            mainHandler.post {
                val raw = live.raw
                toast(
                    "No player lock. green=${raw.playerGreenness.toInt()} " +
                        "locked=${raw.playerLocked} seen=${raw.projectileCount} blobs=" +
                        "${live.blobCount} - put the brawler on open ground"
                )
            }
            return
        }
        if (!live.playerDetected && !anchors.calibrated) {
            val why = "player not found on open ground (blobs ${live.blobCount})"
            mainHandler.post {
                toast(why)
                openMenuWithStatus(why)
            }
            return
        }

        mainHandler.post {
            val updated = anchors.copy(
                playerX = (live.playerX / displayWidth).coerceIn(0.05f, 0.95f),
                playerY = (live.playerY / displayHeight).coerceIn(0.05f, 0.95f),
                calibrated = true,
                calibratedForWidth = displayWidth,
                calibratedForHeight = displayHeight
            )
            anchors = updated
            prefs.setAnchors(updated)
            synchronized(detectorLock) { d.setAnchors(updated) }
            dodgeState.reset()
            events.calibration(
                source = "auto", ok = true,
                playerX = updated.playerX, playerY = updated.playerY,
                joyX = updated.joystickX, joyY = updated.joystickY,
                screenW = displayWidth, screenH = displayHeight,
                reason = "player lock ${live.playerDetected} fromAnchor ${live.playerFromAnchor}"
            )
            calibrationView?.applyAnchors(updated)
            calibrationView?.setStatus(
                "Player anchor detected at ${"%.2f".format(updated.playerX)}, " +
                    "${"%.2f".format(updated.playerY)}. Drag to adjust, then LOCK."
            )
            triggerHapticFeedback(HapticFeedbackConstants.CONFIRM)
            toast("Player anchor detected")
        }
    }

    /** Reopens the menu carrying a specific reason, so a failure is readable. */
    private fun openMenuWithStatus(reason: String) {
        pendingMenuStatus = reason
        openMenu(0, 0)
    }

    private var pendingMenuStatus: String? = null

    private fun anchorCurrentOverlay(value: Anchors) {
        calibrationView?.applyAnchors(value)
    }

    private fun removeCalibrationOverlay() {
        calibrationView?.let { runCatching { windowManager.removeView(it) } }
        calibrationView = null
        pushMaskRegions()
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /** The menu panel: rounded, opaque and outlined, so it reads as a control. */
    private fun panelBackground() =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(16f)
            setColor(COLOR_MENU)
            setStroke(dp(1f).roundToInt(), 0x66FFFFFF)
        }

    private fun roundedBackground(color: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(2f).roundToInt(), 0xFFFFFFFF.toInt())
        }

    private fun triggerHapticFeedback(constants: Int) {
        try {
            bubbleView?.performHapticFeedback(constants)
        } catch (t: Throwable) {
            Log.w(TAG, "haptic feedback failed", t)
        }
    }

    private fun toast(message: String) {
        try {
            Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Log.w(TAG, "toast failed", t)
        }
    }

}
