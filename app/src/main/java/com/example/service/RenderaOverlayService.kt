package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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
import com.example.ui.components.RenderaDebugHudView
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

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // Overlay views
    enum class BubbleState {
        READY,
        PAUSED,
        DODGING
    }

    private var currentBubbleState = BubbleState.READY
    private var floatingBubbleView: View? = null
    private var bubbleIconView: ImageView? = null
    private var bubbleLabelView: TextView? = null
    private var bubbleBgDrawable: android.graphics.drawable.GradientDrawable? = null
    private var hudMenuView: View? = null
    private var calibrationOverlayView: View? = null
    private var debugHudView: RenderaDebugHudView? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    private var lastDodgeTimestamp = 0L
    private var totalDodges = 0
    private var totalThreats = 0
    private var fpsCounter = 0
    private var lastFpsTimestamp = System.currentTimeMillis()
    private var activeGame = "Universal"

    fun showDebugHud() {
        if (debugHudView != null) return
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val hudParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        val hud = RenderaDebugHudView(this)
        debugHudView = hud
        try {
            windowManager.addView(hud, hudParams)
            Log.i(TAG, "Rendera Tactical Debug HUD Overlay successfully attached.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach Debug HUD Overlay", e)
            debugHudView = null
        }
    }

    fun removeDebugHud() {
        debugHudView?.let {
            try {
                windowManager.removeView(it)
                Log.i(TAG, "Rendera Tactical Debug HUD Overlay removed.")
            } catch (e: Exception) {
                Log.w(TAG, "Error removing debug HUD view", e)
            }
            debugHudView = null
        }
    }

    fun toggleDebugHud() {
        val newState = !prefs.isDebugOverlayEnabled.value
        prefs.setDebugOverlayEnabled(newState)
        if (newState) {
            showDebugHud()
        } else {
            removeDebugHud()
        }
    }

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
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    private fun checkAndSyncDisplayMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        if (metrics.widthPixels != screenWidth || metrics.heightPixels != screenHeight) {
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
            serviceScope.launch(Dispatchers.Main) {
                recreateVirtualDisplay()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        fetchScreenDimensions()
        recreateVirtualDisplay()
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
                if (prefs.isDebugOverlayEnabled.value) {
                    showDebugHud()
                }
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
            .setContentText("Monitoring $activeGame: Tracking Green Ring & Joystick")
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
                recreateVirtualDisplay()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize MediaProjection", e)
            }
        }
    }

    private fun recreateVirtualDisplay() {
        val proj = mediaProjection ?: return
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null

            val isLandscape = screenWidth > screenHeight
            // 640x360 for landscape, or 360x640 for portrait
            val captureWidth = if (isLandscape) 640 else 360
            val captureHeight = ((screenHeight.toFloat() / screenWidth) * captureWidth).toInt().coerceAtLeast(180)

            imageReader = ImageReader.newInstance(
                captureWidth,
                captureHeight,
                PixelFormat.RGBA_8888,
                2
            )

            virtualDisplay = proj.createVirtualDisplay(
                "RenderaCapture",
                captureWidth,
                captureHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )
            Log.i(TAG, "VirtualDisplay created: $captureWidth x $captureHeight (isLandscape=$isLandscape)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to recreate VirtualDisplay", e)
        }
    }

    private fun updateBubbleUi(state: BubbleState) {
        currentBubbleState = state
        val icon = bubbleIconView ?: return
        val label = bubbleLabelView ?: return
        val bg = bubbleBgDrawable ?: return

        when (state) {
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

        val initialBubbleState = if (prefs.currentProfile.value.autoDodgeEnabled) BubbleState.READY else BubbleState.PAUSED
        updateBubbleUi(initialBubbleState)

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
                        triggerHapticFeedback(40L)
                        toggleMiniMenu(params.x, params.y)
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
            (290 * resources.displayMetrics.density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bubbleX + 70 * resources.displayMetrics.density).toInt().coerceAtMost(screenWidth - (300 * resources.displayMetrics.density).toInt())
            y = bubbleY.coerceAtMost(screenHeight - (400 * resources.displayMetrics.density).toInt())
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

            addView(TextView(this@RenderaOverlayService).apply {
                text = "RENDERA REAL-TIME RADAR"
                setTextColor(android.graphics.Color.argb(255, 157, 78, 221))
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER_HORIZONTAL
            })

            addView(TextView(this@RenderaOverlayService).apply {
                text = "Target: $activeGame (${if (screenWidth > screenHeight) "Landscape" else "Portrait"})"
                setTextColor(android.graphics.Color.argb(200, 180, 190, 210))
                textSize = 11f
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 2, 0, (10 * resources.displayMetrics.density).toInt())
            })

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

            // Calibration & Custom Position Overlay
            val calibBtn = TextView(this@RenderaOverlayService).apply {
                text = "CALIBRATE JOYSTICK & PLAYER"
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
                    showInteractiveCalibrationOverlay()
                }
            }
            addView(calibBtn)

            // Radar Debug HUD Toggle Button
            val isHudOn = prefs.isDebugOverlayEnabled.value
            val hudToggleBtn = TextView(this@RenderaOverlayService).apply {
                text = if (isHudOn) "RADAR DEBUG HUD: [PÄÄLLÄ]" else "RADAR DEBUG HUD: [POIS]"
                setTextColor(if (isHudOn) android.graphics.Color.argb(255, 5, 255, 161) else android.graphics.Color.argb(255, 200, 210, 225))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                val btnBg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.argb(140, 16, 28, 40))
                    cornerRadius = 12 * resources.displayMetrics.density
                    setStroke(1, if (isHudOn) android.graphics.Color.argb(200, 5, 255, 161) else android.graphics.Color.argb(120, 100, 120, 140))
                }
                background = btnBg
                val p = (10 * resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = (8 * resources.displayMetrics.density).toInt()
                layoutParams = lp
                setOnClickListener {
                    toggleDebugHud()
                    removeHudMenu()
                    triggerHapticFeedback(30L)
                }
            }
            addView(hudToggleBtn)

            // Fast Auto-Detect Button (Brawl Stars)
            val autoDetectBtn = TextView(this@RenderaOverlayService).apply {
                text = "SMART AUTO-DETECT (BRAWL STARS)"
                setTextColor(android.graphics.Color.argb(255, 255, 215, 0))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                val btnBg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.argb(170, 42, 32, 12))
                    cornerRadius = 12 * resources.displayMetrics.density
                    setStroke(1, android.graphics.Color.argb(230, 255, 215, 0))
                }
                background = btnBg
                val p = (10 * resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = (8 * resources.displayMetrics.density).toInt()
                layoutParams = lp
                setOnClickListener {
                    removeHudMenu()
                    runQuickAutoDetect()
                }
            }
            addView(autoDetectBtn)

            // Stop Rendera Button
            val stopBtn = TextView(this@RenderaOverlayService).apply {
                text = "STOP RENDERA"
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
                text = "CLOSE"
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

    private fun showInteractiveCalibrationOverlay() {
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
        val reticleRadiusPx = profile.joystickRadius.coerceAtLeast(120f)
        val reticleDiameterPx = (reticleRadiusPx * 2).toInt()

        var currentJoyX = profile.joystickCenterX * screenWidth
        var currentJoyY = profile.joystickCenterY * screenHeight
        var currentPlayerX = profile.playerCenterX * screenWidth
        var currentPlayerY = profile.playerCenterY * screenHeight

        var activeEditMode = "JOYSTICK" // "JOYSTICK" or "PLAYER"

        val rootOverlay = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.argb(135, 0, 0, 0))
        }

        // Joystick Reticle Ring (Cyan)
        val joyRing = FrameLayout(this).apply {
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.argb(70, 0, 240, 255))
                setStroke(4, android.graphics.Color.argb(255, 0, 240, 255), 10f, 8f)
            }
            background = bg

            val cross = TextView(this@RenderaOverlayService).apply {
                text = "JOYSTICK"
                textSize = 10f
                setTextColor(android.graphics.Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            addView(cross, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        val joyRingParams = FrameLayout.LayoutParams(reticleDiameterPx, reticleDiameterPx).apply {
            leftMargin = (currentJoyX - reticleRadiusPx).toInt().coerceIn(0, screenWidth - reticleDiameterPx)
            topMargin = (currentJoyY - reticleRadiusPx).toInt().coerceIn(0, screenHeight - reticleDiameterPx)
        }
        rootOverlay.addView(joyRing, joyRingParams)

        // Player Reticle Ring (Lime Green)
        val playerRadiusPx = 80f
        val playerDiameterPx = (playerRadiusPx * 2).toInt()
        val playerRing = FrameLayout(this).apply {
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.argb(80, 5, 255, 161))
                setStroke(4, android.graphics.Color.argb(255, 5, 255, 161))
            }
            background = bg

            val pText = TextView(this@RenderaOverlayService).apply {
                text = "PLAYER"
                textSize = 9f
                setTextColor(android.graphics.Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            addView(pText, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        val playerRingParams = FrameLayout.LayoutParams(playerDiameterPx, playerDiameterPx).apply {
            leftMargin = (currentPlayerX - playerRadiusPx).toInt().coerceIn(0, screenWidth - playerDiameterPx)
            topMargin = (currentPlayerY - playerRadiusPx).toInt().coerceIn(0, screenHeight - playerDiameterPx)
        }
        rootOverlay.addView(playerRing, playerRingParams)

        // Header Instructions & Mode Switcher
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.argb(235, 12, 16, 28))
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
            }
            layoutParams = lp

            addView(TextView(this@RenderaOverlayService).apply {
                text = "TARGET CALIBRATION & ANCHORS"
                setTextColor(android.graphics.Color.argb(255, 0, 240, 255))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            })

            val subLabel = TextView(this@RenderaOverlayService).apply {
                text = "Touch screen to position the JOYSTICK anchor. (Green Ring auto-tracks player in real time!)"
                setTextColor(android.graphics.Color.argb(230, 210, 225, 245))
                textSize = 12f
                gravity = Gravity.CENTER
                val topP = (4 * resources.displayMetrics.density).toInt()
                setPadding(0, topP, 0, (8 * resources.displayMetrics.density).toInt())
            }
            addView(subLabel)

            // Switcher buttons
            val switchRow = LinearLayout(this@RenderaOverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            val joyTabBtn = TextView(this@RenderaOverlayService).apply {
                text = "Edit Joystick"
                setTextColor(android.graphics.Color.BLACK)
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.argb(255, 0, 240, 255))
                    cornerRadius = 12 * resources.displayMetrics.density
                }
                background = bg
                val p = (8 * resources.displayMetrics.density).toInt()
                setPadding(p * 2, p, p * 2, p)
            }

            val playerTabBtn = TextView(this@RenderaOverlayService).apply {
                text = "Edit Player Center"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.argb(120, 255, 255, 255))
                    cornerRadius = 12 * resources.displayMetrics.density
                }
                background = bg
                val p = (8 * resources.displayMetrics.density).toInt()
                setPadding(p * 2, p, p * 2, p)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginStart = (12 * resources.displayMetrics.density).toInt()
                layoutParams = lp
            }

            switchRow.addView(joyTabBtn)
            switchRow.addView(playerTabBtn)
            addView(switchRow)

            val autoDetectBtn = TextView(this@RenderaOverlayService).apply {
                text = "SMART AUTO-DETECT (Tunnista peli ruudulta)"
                setTextColor(android.graphics.Color.argb(255, 255, 215, 0))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.argb(170, 42, 32, 12))
                    cornerRadius = 12 * resources.displayMetrics.density
                    setStroke(1, android.graphics.Color.argb(230, 255, 215, 0))
                }
                background = bg
                val p = (8 * resources.displayMetrics.density).toInt()
                setPadding(p * 2, p, p * 2, p)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = (10 * resources.displayMetrics.density).toInt()
                layoutParams = lp
                setOnClickListener {
                    val frame = acquireCurrentFrameBitmap()
                    if (frame != null) {
                        val (joy, player) = threatDetector.autoCalibrateFromFrame(
                            frame = frame,
                            screenWidth = screenWidth,
                            screenHeight = screenHeight,
                            activeWidth = activeCaptureWidth,
                            activeHeight = activeCaptureHeight
                        )
                        currentJoyX = joy.first
                        currentJoyY = joy.second
                        currentPlayerX = player.first
                        currentPlayerY = player.second
                    } else {
                        currentJoyX = 0.20f * screenWidth
                        currentJoyY = 0.78f * screenHeight
                        currentPlayerX = 0.50f * screenWidth
                        currentPlayerY = 0.50f * screenHeight
                    }
                    joyRingParams.leftMargin = (currentJoyX - reticleRadiusPx).toInt().coerceIn(0, screenWidth - reticleDiameterPx)
                    joyRingParams.topMargin = (currentJoyY - reticleRadiusPx).toInt().coerceIn(0, screenHeight - reticleDiameterPx)
                    joyRing.layoutParams = joyRingParams

                    playerRingParams.leftMargin = (currentPlayerX - playerRadiusPx).toInt().coerceIn(0, screenWidth - playerDiameterPx)
                    playerRingParams.topMargin = (currentPlayerY - playerRadiusPx).toInt().coerceIn(0, screenHeight - playerDiameterPx)
                    playerRing.layoutParams = playerRingParams

                    subLabel.text = "Automaattisesti kalibroitu Brawl Starsille. Voit edelleen säätää koskettamalla."
                    triggerHapticFeedback(50L)
                }
            }
            addView(autoDetectBtn)

            joyTabBtn.setOnClickListener {
                activeEditMode = "JOYSTICK"
                subLabel.text = "Touch screen to move the JOYSTICK anchor."
                (joyTabBtn.background as? android.graphics.drawable.GradientDrawable)?.setColor(android.graphics.Color.argb(255, 0, 240, 255))
                joyTabBtn.setTextColor(android.graphics.Color.BLACK)
                (playerTabBtn.background as? android.graphics.drawable.GradientDrawable)?.setColor(android.graphics.Color.argb(120, 255, 255, 255))
                playerTabBtn.setTextColor(android.graphics.Color.WHITE)
                triggerHapticFeedback(20L)
            }

            playerTabBtn.setOnClickListener {
                activeEditMode = "PLAYER"
                subLabel.text = "Touch screen to move the DEFAULT PLAYER position (used if green ring is hidden)."
                (playerTabBtn.background as? android.graphics.drawable.GradientDrawable)?.setColor(android.graphics.Color.argb(255, 5, 255, 161))
                playerTabBtn.setTextColor(android.graphics.Color.BLACK)
                (joyTabBtn.background as? android.graphics.drawable.GradientDrawable)?.setColor(android.graphics.Color.argb(120, 255, 255, 255))
                joyTabBtn.setTextColor(android.graphics.Color.WHITE)
                triggerHapticFeedback(20L)
            }
        }
        rootOverlay.addView(header)

        // Screen Touch Drag
        rootOverlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                if (activeEditMode == "JOYSTICK") {
                    currentJoyX = event.rawX
                    currentJoyY = event.rawY
                    joyRingParams.leftMargin = (currentJoyX - reticleRadiusPx).toInt().coerceIn(0, screenWidth - reticleDiameterPx)
                    joyRingParams.topMargin = (currentJoyY - reticleRadiusPx).toInt().coerceIn(0, screenHeight - reticleDiameterPx)
                    joyRing.layoutParams = joyRingParams
                } else {
                    currentPlayerX = event.rawX
                    currentPlayerY = event.rawY
                    playerRingParams.leftMargin = (currentPlayerX - playerRadiusPx).toInt().coerceIn(0, screenWidth - playerDiameterPx)
                    playerRingParams.topMargin = (currentPlayerY - playerRadiusPx).toInt().coerceIn(0, screenHeight - playerDiameterPx)
                    playerRing.layoutParams = playerRingParams
                }
                triggerHapticFeedback(15L)
                true
            } else {
                false
            }
        }

        // Save Button
        val saveBtn = TextView(this).apply {
            text = "LOCK & ACTIVATE (READY)"
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
            val lp = FrameLayout.LayoutParams((280 * resources.displayMetrics.density).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (36 * resources.displayMetrics.density).toInt()
            }
            layoutParams = lp
            setOnClickListener {
                val normJoyX = (currentJoyX / screenWidth.toFloat()).coerceIn(0.05f, 0.95f)
                val normJoyY = (currentJoyY / screenHeight.toFloat()).coerceIn(0.05f, 0.95f)
                val normPlayerX = (currentPlayerX / screenWidth.toFloat()).coerceIn(0.05f, 0.95f)
                val normPlayerY = (currentPlayerY / screenHeight.toFloat()).coerceIn(0.05f, 0.95f)

                prefs.updateJoystickCalibration(normJoyX, normJoyY, reticleRadiusPx)
                prefs.updatePlayerCalibration(normPlayerX, normPlayerY)
                threatDetector.setManualJoystickCalibration(currentJoyX, currentJoyY)
                threatDetector.setManualPlayerCalibration(currentPlayerX, currentPlayerY)

                triggerHapticFeedback(70L)
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

    private fun runQuickAutoDetect() {
        val latestBitmap = acquireCurrentFrameBitmap()
        val (joy, player) = threatDetector.autoCalibrateFromFrame(
            frame = latestBitmap ?: Bitmap.createBitmap(screenWidth.coerceAtLeast(10), screenHeight.coerceAtLeast(10), Bitmap.Config.ARGB_8888),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            activeWidth = activeCaptureWidth,
            activeHeight = activeCaptureHeight
        )
        val normJoyX = (joy.first / screenWidth.toFloat()).coerceIn(0.05f, 0.95f)
        val normJoyY = (joy.second / screenHeight.toFloat()).coerceIn(0.05f, 0.95f)
        val normPlayerX = (player.first / screenWidth.toFloat()).coerceIn(0.05f, 0.95f)
        val normPlayerY = (player.second / screenHeight.toFloat()).coerceIn(0.05f, 0.95f)

        prefs.updateJoystickCalibration(normJoyX, normJoyY, 140f)
        prefs.updatePlayerCalibration(normPlayerX, normPlayerY)
        threatDetector.setManualJoystickCalibration(joy.first, joy.second)
        threatDetector.setManualPlayerCalibration(player.first, player.second)

        triggerHapticFeedback(70L)
        android.widget.Toast.makeText(
            this@RenderaOverlayService,
            "Calibrated for Brawl Stars: Joystick (${joy.first.toInt()}, ${joy.second.toInt()}), Player (${player.first.toInt()}, ${player.second.toInt()})",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun startDetectionLoop() {
        serviceScope.launch(Dispatchers.Default) {
            while (isActive) {
                val loopStartTime = System.currentTimeMillis()
                val profile = prefs.currentProfile.value

                checkAndSyncDisplayMetrics()

                if (profile.autoDodgeEnabled && currentBubbleState != BubbleState.PAUSED) {
                    val frameBitmap = acquireCurrentFrameBitmap()
                    if (frameBitmap != null) {
                        val result = threatDetector.analyzeFrame(
                            frame = frameBitmap,
                            profile = profile,
                            screenWidth = screenWidth,
                            screenHeight = screenHeight,
                            activeWidth = activeCaptureWidth,
                            activeHeight = activeCaptureHeight
                        )

                        if (result != null) {
                            val threat = result.threat
                            if (threat != null && (threat.threatLevel == ThreatLevel.IMMINENT_DANGER || threat.threatLevel == ThreatLevel.LETHAL)) {
                                totalThreats++
                                executeAutoDodge(threat, profile, result.joystickX, result.joystickY)
                            }

                            val loopElapsed = (System.currentTimeMillis() - loopStartTime)
                            val isAccActive = RenderaAccessibilityService.isAvailable()
                            debugHudView?.updateAnalysis(
                                result = result,
                                fps = if (fpsCounter > 0) fpsCounter else 60,
                                latencyMs = loopElapsed,
                                isAccessibilityActive = isAccActive,
                                isAutoDodgeEnabled = profile.autoDodgeEnabled
                            )

                            fpsCounter++
                            val now = System.currentTimeMillis()
                            if (now - lastFpsTimestamp >= 1000L) {
                                val currentFps = fpsCounter
                                fpsCounter = 0
                                lastFpsTimestamp = now

                                val statusAdvice = if (result.isPlayerGreenRingTracked) {
                                    "[LOCKED] Player @ (${result.playerX.toInt()}, ${result.playerY.toInt()}) | Joy: (${result.joystickX.toInt()}, ${result.joystickY.toInt()}) | Enemies: ${result.enemyCount}"
                                } else {
                                    "[SEARCHING] Scanning for Player | Joy: (${result.joystickX.toInt()}, ${result.joystickY.toInt()})"
                                }

                                _stats.value = _stats.value.copy(
                                    isRunning = true,
                                    fps = currentFps,
                                    threatsDetected = totalThreats,
                                    dodgesExecuted = totalDodges,
                                    currentThreatLevel = threat?.threatLevel ?: ThreatLevel.SAFE,
                                    latencyMs = (now - loopStartTime),
                                    isJoystickCalibrated = result.isJoystickTracked,
                                    isPlayerCalibrated = result.isPlayerGreenRingTracked,
                                    activeGamePackage = activeGame,
                                    latestTacticalAdvice = statusAdvice
                                )
                            }
                        }
                    }
                }

                delay(16L) // ~60 FPS scan loop
            }
        }
    }

    private var reusableBitmap: Bitmap? = null
    private var activeCaptureWidth: Int = 640
    private var activeCaptureHeight: Int = 360

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

            activeCaptureWidth = image.width
            activeCaptureHeight = image.height

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

    private fun executeAutoDodge(
        threat: ThreatVector,
        profile: DodgeProfile,
        joyCenterX: Float,
        joyCenterY: Float
    ) {
        val now = System.currentTimeMillis()
        if (now - lastDodgeTimestamp < profile.dodgeCooldownMs) {
            return
        }
        lastDodgeTimestamp = now

        // Calculate stroke displacement from dynamic joystick anchor
        val strokeDistance = profile.joystickRadius * profile.dodgeDistanceFactor
        val targetX = (joyCenterX + threat.dodgeDirX * strokeDistance).coerceIn(10f, screenWidth - 10f)
        val targetY = (joyCenterY + threat.dodgeDirY * strokeDistance).coerceIn(10f, screenHeight - 10f)

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
                delay(260L)
                updateBubbleUi(BubbleState.READY)
            }
            if (profile.soundHapticEnabled) {
                triggerHapticFeedback(75L)
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
        removeDebugHud()

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
