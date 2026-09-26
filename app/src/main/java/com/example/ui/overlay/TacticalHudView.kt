package com.example.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.hypot

/**
 * Compact in-game tactical readout.
 *
 * ## Why it is a small panel instead of a full-screen overlay
 *
 * MediaProjection captures **every** window on the display, including Rendera's
 * own. The previous HUD was `MATCH_PARENT`, so the boxes it drew were fed back
 * into the vision engine as moving objects, and the vision engine's output was
 * then drawn as more boxes: a self-reinforcing feedback loop. Keeping the HUD to
 * one corner panel bounds the damage to a small rectangle, which the engine is
 * told to mask explicitly.
 *
 * It is also `FLAG_NOT_TOUCHABLE`, so it can never intercept a game input.
 */
class TacticalHudView(context: Context) : View(context) {

    /** One tracked object, in screen pixels. */
    data class Entity(
        val kind: Kind,
        val x: Float,
        val y: Float,
        val radius: Float,
        val vx: Float = 0f,
        val vy: Float = 0f,
        val label: String = ""
    )

    enum class Kind { PLAYER, JOYSTICK, PROJECTILE, ENEMY, THREAT, ESCAPE }

    /** Everything the HUD needs for one frame. */
    data class Snapshot(
        val entities: List<Entity> = emptyList(),
        val fps: Int = 0,
        val visionMillis: Double = 0.0,
        val droppedFrames: Long = 0,
        val playerLocked: Boolean = false,
        val playerFromAnchor: Boolean = false,
        val projectiles: Int = 0,
        val threatSeverity: String = "SAFE",
        val timeToImpactMs: Long = 0L,
        val escapeHeadingDeg: Float = 0f,
        val escapeSufficient: Boolean = true,
        val anchorsCalibrated: Boolean = false,
        val accessibilityReady: Boolean = false,
        val gameForeground: Boolean = true,
        val autoDodgeArmed: Boolean = false,
        val note: String = ""
    )

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(215, 8, 10, 20)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.argb(220, 0, 240, 255)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = Color.argb(255, 157, 78, 221)
        strokeCap = Paint.Cap.ROUND
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 0, 240, 255)
        textSize = dp(10f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(9.5f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 255, 190, 60)
        textSize = dp(9.5f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    /** Reused; assigning `color` per frame avoids a per-draw allocation. */
    private val severityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val goodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 5, 255, 161)
        textSize = dp(9.5f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private var snapshot = Snapshot()
    private val lines = ArrayList<String>(14)

    fun update(snap: Snapshot) {
        snapshot = snap
        invalidate()
    }

    /** Bounds of the drawn panel, in this view's own coordinates. */
    fun panelBounds(out: android.graphics.Rect) {
        val w = measuredWidth
        val h = measuredHeight
        out.set(0, 0, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val pad = dp(6f)
        canvas.drawRoundRect(0f, 0f, w, h, dp(8f), dp(8f), bgPaint)
        canvas.drawRoundRect(0f, 0f, w, h, dp(8f), dp(8f), borderPaint)

        val s = snapshot
        // Compared against the exact ThreatSeverity enum name. The previous
        // "IMMINENT" could never match "IMMINENT_DANGER", so imminent danger was
        // rendered with the safe colour.
        val severityColor = when (s.threatSeverity) {
            "LETHAL" -> Color.argb(255, 255, 70, 70)
            "IMMINENT_DANGER" -> Color.argb(255, 255, 170, 0)
            "WARNING" -> Color.argb(255, 255, 215, 0)
            else -> Color.argb(255, 5, 255, 161)
        }

        lines.clear()
        lines += "RENDERA ${if (s.autoDodgeArmed) "ARMED" else "PAUSED"}"
        lines += "fps ${s.fps}  vision ${"%.1f".format(s.visionMillis)}ms  drop ${s.droppedFrames}"
        lines += "player " + when {
            s.playerLocked && !s.playerFromAnchor -> "LOCKED"
            s.playerFromAnchor -> "ANCHOR"
            else -> "SEARCH"
        } + "  joy ${if (s.anchorsCalibrated) "SET" else "UNSET"}"
        lines += "proj ${s.projectiles}  thr ${s.threatSeverity}"
        if (s.threatSeverity != "SAFE") {
            lines += "tti ${s.timeToImpactMs}ms  esc ${s.escapeHeadingDeg.toInt()}deg" +
                if (s.escapeSufficient) "" else " PARTIAL"
        }
        lines += "acc " + if (s.accessibilityReady) "READY" else "OFF"
        lines += "  game " + if (s.gameForeground) "FGD" else "BG"
        if (s.note.isNotEmpty()) lines += s.note

        var y = pad + titlePaint.textSize
        canvas.drawText(lines[0], pad, y, titlePaint)
        y += titlePaint.textSize + dp(2f)
        severityPaint.color = severityColor
        for (i in 1 until lines.size) {
            val paint = when {
                // The threat line is coloured by severity, which is the one
                // number the user must be able to read at a glance.
                lines[i].contains("thr ") || lines[i].contains("tti ") -> severityPaint
                lines[i].contains("UNSET") || lines[i].contains("OFF") ||
                    lines[i].contains("PARTIAL") || lines[i].contains("SEARCH") -> warnPaint
                lines[i].contains("LOCKED") || lines[i].contains("READY") ||
                    lines[i].contains("ARMED") -> goodPaint
                else -> bodyPaint
            }
            y += bodyPaint.textSize + dp(1.5f)
            if (y > h - pad) break
            canvas.drawText(lines[i], pad, y, paint)
        }
    }

    /**
     * Draws the reticles over the game. Kept separate from [onDraw] so the panel
     * itself can stay small while the world-space indicators still cover the
     * whole screen. The host calls this from its own full-screen pass.
     */
    fun drawWorldOverlay(canvas: Canvas) {
        val s = snapshot
        for (e in s.entities) {
            val color = when (e.kind) {
                Kind.PLAYER -> Color.argb(255, 5, 255, 161)
                Kind.JOYSTICK -> Color.argb(200, 0, 240, 255)
                Kind.PROJECTILE -> Color.argb(255, 255, 215, 0)
                Kind.ENEMY -> Color.argb(255, 255, 80, 80)
                Kind.THREAT -> Color.argb(255, 255, 60, 60)
                Kind.ESCAPE -> Color.argb(255, 157, 78, 221)
            }
            strokePaint.color = color
            fillPaint.color = (color and 0x00FFFFFF) or 0x30000000
            canvas.drawCircle(e.x, e.y, e.radius.coerceAtLeast(2f), fillPaint)
            canvas.drawCircle(e.x, e.y, e.radius.coerceAtLeast(2f), strokePaint)
            if (e.kind == Kind.PROJECTILE && (e.vx != 0f || e.vy != 0f)) {
                val len = 0.12f
                canvas.drawLine(
                    e.x, e.y,
                    e.x + e.vx * len, e.y + e.vy * len,
                    linePaint
                )
            }
        }
    }

    /** Builds the reticle list from the current vision state. */
    fun entitiesFor(
        playerX: Float,
        playerY: Float,
        playerRadius: Float,
        playerLocked: Boolean,
        joyX: Float,
        joyY: Float,
        joyRadius: Float,
        tracks: FloatArray,
        enemies: FloatArray,
        threatX: Float,
        threatY: Float,
        hasThreat: Boolean,
        escapeX: Float,
        escapeY: Float,
        hasEscape: Boolean
    ): List<Entity> {
        val out = ArrayList<Entity>(tracks.size / 6 + enemies.size / 3 + 4)
        out += Entity(
            Kind.PLAYER, playerX, playerY, playerRadius,
            label = if (playerLocked) "PLAYER" else "PLAYER?"
        )
        out += Entity(Kind.JOYSTICK, joyX, joyY, joyRadius, label = "STICK")
        var i = 0
        while (i + 5 < tracks.size) {
            val isProjectile = tracks[i + 5] > 0.5f
            out += Entity(
                kind = if (isProjectile) Kind.PROJECTILE else Kind.ENEMY,
                x = tracks[i],
                y = tracks[i + 1],
                radius = dp(if (isProjectile) 16f else 10f),
                vx = tracks[i + 2],
                vy = tracks[i + 3],
                label = if (isProjectile) "AMMO" else "TRACK"
            )
            i += 6
        }
        var j = 0
        while (j + 2 < enemies.size) {
            out += Entity(Kind.ENEMY, enemies[j], enemies[j + 1], dp(22f), label = "ENEMY")
            j += 3
        }
        if (hasThreat) {
            out += Entity(Kind.THREAT, threatX, threatY, dp(14f), label = "THREAT")
        }
        if (hasEscape) {
            out += Entity(
                Kind.ESCAPE, escapeX, escapeY, dp(12f),
                label = "ESC ${"%.0f".format(escapeX)},${"%.0f".format(escapeY)}"
            )
        }
        return out
    }

    internal fun distance(a: Entity, b: Entity): Float = hypot(a.x - b.x, a.y - b.y)
}
