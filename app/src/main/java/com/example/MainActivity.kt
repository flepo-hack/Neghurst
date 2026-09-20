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
import androidx.compose.material.icons.filled.PlayArrow
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
import com.example.model.GameAppInfo
import com.example.service.RenderaAccessibilityService
import com.example.service.RenderaOverlayService
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.RenderaTheme
import com.example.ui.theme.SafeGreen
import com.example.ui.theme.ThreatRed
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private lateinit var prefs: RenderaPreferences
    private lateinit var appsRepo: InstalledAppsRepository
    private var mediaProjectionManager: MediaProjectionManager? = null

    private var pendingGameToLaunch: GameAppInfo? = null
    private var screenCaptureResultCode: Int = 0
    private var screenCaptureData: Intent? = null

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            screenCaptureResultCode = result.resultCode
            screenCaptureData = result.data
            Toast.makeText(this, "Screen capture authorized successfully!", Toast.LENGTH_SHORT).show()

            val game = pendingGameToLaunch
            if (game != null) {
                startOverlayAndLaunchGame(game)
                pendingGameToLaunch = null
            } else {
                startOverlayService("Universal")
            }
        } else {
            Toast.makeText(this, "Screen capture permission is required for vision detection.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        prefs = RenderaPreferences(this)
        appsRepo = InstalledAppsRepository(this)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setContent {
            RenderaTheme {
                MainContent()
            }
        }
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Rendera bubble active on screen!", Toast.LENGTH_SHORT).show()
    }

    private fun stopOverlayService() {
        val intent = Intent(this, RenderaOverlayService::class.java).apply {
            action = RenderaOverlayService.ACTION_STOP
        }
        startService(intent)
        Toast.makeText(this, "Rendera bubble stopped.", Toast.LENGTH_SHORT).show()
    }

    private fun startOverlayAndLaunchGame(game: GameAppInfo) {
        startOverlayService(game.appName, game.packageName)
        if (game.packageName.isNotEmpty()) {
            appsRepo.launchApp(game.packageName)
        }
    }

    @Composable
    private fun MainContent() {
        var hasOverlay by remember { mutableStateOf(checkOverlayPermission()) }
        var hasAccessibility by remember { mutableStateOf(checkAccessibilityPermission()) }
        var hasMediaProjection by remember { mutableStateOf(screenCaptureResultCode != 0) }

        val profile by prefs.currentProfile.collectAsState()
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

                        // Status pill
                        val isRunning = RenderaOverlayService.isRunning
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
                                text = if (isRunning) "● OVERLAY ACTIVE ON SCREEN" else "○ RENDERA IDLE",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isRunning) SafeGreen else Color(0xFFE0AAFF)
                            )
                        }

                        Spacer(modifier = Modifier.height(30.dp))

                        // LARGE PROMINENT START / STOP BUTTON
                        Button(
                            onClick = {
                                if (isRunning) {
                                    stopOverlayService()
                                } else {
                                    showGameSelectDialog = true
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
                                InstructionRow(num = "1", text = "Press START and pick your game (e.g. Brawl Stars).")
                                InstructionRow(num = "2", text = "In-game, tap the floating bubble to calibrate your movement joystick.")
                                InstructionRow(num = "3", text = "After calibration, a single tap pauses or resumes auto-dodge.")
                                InstructionRow(num = "4", text = "Long-press the bubble anytime to open the settings menu.")
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
                                        text = "${(profile.sensitivity * 100).toInt()}%",
                                        color = NeonCyan,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Slider(
                                    value = profile.sensitivity,
                                    onValueChange = { newVal ->
                                        prefs.updateSensitivity(newVal)
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
                                            iconChar = "⚡",
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
                                            iconChar = "🎮",
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
        iconChar: String,
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
                Text(
                    text = iconChar,
                    fontSize = 22.sp
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
