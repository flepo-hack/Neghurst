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
import com.example.MainActivity
import com.example.R
import com.example.RenderaApp
import com.example.data.RenderaPreferences
import com.example.model.DetectionStats
import com.example.model.ThreatLevel
import com.example.ui.overlay.CalibrationOverlayView
import com.example.ui.overlay.TacticalHudView
import com.example.vision.AnchorCalibrator
import com.example.vision.AnchorTarget
import com.example.vision.Anchors
import com.example.vision.DodgeDecisionState
import com.example.vision.ScreenThreatDetector
import com.example.vision.nativebridge.ScreenRegion
import com.example.vision.nativebridge.VisionTuning
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
class RenderaOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.example.RenderaOverlayService.START"
        const val ACTION_STOP = "com.example.RenderaOverlayService.STOP"
        const val ACTION_TOGGLE = "com.example.RenderaOverlayService.TOGGLE"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA_INTENT = "dataIntent"
        const val EXTRA_GAME_NAME = "gameName"
        const val EXTRA_PACKAGE_NAME = "packageName"

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

        // ARGB colours whose top bit is set do not fit in a Kotlin Int literal:
        // 0xCC2A0845 is 3425306693, so `const val x = 0xCC2A0845` is inferred as
        // Long and every use as a colour fails to compile. These are `val` with an
        // explicit narrowing, which keeps the readable hex and the Int type.
        private val COLOR_IDLE: Int = 0xCC2A0845.toInt()
        private val COLOR_ARMED: Int = 0xE60F3822.toInt()
        private val COLOR_MENU: Int = 0xF01A0F2E.toInt()

        @Volatile
        var isRunning = false
            private set
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

    @Volatile private var autoDodgeArmed = false
    @Volatile private var anchors: Anchors = Anchors.defaultFor(0, 0)

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = RenderaPreferences.get(this)
        isRunning = true
        statsWindowStartMs = SystemClock.elapsedRealtime()
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
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
                @Suppress("DEPRECATION")
                val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA_INTENT)
                val gameName = intent?.getStringExtra(EXTRA_GAME_NAME) ?: "Universal"
                val pkg = intent?.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
                if (pkg.isNotEmpty() || gameName != "Universal") {
                    prefs.setTarget(pkg, gameName)
                }
                // startForeground MUST precede getMediaProjection() on API 29+.
                startInForeground()
                if (setupCapture(resultCode, data)) {
                    ensureDetector()
                    if (bubbleView == null) showFloatingBubble()
                    startVisionLoop()
                    mainHandler.post { prefs.setAutoDodge(true); autoDodgeArmed = true; refreshBubbleUi() }
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
        isRunning = false
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
    }

    // -----------------------------------------------------------------------
    // Foreground notification
    // -----------------------------------------------------------------------

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
    private fun setupCapture(resultCode: Int, data: Intent?): Boolean {
        if (resultCode == 0 || data == null) {
            Log.e(TAG, "No MediaProjection consent; vision cannot start")
            mainHandler.post { toast("Screen capture permission is required") }
            return false
        }
        if (mediaProjection != null) return true

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.e(TAG, "getMediaProjection failed", t)
            mainHandler.post { toast("Screen capture could not be started") }
            return false
        }
        if (projection == null) {
            Log.e(TAG, "getMediaProjection returned null")
            return false
        }
        mediaProjection = projection

        // The token now exists, so the typed foreground call is safe.
        runCatching { upgradeForegroundToMediaProjection() }
            .onFailure { Log.w(TAG, "typed foreground upgrade failed", it) }

        resolveDisplayGeometry()
        computeCaptureSize()

        // 1. Callback FIRST. Without it, createVirtualDisplay throws on API 34+.
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped by the system or the user")
                mainHandler.post { onProjectionStopped() }
            }
        }
        mediaProjectionCallback = callback
        try {
            projection.registerCallback(callback, mainHandler)
        } catch (t: Throwable) {
            Log.e(TAG, "registerCallback failed", t)
        }

        // 2. Reader. YUV_420_888 so we can read the luma plane directly.
        frameRing.configure(captureWidth, captureHeight)
        val reader = ImageReader.newInstance(
            captureWidth, captureHeight, android.graphics.ImageFormat.YUV_420_888, 2
        )
        // A dedicated thread for the copy. Registering on the main handler ran
        // three per-frame memcpys on the UI thread every frame, which is enough
        // to make the game stutter and the capture drop frames.
        val thread = HandlerThread("RenderaCapture", android.os.Process.THREAD_PRIORITY_DISPLAY)
        captureThread = thread
        thread.start()
        val captureHandler = Handler(thread.looper)
        reader.setOnImageAvailableListener({ r: ImageReader ->
            // Runs on the reader's own handler thread. Copy the planes out and
            // hand the image straight back; never hold it, it holds a buffer.
            var image: Image? = null
            try {
                image = r.acquireLatestImage()
                if (image != null) frameRing.publish(image)
            } catch (t: Throwable) {
                Log.w(TAG, "Frame acquisition failed", t)
            } finally {
                try {
                    image?.close()
                } catch (ignored: Throwable) {
                    // Nothing useful to do; the image is being discarded anyway.
                }
            }
        }, captureHandler)
        imageReader = reader

        // 3. Virtual display.
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
            Log.e(TAG, "createVirtualDisplay failed", t)
            mainHandler.post { toast("Screen capture could not start (API level)") }
            null
        }

        if (virtualDisplay == null) {
            Log.e(TAG, "virtualDisplay is null; no frames will arrive")
            releaseCapture()
            return false
        }

        Log.i(TAG, "Capture started: ${captureWidth}x$captureHeight, display ${displayWidth}x$displayHeight")
        return true
    }

    private fun releaseCapture() {
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
        try {
            mediaProjection?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "mediaProjection stop failed", t)
        }
        mediaProjection = null
        frameRing.release()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun onProjectionStopped() {
        mainHandler.post {
            toast("Screen capture ended")
            stopEverything()
            stopSelf()
        }
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
    private fun resolveDisplayGeometry() {
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
        return if (rotated) metrics.height to metrics.width
        else metrics.width to metrics.height
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
        resolveDisplayGeometry()
        if (beforeW == displayWidth && beforeH == displayHeight) return

        Log.i(TAG, "Display changed ${beforeW}x$beforeH -> ${displayWidth}x$displayHeight")
        computeCaptureSize()
        frameRing.configure(captureWidth, captureHeight)

        // Re-point the existing VirtualDisplay at the new size. Recreating the
        // whole MediaProjection would need fresh user consent, which is not
        // something we can ask for from the background.
        try {
            virtualDisplay?.resize(captureWidth, captureHeight, densityDpi())
        } catch (t: Throwable) {
            Log.w(TAG, "VirtualDisplay resize failed", t)
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
        frameRing.release()
        frameRing.configure(captureWidth, captureHeight)
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
        if (displayWidth <= 0 || displayHeight <= 0) resolveDisplayGeometry()
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

                        val analysis = if (!isTargetInForeground()) {
                            // Outside the game, drop the history instead of
                            // feeding the tracker whatever is on screen. A scene
                            // change would otherwise be read as one enormous
                            // motion event and dump every track. No `continue`
                            // here: it would skip the statistics window below
                            // and leave it stale for however long the app stays
                            // backgrounded.
                            synchronized(detectorLock) { d.reset() }
                            // A stale analysis would let auto-detect calibrate
                            // from a scene that no longer exists.
                            latestAnalysis = null
                            null
                        } else synchronized(detectorLock) { d.process(
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
                        if (analysis != null) onAnalysis(analysis, d)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Vision frame failed", t)
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
                }
            }
        }
    }

    /**
     * True when the configured game is the app in the foreground.
     *
     * Falls back to "true" when the service is not connected or no target is
     * configured, so a missing accessibility service does not silently stop
     * detection; the HUD reports the real state separately.
     */
    private fun isTargetInForeground(): Boolean {
        if (!RenderaAccessibilityService.isAvailable()) return true
        return RenderaAccessibilityService.isTargetInForeground(prefs.targetPackage.value)
    }

    private fun onAnalysis(analysis: ScreenThreatDetector.Analysis?, d: ScreenThreatDetector) {
        if (analysis == null) return
        latestAnalysis = analysis

        latestVisionMillis = analysis.processMillis
        if (analysis.escape.hasThreat) {
            latestSeverity = analysis.escape.severity
            threatCount++
        } else {
            latestSeverity = ThreatLevel.SAFE
        }
        if (autoDodgeArmed && analysis.hasDodgeableThreat && displayWidth > 0) {
            maybeDodge(analysis, d)
        }
        publishHud(analysis, d)
    }

    private fun maybeDodge(analysis: ScreenThreatDetector.Analysis, d: ScreenThreatDetector) {
        if (!RenderaAccessibilityService.isAvailable()) return
        if (displayWidth <= 0 || displayHeight <= 0) return

        // The only hard gate is whether a gesture can be physically delivered.
        // Everything else is per-threat bookkeeping, so a second projectile
        // arriving mid-dodge is answered as soon as the stick is free instead of
        // being swallowed by a cooldown.
        if (!dodgeState.shouldDispatch(analysis, SystemClock.elapsedRealtime())) return

        val plan = d.planDodge(analysis, displayWidth, displayHeight)
        if (plan.isEmpty) {
            // Refused: no usable plan. Forget the commitment so it is retried
            // once anchors are fixed rather than being treated as handled.
            dodgeState.onDispatchFailed()
            Log.d(TAG, "Dodge suppressed: no usable plan (anchors calibrated=${anchors.calibrated})")
            return
        }

        val accepted = RenderaAccessibilityService.dispatch(plan) { success ->
            if (success) {
                lastDodgeAtMs = SystemClock.elapsedRealtime()
                lastDodgeAngleDeg = analysis.escape.escapeHeadingDeg
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
            gameInForeground = isTargetInForeground(),
            activeGamePackage = prefs.targetPackage.value,
            latestTacticalAdvice = buildAdvice()
        )
        Log.i(TAG, "stats: $state")
    }

    private fun buildAdvice(): String = when {
        detector?.isNativeAvailable != true -> "Native vision engine missing from this build."
        !anchors.calibrated -> "Long press the bubble and lock the anchors."
        !RenderaAccessibilityService.isAvailable() -> "Enable the Rendera accessibility service."
        !isTargetInForeground() -> "Waiting for ${prefs.targetPackage.value} to come forward."
        autoDodgeArmed -> "Armed. ${latestFps} fps, ${frameRing.droppedCount} frames dropped."
        else -> "Paused. Tap the bubble to arm."
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
        val hud = hudView ?: return
        if (!prefs.debugOverlayEnabled.value) return

        val currentAnchors = anchors
        val playerRadius = synchronized(detectorLock) { d.currentTuning() }.playerRadiusNorm * displayWidth
        val joy = currentAnchors.joystickPx(displayWidth, displayHeight)
        val dragPx = currentAnchors.joystickRadiusPx(displayWidth)
        val tracks = d.debugTrackSnapshot()
        val enemies = d.debugEnemySnapshot()
        val esc = analysis.escape
        val hasThreat = analysis.threat != null

        val entities = hud.entitiesFor(
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
            gameForeground = isTargetInForeground(),
            autoDodgeArmed = autoDodgeArmed,
            dodgePlan = dodgeState.describe(),
            note = buildAdvice()
        )

        mainHandler.post { hudView?.update(snapshot) }
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
                        toggleAutoDodge()
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
        val w = dp(260f).roundToInt()
        val rowH = dp(44f).roundToInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_MENU)
            elevation = dp(12f)
        }

        fun addButton(text: String, onClick: () -> Unit) {
            val tv = TextView(this).apply {
                this.text = text
                setTextColor(0xFFEDE7FF.toInt())
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14f).roundToInt(), 0, dp(14f).roundToInt(), 0)
                setOnClickListener {
                    // Tear down after this dispatch completes, otherwise removing
                    // a view from inside its own click listener drops the rest of
                    // the gesture and can throw on OEM builds.
                    it.post { runClick(onClick) }
                }
            }
            root.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowH))
        }

        addButton(getString(if (autoDodgeArmed) R.string.menu_pause else R.string.menu_arm)) { toggleAutoDodge() }
        addButton(getString(R.string.menu_calibrate)) {
            closeMenu()
            showCalibrationOverlay()
        }
        addButton(getString(R.string.menu_hud)) {
            prefs.setDebugOverlayEnabled(!prefs.debugOverlayEnabled.value)
            toggleHud()
            closeMenu()
            toast(if (prefs.debugOverlayEnabled.value) "HUD ON" else "HUD OFF")
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
            text = buildAdvice()
            setTextColor(0xFF9C93B8.toInt())
            textSize = 10f
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
            y = (anchorY + dp(72f)).coerceIn(0, (displayHeight - dp(400f)).coerceAtLeast(0))
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
        if (prefs.debugOverlayEnabled.value) showHud() else removeHud()
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
            pushMaskRegions()
        } catch (t: Throwable) {
            Log.e(TAG, "Could not show the HUD", t)
        }
    }

    private fun removeHud() {
        hudView?.let { runCatching { windowManager.removeView(it) } }
        hudView = null
    }

    // -----------------------------------------------------------------------
    // Calibration overlay
    // -----------------------------------------------------------------------

    private fun showCalibrationOverlay() {
        if (calibrationView != null) return
        resolveDisplayGeometry()
        // Start from whatever the user last committed; if nothing is committed,
        // start from the game's actual HUD layout so the crosshair lands on the
        // stick to begin with instead of in a corner.
        val committed = prefs.currentAnchors()
        anchors = if (committed.calibrated && committed.matchesDisplay(displayWidth, displayHeight)) {
            committed
        } else {
            AnchorCalibrator.suggestJoystick(displayWidth, displayHeight)
        }

        val view = CalibrationOverlayView(
            context = this,
            displayWidthPx = displayWidth,
            displayHeightPx = displayHeight,
            anchors = anchors,
            callbacks = object : CalibrationOverlayView.Callbacks {
                override fun onAnchorMoved(target: AnchorTarget, screenX: Float, screenY: Float) {
                    val updated = AnchorCalibrator.applyTouch(
                        anchors = anchors,
                        target = target,
                        screenX = screenX,
                        screenY = screenY,
                        displayWidth = displayWidth,
                        displayHeight = displayHeight
                    )
                    anchors = updated
                    view.setStatus(
                        "${target.name}: ${"%.3f".format(updated.playerX)}, ${"%.3f".format(updated.playerY)}"
                    )
                }

                override fun onAutoDetectRequested() {
                    view.setStatus("Auto-detect needs a live game frame. " +
                        "Place the brawler on open ground, then press LOCK & ACTIVATE.")
                    // Honest limitation: auto-detect runs from the live vision
                    // loop, not from a one-off screenshot, because the player is
                    // found on the world-anchored aligned frame which needs the
                    // engine's motion history.
                    runAutoDetect()
                }

                override fun onCommitted(committed: Anchors) {
                    anchors = committed
                    prefs.setAnchors(committed)
                    synchronized(detectorLock) {
                        detector?.setAnchors(committed)
                        detector?.applyTuning(tuningFromPrefs())
                    }
                    // New anchors mean a new escape geometry.
                    dodgeState.reset()
                    pushMaskRegions()
                    removeCalibrationOverlay()
                    triggerHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    toast("Anchors locked for ${displayWidth}x$displayHeight")
                }

                override fun onCancelled() {
                    removeCalibrationOverlay()
                }

                override fun onTargetChanged(target: AnchorTarget) {
                    view.setStatus("Editing $target")
                }
            }
        )

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
     * Seeds the calibration from the live detector.
     *
     * The old implementation read a single `Bitmap` and, when that read failed,
     * fell back to hard-coded fractions and then **saved them as if the
     * calibration had succeeded** while reporting "Calibrated &amp; Active". That
     * is the "auto calib does nothing" symptom. Here a failure is reported and
     * the previously committed anchors are left untouched.
     */
    private fun runAutoDetect() {
        val d = detector
        if (d == null || !d.isNativeAvailable) {
            mainHandler.post { toast("Vision engine unavailable; auto-detect skipped") }
            return
        }
        val live = latestAnalysis ?: run {
            mainHandler.post {
                toast("No analysed frame yet. Play for a second, then retry.")
            }
            return
        }
        if (!live.playerDetected) {
            mainHandler.post {
                toast("Player not found. Put the brawler in the open and retry.")
            }
            return
        }
        mainHandler.post {
            val updated = anchors.copy(
                joystickX = anchors.joystickX,
                joystickY = anchors.joystickY,
                playerX = (live.playerX / displayWidth).coerceIn(0.05f, 0.95f),
                playerY = (live.playerY / displayHeight).coerceIn(0.05f, 0.95f),
                calibrated = true,
                calibratedForWidth = displayWidth,
                calibratedForHeight = displayHeight
            )
            anchors = updated
            calibrationView?.applyAnchors(updated)
            toast("Player anchor auto-detected")
        }
    }

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
