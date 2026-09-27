package com.example.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import com.example.vision.nativebridge.NativeVisionEngine
import com.example.vision.nativebridge.TrackKind
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Full screen, non interactive layer that draws what the engine can actually see.
 *
 * ## Why this is separate from the text panel
 *
 * The HUD panel is a 232x140 box in one corner. It is the right size for a
 * readout and the wrong size for anything that has to be drawn where it
 * actually is on screen. The code to draw the brawler, the joystick and the
 * enemies existed and was never called, so the panel showed numbers and no
 * picture - which is the whole point of a debug overlay, and read as "it sees
 * nothing" when in fact it could not draw.
 *
 * This view covers the display and draws each entity at its real screen
 * position. It is `FLAG_NOT_TOUCHABLE` at the window level, so it cannot steal a
 * single touch from the game.
 */
class RenderaReticleOverlay(context: Context) : View(context) {

    /** One thing to draw, already in screen pixels. */
    data class Mark(
        val kind: Kind,
        val x: Float,
        val y: Float,
        val radius: Float,
        val vx: Float = 0f,
        val vy: Float = 0f,
        val label: String = ""
    )

    enum class Kind { PLAYER, JOYSTICK, PROJECTILE, BALL, BOUNCER, ENEMY, THREAT, ESCAPE }

    /** Everything to draw for one frame. */
    data class Frame(
        val marks: List<Mark> = emptyList(),
        val playerDetected: Boolean = false,
        val playerFromAnchor: Boolean = false,
        val captureOk: Boolean = false,
        val note: String = ""
    )

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(9f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(11f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = Color.WHITE
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.argb(160, 0, 240, 255)
    }
    private val arrow = Path()

    @Volatile
    private var frame: Frame = Frame()

    fun update(f: Frame) {
        frame = f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val f = frame
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // A clear, honest banner. A debug layer that draws nothing and says
        // nothing is indistinguishable from a broken one.
        drawBanner(canvas, w, f)

        for (m in f.marks) {
            when (m.kind) {
                Kind.PLAYER -> drawPlayer(canvas, m)
                Kind.JOYSTICK -> drawJoystick(canvas, m)
                Kind.PROJECTILE -> drawProjectile(canvas, m)
                Kind.BALL -> drawBall(canvas, m)
                Kind.BOUNCER -> drawBouncer(canvas, m)
                Kind.ENEMY -> drawEnemy(canvas, m)
                Kind.THREAT -> drawThreat(canvas, m)
                Kind.ESCAPE -> drawEscape(canvas, m)
            }
        }
    }

    private fun drawBanner(canvas: Canvas, w: Float, f: Frame) {
        // The advice string is produced by the service, which is the only place
        // that knows why. A banner that guesses its own reason disagrees with the
        // menu, and the disagreement is what makes these states hard to read.
        val text = when {
            !f.captureOk -> f.note.ifEmpty { "NO CAPTURE - grant screen recording" }
            !f.playerDetected && f.playerFromAnchor -> "player: using the calibrated anchor, not detected"
            !f.playerDetected -> "player: NOT detected - try AUTO DETECT on open ground"
            else -> "capturing, player locked"
        }
        val pad = dp(6f)
        val tw = notePaint.measureText(text)
        val x = (w - tw) / 2f
        val y = dp(16f)
        fillPaint.color = Color.argb(190, 8, 10, 20)
        canvas.drawRoundRect(
            x - pad, y - dp(12f), x + tw + pad, y + dp(5f), dp(6f), dp(6f), fillPaint
        )
        notePaint.color = when {
            !f.captureOk -> Color.argb(255, 255, 90, 90)
            !f.playerDetected -> Color.argb(255, 255, 190, 60)
            else -> Color.argb(255, 5, 255, 161)
        }
        canvas.drawText(text, x, y, notePaint)
    }

    private fun drawPlayer(canvas: Canvas, m: Mark) {
        val r = maxOf(m.radius, dp(9f))
        // A filled disc with a ring, so it reads as "the brawler", and a cross
        // hair on the exact collider centre.
        fillPaint.color = Color.argb(70, 5, 255, 161)
        canvas.drawCircle(m.x, m.y, r, fillPaint)
        ringPaint.color = Color.argb(255, 5, 255, 161)
        ringPaint.strokeWidth = dp(2.5f)
        canvas.drawCircle(m.x, m.y, r, ringPaint)
        val arm = r * 0.45f
        canvas.drawLine(m.x - arm, m.y, m.x + arm, m.y, ringPaint)
        canvas.drawLine(m.x, m.y - arm, m.x, m.y + arm, ringPaint)
        drawLabel(canvas, m.x + r + dp(3f), m.y, m.label)
    }

    private fun drawJoystick(canvas: Canvas, m: Mark) {
        ringPaint.color = Color.argb(200, 0, 240, 255)
        ringPaint.strokeWidth = dp(2f)
        canvas.drawCircle(m.x, m.y, m.radius, ringPaint)
        // Cross hairs at the extremes of the usable travel, so it is obvious
        // where the stick can actually reach.
        val t = m.radius * 0.28f
        ringPaint.color = Color.argb(120, 0, 240, 255)
        canvas.drawLine(m.x - m.radius, m.y, m.x + m.radius, m.y, ringPaint)
        canvas.drawLine(m.x, m.y - m.radius, m.x, m.y + m.radius, ringPaint)
        ringPaint.color = Color.argb(90, 0, 240, 255)
        canvas.drawCircle(m.x, m.y, t, ringPaint)
        drawLabel(canvas, m.x + m.radius + dp(3f), m.y, m.label)
    }

    private fun drawProjectile(canvas: Canvas, m: Mark) {
        val r = maxOf(m.radius, dp(5f))
        fillPaint.color = Color.argb(90, 255, 215, 0)
        canvas.drawCircle(m.x, m.y, r, fillPaint)
        ringPaint.color = Color.argb(255, 255, 215, 0)
        ringPaint.strokeWidth = dp(2f)
        canvas.drawCircle(m.x, m.y, r, ringPaint)
        drawVelocity(canvas, m, r, Color.argb(220, 255, 215, 0))
        drawLabel(canvas, m.x + r + dp(3f), m.y, m.label)
    }

    private fun drawBall(canvas: Canvas, m: Mark) {
        val r = maxOf(m.radius, dp(11f))
        fillPaint.color = Color.argb(80, 255, 140, 40)
        canvas.drawCircle(m.x, m.y, r, fillPaint)
        ringPaint.color = Color.argb(255, 255, 140, 40)
        ringPaint.strokeWidth = dp(2.5f)
        canvas.drawCircle(m.x, m.y, r, ringPaint)
        drawVelocity(canvas, m, r, Color.argb(220, 255, 140, 40))
        drawLabel(canvas, m.x + r + dp(3f), m.y, m.label)
    }

    private fun drawBouncer(canvas: Canvas, m: Mark) {
        val r = maxOf(m.radius, dp(4f))
        ringPaint.color = Color.argb(220, 255, 150, 255)
        ringPaint.strokeWidth = dp(1.5f)
        // A diamond: a bouncer is reported but deliberately not dodged, so it
        // must look different from a shot that is.
        val d = r * 1.4f
        arrow.reset()
        arrow.moveTo(m.x, m.y - d); arrow.lineTo(m.x + d, m.y)
        arrow.lineTo(m.x, m.y + d); arrow.lineTo(m.x - d, m.y)
        arrow.close()
        canvas.drawPath(arrow, ringPaint)
        drawLabel(canvas, m.x + d + dp(3f), m.y, m.label)
    }

    private fun drawEnemy(canvas: Canvas, m: Mark) {
        val r = maxOf(m.radius, dp(9f))
        fillPaint.color = Color.argb(60, 255, 70, 70)
        canvas.drawCircle(m.x, m.y, r, fillPaint)
        ringPaint.color = Color.argb(230, 255, 70, 70)
        ringPaint.strokeWidth = dp(2f)
        canvas.drawCircle(m.x, m.y, r, ringPaint)
        drawLabel(canvas, m.x + r + dp(3f), m.y, m.label)
    }

    private fun drawThreat(canvas: Canvas, m: Mark) {
        val r = maxOf(m.radius, dp(10f))
        ringPaint.color = Color.argb(255, 255, 40, 40)
        ringPaint.strokeWidth = dp(3f)
        canvas.drawCircle(m.x, m.y, r, ringPaint)
        ringPaint.color = Color.argb(160, 255, 40, 40)
        ringPaint.strokeWidth = dp(1.5f)
        canvas.drawCircle(m.x, m.y, r * 1.8f, ringPaint)
        drawLabel(canvas, m.x + r + dp(3f), m.y, m.label)
    }

    private fun drawEscape(canvas: Canvas, m: Mark) {
        val r = maxOf(m.radius, dp(8f))
        ringPaint.color = Color.argb(255, 157, 78, 221)
        ringPaint.strokeWidth = dp(2.5f)
        // An arrow out of the player in the chosen direction, which is the one
        // thing worth seeing while tuning the escape.
        arrow.reset()
        arrow.moveTo(m.x - r, m.y)
        arrow.lineTo(m.x + r, m.y)
        arrow.moveTo(m.x + r, m.y)
        arrow.lineTo(m.x + r - dp(5f), m.y - dp(4f))
        arrow.moveTo(m.x + r, m.y)
        arrow.lineTo(m.x + r - dp(5f), m.y + dp(4f))
        canvas.drawPath(arrow, ringPaint)
        drawLabel(canvas, m.x + r + dp(4f), m.y, m.label)
    }

    private fun drawVelocity(canvas: Canvas, m: Mark, r: Float, colour: Int) {
        val v = hypot(m.vx, m.vy)
        if (v < 1f) return
        // 120 ms of travel, so the arrow shows where it is heading and how fast.
        val k = 0.12f / v
        pathPaint.color = colour
        canvas.drawLine(m.x, m.y, m.x + m.vx * k, m.y + m.vy * k, pathPaint)
        val ex = m.x + m.vx * k
        val ey = m.y + m.vy * k
        val ux = m.vx / v
        val uy = m.vy / v
        val head = dp(6f)
        arrow.reset()
        arrow.moveTo(ex, ey)
        arrow.lineTo(ex - ux * head - uy * head * 0.6f, ey - uy * head + ux * head * 0.6f)
        arrow.moveTo(ex, ey)
        arrow.lineTo(ex - ux * head + uy * head * 0.6f, ey - uy * head - ux * head * 0.6f)
        canvas.drawPath(arrow, pathPaint)
        if (abs(ux) > 0.001f || abs(uy) > 0.001f) {
            ringPaint.color = colour
            ringPaint.strokeWidth = dp(1f)
            canvas.drawCircle(m.x, m.y, r, ringPaint)
        }
    }

    private fun drawLabel(canvas: Canvas, x: Float, y: Float, text: String) {
        if (text.isEmpty()) return
        labelPaint.color = Color.argb(220, 255, 255, 255)
        canvas.drawText(text, x, y, labelPaint)
    }

    companion object {
        /** Floats per track in the engine readback, used to decode kinds. */
        const val TRACK_FLOATS = NativeVisionEngine.TRACK_FLOATS

        /** Decodes the engine's track array into marks, in screen pixels. */
        fun marksFromTracks(
            tracks: FloatArray,
            playerX: Float,
            playerY: Float,
            playerRadius: Float,
            playerDetected: Boolean,
            playerFromAnchor: Boolean,
            joystickX: Float,
            joystickY: Float,
            joystickRadius: Float,
            enemies: FloatArray,
            threatX: Float,
            threatY: Float,
            hasThreat: Boolean,
            escapeX: Float,
            escapeY: Float,
            hasEscape: Boolean,
            density: Float
        ): List<Mark> {
            val out = ArrayList<Mark>(tracks.size / TRACK_FLOATS + enemies.size / 3 + 6)
            out += Mark(
                Kind.PLAYER, playerX, playerY, playerRadius,
                label = if (playerDetected) "YOU" else if (playerFromAnchor) "YOU(anchor)" else "YOU?"
            )
            out += Mark(Kind.JOYSTICK, joystickX, joystickY, joystickRadius, label = "STICK")
            var i = 0
            while (i + TRACK_FLOATS - 1 < tracks.size) {
                val kind = TrackKind.fromCode(tracks[i + 6].toInt())
                out += when (kind) {
                    TrackKind.BALL -> Mark(Kind.BALL, tracks[i], tracks[i + 1], 16f * density, tracks[i + 2], tracks[i + 3], "BALL")
                    TrackKind.BOUNCER -> Mark(Kind.BOUNCER, tracks[i], tracks[i + 1], 6f * density, tracks[i + 2], tracks[i + 3], "BNC")
                    TrackKind.PROJECTILE -> Mark(Kind.PROJECTILE, tracks[i], tracks[i + 1], 8f * density, tracks[i + 2], tracks[i + 3], "AMMO")
                    TrackKind.UNKNOWN -> Mark(Kind.ENEMY, tracks[i], tracks[i + 1], 8f * density, tracks[i + 2], tracks[i + 3], "TRK")
                }
                i += TRACK_FLOATS
            }
            var j = 0
            while (j + 2 < enemies.size) {
                out += Mark(Kind.ENEMY, enemies[j], enemies[j + 1], 12f * density, label = "FOE")
                j += 3
            }
            if (hasThreat) {
                out += Mark(Kind.THREAT, threatX, threatY, 14f * density, label = "HIT")
            }
            if (hasEscape) {
                out += Mark(Kind.ESCAPE, escapeX, escapeY, 12f * density, label = "ESC")
            }
            return out
        }
    }
}
