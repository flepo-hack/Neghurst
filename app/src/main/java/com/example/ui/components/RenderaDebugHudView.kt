package com.example.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.example.model.DodgeProfile
import com.example.model.EntityType
import com.example.vision.ScreenThreatDetector
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * In-game tactical radar.
 *
 * Deliberately restrained. The previous version drew a 600x120 px
 * "WAITING FOR MATCH" banner across the middle of the playfield and enabled
 * itself by default, which is why the app looked broken the moment it started.
 * Now it is opt-in, and it only draws in the playfield margins and around the
 * tracked objects.
 *
 * Every label is read from the real pipeline output; nothing is inferred.
 */
class RenderaDebugHudView(context: Context) : View(context) {

    private var result: ScreenThreatDetector.FrameAnalysisResult? = null
    private var profile: DodgeProfile = DodgeProfile()
    private var armed = false

    private val d = resources.displayMetrics.density
    private fun px(v: Float) = v * d

    private val strokeGreen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF05FFA1"); style = Paint.Style.STROKE
        strokeWidth = px(3f); setShadowLayer(px(6f), 0f, 0f, Color.parseColor("#80000000"))
    }
    private val fillGreen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2205FFA1"); style = Paint.Style.FILL
    }
    private val strokeCyan = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF00F0FF"); style = Paint.Style.STROKE
        strokeWidth = px(2.5f)
        pathEffect = DashPathEffect(floatArrayOf(px(10f), px(7f)), 0f)
    }
    private val fillCyan = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF00F0FF"); style = Paint.Style.FILL
    }
    private val strokeRed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFF2A55"); style = Paint.Style.STROKE
        strokeWidth = px(2.5f)
    }
    private val fillAmber = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFB020"); style = Paint.Style.FILL
    }
    private val strokeAmber = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFE600"); style = Paint.Style.STROKE
        strokeWidth = px(2f)
    }
    private val trailRed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAFF2A55"); style = Paint.Style.STROKE
        strokeWidth = px(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(px(8f), px(8f)), 0f)
    }
    private val vecMagenta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD946EF"); style = Paint.Style.STROKE
        strokeWidth = px(5f); strokeCap = Paint.Cap.ROUND
    }
    private val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D9090D16"); style = Paint.Style.FILL
    }
    private val badgeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5A189A"); style = Paint.Style.STROKE
        strokeWidth = px(1.5f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; isFakeBoldText = true; textSize = px(11f)
    }
    private val textDim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFA0AEC0"); textSize = px(10f)
    }

    private val arrow = Path()
    private val rect = RectF()
    private var lastWidth = 0
    private var lastHeight = 0

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun updateAnalysis(
        result: ScreenThreatDetector.FrameAnalysisResult?,
        profile: DodgeProfile,
        armed: Boolean
    ) {
        this.result = result
        this.profile = profile
        this.armed = armed
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        lastWidth = width
        lastHeight = height
        val r = result
        drawStatusStrip(canvas)
        if (r == null) return

        // Joystick anchor: only when it is actually confirmed, so an uncalibrated
        // session cannot masquerade as a calibrated one.
        if (r.joystickAnchorConfirmed) {
            val jr = r.joystickRadiusPx.coerceAtLeast(px(24f))
            canvas.drawCircle(r.joystickX, r.joystickY, jr, strokeCyan)
            canvas.drawCircle(r.joystickX, r.joystickY, px(4f), fillCyan)
        }

        // Player reticle: green when the anchor is locked, amber when assumed.
        val playerPaint = if (r.playerAnchorConfirmed) strokeGreen else strokeAmber
        val playerFill = if (r.playerAnchorConfirmed) fillGreen else Paint()
        val pr = playerRadius()
        canvas.drawCircle(r.playerX, r.playerY, pr, playerFill)
        canvas.drawCircle(r.playerX, r.playerY, pr, playerPaint)
        canvas.drawLine(r.playerX - pr - px(10f), r.playerY, r.playerX - pr + px(2f), r.playerY, playerPaint)
        canvas.drawLine(r.playerX + pr - px(2f), r.playerY, r.playerX + pr + px(10f), r.playerY, playerPaint)

        for (e in r.debugEntities) {
            when (e.type) {
                EntityType.PROJECTILE -> {
                    canvas.drawCircle(e.x, e.y, px(7f), fillAmber)
                    canvas.drawCircle(e.x, e.y, px(7f), strokeAmber)
                    val sp = hypot(e.vx, e.vy)
                    if (sp > 1f) {
                        val k = px(26f) / sp
                        canvas.drawLine(e.x, e.y, e.x + e.vx * k, e.y + e.vy * k, strokeAmber)
                    }
                }
                EntityType.ENEMY -> {
                    canvas.drawCircle(e.x, e.y, px(11f), strokeRed)
                    canvas.drawLine(r.playerX, r.playerY, e.x, e.y, trailRed)
                }
                else -> Unit
            }
        }

        val t = r.threat
        if (t != null) {
            canvas.drawLine(t.threatX, t.threatY, r.playerX, r.playerY, trailRed)
            val angle = r.dodgeAngleDeg ?: t.dodgeAngleDeg
            if (r.joystickAnchorConfirmed) drawDodgeVector(canvas, r.joystickX, r.joystickY, angle, r.joystickRadiusPx)
            badge(
                canvas, (t.threatX + r.playerX) / 2f, (t.threatY + r.playerY) / 2f,
                "TTI ${t.timeToImpactMs}ms  ${t.threatLevel}", Color.parseColor("#FFFF2A55")
            )
        }
    }

    private fun playerRadius(): Float =
        (profile.effectiveHitboxPx(profile.tilePixels.takeIf { it > 1f } ?: (lastWidth / 13f)))
            .coerceIn(px(14f), px(120f))

    private fun drawDodgeVector(canvas: Canvas, cx: Float, cy: Float, angleDeg: Float, radiusPx: Float) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val len = radiusPx * profile.dodgeDeflection.coerceIn(0.2f, 1f)
        val ex = cx + (len * cos(rad)).toFloat()
        val ey = cy + (len * sin(rad)).toFloat()
        canvas.drawLine(cx, cy, ex, ey, vecMagenta)
        arrowHead(canvas, cx, cy, ex, ey)
        badge(canvas, ex, ey - px(14f), "DODGE ${angleDeg.roundToInt()}\u00B0", Color.parseColor("#FFD946EF"))
    }

    private fun arrowHead(canvas: Canvas, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val size = px(11f)
        val a = Math.atan2((toY - fromY).toDouble(), (toX - fromX).toDouble())
        arrow.reset()
        arrow.moveTo(toX, toY)
        arrow.lineTo((toX - size * cos(a - Math.PI / 6)).toFloat(), (toY - size * sin(a - Math.PI / 6)).toFloat())
        arrow.lineTo((toX - size * cos(a + Math.PI / 6)).toFloat(), (toY - size * sin(a + Math.PI / 6)).toFloat())
        arrow.close()
        val fill = Paint(vecMagenta).apply { style = Paint.Style.FILL }
        canvas.drawPath(arrow, fill)
    }

    private fun drawStatusStrip(canvas: Canvas) {
        val r = result
        val h = px(20f)
        canvas.drawRect(0f, 0f, width.toFloat(), h, barBg)
        val status = when {
            r == null -> "STARTING"
            !r.playerAnchorConfirmed && !r.joystickAnchorConfirmed -> "UNCALIBRATED - TAP THE R BUBBLE"
            !r.playerAnchorConfirmed -> "PLAYER ANCHOR NOT LOCKED"
            !r.joystickAnchorConfirmed -> "JOYSTICK ANCHOR NOT LOCKED"
            !armed -> "PAUSED"
            r.threat != null -> "DODGING"
            else -> "MONITORING"
        }
        val tint = when {
            !armed -> Color.parseColor("#FFFFB020")
            r?.threat != null -> Color.parseColor("#FFFF2A55")
            else -> Color.parseColor("#FF05FFA1")
        }
        text.color = tint
        text.textSize = px(11f)
        canvas.drawText(status, px(6f), px(14f), text)

        textDim.textSize = px(9f)
        textDim.color = Color.parseColor("#FF8892A6")
        val right = if (r == null) "" else {
            "${r.engine}  cam ${if (r.isCameraMoving) "${r.cameraDx},${r.cameraDy}" else "0,0"}  ammo ${r.projectileCount}"
        }
        val tw = textDim.measureText(right)
        canvas.drawText(right, width - tw - px(6f), px(14f), textDim)
    }

    private fun badge(canvas: Canvas, cx: Float, cy: Float, msg: String, border: Int) {
        text.textSize = px(10f)
        val tw = text.measureText(msg)
        val pad = px(5f)
        rect.set(
            cx - tw / 2f - pad, cy - px(9f),
            cx + tw / 2f + pad, cy + px(6f)
        )
        canvas.drawRoundRect(rect, px(3f), px(3f), barBg)
        badgeBorder.color = border
        canvas.drawRoundRect(rect, px(3f), px(3f), badgeBorder)
        text.color = Color.WHITE
        canvas.drawText(msg, cx - tw / 2f, cy, text)
    }
}
