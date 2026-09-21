package com.example.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.example.model.EntityType
import com.example.vision.ScreenThreatDetector
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * High-Performance Hardware-Accelerated Tactical Radar & Debug Overlay for Brawl Stars.
 *
 * Renders real-time bounding overlays, trajectory prediction vectors, detected player & enemy brawlers,
 * hostile projectiles, wall obstacles, and joystick evasion vectors directly over the live game screen.
 */
class RenderaDebugHudView(context: Context) : View(context) {

    private var analysisResult: ScreenThreatDetector.FrameAnalysisResult? = null
    private var fps: Int = 60
    private var latencyMs: Long = 1
    private var isAccessibilityActive: Boolean = true
    private var isAutoDodgeEnabled: Boolean = true

    // Reusable Paints for zero heap allocation during draw()
    private val paintPlayer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#05FFA1") // Vibrant Neon Green
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
    }

    private val paintPlayerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3305FFA1")
        style = Paint.Style.FILL
    }

    private val paintJoy = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00F0FF") // Cyber Cyan
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }

    private val paintJoyCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00F0FF")
        style = Paint.Style.FILL
    }

    private val paintDodgeVector = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D946EF") // Electric Magenta / Violet
        style = Paint.Style.STROKE
        strokeWidth = 6.5f
        strokeCap = Paint.Cap.ROUND
    }

    private val paintEnemy = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2A55") // High-Threat Crimson Red
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val paintEnemyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FF2A55")
        style = Paint.Style.FILL
    }

    private val paintProjectile = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9900") // Glowing Flame Amber
        style = Paint.Style.FILL
    }

    private val paintProjectileStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE600") // Electric Yellow Warning
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val paintTrajectoryLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFF3366")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
    }

    private val paintTextBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DD0B0F19")
        style = Paint.Style.FILL
    }

    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
    }

    private val paintSubText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A0AEC0")
        textSize = 22f
    }

    private val paintHudBarBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6090D16")
        style = Paint.Style.FILL
    }

    private val paintHudBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val arrowPath = Path()
    private val textRect = RectF()

    init {
        // Force hardware acceleration for smooth 60fps overlay
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun updateAnalysis(
        result: ScreenThreatDetector.FrameAnalysisResult?,
        fps: Int,
        latencyMs: Long,
        isAccessibilityActive: Boolean,
        isAutoDodgeEnabled: Boolean
    ) {
        this.analysisResult = result
        this.fps = fps
        this.latencyMs = latencyMs
        this.isAccessibilityActive = isAccessibilityActive
        this.isAutoDodgeEnabled = isAutoDodgeEnabled
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val res = analysisResult

        // 1. Draw Tactical Top HUD Bar
        drawTopHudBar(canvas, w, res)

        if (res == null) return

        // 2. Draw Entities (Enemies, Projectiles, Obstacles)
        for (entity in res.debugEntities) {
            when (entity.type) {
                EntityType.PLAYER -> {
                    // Handled specially below for enhanced reticle
                }
                EntityType.JOYSTICK -> {
                    // Handled specially below for joystick anchor & vector
                }
                EntityType.ENEMY -> {
                    drawEnemyEntity(canvas, entity, res.playerX, res.playerY)
                }
                EntityType.PROJECTILE -> {
                    drawProjectileEntity(canvas, entity, res.playerX, res.playerY)
                }
                EntityType.OBSTACLE -> {
                    drawObstacleEntity(canvas, entity)
                }
            }
        }

        // 3. Draw Player Reticle & Lock Status
        drawPlayerReticle(canvas, res)

        // 4. Draw Joystick Anchor and Dynamic Evasion Vector
        drawJoystickAnchorAndVector(canvas, res)

        // 5. Draw Active Threat Trajectory Line to Player
        res.threat?.let { threat ->
            canvas.drawLine(threat.threatX, threat.threatY, res.playerX, res.playerY, paintTrajectoryLine)
            val midX = (threat.threatX + res.playerX) / 2f
            val midY = (threat.threatY + res.playerY) / 2f
            drawFloatingBadge(
                canvas,
                midX,
                midY,
                "⚠️ IMPACT IN ${threat.timeToImpactMs}ms",
                Color.parseColor("#FF2A55"),
                Color.WHITE
            )
        }
    }

    private fun drawTopHudBar(canvas: Canvas, screenW: Float, res: ScreenThreatDetector.FrameAnalysisResult?) {
        val barHeight = 64f
        canvas.drawRect(0f, 0f, screenW, barHeight, paintHudBarBg)
        canvas.drawLine(0f, barHeight, screenW, barHeight, paintHudBorder)

        // Left Status: FPS, Latency & Auto-Dodge
        val fpsColor = if (fps >= 45) "#05FFA1" else if (fps >= 25) "#FFCC00" else "#FF3366"
        paintText.textSize = 24f
        paintText.color = Color.parseColor(fpsColor)
        canvas.drawText("RENDERA RADAR  |  ${fps} FPS  |  ${latencyMs}ms", 24f, 40f, paintText)

        // Middle: Game Threat Status
        val threat = res?.threat
        val threatStatusText = when {
            threat != null -> "🚨 DANGER: ${threat.threatLevel} (${threat.speed.toInt()} px/s)"
            res != null && res.enemyCount > 0 -> "🎯 TRACKING (${res.enemyCount} ENEMIES)"
            res != null -> "🛡️ SCANNING (SAFE)"
            else -> "⏳ CALIBRATING"
        }
        val threatColor = when {
            threat != null -> "#FF2A55"
            res != null && res.enemyCount > 0 -> "#00F0FF"
            else -> "#05FFA1"
        }
        paintText.color = Color.parseColor(threatColor)
        val middleX = (screenW / 2f) - 140f
        canvas.drawText(threatStatusText, middleX, 40f, paintText)

        // Right Status: Accessibility and Auto-Dodge Mode
        val accText = if (isAccessibilityActive) "ACC: OK" else "⚠️ ACC: OFF"
        val accColor = if (isAccessibilityActive) "#05FFA1" else "#FF2A55"
        paintText.color = Color.parseColor(accColor)
        val rightX = screenW - 320f
        canvas.drawText(accText, rightX, 40f, paintText)

        val modeText = if (isAutoDodgeEnabled) "AUTO-DODGE" else "MONITOR"
        paintSubText.textSize = 20f
        paintSubText.color = if (isAutoDodgeEnabled) Color.parseColor("#05FFA1") else Color.LTGRAY
        canvas.drawText(modeText, rightX + 160f, 40f, paintSubText)
    }

    private fun drawPlayerReticle(canvas: Canvas, res: ScreenThreatDetector.FrameAnalysisResult) {
        val px = res.playerX
        val py = res.playerY
        val radius = 56f

        // Foot indicator circle
        canvas.drawCircle(px, py, radius, paintPlayerFill)
        canvas.drawCircle(px, py, radius, paintPlayer)

        // Crosshairs
        canvas.drawLine(px - radius - 16f, py, px + radius + 16f, py, paintPlayer)
        canvas.drawLine(px, py - radius - 16f, px, py + radius + 16f, paintPlayer)

        // Label above player
        val lockLabel = if (res.isPlayerGreenRingTracked) "👤 MINÄ [LOCKED]" else "👤 MINÄ [CALIB]"
        drawFloatingBadge(canvas, px, py - radius - 24f, lockLabel, Color.parseColor("#05FFA1"), Color.BLACK)
    }

    private fun drawJoystickAnchorAndVector(canvas: Canvas, res: ScreenThreatDetector.FrameAnalysisResult) {
        val jx = res.joystickX
        val jy = res.joystickY
        val joyRadius = 140f

        // Joystick base ring
        canvas.drawCircle(jx, jy, joyRadius, paintJoy)
        canvas.drawCircle(jx, jy, 16f, paintJoyCenter)

        // Label
        drawFloatingBadge(canvas, jx, jy + joyRadius + 28f, "🕹️ JOYSTICK ANCHOR", Color.parseColor("#00F0FF"), Color.BLACK)

        // Dynamic Evasion Vector (if threat or dodge angle is computed)
        val angleDeg = res.dodgeAngleDeg ?: res.threat?.dodgeAngleDeg
        if (angleDeg != null) {
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val vectorLength = joyRadius * 0.95f
            val endX = jx + (vectorLength * cos(angleRad)).toFloat()
            val endY = jy + (vectorLength * sin(angleRad)).toFloat()

            // Draw thick glowing evasion vector
            canvas.drawLine(jx, jy, endX, endY, paintDodgeVector)

            // Draw Arrowhead
            drawArrowHead(canvas, jx, jy, endX, endY, paintDodgeVector)

            // Draw Badge
            drawFloatingBadge(
                canvas,
                endX,
                endY - 20f,
                "⚡ DODGE ${angleDeg.toInt()}°",
                Color.parseColor("#D946EF"),
                Color.WHITE
            )
        }
    }

    private fun drawEnemyEntity(canvas: Canvas, entity: com.example.model.DetectedEntity, playerX: Float, playerY: Float) {
        canvas.drawCircle(entity.x, entity.y, entity.radius, paintEnemyFill)
        canvas.drawCircle(entity.x, entity.y, entity.radius, paintEnemy)

        val dist = hypot(entity.x - playerX, entity.y - playerY).toInt()
        val label = "${entity.label} (${dist}px)"
        drawFloatingBadge(canvas, entity.x, entity.y - entity.radius - 18f, label, Color.parseColor("#FF2A55"), Color.WHITE)
    }

    private fun drawProjectileEntity(canvas: Canvas, entity: com.example.model.DetectedEntity, playerX: Float, playerY: Float) {
        // Glowing Projectile Core
        canvas.drawCircle(entity.x, entity.y, entity.radius, paintProjectile)
        canvas.drawCircle(entity.x, entity.y, entity.radius, paintProjectileStroke)

        // Velocity vector if speed > 10px/s
        val speed = hypot(entity.vx, entity.vy)
        if (speed > 10f) {
            val normVx = (entity.vx / speed) * 60f
            val normVy = (entity.vy / speed) * 60f
            paintProjectileStroke.strokeWidth = 4f
            canvas.drawLine(entity.x, entity.y, entity.x + normVx, entity.y + normVy, paintProjectileStroke)
        }

        drawFloatingBadge(canvas, entity.x, entity.y - entity.radius - 16f, entity.label, Color.parseColor("#FF9900"), Color.BLACK)
    }

    private fun drawObstacleEntity(canvas: Canvas, entity: com.example.model.DetectedEntity) {
        paintJoy.color = Color.parseColor("#888888")
        canvas.drawCircle(entity.x, entity.y, entity.radius, paintJoy)
        paintJoy.color = Color.parseColor("#00F0FF")
    }

    private fun drawFloatingBadge(canvas: Canvas, centerX: Float, centerY: Float, text: String, badgeColor: Int, textColor: Int) {
        paintText.textSize = 22f
        val textWidth = paintText.measureText(text)
        val padX = 14f
        val padY = 8f

        textRect.set(
            centerX - (textWidth / 2f) - padX,
            centerY - 16f - padY,
            centerX + (textWidth / 2f) + padX,
            centerY + 10f + padY
        )

        paintTextBg.color = Color.parseColor("#E6090D16")
        canvas.drawRoundRect(textRect, 8f, 8f, paintTextBg)

        paintHudBorder.color = badgeColor
        paintHudBorder.strokeWidth = 2f
        canvas.drawRoundRect(textRect, 8f, 8f, paintHudBorder)

        paintText.color = textColor
        canvas.drawText(text, centerX - (textWidth / 2f), centerY + 4f, paintText)
    }

    private fun drawArrowHead(canvas: Canvas, fromX: Float, fromY: Float, toX: Float, toY: Float, paint: Paint) {
        val arrowSize = 24f
        val angle = Math.atan2((toY - fromY).toDouble(), (toX - fromX).toDouble())

        arrowPath.reset()
        arrowPath.moveTo(toX, toY)
        arrowPath.lineTo(
            (toX - arrowSize * cos(angle - Math.PI / 6)).toFloat(),
            (toY - arrowSize * sin(angle - Math.PI / 6)).toFloat()
        )
        arrowPath.lineTo(
            (toX - arrowSize * cos(angle + Math.PI / 6)).toFloat(),
            (toY - arrowSize * sin(angle + Math.PI / 6)).toFloat()
        )
        arrowPath.close()

        val prevStyle = paint.style
        paint.style = Paint.Style.FILL
        canvas.drawPath(arrowPath, paint)
        paint.style = prevStyle
    }
}
