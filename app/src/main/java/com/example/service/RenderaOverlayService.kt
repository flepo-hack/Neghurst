package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
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
import android.widget.ImageView
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
import com.example.model.ThreatVector
import com.example.vision.GeminiTacticalAdvisor
import com.example.vision.ScreenThreatDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class RenderaOverlayService : Service() {

    companion object {
        private const val TAG = "RenderaOverlayService"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.action.START"
        const val ACTION_STOP = "com.example.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA_INTENT = "extra_data_intent"
        const val EXTRA_GAME_NAME = "extra_game_name"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"

        private val _stats = MutableStateFlow(DetectionStats())
        val stats: StateFlow<DetectionStats> = _stats.asStateFlow()

        var isRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var prefs: RenderaPreferences
    private lateinit var threatDetector: ScreenThreatDetector
    private val geminiAdvisor = GeminiTacticalAdvisor()

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // Overlay views
    enum class BubbleState {
        NEED_CALIBRATION,
        READY,
        PAUSED,
        DODGING
    }

    private var currentBubbleState = BubbleState.NEED_CALIBRATION
    private var floatingBubbleView: View? = null
    private var bubbleIconView: ImageView? = null
    private var bubbleLabelView: TextView? = null
    private var bubbleBgDrawable: android.graphics.drawable.GradientDrawable? = null
    private var hudMenuView: View? = null
    private var calibrationOverlayView: View? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    private var lastDodgeTimestamp = 0L
    private var totalDodges = 0
    private var totalThreats = 0
    private var fpsCounter = 0
    private var lastFpsTimestamp = System.currentTimeMillis()
    private var activeGame = "Universal"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        prefs = RenderaPreferences(this)
        threatDetector = ScreenThreatDetector()

        fetchScreenDimensions()
    }

    private fun fetchScreenDimensions() {
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA_INTENT)
                }
                activeGame = intent.getStringExtra(EXTRA_GAME_NAME) ?: "Universal"

                startInForeground()
                setupMediaProjection(resultCode, data)
                showFloatingBubble()
                startDetectionLoop()
                isRunning = true
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, RenderaApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Rendera AI Dodge Active")
            .setContentText("Monitoring $activeGame screen for incoming projectiles & threat vectors")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent?) {
        if (resultCode != 0 && data != null && mediaProjection == null) {
            try {
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
                if (mediaProjection != null) {
                    // Downsample capture width/height to 480x854 or equivalent aspect for high FPS, low RAM
                    val captureWidth = 480
                    val captureHeight = ((screenHeight.toFloat() / screenWidth) * captureWidth).toInt()

                    imageReader = ImageReader.newInstance(
                        captureWidth,
                        captureHeight,
                        PixelFormat.RGBA_8888,
                        2
                    )

                    virtualDisplay = mediaProjection?.createVirtualDisplay(
                        "RenderaCapture",
                        captureWidth,
                        captureHeight,
                        screenDensity,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        imageReader?.surface,
                        null,
                        null
                    )
                    Log.i(TAG, "MediaProjection and VirtualDisplay initialized ($captureWidth x $captureHeight)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize MediaProjection", e)
            }
        }
    }

    private fun updateBubbleUi(state: BubbleState) {
        currentBubbleState = state
        val icon = bubbleIconView ?: return
        val label = bubbleLabelView ?: return
        val bg = bubbleBgDrawable ?: return

        when (state) {
            BubbleState.NEED_CALIBRATION -> {
                bg.setColor(android.graphics.Color.argb(235, 25, 20, 12))
                bg.setStroke((3 * resources.displayMetrics.density).toInt(), android.graphics.Color.argb(255, 255, 183, 3))
                icon.setImageResource(android.R.drawable.ic_menu_crop)
                icon.setColorFilter(android.graphics.Color.argb(255, 255, 183, 3))
                label.text = "CALIB"
                label.setTextColor(android.graphics.Color.argb(255, 255, 183, 3))
            }
            BubbleState.READY -> {
                bg.setColor(android.graphics.Color.argb(235, 10, 26, 18))
                bg.setStroke((3 * resources.displayMetrics.density).toInt(), android.graphics.Color.argb(255, 5, 255, 161))
                icon.setImageResource(android.R.drawable.ic_menu_compass)
                icon.setColorFilter(android.graphics.Color.argb(255, 5, 255, 161))
                label.text = "READY"
                label.setTextColor(android.graphics.Color.argb(255, 5, 255, 161))
            }
            BubbleState.PAUSED -> {
                bg.setColor(android.graphics.Color.argb(235, 32, 22, 12))
                bg.setStroke((3 * resources.displayMetrics.density).toInt(), android.graphics.Color.argb(255, 255, 170, 0))
                icon.setImageResource(android.R.drawable.ic_media_pause)
                icon.setColorFilter(android.graphics.Color.argb(255, 255, 170, 0))
                label.text = "PAUSED"
                label.setTextColor(android.graphics.Color.argb(255, 255, 170, 0))
            }
            BubbleState.DODGING -> {
                bg.setColor(android.graphics.Color.argb(245, 60, 15, 80))
                bg.setStroke((4 * resources.displayMetrics.density).toInt(), android.graphics.Color.argb(255, 157, 78, 221))
                icon.setImageResource(android.R.drawable.ic_menu_send)
                icon.setColorFilter(android.graphics.Color.WHITE)
                label.text = "DODGE"
                label.setTextColor(android.graphics.Color.WHITE)
            }
        }
    }

    private fun showFloatingBubble() {
        if (floatingBubbleView != null) return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = screenHeight / 3
        }

        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val sizePx = (62 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)

            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.argb(235, 11, 15, 25))
                setStroke((3 * resources.displayMetrics.density).toInt(), android.graphics.Color.argb(255, 0, 240, 255))
            }
            bubbleBgDrawable = bg
            background = bg

            val icon = ImageView(this@RenderaOverlayService).apply {
                setImageResource(android.R.drawable.ic_menu_compass)
                setColorFilter(android.graphics.Color.argb(255, 0, 240, 255))
                val iconSize = (24 * resources.displayMetrics.density).toInt()
                val lp = LinearLayout.LayoutParams(iconSize, iconSize)
                lp.bottomMargin = (2 * resources.displayMetrics.density).toInt()
                layoutParams = lp
            }
            bubbleIconView = icon
            addView(icon)

            val label = TextView(this@RenderaOverlayService).apply {
                text = "READY"
                textSize = 9f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.argb(255, 5, 255, 161))
                gravity = Gravity.CENTER
            }
            bubbleLabelView = label
            addView(label)
        }

        val initialBubbleState = if (prefs.isJoystickCalibrated()) {
            if (prefs.currentProfile.value.autoDodgeEnabled) BubbleState.READY else BubbleState.PAUSED
        } else {
            BubbleState.NEED_CALIBRATION
        }
        updateBubbleUi(initialBubbleState)

        // Gesture handling: Tap = Calibrate / Toggle Pause; Long Press = Mini Menu; Drag = Move
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDrag = false
        var isLongPressed = false

        val longPressRunnable = Runnable {
            if (!isDrag) {
                isLongPressed = true
                triggerHapticFeedback(70L)
                toggleMiniMenu(params.x, params.y)
            }
        }

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDrag = false
                    isLongPressed = false
                    mainHandler.postDelayed(longPressRunnable, 450L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) {
                        isDrag = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (isDrag) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(bubble, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!isDrag && !isLongPressed) {
                        if (!prefs.isJoystickCalibrated() || currentBubbleState == BubbleState.NEED_CALIBRATION) {
                            showJoystickCalibrationOverlay()
                        } else {
                            // Toggle Pause / Resume
                            val currentProf = prefs.currentProfile.value
                            val newEnabled = !currentProf.autoDodgeEnabled
                            prefs.toggleAutoDodge(newEnabled)
                            triggerHapticFeedback(40L)
                            if (newEnabled) {
                                updateBubbleUi(BubbleState.READY)
                            } else {
                                updateBubbleUi(BubbleState.PAUSED)
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    false
                }
                else -> false
            }
        }

        floatingBubbleView = bubble
        windowManager.addView(bubble, params)
    }

    private fun toggleMiniMenu(bubbleX: Int, bubbleY: Int) {
        if (hudMenuView != null) {
            removeHudMenu()
            return
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            (280 * resources.displayMetrics.density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bubbleX + 70 * resources.displayMetrics.density).toInt().coerceAtMost(screenWidth - (290 * resources.displayMetrics.density).toInt())
            y = bubbleY.coerceAtMost(screenHeight - (380 * resources.displayMetrics.density).toInt())
        }

        val menuLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)

            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(248, 16, 12, 32))
                cornerRadius = 20 * resources.displayMetrics.density
                setStroke(2, android.graphics.Color.argb(220, 157, 78, 221))
            }
            background = bg

            // Header Title
            addView(TextView(this@RenderaOverlayService).apply {
                text = "⚡ RENDERA MENU"
                setTextColor(android.graphics.Color.argb(255, 157, 78, 221))
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER_HORIZONTAL
            })

            addView(TextView(this@RenderaOverlayService).apply {
                text = "Target: $activeGame"
                setTextColor(android.graphics.Color.argb(200, 180, 190, 210))
                textSize = 11f
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 2, 0, (10 * resources.displayMetrics.density).toInt())
            })

            // Sensitivity text & slider
            val currentProf = prefs.currentProfile.value
            val sensLabel = TextView(this@RenderaOverlayService).apply {
                text = "Dodge Sensitivity: ${(currentProf.sensitivity * 100).toInt()}%"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            addView(sensLabel)

            val seekBar = android.widget.SeekBar(this@RenderaOverlayService).apply {
                max = 100
                progress = (currentProf.sensitivity * 100).toInt().coerceIn(10, 100)
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val sensVal = progress.coerceIn(10, 100) / 100f
                            sensLabel.text = "Dodge Sensitivity: $progress%"
                            prefs.updateSensitivity(sensVal)
                        }
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                        triggerHapticFeedback(20L)
                    }
                })
            }
            addView(seekBar)

            // Recalibrate Joystick Button
            val joyBtn = TextView(this@RenderaOverlayService).apply {
                text = "🎯 Recalibrate Joystick"
                setTextColor(android.graphics.Color.argb(255, 0, 240, 255))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                val btnBg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.argb(140, 20, 30, 55))
                    cornerRadius = 12 * resources.displayMetrics.density
                    setStroke(1, android.graphics.Color.argb(180, 0, 240, 255))
                }
                background = btnBg
                val p = (10 * resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = (10 * resources.displayMetrics.density).toInt()
                layoutParams = lp
                setOnClickListener {
                    removeHudMenu()
                    showJoystickCalibrationOverlay()
                }
            }
            addView(joyBtn)

            // Stop Rendera Button
            val stopBtn = TextView(this@RenderaOverlayService).apply {
                text = "🛑 Stop Rendera"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                val btnBg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.argb(220, 220, 38, 38))
                    cornerRadius = 12 * resources.displayMetrics.density
                }
                background = btnBg
                val p = (10 * resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = (8 * resources.displayMetrics.density).toInt()
                layoutParams = lp
                setOnClickListener {
                    removeHudMenu()
                    stopSelf()
                }
            }
            addView(stopBtn)

            // Close Menu Button
            val closeBtn = TextView(this@RenderaOverlayService).apply {
                text = "✕ Close"
                setTextColor(android.graphics.Color.argb(200, 160, 170, 190))
                textSize = 11f
                gravity = Gravity.CENTER
                val p = (8 * resources.displayMetrics.density).toInt()
                setPadding(0, p, 0, 0)
                setOnClickListener {
                    removeHudMenu()
                }
            }
            addView(closeBtn)
        }

        hudMenuView = menuLayout
        windowManager.addView(menuLayout, params)
    }

    private fun removeHudMenu() {
        hudMenuView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Ignore
            }
            hudMenuView = null
        }
    }

    private fun showJoystickCalibrationOverlay() {
        if (calibrationOverlayView != null) return

        removeHudMenu()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val profile = prefs.currentProfile.value
        val reticleRadiusPx = profile.joystickRadius
        val reticleDiameterPx = (reticleRadiusPx * 2).toInt()

        // Auto-detect joystick from latest screen frame if possible
        val currentFrame = acquireCurrentFrameBitmap()
        val scannedCoords = if (currentFrame != null) threatDetector.scanForJoystick(currentFrame) else null

        var currentJoyX = if (scannedCoords != null) (scannedCoords.first * screenWidth) else (profile.joystickCenterX * screenWidth)
        var currentJoyY = if (scannedCoords != null) (scannedCoords.second * screenHeight) else (profile.joystickCenterY * screenHeight)

        val rootOverlay = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.argb(125, 0, 0, 0))
        }

        // Instructions header banner
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.argb(230, 10, 15, 28))
            val pad = (14 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
            }
            layoutParams = lp

            addView(TextView(this@RenderaOverlayService).apply {
                text = "🎯 JOYSTICK CALIBRATION"
                setTextColor(android.graphics.Color.argb(255, 0, 240, 255))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@RenderaOverlayService).apply {
                text = if (scannedCoords != null) {
                    "Joystick detected automatically! Touch anywhere on screen to adjust, then press 'LOCK & ACTIVATE'."
                } else {
                    "Touch the screen over your virtual movement joystick, then press 'LOCK & ACTIVATE'."
                }
                setTextColor(android.graphics.Color.argb(230, 200, 220, 240))
                textSize = 12f
                gravity = Gravity.CENTER
                val topP = (4 * resources.displayMetrics.density).toInt()
                setPadding(0, topP, 0, 0)
            })
        }
        rootOverlay.addView(banner)

        // Joystick Reticle Ring
        val joyRing = FrameLayout(this).apply {
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.argb(80, 5, 255, 161))
                setStroke(4, android.graphics.Color.argb(255, 5, 255, 161), 12f, 10f)
            }
            background = bg

            val cross = ImageView(this@RenderaOverlayService).apply {
                setImageResource(android.R.drawable.ic_menu_crop)
                setColorFilter(android.graphics.Color.WHITE)
                val pad = (14 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            addView(cross)
        }

        val ringParams = FrameLayout.LayoutParams(reticleDiameterPx, reticleDiameterPx).apply {
            leftMargin = (currentJoyX - reticleRadiusPx).toInt().coerceIn(0, screenWidth - reticleDiameterPx)
            topMargin = (currentJoyY - reticleRadiusPx).toInt().coerceIn(0, screenHeight - reticleDiameterPx)
        }
        rootOverlay.addView(joyRing, ringParams)

        // Touching ANYWHERE on the screen moves the reticle to that location!
        rootOverlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                currentJoyX = event.rawX
                currentJoyY = event.rawY
                ringParams.leftMargin = (currentJoyX - reticleRadiusPx).toInt().coerceIn(0, screenWidth - reticleDiameterPx)
                ringParams.topMargin = (currentJoyY - reticleRadiusPx).toInt().coerceIn(0, screenHeight - reticleDiameterPx)
                joyRing.layoutParams = ringParams
                triggerHapticFeedback(25L)
                true
            } else {
                false
            }
        }

        // Lock & Start Button
        val saveBtn = TextView(this).apply {
            text = "✓ LOCK & ACTIVATE (READY)"
            setTextColor(android.graphics.Color.BLACK)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            val btnBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(255, 5, 255, 161))
                cornerRadius = 18 * resources.displayMetrics.density
            }
            background = btnBg
            val p = (14 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            val lp = FrameLayout.LayoutParams((260 * resources.displayMetrics.density).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (48 * resources.displayMetrics.density).toInt()
            }
            layoutParams = lp
            setOnClickListener {
                val normX = (currentJoyX / screenWidth.toFloat()).coerceIn(0.05f, 0.95f)
                val normY = (currentJoyY / screenHeight.toFloat()).coerceIn(0.10f, 0.95f)
                prefs.updateJoystickCalibration(normX, normY, reticleRadiusPx)
                triggerHapticFeedback(80L)
                removeCalibrationOverlay()
                updateBubbleUi(BubbleState.READY)
            }
        }
        rootOverlay.addView(saveBtn)

        calibrationOverlayView = rootOverlay
        windowManager.addView(rootOverlay, overlayParams)
        triggerHapticFeedback(40L)
    }

    private fun removeCalibrationOverlay() {
        calibrationOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Ignore
            }
            calibrationOverlayView = null
        }
    }

    private fun triggerGeminiAiScan() {
        val latestBitmap = acquireCurrentFrameBitmap()
        if (latestBitmap == null) {
            _stats.value = _stats.value.copy(
                latestTacticalAdvice = "AI Vision: Waiting for screen frame buffer..."
            )
            return
        }

        serviceScope.launch {
            _stats.value = _stats.value.copy(
                latestTacticalAdvice = "AI Vision: Contacting Gemini Flash for tactical evaluation..."
            )
            val advice = geminiAdvisor.analyzeGameScene(latestBitmap, activeGame)
            _stats.value = _stats.value.copy(
                latestTacticalAdvice = advice
            )
        }
    }

    private fun startDetectionLoop() {
        serviceScope.launch(Dispatchers.Default) {
            while (isActive) {
                val loopStartTime = System.currentTimeMillis()
                val profile = prefs.currentProfile.value

                if (profile.autoDodgeEnabled && currentBubbleState != BubbleState.PAUSED && prefs.isJoystickCalibrated()) {
                    val frameBitmap = acquireCurrentFrameBitmap()
                    if (frameBitmap != null) {
                        val threat = threatDetector.analyzeFrame(
                            frame = frameBitmap,
                            profile = profile,
                            screenWidth = screenWidth,
                            screenHeight = screenHeight
                        )

                        if (threat != null && (threat.threatLevel == ThreatLevel.IMMINENT_DANGER || threat.threatLevel == ThreatLevel.LETHAL)) {
                            totalThreats++
                            executeAutoDodge(threat, profile)
                        }

                        // FPS calculation
                        fpsCounter++
                        val now = System.currentTimeMillis()
                        if (now - lastFpsTimestamp >= 1000L) {
                            val currentFps = fpsCounter
                            fpsCounter = 0
                            lastFpsTimestamp = now

                            _stats.value = _stats.value.copy(
                                isRunning = true,
                                fps = currentFps,
                                threatsDetected = totalThreats,
                                dodgesExecuted = totalDodges,
                                currentThreatLevel = threat?.threatLevel ?: ThreatLevel.SAFE,
                                latencyMs = (now - loopStartTime),
                                isJoystickCalibrated = true,
                                isPlayerCalibrated = true,
                                activeGamePackage = activeGame
                            )
                        }
                    }
                }

                // Target ~30-60 FPS scan loop
                delay(20L)
            }
        }
    }

    private var reusableBitmap: Bitmap? = null

    private fun acquireCurrentFrameBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        var image: Image? = null
        return try {
            image = reader.acquireLatestImage() ?: return null
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val targetWidth = image.width + rowPadding / pixelStride
            val targetHeight = image.height

            var bmp = reusableBitmap
            if (bmp == null || bmp.width != targetWidth || bmp.height != targetHeight || bmp.isRecycled) {
                bmp = Bitmap.createBitmap(
                    targetWidth,
                    targetHeight,
                    Bitmap.Config.ARGB_8888
                )
                reusableBitmap = bmp
            }
            buffer.rewind()
            bmp.copyPixelsFromBuffer(buffer)
            bmp
        } catch (e: Exception) {
            null
        } finally {
            image?.close()
        }
    }

    private fun executeAutoDodge(threat: ThreatVector, profile: DodgeProfile) {
        val now = System.currentTimeMillis()
        if (now - lastDodgeTimestamp < profile.dodgeCooldownMs) {
            // Still in cooldown window from previous dodge maneuver
            return
        }
        lastDodgeTimestamp = now

        // Calculate absolute joystick pixel center
        val joyCenterX = profile.joystickCenterX * screenWidth
        val joyCenterY = profile.joystickCenterY * screenHeight

        // Push analog stick along optimal escape vector
        val strokeDistance = profile.joystickRadius * profile.dodgeDistanceFactor
        val targetX = joyCenterX + threat.dodgeDirX * strokeDistance
        val targetY = joyCenterY + threat.dodgeDirY * strokeDistance

        val success = RenderaAccessibilityService.executeDodgeGesture(
            startX = joyCenterX,
            startY = joyCenterY,
            endX = targetX,
            endY = targetY,
            durationMs = profile.dodgeDurationMs
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

        if (success) {
            serviceScope.launch(Dispatchers.Main) {
                updateBubbleUi(BubbleState.DODGING)
                delay(280L)
                updateBubbleUi(BubbleState.READY)
            }
            if (profile.soundHapticEnabled) {
                triggerHapticFeedback(80L)
            }
        }
    }

    private fun triggerHapticFeedback(durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vibrator.vibrate(durationMs)
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()

        removeCalibrationOverlay()
        removeHudMenu()

        floatingBubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Ignore
            }
            floatingBubbleView = null
        }

        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null

        _stats.value = DetectionStats()
        Log.i(TAG, "RenderaOverlayService stopped and destroyed.")
    }
}
