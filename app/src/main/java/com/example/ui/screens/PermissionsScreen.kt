package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.RenderaAccessibilityService
import com.example.service.RenderaOverlayService
import com.example.ui.components.TacticalCard
import com.example.ui.theme.BorderCyan
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SafeGreen
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.ThreatRed
import com.example.ui.theme.WarningAmber

@Composable
fun PermissionsScreen(
    hasOverlayPermission: Boolean,
    hasAccessibilityPermission: Boolean,
    hasMediaProjectionPermission: Boolean,
    onRequestMediaProjection: () -> Unit,
    onContinueToGames: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val allReady = hasOverlayPermission && hasAccessibilityPermission

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Shield Icon Header
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(NeonCyan.copy(alpha = 0.12f))
                .border(2.dp, NeonCyan, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = "Permissions",
                tint = NeonCyan,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "SYSTEM AUTHORIZATION",
            color = NeonCyan,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Rendera operates in real-time over games to read display threats and execute sub-second joystick dodge maneuvers. Grant the 2 system permissions below.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Permission Card 1: Draw Over Other Apps
        PermissionItemCard(
            title = "1. Floating Bubble & HUD Overlay",
            description = "Draws the floating assist bubble and joystick calibration reticle on top of active games.",
            icon = Icons.Default.Layers,
            isGranted = hasOverlayPermission,
            actionLabel = if (hasOverlayPermission) "Active" else "Authorize Overlay",
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Permission Card 2: Accessibility Service
        PermissionItemCard(
            title = "2. Tactical Joystick Controller",
            description = "Enables programmatic swipe gestures on the virtual joystick via Android's AccessibilityService to dodge projectiles instantly.",
            icon = Icons.Default.TouchApp,
            isGranted = hasAccessibilityPermission,
            actionLabel = if (hasAccessibilityPermission) "Active" else "Enable in Settings",
            onAction = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                context.startActivity(intent)
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Permission Card 3: Screen Capture (MediaProjection)
        PermissionItemCard(
            title = "3. Real-Time Screen Reader",
            description = "Transfers frame buffer to Rendera's high-speed threat detection engine (60 FPS optical motion & bullet detector).",
            icon = Icons.AutoMirrored.Filled.ScreenShare,
            isGranted = hasMediaProjectionPermission,
            actionLabel = if (hasMediaProjectionPermission) "Capture Ready" else "Arm Screen Reader",
            onAction = {
                onRequestMediaProjection()
            }
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Action Button: Proceed to Game Selection
        Button(
            onClick = onContinueToGames,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (allReady) NeonCyan else SurfaceDark,
                contentColor = if (allReady) Color.Black else NeonCyan
            ),
            shape = RoundedCornerShape(14.dp),
            border = if (!allReady) BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f)) else null
        ) {
            Text(
                text = if (allReady) "PROCEED TO GAME SELECTION →" else "CONTINUE / TEST IN ARENA →",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 1.sp
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun PermissionItemCard(
    title: String,
    description: String,
    icon: ImageVector,
    isGranted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    TacticalCard(
        borderColor = if (isGranted) SafeGreen.copy(alpha = 0.4f) else WarningAmber.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isGranted) SafeGreen.copy(alpha = 0.15f) else WarningAmber.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isGranted) SafeGreen else WarningAmber,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isGranted) "Status: Authorized" else "Status: Pending Activation",
                        color = if (isGranted) SafeGreen else WarningAmber,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Icon(
                    imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isGranted) SafeGreen else WarningAmber,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, if (isGranted) SafeGreen else NeonCyan),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isGranted) SafeGreen else NeonCyan
                )
            ) {
                Text(
                    text = actionLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
