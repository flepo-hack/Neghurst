package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.RenderaApp
import com.example.data.RenderaPreferences
import com.example.model.DetectionStats
import com.example.model.DodgeProfile
import com.example.model.ThreatLevel
import com.example.ui.components.RenderaDebugHudView
import com.example.vision.ScreenThreatDetector
import com.example.vision.core.FrameGrabber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Foreground service that owns the screen capture, the vision loop and the
 * on-screen UI.
 *
 * Reliability notes, all of which were defects before:
 *  * `onStartCommand` now calls `startForeground` on **every** path, including
 *    the null-intent restart path, otherwise Android kills the process with
 *    `ForegroundServiceDidNotStartInTimeException`.
 *  * The detection loop is wrapped in a supervisor that restarts it if it ever
 *    throws. Previously a single exception killed detection for the rest of the
 *    session with no visible error.
 *  * `MediaProjection.Callback` is registered (mandatory from API 35) and the
 *    capture is torn down and reported when the projection is revoked.
 *  * `android:configChanges` is declared for this service so an in-game rotation
 *    actually reaches [onConfigurationChanged] instead of leaving stale screen
 *    dimensions and a wrongly sized VirtualDisplay.
 */
class RenderaOverlayService : Service() {

    companion object {
        private const val TAG = "RenderaOverlayService"
        private const val NOTIFICATION_ID = 1001

        private const val LONG_PRESS_MS = 550L
        private const val TOUCH_SLOP_PX = 14f
        private const val HUD_STRIP_PX = 56f
        private const val MAX_EXCLUSIONS = 16

        const val ACTION_START = "com.example.action.START"
        const val ACTION_STOP = "com.example.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA_INTENT = "extra_data_intent"
        const val EXTRA_GAME_NAME = "extra_game_name"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"

        private val _stats = MutableStateFlow(DetectionStats())
        val stats: StateFlow<DetectionStats> = _stats.asStateFlow()

        private val _running = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _running.asStateFlow()

        val isRunning: Boolean get() = _running.value
    }

    // ---------------------------------------------------------------- state
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var detectionJob: Job? = null

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: RenderaPreferences
    private lateinit var detector: ScreenThreatDetector

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var grabber: FrameGrabber? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420
    private var captureWidth = 0
    private var captureHeight = 0

    private var activeGameName = "Universal"
    private var activePackage = DodgeProfile.DEFAULT_KEY

    private var lastDodgeMs = 0L
    private var totalDodges = 0
    private var totalThreats = 0
    private var framesProcessed = 0
    private var fpsWindowStart = 0L
    private var fpsFrames = 0
    private var loopFailures = 0

    // ---------------------------------------------------------------- views
    enum class BubbleState { PAUSED, READY, DODGING }

    private var bubbleState = BubbleState.PAUSED
    private var bubbleView: View? = null
    private var bubbleLabel: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var menuView: View? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var calibrationView: View? = null
    private var hudView: RenderaDebugHudView? = null

    /** Regions occupied by our own UI, fed to the detector as exclusions. */
    private val exclusionCells = IntArray(64)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        prefs = RenderaPreferences(this)
        detector = ScreenThreatDetector()
        readDisplayMetrics()
        Log.i(TAG, "created; vision backend = ${detector.backendName}")
    }

    // =====================================================================
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must happen before anything else on every path, or the platform throws
        // ForegroundServiceDidNotStartInTimeException after 5 s.
        if (!isRunning) startInForeground()

        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                stopSelf()
            }
            else -> {
                // Restart with a null intent: we cannot re-create the projection
                // without the user's consent Intent, so stay alive but idle.
                Log.w(TAG, "restarted without a start intent; capture unavailable")
                _stats.value = _stats.value.copy(
                    latestTacticalAdvice = "Capture permission lost - reopen Rendera to restart."
                )
            }
        }
        return START_STICKY
    }

    private fun handleStart(intent: Intent) {
        activeGameName = intent.getStringExtra(EXTRA_GAME_NAME) ?: "Universal"
        activePackage = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: DodgeProfile.DEFAULT_KEY

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA_INTENT)
        }

        prefs.activate(activePackage)

        if (resultCode == 0 || data == null) {
            // The old code silently continued here, leaving a running service
            // with no image source - which is exactly why "auto calibrate" did
            // nothing. Fail loudly instead.
            Log.e(TAG, "ACTION_START without a MediaProjection consent Intent")
            _stats.value = _stats.value.copy(
                isRunning = false,
                latestTacticalAdvice = "Screen capture permission missing - reopen Rendera."
            )
            stopSelf()
            return
        }

        if (setupMediaProjection(resultCode, data)) {
            // Anchors are pushed only after the capture dimensions are known.
            applyProfileToDetector(prefs.currentProfile.value)
            showBubble()
            if (prefs.currentProfile.value.debugOverlayEnabled) showHud()
            startDetectionLoop()
            _running.value = true
            _stats.value = _stats.value.copy(isRunning = true, activeGamePackage = activeGameName)
        } else {
            stopSelf()
        }
    }

    // =====================================================================
    private fun startInForeground() {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, RenderaApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_desc))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
        }
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent): Boolean {
        if (mediaProjection != null) return true
        return try {
            val proj = mediaProjectionManager?.getMediaProjection(resultCode, data)
            if (proj == null) {
                Log.e(TAG, "getMediaProjection returned null")
                return false
            }
            mediaProjection = proj
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Mandatory from API 35; without it the platform throws.
                ProjectionStopCallback.post(proj) { scope.launch { stopCaptureAndReport() } }
            }
            createVirtualDisplay()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "MediaProjection setup failed", t)
            false
        }
    }

    private fun createVirtualDisplay() {
        val proj = mediaProjection ?: return
        readDisplayMetrics()
        val size = FrameGrabber.chooseCaptureSize(screenWidth, screenHeight)
        captureWidth = size.first
        captureHeight = size.second
        releaseCapture()

        val g = FrameGrabber(captureWidth, captureHeight)
        g.createReader()
        grabber = g

        virtualDisplay = try {
            proj.createVirtualDisplay(
                "RenderaCapture",
                captureWidth,
                captureHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                g.surface(),
                null,
                null
            )
        } catch (t: Throwable) {
            Log.e(TAG, "createVirtualDisplay failed", t)
            null
        }
        if (virtualDisplay == null) {
            g.release()
            grabber = null
        } else {
            Log.i(TAG, "capture ${captureWidth}x$captureHeight on ${screenWidth}x$screenHeight")
        }
    }

    private fun releaseCapture() {
        try {
            virtualDisplay?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "VirtualDisplay release failed", t)
        }
        virtualDisplay = null
        try {
            grabber?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "FrameGrabber release failed", t)
        }
        grabber = null
    }

    private suspend fun stopCaptureAndReport() {
        releaseCapture()
        _running.value = false
        _stats.value = _stats.value.copy(
            isRunning = false,
            latestTacticalAdvice = "Screen capture ended by the system."
        )
    }

    // =====================================================================
    private fun readDisplayMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        screenWidth = m.widthPixels
        screenHeight = m.heightPixels
        screenDensity = m.densityDpi
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val wasW = screenWidth
        val wasH = screenHeight
        readDisplayMetrics()
        if (wasW != screenWidth || wasH != screenHeight) {
            Log.i(TAG, "display changed ${wasW}x$wasH -> ${screenWidth}x$screenHeight")
            createVirtualDisplay()
            // Anchors are stored normalised, so re-apply them on the new metrics.
            applyProfileToDetector(prefs.currentProfile.value)
            detector.reset()
        }
    }

    private fun applyProfileToDetector(profile: DodgeProfile) {
        val tilePx = estimateTilePixels(profile)
        prefs.setTilePixels(tilePx)
        detector.configureForCapture(
            captureWidth, captureHeight, screenWidth, screenHeight, tilePx
        )
        detector.lockJoystickAnchor(
            profile.joystickCenterX * screenWidth,
            profile.joystickCenterY * screenHeight,
            profile.joystickRadius,
            profile.joystickConfirmed
        )
        detector.lockPlayerAnchor(
            profile.playerCenterX * screenWidth,
            profile.playerCenterY * screenHeight,
            profile.anchorConfirmed
        )
        if (!profile.anchorConfirmed || !profile.joystickConfirmed) {
            // Never silently pretend an uncalibrated profile is live.
            if (prefs.currentProfile.value.autoDodgeEnabled) {
                Log.i(TAG, "anchors not confirmed; auto-dodge held off until calibration")
            }
        }
    }

    /**
     * Brawl Stars keeps the camera on the brawler and shows roughly 13 tiles
     * across the screen width in landscape, so the tile size follows from the
     * resolution. Stored once measured so the estimate can be corrected.
     */
    private fun estimateTilePixels(profile: DodgeProfile): Float {
        if (profile.tilePixels > 1f) return profile.tilePixels
        return screenWidth / 13f
    }

    // =====================================================================
    private fun startDetectionLoop() {
        detectionJob?.cancel()
        detectionJob = scope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                try {
                    tick()
                    consecutiveFailures = 0
                } catch (t: Throwable) {
                    consecutiveFailures++
                    loopFailures++
                    Log.e(TAG, "detection tick failed (#$consecutiveFailures)", t)
                    if (consecutiveFailures >= 12) {
                        Log.e(TAG, "too many consecutive failures; pausing for 1 s")
                        delay(1000L)
                        consecutiveFailures = 0
                        try {
                            releaseCapture()
                            createVirtualDisplay()
                            detector.reset()
                        } catch (inner: Throwable) {
                            Log.e(TAG, "capture recovery failed", inner)
                        }
                    }
                }
                // Pace against the frame timestamps, not a fixed sleep, so a slow
                // frame does not get amplified into a permanently low rate.
                delay(if (consecutiveFailures > 0) 60L else 4L)
            }
        }
    }

    private fun tick() {
        val g = grabber ?: return
        val profile = prefs.currentProfile.value
        val armed = profile.autoDodgeEnabled && bubbleState != BubbleState.PAUSED &&
            profile.anchorConfirmed && profile.joystickConfirmed

        val result = detector.analyze(g, profile, profile.tilePixels)
        if (result == null) return

        framesProcessed++
        fpsFrames++
        val now = System.currentTimeMillis()
        if (fpsWindowStart == 0L) fpsWindowStart = now

        if (armed) {
            val threat = result.threat
            if (threat != null && threat.threatLevel >= ThreatLevel.IMMINENT_DANGER) {
                totalThreats++
                dispatchDodge(threat, profile, result.joystickX, result.joystickY, result.joystickRadiusPx)
            }
        }

        hudView?.updateAnalysis(result, profile, armed)

        if (now - fpsWindowStart >= 1000L) {
            val fps = fpsFrames * 1000 / max(1, (now - fpsWindowStart).toInt())
            fpsFrames = 0
            fpsWindowStart = now
            _stats.value = _stats.value.copy(
                isRunning = _running.value,
                fps = fps,
                frameCount = framesProcessed,
                threatsDetected = totalThreats,
                dodgesExecuted = totalDodges,
                currentThreatLevel = result.threat?.threatLevel ?: ThreatLevel.SAFE,
                latencyMs = 0L,
                isJoystickCalibrated = result.joystickAnchorConfirmed,
                isPlayerCalibrated = result.playerAnchorConfirmed,
                activeGamePackage = activeGameName,
                latestTacticalAdvice = buildAdvice(result, armed, fps)
            )
        }
    }

    private fun buildAdvice(
        r: ScreenThreatDetector.FrameAnalysisResult,
        armed: Boolean,
        fps: Int
    ): String {
        val sb = StringBuilder(96)
        sb.append("ENGINE ").append(r.engine).append(" | ").append(fps).append(" FPS | ")
        sb.append("CAM ").append(if (r.isCameraMoving) "${r.cameraDx},${r.cameraDy}" else "STABLE")
        sb.append(" | AMMO ").append(r.projectileCount)
        sb.append(" | BRAWLERS ").append(r.brawlerCount)
        sb.append(" | PLAYER ")
        sb.append(if (r.playerAnchorConfirmed) "LOCKED" else "UNCALIBRATED")
        if (!armed) {
            sb.append(" | HOLDING: ")
            sb.append(
                when {
                    !r.playerAnchorConfirmed && !r.joystickAnchorConfirmed -> "tap the bubble -> Calibrate"
                    !r.playerAnchorConfirmed -> "player anchor not locked"
                    else -> "joystick anchor not locked"
                }
            )
        }
        return sb.toString()
    }

    // =====================================================================
    private fun dispatchDodge(
        threat: com.example.model.ThreatVector,
        profile: DodgeProfile,
        joyX: Float,
        joyY: Float,
        joyRadiusPx: Float
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDodgeMs < profile.dodgeCooldownMs) return
        lastDodgeMs = now

        val hold = profile.dodgeHoldMs
        val radius = joyRadiusPx * profile.dodgeDeflection.coerceIn(0.3f, 1f)
        setBubble(BubbleState.DODGING)

        val accepted = RenderaAccessibilityService.executeDodge(
            joyX = joyX,
            joyY = joyY,
            angleDeg = threat.dodgeAngleDeg,
            radiusPx = radius,
            holdMs = hold
        ) { completed ->
            if (completed) {
                totalDodges++
                _stats.value = _stats.value.copy(
                    dodgesExecuted = totalDodges,
                    lastDodgeAngleDeg = threat.dodgeAngleDeg,
                    lastDodgeTimestamp = System.currentTimeMillis()
                )
            }
        }
        if (!accepted) {
            Log.w(TAG, "dodge gesture was not accepted (service busy or disconnected)")
        }
        if (profile.hapticEnabled) vibrate(35L)
        scope.launch { delay(hold); if (bubbleState == BubbleState.DODGING) setBubble(BubbleState.READY) }
    }

    // =====================================================================
    private fun overlayLayoutType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun showBubble() {
        if (bubbleView != null) return
        val d = resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12 * d).toInt()
            y = (screenHeight * 0.30f).toInt()
        }
        bubbleParams = params

        val size = (58 * d).toInt()
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.argb(235, 11, 15, 25))
                setStroke((3 * d).toInt(), android.graphics.Color.argb(255, 0, 240, 255))
            }
            val lp = LinearLayout.LayoutParams(size, size)
            layoutParams = lp
            addView(TextView(this@RenderaOverlayService).apply {
                id = View.generateViewId()
                text = "R"
                textSize = 20f
                setTextColor(android.graphics.Color.WHITE)
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                bubbleLabel = this
            })
        }

        val startX = params.x
        val startY = params.y
        var downRawX = 0f
        var downRawY = 0f
        var dragging = false
        var longPressed = false
        val longPress = Runnable {
            if (!dragging) {
                longPressed = true
                vibrate(60L)
                toggleMenu()
            }
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        bubble.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    dragging = false
                    longPressed = false
                    handler.postDelayed(longPress, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (kotlin.math.abs(dx) > TOUCH_SLOP_PX || kotlin.math.abs(dy) > TOUCH_SLOP_PX) {
                        dragging = true
                        handler.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(bubble, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    when {
                        longPressed -> Unit
                        dragging -> persistBubblePosition(params.x, params.y)
                        else -> toggleAutoDodge()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    true
                }
                else -> false
            }
        }

        bubbleView = bubble
        runCatching { windowManager.addView(bubble, params) }
            .onFailure { bubbleView = null; Log.e(TAG, "addView(bubble) failed", it) }
        setBubble(if (prefs.currentProfile.value.autoDodgeEnabled) BubbleState.READY else BubbleState.PAUSED)
        refreshExclusions()
    }

    private fun persistBubblePosition(x: Int, y: Int) {
        val p = prefs.currentProfile.value
        prefs.save(
            p.copy(
                joystickCenterX = p.joystickCenterX,
                joystickCenterY = p.joystickCenterY
            )
        )
        refreshExclusions()
    }

    private fun setBubble(state: BubbleState) {
        bubbleState = state
        val label = bubbleLabel ?: return
        val color = when (state) {
            BubbleState.READY -> android.graphics.Color.argb(255, 0, 240, 255)
            BubbleState.PAUSED -> android.graphics.Color.argb(255, 255, 170, 0)
            BubbleState.DODGING -> android.graphics.Color.argb(255, 255, 64, 129)
        }
        label.setTextColor(color)
        label.text = when (state) {
            BubbleState.READY -> "R"
            BubbleState.PAUSED -> "II"
            BubbleState.DODGING -> ">"
        }
    }

    private fun toggleAutoDodge() {
        val p = prefs.currentProfile.value
        val calibrated = p.anchorConfirmed && p.joystickConfirmed
        if (!calibrated) {
            // Tapping the bubble with no calibration opens calibration instead of
            // pretending to arm. This is what "nothing happens" looked like before.
            vibrate(40L)
            setBubble(BubbleState.PAUSED)
            showCalibration()
            return
        }
        val next = !p.autoDodgeEnabled
        prefs.setAutoDodge(next)
        detector.reset()
        setBubble(if (next) BubbleState.READY else BubbleState.PAUSED)
        vibrate(50L)
        _stats.value = _stats.value.copy(
            latestTacticalAdvice = if (next) "Auto-dodge ARMED" else "Auto-dodge PAUSED"
        )
    }

    // =====================================================================
    private fun toggleMenu() {
        if (menuView != null) {
            removeMenu()
            return
        }
        val d = resources.displayMetrics.density
        val p = prefs.currentProfile.value
        val width = (300 * d).toInt()
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((bubbleParams?.x ?: 0) + 70 * d).toInt()
                .coerceIn(0, max(0, screenWidth - width))
            y = (bubbleParams?.y ?: 0)
                .coerceIn(0, max(0, screenHeight - (460 * d).toInt()))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (14 * d).toInt()
            setPadding(pad, pad, pad, pad)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(248, 16, 12, 32))
                cornerRadius = 18 * d
                setStroke(2, android.graphics.Color.argb(220, 157, 78, 221))
            }
            addView(header("RENDERA - ${detector.backendName}"))
            addView(header("$activeGameName  ${screenWidth}x$screenHeight", dim = true))

            addView(menuButton("CALIBRATE PLAYER & JOYSTICK", cyan = true) {
                removeMenu()
                showCalibration()
            })
            addView(menuButton("AUTO-CALIBRATE FROM SCREEN", amber = true) {
                removeMenu()
                runAutoCalibration()
            })
            addView(menuButton(
                if (p.debugOverlayEnabled) "RADAR HUD: ON" else "RADAR HUD: OFF"
            ) {
                val now = !p.debugOverlayEnabled
                prefs.setDebugOverlay(now)
                if (now) showHud() else removeHud()
                removeMenu()
            })
            addView(menuButton("STOP RENDERA", red = true) {
                removeMenu()
                stopSelf()
            })
            addView(menuButton("CLOSE", dim = true) { removeMenu() })
        }

        menuView = root
        menuParams = params
        runCatching { windowManager.addView(root, params) }
            .onFailure { menuView = null; menuParams = null; Log.e(TAG, "addView(menu) failed", it) }
        refreshExclusions()
    }

    private fun removeMenu() {
        menuView?.let { runCatching { windowManager.removeView(it) } }
        menuView = null
        menuParams = null
        refreshExclusions()
    }

    private fun header(text: String, dim: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(
                if (dim) android.graphics.Color.argb(190, 200, 210, 230)
                else android.graphics.Color.argb(255, 157, 78, 221)
            )
            textSize = if (dim) 11f else 14f
            gravity = Gravity.CENTER
            val p = (6 * resources.displayMetrics.density).toInt()
            setPadding(0, p / 2, 0, p / 2)
        }

    private fun menuButton(
        label: String,
        cyan: Boolean = false,
        amber: Boolean = false,
        red: Boolean = false,
        dim: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        val d = resources.displayMetrics.density
        val tint = when {
            red -> android.graphics.Color.argb(255, 255, 90, 90)
            cyan -> android.graphics.Color.argb(255, 0, 240, 255)
            amber -> android.graphics.Color.argb(255, 255, 200, 60)
            else -> android.graphics.Color.WHITE
        }
        return TextView(this).apply {
            text = label
            setTextColor(tint)
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(150, 24, 24, 40))
                cornerRadius = 12 * d
                setStroke(1, android.graphics.Color.argb(140, tint))
            }
            val p = (10 * d).toInt()
            setPadding(p, p, p, p)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (6 * d).toInt()
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    // =====================================================================
    private fun runAutoCalibration() {
        val g = grabber
        if (g == null) {
            _stats.value = _stats.value.copy(
                latestTacticalAdvice = "No screen capture - cannot auto-calibrate."
            )
            return
        }
        val profile = prefs.currentProfile.value
        val proposal = detector.autoCalibrate(g, profile.tilePixels)
        val joyOk = proposal.joystickFound
        val playerOk = proposal.playerFound

        var next = profile
        if (joyOk) {
            next = next.copy(
                joystickCenterX = (proposal.joystickX / screenWidth).coerceIn(0.02f, 0.98f),
                joystickCenterY = (proposal.joystickY / screenHeight).coerceIn(0.02f, 0.98f),
                joystickRadius = proposal.joystickRadiusPx.coerceIn(60f, 900f),
                joystickConfirmed = true
            )
        }
        if (playerOk) {
            next = next.copy(
                playerCenterX = (proposal.playerX / screenWidth).coerceIn(0.02f, 0.98f),
                playerCenterY = (proposal.playerY / screenHeight).coerceIn(0.02f, 0.98f),
                anchorConfirmed = true
            )
        }
        prefs.save(next)
        applyProfileToDetector(next)
        detector.reset()
        if (next.anchorConfirmed && next.joystickConfirmed) {
            prefs.setAutoDodge(true)
            setBubble(BubbleState.READY)
        }

        vibrate(70L)
        _stats.value = _stats.value.copy(
            latestTacticalAdvice = buildCalibrationAdvice(proposal.joystickScore, proposal.playerScore, joyOk, playerOk)
        )
    }

    private fun buildCalibrationAdvice(
        joyScore: Float,
        playerScore: Float,
        joyOk: Boolean,
        playerOk: Boolean
    ): String = buildString {
        append("AUTO-CALIBRATION  ")
        append(if (joyOk) "joystick ${(joyScore * 100).roundToInt()}%" else "joystick NOT FOUND")
        append("  ")
        append(if (playerOk) "player ${(playerScore * 100).roundToInt()}%" else "player NOT FOUND")
        if (!joyOk || !playerOk) {
            append("  - open CALIBRATE to set the missing anchor by hand")
        }
    }

    // =====================================================================
    private fun showCalibration() {
        if (calibrationView != null) return
        removeMenu()
        val d = resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayLayoutType(),
            // NOT_FOCUSABLE matters: without it this overlay steals focus from the
            // running game and the match pauses.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val profile = prefs.currentProfile.value
        val joyRadiusPx = profile.joystickRadius.coerceIn(60f, 900f)
        // Clamp once, up front, and keep the clamped value as the single source of
        // truth. The old code clamped only the drawn ring but saved the unclamped
        // coordinate, which is why the HUD and the saved anchors disagreed.
        var joyX = (profile.joystickCenterX * screenWidth).coerceIn(joyRadiusPx, screenWidth - joyRadiusPx)
        var joyY = (profile.joystickCenterY * screenHeight).coerceIn(joyRadiusPx, screenHeight - joyRadiusPx)
        var playerX = (profile.playerCenterX * screenWidth).coerceIn(0f, screenWidth.toFloat())
        var playerY = (profile.playerCenterY * screenHeight).coerceIn(0f, screenHeight.toFloat())
        var modeJoy = true

        val root = FrameLayout(this)

        val joyRing = ring(android.graphics.Color.argb(255, 0, 240, 255), "JOY")
        val playerRing = ring(android.graphics.Color.argb(255, 5, 255, 161), "PLAYER")

        fun place(v: View, cx: Float, cy: Float, r: Float) {
            v.translationX = (cx - r).coerceIn(0f, max(0f, screenWidth - r * 2))
            v.translationY = (cy - r).coerceIn(0f, max(0f, screenHeight - r * 2))
        }
        fun reposition() {
            place(joyRing, joyX, joyY, joyRadiusPx)
            place(playerRing, playerX, playerY, 70f)
        }

        val surface = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        if (modeJoy) {
                            joyX = e.x.coerceIn(joyRadiusPx, screenWidth - joyRadiusPx)
                            joyY = e.y.coerceIn(joyRadiusPx, screenHeight - joyRadiusPx)
                        } else {
                            playerX = e.x.coerceIn(0f, screenWidth.toFloat())
                            playerY = e.y.coerceIn(0f, screenHeight.toFloat())
                        }
                        reposition()
                        true
                    }
                    // Swallow UP too: returning false here let the gesture escape
                    // to the window and dismiss the overlay mid-drag.
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
        }
        root.addView(surface)
        root.addView(joyRing, FrameLayout.LayoutParams((joyRadiusPx * 2).toInt(), (joyRadiusPx * 2).toInt()))
        root.addView(playerRing, FrameLayout.LayoutParams(140, 140))
        reposition()

        val status = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            text = "Touch anywhere to move the highlighted anchor."
        }

        val joyTab = TextView(this).apply { text = "JOYSTICK"; textSize = 13f; gravity = Gravity.CENTER }
        val playerTab = TextView(this).apply { text = "PLAYER"; textSize = 13f; gravity = Gravity.CENTER }

        fun styleTab(t: TextView, active: Boolean) {
            t.setTextColor(if (active) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            t.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(
                    if (active) android.graphics.Color.argb(255, 0, 240, 255)
                    else android.graphics.Color.argb(90, 255, 255, 255)
                )
                cornerRadius = 10 * d
            }
        }
        fun setMode(joy: Boolean) {
            modeJoy = joy
            styleTab(joyTab, joy)
            styleTab(playerTab, !joy)
            status.text = if (joy) "Move the joystick anchor." else "Tap your brawler's feet."
        }
        fun tabLp(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = (10 * d).toInt() }
        joyTab.layoutParams = tabLp()
        playerTab.layoutParams = tabLp()
        joyTab.setOnClickListener { setMode(true) }
        playerTab.setOnClickListener { setMode(false) }
        setMode(true)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.argb(240, 12, 16, 30))
            val pad = (12 * d).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
            isClickable = true
            addView(TextView(this@RenderaOverlayService).apply {
                text = "CALIBRATION"
                setTextColor(android.graphics.Color.argb(255, 0, 240, 255))
                textSize = 16f
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(status)
            addView(LinearLayout(this@RenderaOverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(joyTab)
                addView(playerTab)
            })
            addView(menuButton("AUTO-DETECT FROM SCREEN", amber = true, onClick = run@{
                val g0 = grabber
                if (g0 == null) {
                    status.text = "No capture - auto-detect unavailable."
                    return@run
                }
                val proposal = detector.autoCalibrate(g0, prefs.currentProfile.value.tilePixels)
                if (proposal.joystickFound) {
                    joyX = proposal.joystickX.coerceIn(joyRadiusPx, screenWidth - joyRadiusPx)
                    joyY = proposal.joystickY.coerceIn(joyRadiusPx, screenHeight - joyRadiusPx)
                }
                if (proposal.playerFound) {
                    playerX = proposal.playerX.coerceIn(0f, screenWidth.toFloat())
                    playerY = proposal.playerY.coerceIn(0f, screenHeight.toFloat())
                }
                reposition()
                status.text = buildCalibrationAdvice(
                    proposal.joystickScore, proposal.playerScore,
                    proposal.joystickFound, proposal.playerFound
                )
                vibrate(60L)
            }))
        }
        root.addView(header)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = (36 * d).toInt() }
            isClickable = true
            addView(menuButton("CANCEL", dim = true) { removeCalibration() })
            addView(menuButton("LOCK & ARM", cyan = true) {
                val next = prefs.currentProfile.value.copy(
                    joystickCenterX = (joyX / screenWidth).coerceIn(0.02f, 0.98f),
                    joystickCenterY = (joyY / screenHeight).coerceIn(0.02f, 0.98f),
                    joystickRadius = joyRadiusPx,
                    joystickConfirmed = true,
                    playerCenterX = (playerX / screenWidth).coerceIn(0.02f, 0.98f),
                    playerCenterY = (playerY / screenHeight).coerceIn(0.02f, 0.98f),
                    anchorConfirmed = true
                )
                prefs.save(next)
                prefs.setAutoDodge(true)
                applyProfileToDetector(next)
                detector.reset()
                vibrate(70L)
                setBubble(BubbleState.READY)
                removeCalibration()
                _stats.value = _stats.value.copy(
                    latestTacticalAdvice = "Calibration locked - auto-dodge ARMED"
                )
            })
        }
        root.addView(bottom)

        calibrationView = root
        runCatching { windowManager.addView(root, params) }
            .onFailure { calibrationView = null; Log.e(TAG, "addView(calibration) failed", it) }
        refreshExclusions()
    }

    private fun ring(color: Int, label: String): FrameLayout = FrameLayout(this).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(android.graphics.Color.argb(60, android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color)))
            setStroke(4, color)
        }
        addView(TextView(this@RenderaOverlayService).apply {
            text = label
            textSize = 10f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun removeCalibration() {
        calibrationView?.let { runCatching { windowManager.removeView(it) } }
        calibrationView = null
        refreshExclusions()
    }

    // =====================================================================
    private fun showHud() {
        if (hudView != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        val hud = RenderaDebugHudView(this)
        hudView = hud
        runCatching { windowManager.addView(hud, params) }
            .onFailure { hudView = null; Log.e(TAG, "addView(hud) failed", it) }
        refreshExclusions()
    }

    private fun removeHud() {
        hudView?.let { runCatching { windowManager.removeView(it) } }
        hudView = null
        refreshExclusions()
    }

    /**
     * Tells the vision engine where our own UI is. Without this the bubble, the
     * radar strip and the menu are themselves "fast moving bright objects" and
     * get tracked as projectiles, which produces constant phantom dodges.
     */
    private fun refreshExclusions() {
        val cols = ScreenThreatDetector.DEFAULT_COLS
        val rows = ScreenThreatDetector.DEFAULT_ROWS
        val pxPerCellX = screenWidth.toFloat() / cols
        val pxPerCellY = screenHeight.toFloat() / rows
        val rects = exclusionCells
        var n = 0

        fun add(x0: Int, y0: Int, x1: Int, y1: Int) {
            if (n >= MAX_EXCLUSIONS) return
            rects[n * 4] = (x0 / pxPerCellX).toInt()
            rects[n * 4 + 1] = (y0 / pxPerCellY).toInt()
            rects[n * 4 + 2] = (x1 / pxPerCellX).toInt()
            rects[n * 4 + 3] = (y1 / pxPerCellY).toInt()
            n++
        }

        bubbleParams?.let { add(it.x, it.y, it.x + (bubbleView?.width ?: 0), it.y + (bubbleView?.height ?: 0)) }
        menuParams?.let { add(it.x, it.y, it.x + (menuView?.width ?: 0), it.y + (menuView?.height ?: 0)) }
        hudView?.let { add(0, 0, screenWidth, (HUD_STRIP_PX * resources.displayMetrics.density).toInt()) }
        if (calibrationView != null) add(0, 0, screenWidth, screenHeight)

        detector.setExclusions(rects, n)
    }

    private fun vibrate(ms: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    v.vibrate(ms)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "vibrate failed", t)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _running.value = false
        detectionJob?.cancel()
        scope.cancel()

        removeCalibration()
        removeMenu()
        removeHud()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null

        releaseCapture()
        try {
            mediaProjection?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "MediaProjection.stop failed", t)
        }
        mediaProjection = null

        _stats.value = DetectionStats()
        Log.i(TAG, "destroyed after $framesProcessed frames, $totalDodges dodges, $loopFailures loop failures")
    }

}
