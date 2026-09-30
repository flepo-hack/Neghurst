package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.InstalledAppsRepository
import com.example.data.RenderaPreferences
import com.example.vision.AnchorCalibrator
import com.example.vision.NativeVisionProbe
import com.example.model.GameAppInfo
import com.example.service.RenderaAccessibilityService
import com.example.service.RenderaOverlayService
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.RenderaTheme
import com.example.vision.DiagnosticsExport
import com.example.ui.theme.SafeGreen
import com.example.ui.theme.ThreatRed
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.delay

private const val REQUEST_OVERLAY = 4712

class MainActivity : ComponentActivity() {

    private lateinit var prefs: RenderaPreferences
    private lateinit var appsRepo: InstalledAppsRepository
    private var mediaProjectionManager: MediaProjectionManager? = null

    private var pendingGameToLaunch: GameAppInfo? = null
    private var screenCaptureResultCode: Int = 0
    private var screenCaptureData: Intent? = null

    /**
     * The service's observable state, held as a field so the Activity methods can
     * read it as well as the composable. Reading a composable local from an
     * Activity method does not compile, and duplicating the state would let the
     * two disagree.
     */
    private val serviceStatusFlow by lazy { RenderaOverlayService.status }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            screenCaptureResultCode = result.resultCode
            screenCaptureData = result.data
            Toast.makeText(this, "Screen capture authorized successfully!", Toast.LENGTH_SHORT).show()

            // The game the user was picking is carried through the consent
            // dialog and started here, so the app is only ever launched once a
            // token actually exists.
            val game = pendingGameToLaunch
            pendingGameToLaunch = null
            if (game != null) {
                startOverlayAndLaunchGame(game)
            } else {
                startOverlayAndLaunchGame(GameAppInfo("Universal", ""))
            }
        } else {
            Toast.makeText(this, "Screen capture permission is required for vision detection.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Bumped on every resume so the permission rows re-read themselves. */
    private var resumeTickState = mutableIntStateOf(0)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OVERLAY) return
        val game = pendingGameToLaunch ?: return
        if (!checkOverlayPermission()) {
            Toast.makeText(
                this,
                "Overlay permission was not granted, so nothing can be drawn.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        startOverlayAndLaunchGame(game)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        prefs = RenderaPreferences.get(this)
        appsRepo = InstalledAppsRepository(this)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setContent {
            RenderaTheme {
                MainContent()
            }
        }
        handleCaptureRequest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // The bubble asks for a fresh grant after a capture ends. On Android 14+
        // the token is single use, so a new consent dialog is unavoidable; this
        // is the only place that can be shown.
        handleCaptureRequest(intent)
    }

    override fun onResume() {
        super.onResume()
        resumeTickState.intValue++
    }

    /**
     * Offers a fresh screen-capture grant when the overlay asked for one.
     *
     * The bubble cannot re-acquire a MediaProjection itself: on Android 14+ the
     * token is single use, and the consent dialog needs an Activity. It sends
     * this extra instead of trying and failing silently.
     */
    private fun handleCaptureRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(RenderaOverlayService.EXTRA_NEEDS_CAPTURE, false) != true) {
            return
        }
        intent.removeExtra(RenderaOverlayService.EXTRA_NEEDS_CAPTURE)
        // The old token is spent whether or not it was used.
        screenCaptureResultCode = 0
        screenCaptureData = null
        Toast.makeText(
            this,
            "Screen capture had ended. Grant it again to resume.",
            Toast.LENGTH_LONG
        ).show()
        requestMediaProjection()
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun checkAccessibilityPermission(): Boolean {
        return RenderaAccessibilityService.isAvailable()
    }

    private fun requestMediaProjection() {
        val captureIntent = mediaProjectionManager?.createScreenCaptureIntent()
        if (captureIntent != null) {
            mediaProjectionLauncher.launch(captureIntent)
        }
    }

    private fun startOverlayService(gameName: String, packageName: String = "") {
        val intent = Intent(this, RenderaOverlayService::class.java).apply {
            action = RenderaOverlayService.ACTION_START
            putExtra(RenderaOverlayService.EXTRA_RESULT_CODE, screenCaptureResultCode)
            putExtra(RenderaOverlayService.EXTRA_DATA_INTENT, screenCaptureData)
            putExtra(RenderaOverlayService.EXTRA_GAME_NAME, gameName)
            putExtra(RenderaOverlayService.EXTRA_PACKAGE_NAME, packageName)
        }

        // The token is single use. Clear it the moment it has been handed over,
        // so a later start asks for a fresh grant rather than replaying a spent
        // one, which fails on Android 14 and later and used to be reported as
        // "please grant screen recording" after the user had already granted it.
        val hadToken = screenCaptureResultCode != 0
        screenCaptureResultCode = 0
        screenCaptureData = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(
            this,
            if (hadToken) "Starting capture..." else "Grant screen recording first",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Shares the diagnostics from the app itself.
     *
     * Also in the in-game menu, but if the capture never starts there is no
     * bubble to long press - and in that situation the log is the only evidence of
     * what went wrong. Text only, never a file URI: `ACTION_SEND` with a
     * `Uri.fromFile` stream throws FileUriExposedException on Android 7 and later.
     */
    private fun shareDiagnosticsFromActivity() {
        try {
            val status = serviceStatusFlow.value
            val log = java.io.File(
                getExternalFilesDir(null) ?: filesDir,
                "rendera-events.jsonl"
            )
            val summary = buildString {
                appendLine("Rendera ${BuildConfig.VERSION_NAME} on " +
                    "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                    "Android ${android.os.Build.VERSION.SDK_INT}")
                appendLine("  capture format    ${status.captureFormat}")
                appendLine("  service running    ${status.running}")
                appendLine("  capture running    ${status.capturing}")
                appendLine("  native engine      ${status.nativeAvailable}")
                appendLine("  accessibility     ${status.accessibilityReady}")
                appendLine("  anchors            ${status.anchorsCalibrated}")
                appendLine("  target             ${status.targetPackage}")
                appendLine("  foreground app     ${status.foregroundPackage}")
                appendLine("  fps                ${status.fps}")
                appendLine("  capture format    ${status.captureFormat}")
                status.stopReason?.let { appendLine("  last stop reason   $it") }
                if (!status.running) {
                    appendLine("  NOTE: 'native engine false' here just means the service is")
                    appendLine("  not running, not that the library failed to load.")
                }
                appendLine("  full log: ${log.absolutePath} (${log.length()} bytes)")
                // A crash recorder writes here, because a crash is the one
                // failure that leaves nothing else behind.
                val crash = java.io.File(
                    getExternalFilesDir(null) ?: filesDir, "rendera-crash.txt"
                )
                if (crash.exists() && crash.length() > 0) {
                    appendLine("  crash log: ${crash.absolutePath} (${crash.length()} bytes)")
                }
            }
            val report = DiagnosticsExport.buildReport(
                summary,
                runCatching { log.readText() }.getOrNull()
            )
            // Downloads, not the app-private directory: on Android 11 and later
            // /Android/data/<pkg> is unreachable without root, which made the one
            // file that explains a failure the one file nobody could open.
            val result = DiagnosticsExport.export(this, "rendera-diagnostics", report)
            if (result.ok) {
                Toast.makeText(this, "Saved to ${result.path}", Toast.LENGTH_LONG).show()
            }
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Rendera diagnostics")
                        putExtra(Intent.EXTRA_TEXT, report)
                    },
                    "Send Rendera diagnostics"
                )
            )
        } catch (t: Throwable) {
            android.util.Log.w("Rendera", "Could not share diagnostics", t)
            Toast.makeText(this, "Could not export diagnostics", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopOverlayService() {
        val intent = Intent(this, RenderaOverlayService::class.java).apply {
            action = RenderaOverlayService.ACTION_STOP
        }
        startService(intent)
        Toast.makeText(this, "Rendera bubble stopped.", Toast.LENGTH_SHORT).show()
    }

    /**
     * Starts capture and the service, in that order, then launches the game.
     *
     * The capture consent dialog was never shown from here: the game was
     * launched immediately, the service started with no token, and the system
     * dialog the user was told to expect never appeared. The token is handed over
     * by [mediaProjectionLauncher] on success, which calls straight back into
     * here with [pendingGameToLaunch] set, so the game is only launched once
     * consent actually exists.
     */
    private fun startOverlayAndLaunchGame(game: GameAppInfo) {
        if (!checkOverlayPermission()) {
            requestOverlayPermissionThenContinue(game)
            return
        }
        if (screenCaptureResultCode == 0 || screenCaptureData == null) {
            Toast.makeText(
                this,
                "Rendera needs screen capture. Approve it to continue.",
                Toast.LENGTH_LONG
            ).show()
            pendingGameToLaunch = game
            requestMediaProjection()
            return
        }
        startOverlayService(game.appName, game.packageName)
        // The game is NOT launched from here. `startForegroundService` is
        // asynchronous, so launching the game the instant it returns backgrounds
        // the process while the service is still starting. From Android 12 an FGS
        // start from the background is refused, and the run dies with
        // `ForegroundServiceDidNotStartInTimeException` - a crash that leaves
        // nothing in the app's own log, which is exactly the report this fixes.
        // RenderaOverlayService launches the game itself, once the projection is
        // live and it holds the foreground.
    }

    /**
     * Asks for the overlay permission and continues once it is granted.
     *
     * The overlay is what draws the bubble and the calibration screen, so
     * without it the app is a launcher for a service that can draw nothing.
     */
    private fun requestOverlayPermissionThenContinue(game: GameAppInfo) {
        Toast.makeText(
            this,
            "Rendera needs permission to draw over other apps.",
            Toast.LENGTH_LONG
        ).show()
        try {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQUEST_OVERLAY
            )
        } catch (t: Throwable) {
            Toast.makeText(this, "Could not open the overlay settings", Toast.LENGTH_LONG).show()
        }
    }

    @Composable
    private fun MainContent() {
        // `remember` without a key is evaluated once and then frozen, so
        // returning from the Settings screen to enable the accessibility service
        // left the row reading "not granted" forever. The resume tick makes the
        // permissions re-read every time the Activity comes forward.
        val resumeTick = resumeTickState.intValue
        var hasOverlay by remember { mutableStateOf(checkOverlayPermission()) }
        var hasAccessibility by remember { mutableStateOf(checkAccessibilityPermission()) }
        var hasMediaProjection by remember { mutableStateOf(screenCaptureResultCode != 0) }

        // The service publishes observable state; a bare `var` read during
        // composition never triggers a recomposition, so the status pill used to
        // show whatever was true the first time the screen was drawn.
        val serviceStatus by serviceStatusFlow.collectAsState()

        // Re-read the permission rows whenever the Activity resumes.
        LaunchedEffect(resumeTick) {
            hasOverlay = checkOverlayPermission()
            hasAccessibility = checkAccessibilityPermission()
            hasMediaProjection = screenCaptureResultCode != 0
        }

        // True only when frames are actually arriving AND the engine can use
        // them. "Service alive" is not the same thing and conflating them is how
        // the app looked healthy while detecting nothing.
        val isRunning = serviceStatus.running && serviceStatus.capturing

        // Read the flows off the shared singleton so the slider, the in-game
        // menu and the vision engine can never disagree. The previous code gave
        // MainActivity and the service their own instances, so writes from one
        // were invisible to the other until the process restarted.
        val sensitivity by prefs.sensitivity.collectAsState()
        val isDebugOverlayEnabled by prefs.debugOverlayEnabled.collectAsState()
        val anchors by prefs.anchors.collectAsState()
        val nativeAvailable = remember { NativeVisionProbe.isNativeAvailable() }
        var installedApps by remember { mutableStateOf<List<GameAppInfo>>(emptyList()) }
        var showGameSelectDialog by remember { mutableStateOf(false) }

        // Periodic permission sync
        LaunchedEffect(Unit) {
            while (true) {
                hasOverlay = checkOverlayPermission()
                hasAccessibility = checkAccessibilityPermission()
                hasMediaProjection = (screenCaptureResultCode != 0)
                delay(1200L)
            }
        }

        // Load installed games
        LaunchedEffect(Unit) {
            installedApps = appsRepo.getInstalledGamesAndApps()
        }

        // Background with glowing deep purple gradient
        val purpleFlowBg = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF0C0314),
                Color(0xFF1E0630),
                Color(0xFF2A0845),
                Color(0xFF130424),
                Color(0xFF09020F)
            )
        )

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(purpleFlowBg),
            containerColor = Color.Transparent
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(purpleFlowBg)
                    .padding(innerPadding)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    item {
                        Spacer(modifier = Modifier.height(28.dp))

                        // CENTER "R" MONOGRAM WITH AMBIENT PURPLE FLOW GLOW
                        Box(
                            modifier = Modifier
                                .size(130.dp)
                                .shadow(
                                    elevation = 28.dp,
                                    shape = CircleShape,
                                    ambientColor = ElectricViolet,
                                    spotColor = Color(0xFFC77DFF)
                                )
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Color(0xFF9D4EDD),
                                            Color(0xFF5A189A),
                                            Color(0xFF240046),
                                            Color(0xFF10002B)
                                        )
                                    ),
                                    shape = CircleShape
                                )
                                .border(
                                    width = 3.dp,
                                    brush = Brush.sweepGradient(
                                        listOf(
                                            Color(0xFFE0AAFF),
                                            ElectricViolet,
                                            NeonCyan,
                                            Color(0xFFE0AAFF)
                                        )
                                    ),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "R",
                                fontSize = 68.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "RENDERA",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = 4.sp
                        )

                        Text(
                            text = "AUTO-DODGE & VISION ENGINE",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NeonCyan,
                            letterSpacing = 2.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Status pill. `isRunning` is the observed value from
                        // above, NOT a second read of the service's bare var,
                        // which is what froze this pill in the first place.
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (isRunning) Color(0xFF0F3822) else Color(0xFF2B1B3D))
                                .border(
                                    BorderStroke(
                                        1.dp,
                                        if (isRunning) SafeGreen else ElectricViolet
                                    ),
                                    RoundedCornerShape(20.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                // Ordered by the sequence the stages actually
                                // run in, because the first wrong thing is the only
                                // one worth reading. Leading with "engine missing"
                                // whenever the detector had not been created yet
                                // named a missing library that was present.
                                text = when {
                                        // Not running is checked first and names
                                        // the cause when there is one, because
                                        // "service running: false" on its own
                                        // cannot distinguish never-started from
                                        // stopped from killed, and those need
                                        // three different fixes.
                                        !serviceStatus.running -> serviceStatus.stopReason
                                            ?.let { "○ STOPPED: ${it.take(28)}" }
                                            ?: "○ SERVICE NOT RUNNING"
                                        !serviceStatus.capturing ->
                                            "○ NOT CAPTURING - TAP THE BUBBLE"
                                        !serviceStatus.nativeAvailable ->
                                            "○ ENGINE NOT LOADED ON THIS DEVICE"
                                        !serviceStatus.anchorsCalibrated ->
                                            "● CAPTURING - SET THE ANCHORS"
                                        !serviceStatus.accessibilityReady ->
                                            "● ENABLE THE ACCESSIBILITY SERVICE"
                                        serviceStatus.suppressedByBackground ->
                                            "● ${serviceStatus.targetPackage} NOT IN FRONT"
                                        else -> "● ARMED - DETECTING"
                                    },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                        isRunning -> SafeGreen
                                        serviceStatus.running -> Color(0xFFFFB44D)
                                        else -> Color(0xFFE0AAFF)
                                    }
                            )
                        }

                        Spacer(modifier = Modifier.height(30.dp))

                        // LARGE PROMINENT START / STOP BUTTON
                        Button(
                            onClick = {
                                if (isRunning) {
                                    stopOverlayService()
                                } else {
                                    // Universal: no game to launch, but the
                                    // consent dialog is still required, and it
                                    // used to be skipped on this path.
                                    pendingGameToLaunch = null
                                    startOverlayAndLaunchGame(
                                        GameAppInfo("Universal", "")
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .height(68.dp)
                                .shadow(
                                    elevation = 20.dp,
                                    shape = RoundedCornerShape(18.dp),
                                    spotColor = if (isRunning) ThreatRed else ElectricViolet,
                                    ambientColor = if (isRunning) ThreatRed else ElectricViolet
                                ),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRunning) ThreatRed else ElectricViolet
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = if (isRunning) "STOP" else "START",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 2.sp,
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(26.dp))

                        // SIMPLE HOW TO USE CARD
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF170C29))
                                .border(BorderStroke(1.dp, Color(0xFF4A1E73)), RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            Column {
                                Text(
                                    text = "HOW TO USE",
                                    color = ElectricViolet,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                InstructionRow(num = "1", text = "Press START and select your target game from the list.")
                                InstructionRow(num = "2", text = "In-game, long-press the bubble and open CALIBRATE ANCHORS. Tap the playfield to place the joystick, tap your brawler to set the player, then press LOCK & ACTIVATE.")
                                InstructionRow(num = "3", text = "A single tap on the bubble arms or pauses auto-dodge. Drag the bubble to move it out of the way.")
                                InstructionRow(num = "4", text = "Rotate the device? The anchors are re-validated, and you re-lock them for the new orientation.")
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // SENSITIVITY SLIDER
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF170C29))
                                .border(BorderStroke(1.dp, Color(0xFF4A1E73)), RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "DODGE SENSITIVITY",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${(sensitivity * 100).toInt()}%",
                                        color = NeonCyan,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Slider(
                                    value = sensitivity,
                                    onValueChange = { newVal ->
                                        prefs.setSensitivity(newVal)
                                    },
                                    valueRange = 0.1f..1.0f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = NeonCyan,
                                        activeTrackColor = ElectricViolet,
                                        inactiveTrackColor = Color(0xFF331D56)
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // TACTICAL RADAR & IN-GAME DEBUG HUD CARD
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF170C29))
                                .border(BorderStroke(1.dp, Color(0xFF00F0FF)), RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "TACTICAL RADAR & DEBUG HUD",
                                            color = NeonCyan,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Visuaalinen tähtäin ja laatikot suoraan peliin: oma hahmo (vihreä), joystick ja väistövektori (violetti), viholliset (punainen), ammukset (keltainen).",
                                            color = Color(0xFFC7B8E0),
                                            fontSize = 11.sp,
                                            lineHeight = 15.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(if (isDebugOverlayEnabled) Color(0xFF0F3822) else Color(0xFF2B1B3D))
                                            .border(
                                                BorderStroke(
                                                    1.5.dp,
                                                    if (isDebugOverlayEnabled) SafeGreen else Color(0xFF6B4C8A)
                                                ),
                                                RoundedCornerShape(20.dp)
                                            )
                                            .clickable {
                                                prefs.setDebugOverlayEnabled(!isDebugOverlayEnabled)
                                            }
                                            .padding(horizontal = 14.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = if (isDebugOverlayEnabled) "PÄÄLLÄ" else "POIS",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isDebugOverlayEnabled) SafeGreen else Color(0xFFE0AAFF)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // ENGINE STATUS: this is the single most useful thing to
                        // surface. A build without the compiled native engine
                        // cannot detect anything, and the previous UI looked
                        // perfectly healthy in that state.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (nativeAvailable) Color(0xFF0F3822) else Color(0xFF3A1414))
                                .border(
                                    BorderStroke(
                                        1.dp,
                                        if (nativeAvailable) SafeGreen else ThreatRed
                                    ),
                                    RoundedCornerShape(16.dp)
                                )
                                .padding(16.dp)
                        ) {
                            Column {
                                Text(
                                    text = "VISION ENGINE",
                                    color = if (nativeAvailable) SafeGreen else ThreatRed,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = NativeVisionProbe.describe(),
                                    color = Color(0xFFD4C7E6),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = AnchorCalibrator.describe(anchors),
                                    color = if (anchors.calibrated) SafeGreen else WarningAmber,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // DIAGNOSTICS. Reachable here as well as in the in-game menu: if the
        // capture never starts there is no bubble to long press, and the log is
        // then the only way to find out why.
        Spacer(modifier = Modifier.height(18.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1A1526))
                .border(1.dp, ElectricViolet.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column {
                Text(
                    text = "DIAGNOSTICS",
                    color = ElectricViolet,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (serviceStatus.running) {
                        "Service is running. ${serviceStatus.fps} fps."
                    } else {
                        "Service is not running."
                    },
                    color = Color(0xFFD4C7E6),
                    fontSize = 11.sp
                )
                if (!serviceStatus.capturing) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Capture is NOT running. Send the log to find out why.",
                        color = Color(0xFFFFB44D),
                        fontSize = 11.sp
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { shareDiagnosticsFromActivity() },
                    modifier = Modifier.fillMaxWidth(0.92f).height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricViolet,
                        contentColor = Color.White
                    )
                ) {
                    Text("SEND DIAGNOSTICS", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // SYSTEM PERMISSIONS
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF170C29))
                                .border(BorderStroke(1.dp, Color(0xFF4A1E73)), RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            Column {
                                Text(
                                    text = "SYSTEM PERMISSIONS",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(10.dp))

                                PermissionStatusRow(
                                    title = "Display Over Other Apps",
                                    isGranted = hasOverlay,
                                    onFix = {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:$packageName")
                                        )
                                        startActivity(intent)
                                    }
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                PermissionStatusRow(
                                    title = "Accessibility (Dodge Gestures)",
                                    isGranted = hasAccessibility,
                                    onFix = {
                                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        startActivity(intent)
                                    }
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                PermissionStatusRow(
                                    title = "Screen Capture (Vision Engine)",
                                    isGranted = hasMediaProjection,
                                    onFix = {
                                        requestMediaProjection()
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(36.dp))
                    }
                }

                // GAME SELECT DIALOG
                if (showGameSelectDialog) {
                    Dialog(onDismissRequest = { showGameSelectDialog = false }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xFF1A0F2E))
                                .border(BorderStroke(2.dp, ElectricViolet), RoundedCornerShape(20.dp))
                                .padding(20.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "SELECT TARGET GAME",
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    IconButton(onClick = { showGameSelectDialog = false }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close",
                                            tint = Color.White
                                        )
                                    }
                                }

                                Text(
                                    text = "Select a game to launch with Rendera overlay enabled:",
                                    color = Color(0xFFC7B8E0),
                                    fontSize = 12.sp
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(280.dp)
                                ) {
                                    // Universal Option
                                    item {
                                        GamePickerOptionItem(
                                            title = "Universal (Current Game On-Screen)",
                                            subtitle = "Launch bubble immediately on active screen",
                                            icon = Icons.Default.Layers,
                                            onClick = {
                                                showGameSelectDialog = false
                                                launchTargetGame(null, hasOverlay, hasAccessibility)
                                            }
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }

                                    items(installedApps) { app ->
                                        GamePickerOptionItem(
                                            title = app.appName,
                                            subtitle = app.packageName,
                                            icon = Icons.Default.SportsEsports,
                                            onClick = {
                                                showGameSelectDialog = false
                                                launchTargetGame(app, hasOverlay, hasAccessibility)
                                            }
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun launchTargetGame(game: GameAppInfo?, hasOverlay: Boolean, hasAccessibility: Boolean) {
        if (!hasOverlay) {
            Toast.makeText(this, "Please enable Display Over Other Apps permission first!", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        if (!hasAccessibility) {
            Toast.makeText(this, "Please enable Rendera Accessibility Service to execute dodge movements!", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            return
        }

        if (screenCaptureResultCode == 0) {
            pendingGameToLaunch = game
            requestMediaProjection()
        } else {
            if (game != null) {
                startOverlayAndLaunchGame(game)
            } else {
                startOverlayService("Universal")
            }
        }
    }

    @Composable
    private fun InstructionRow(num: String, text: String) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3C185A)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = num,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE0AAFF)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                fontSize = 12.sp,
                color = Color(0xFFD4C7E6),
                lineHeight = 16.sp
            )
        }
    }

    @Composable
    private fun PermissionStatusRow(
        title: String,
        isGranted: Boolean,
        onFix: () -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isGranted) SafeGreen else WarningAmber,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    color = if (isGranted) Color.White else WarningAmber,
                    fontSize = 13.sp
                )
            }

            if (!isGranted) {
                Button(
                    onClick = onFix,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A1A59)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        text = "ENABLE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan
                    )
                }
            } else {
                Text(
                    text = "READY",
                    color = SafeGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    @Composable
    private fun GamePickerOptionItem(
        title: String,
        subtitle: String,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        onClick: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF23143F))
                .border(BorderStroke(1.dp, Color(0xFF4C2280)), RoundedCornerShape(12.dp))
                .clickable { onClick() }
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = subtitle,
                        color = Color(0xFFB1A2CC),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
