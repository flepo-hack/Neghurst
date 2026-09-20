package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ThreatLevel
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderCyan
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SafeGreen
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.ThreatRed
import com.example.ui.theme.WarningAmber
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun TacticalCard(
    modifier: Modifier = Modifier,
    borderColor: Color = NeonCyan.copy(alpha = 0.35f),
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceDark.copy(alpha = 0.85f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
fun ThreatLevelBadge(
    threatLevel: ThreatLevel,
    modifier: Modifier = Modifier
) {
    val (color, label) = when (threatLevel) {
        ThreatLevel.SAFE -> Pair(SafeGreen, "STANDBY / CLEAR")
        ThreatLevel.WARNING -> Pair(WarningAmber, "THREAT DETECTED")
        ThreatLevel.IMMINENT_DANGER -> Pair(ThreatRed, "INCOMING PROJECTILE")
        ThreatLevel.LETHAL -> Pair(Color(0xFFFF0055), "CRITICAL DODGE!")
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .border(1.dp, color.copy(alpha = if (threatLevel != ThreatLevel.SAFE) alpha else 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = if (threatLevel != ThreatLevel.SAFE) alpha else 1.0f))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun TacticalStatItem(
    label: String,
    value: String,
    unit: String = "",
    color: Color = NeonCyan,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = color,
                fontSize = 20.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            if (unit.isNotEmpty()) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = unit,
                    color = color.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
fun JoystickRadarDisplay(
    dodgeAngleDeg: Float,
    isDodgeActive: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(140.dp)
            .clip(CircleShape)
            .background(BackgroundDark)
            .border(2.dp, BorderCyan, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(130.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.width / 2f - 8f

            // Concentric rings
            drawCircle(
                color = NeonCyan.copy(alpha = 0.15f),
                radius = radius,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
            drawCircle(
                color = NeonCyan.copy(alpha = 0.25f),
                radius = radius * 0.6f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
            drawCircle(
                color = NeonCyan.copy(alpha = 0.4f),
                radius = radius * 0.25f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )

            // Crosshairs
            drawLine(
                color = NeonCyan.copy(alpha = 0.2f),
                start = Offset(center.x - radius, center.y),
                end = Offset(center.x + radius, center.y),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = NeonCyan.copy(alpha = 0.2f),
                start = Offset(center.x, center.y - radius),
                end = Offset(center.x, center.y + radius),
                strokeWidth = 1.dp.toPx()
            )

            // Center hero marker
            drawCircle(
                color = SafeGreen,
                radius = 5.dp.toPx(),
                center = center
            )

            // Dodge vector indicator
            if (isDodgeActive || dodgeAngleDeg != 0f) {
                val rad = Math.toRadians(dodgeAngleDeg.toDouble())
                val endX = (center.x + cos(rad) * radius * 0.85f).toFloat()
                val endY = (center.y + sin(rad) * radius * 0.85f).toFloat()

                drawLine(
                    brush = Brush.linearGradient(
                        listOf(ElectricViolet, NeonCyan)
                    ),
                    start = center,
                    end = Offset(endX, endY),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round
                )

                drawCircle(
                    color = NeonCyan,
                    radius = 6.dp.toPx(),
                    center = Offset(endX, endY)
                )
            }
        }
    }
}
