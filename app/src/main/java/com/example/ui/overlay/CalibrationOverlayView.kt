package com.example.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.example.vision.AnchorTarget
import com.example.vision.Anchors
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Full-screen calibration overlay.
 *
 * ## Why this is one custom View instead of a layout of TextViews
 *
 * The previous implementation stacked a `MATCH_PARENT` touch-capturing `View`
 * with two reticle views *underneath* a header and a bottom bar, and marked the
 * two bar containers `isClickable = true`. That combination is what made
 * "LOCK &amp; ACTIVATE" and "SMART AUTO-DETECT" silently do nothing: a
 * `ViewGroup` marked clickable consumes the ACTION_DOWN in its own
 * `onTouchEvent` for any touch that lands in its padding but not on a child, and
 * because the whole overlay is a single window there is no way for a child to
 * re-claim the stream. On several OEM builds the click simply never arrived.
 *
 * Here there is exactly one view, and it does all of its own hit testing in
 * [onTouchEvent]. Buttons are tested first and always win; a touch anywhere else
 * drags the active reticle. That makes the precedence explicit and testable
 * instead of emergent from z-order.
 *
 * The overlay also reports the live touch position, so the host can show the
 * value being captured rather than making the user commit blind and find out
 * afterwards.
 */
class CalibrationOverlayView(
    context: Context,
    private val displayWidthPx: Int,
    private val displayHeightPx: Int,
    private var anchors: Anchors,
    private val callbacks: Callbacks
) : View(context) {

    interface Callbacks {
        /** The user moved a reticle. [screenX]/[screenY] are screen pixels. */
        fun onAnchorMoved(target: AnchorTarget, screenX: Float, screenY: Float)

        /** The user asked to run automatic detection. */
        fun onAutoDetectRequested()

        /** The user committed. [anchors] is the current value. */
        fun onCommitted(anchors: Anchors)

        /** The user cancelled. */
        fun onCancelled()

        /** The user tapped the header to toggle which anchor is being edited. */
        fun onTargetChanged(target: AnchorTarget)
    }

    private val density = context.resources.displayMetrics.density

    private fun dp(v: Float) = v * density

    private var activeTarget: AnchorTarget = AnchorTarget.JOYSTICK

    private var joyX = 0f
    private var joyY = 0f
    private var playerX = 0f
    private var playerY = 0f
    private var stickRadius = 0f

    private var dragging = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val touchSlop = dp(6f)

    // --- layout, recomputed whenever the size changes ---
    private val joyRect = RectF()
    private val playerRect = RectF()
    private val targetTabRect = RectF()
    private val autoDetectRect = RectF()
    private val commitRect = RectF()
    private val cancelRect = RectF()
    private val statusRect = RectF()

    // --- paints ---
    private val scrimPaint = Paint().apply { color = Color.argb(150, 4, 2, 10) }
    private val joyRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.argb(255, 0, 240, 255)
    }
    private val joyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(60, 0, 240, 255)
    }
    private val playerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.argb(255, 5, 255, 161)
    }
    private val playerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(60, 5, 255, 161)
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 14, 18, 32)
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val buttonStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(13f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 210, 225, 245)
        textSize = dp(11f)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.WHITE
    }
    private val tickPath = Path()

    private var statusText = "Touch the playfield to move the JOYSTICK anchor."

    init {
        // The view draws its own background, so it must opt out of the framework
        // skipping onDraw.
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        applyAnchors(anchors)
    }

    fun applyAnchors(value: Anchors) {
        anchors = value
        joyX = value.joystickX * displayWidthPx
        joyY = value.joystickY * displayHeightPx
        playerX = value.playerX * displayWidthPx
        playerY = value.playerY * displayHeightPx
        stickRadius = (value.joystickRadiusNorm * displayWidthPx).coerceAtLeast(dp(48f))
        invalidate()
    }

    fun setStatus(text: String) {
        statusText = text
        invalidate()
    }

    fun activeAnchor(): AnchorTarget = activeTarget

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutUi(w, h)
    }

    /**
     * Buttons are laid out from the *view's* measured size, not the display size,
     * so they stay inside the window even if the overlay is inset by a cutout or
     * a system bar.
     */
    private fun layoutUi(w: Int, h: Int) {
        val pad = dp(12f)
        val headerH = dp(112f)
        val barH = dp(58f)
        val bottomInset = dp(24f)

        val headerLeft = pad
        val headerRight = w - pad
        targetTabRect.set(
            headerLeft + pad,
            pad + dp(30f),
            headerLeft + pad + (headerRight - headerLeft - pad * 2) * 0.5f - dp(4f),
            pad + dp(64f)
        )
        autoDetectRect.set(
            targetTabRect.right + dp(8f),
            targetTabRect.top,
            headerRight - pad,
            targetTabRect.bottom
        )
        statusRect.set(headerLeft, pad + dp(70f), headerRight, pad + dp(96f))

        val barTop = h - bottomInset - barH
        val gap = dp(10f)
        val halfW = (w - pad * 2 - gap) / 2f
        cancelRect.set(pad, barTop, pad + halfW, barTop + barH)
        commitRect.set(pad + halfW + gap, barTop, w - pad, barTop + barH)

        joyRect.set(joyX - stickRadius, joyY - stickRadius, joyX + stickRadius, joyY + stickRadius)
        playerRect.set(
            playerX - dp(56f), playerY - dp(56f),
            playerX + dp(56f), playerY + dp(56f)
        )
        headerHeightHint = headerH
    }

    private var headerHeightHint = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(0f, 0f, w, h, scrimPaint)

        // Header plate.
        val pad = dp(12f)
        canvas.drawRoundRect(
            pad, pad, w - pad, pad + headerHeightHint, dp(10f), dp(10f), headerPaint
        )

        drawReticle(canvas, joyX, joyY, stickRadius, joyRingPaint, joyFillPaint, "STICK")
        drawReticle(canvas, playerX, playerY, dp(56f), playerRingPaint, playerFillPaint, "BRAWLER")

        // Target tabs.
        drawButton(
            canvas, targetTabRect, "JOYSTICK",
            active = activeTarget == AnchorTarget.JOYSTICK
        )
        drawButton(
            canvas, autoDetectRect, "AUTO DETECT",
            active = false, actionButton = true
        )
        canvas.drawText(statusText, statusRect.left, statusRect.centerY(), smallTextPaint)

        drawButton(canvas, cancelRect, "CANCEL", active = false, danger = true)
        drawButton(canvas, commitRect, "LOCK & ACTIVATE", active = true, primary = true)
    }

    private fun drawReticle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        ring: Paint,
        fill: Paint,
        label: String
    ) {
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, ring)
        // Cross hair, so the exact anchor point is unambiguous.
        val arm = r * 0.22f
        canvas.drawLine(cx - arm, cy, cx + arm, cy, crossPaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, crossPaint)
        val labelY = cy - r - dp(6f)
        canvas.drawText(label, cx - textPaint.measureText(label) / 2f, labelY, textPaint)
    }

    private fun drawButton(
        canvas: Canvas,
        rect: RectF,
        label: String,
        active: Boolean,
        primary: Boolean = false,
        danger: Boolean = false,
        actionButton: Boolean = false
    ) {
        buttonPaint.color = when {
            primary -> Color.argb(255, 5, 255, 161)
            danger -> Color.argb(230, 190, 40, 40)
            actionButton -> Color.argb(200, 60, 48, 12)
            active -> Color.argb(255, 0, 240, 255)
            else -> Color.argb(160, 40, 44, 56)
        }
        buttonStrokePaint.color = if (active || primary) {
            Color.argb(255, 255, 255, 255)
        } else {
            Color.argb(140, 150, 160, 180)
        }
        canvas.drawRoundRect(rect, dp(12f), dp(12f), buttonPaint)
        canvas.drawRoundRect(rect, dp(12f), dp(12f), buttonStrokePaint)
        textPaint.color = if (primary || active) Color.BLACK else Color.WHITE
        canvas.drawText(
            label,
            rect.centerX() - textPaint.measureText(label) / 2f,
            rect.centerY() + textPaint.textSize * 0.36f,
            textPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Buttons are tested first and unconditionally win. This is the
                // precedence the old layered layout could not guarantee.
                when {
                    commitRect.contains(x, y) -> {
                        performClick()
                        callbacks.onCommitted(currentAnchors())
                        return true
                    }
                    cancelRect.contains(x, y) -> {
                        performClick()
                        callbacks.onCancelled()
                        return true
                    }
                    autoDetectRect.contains(x, y) -> {
                        performClick()
                        callbacks.onAutoDetectRequested()
                        return true
                    }
                    targetTabRect.contains(x, y) -> {
                        performClick()
                        setActiveTarget(
                            if (activeTarget == AnchorTarget.JOYSTICK) AnchorTarget.PLAYER
                            else AnchorTarget.JOYSTICK
                        )
                        callbacks.onTargetChanged(activeTarget)
                        return true
                    }
                }
                // Anything else starts a reticle drag.
                dragging = true
                lastTouchX = x
                lastTouchY = y
                moveActiveAnchor(x, y, force = true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                if (hypot(x - lastTouchX, y - lastTouchY) < touchSlop) return true
                lastTouchX = x
                lastTouchY = y
                moveActiveAnchor(x, y, force = false)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Always consume the whole gesture, including UP. Returning false
                // here would let a parent steal the stream and leave the reticle
                // half moved.
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun moveActiveAnchor(x: Float, y: Float, force: Boolean) {
        // Reticle centres must be at least their own radius away from the edge,
        // otherwise half the circle lands off screen and the drag is useless.
        val cx: Float
        val cy: Float
        when (activeTarget) {
            AnchorTarget.JOYSTICK -> {
                cx = x.coerceIn(stickRadius, displayWidthPx - stickRadius)
                cy = y.coerceIn(stickRadius, displayHeightPx - stickRadius)
                joyX = cx
                joyY = cy
            }
            AnchorTarget.PLAYER -> {
                val r = dp(56f)
                cx = x.coerceIn(r, displayWidthPx - r)
                cy = y.coerceIn(r, displayHeightPx - r)
                playerX = cx
                playerY = cy
            }
        }
        applyAnchors(
            anchors.copy(
                joystickX = joyX / displayWidthPx,
                joystickY = joyY / displayHeightPx,
                playerX = playerX / displayWidthPx,
                playerY = playerY / displayHeightPx,
                joystickRadiusNorm = (stickRadius / displayWidthPx).coerceIn(0.02f, 0.45f)
            )
        )
        callbacks.onAnchorMoved(activeTarget, cx, cy)
    }

    private fun setActiveTarget(target: AnchorTarget) {
        activeTarget = target
        statusText = if (target == AnchorTarget.JOYSTICK) {
            "Touch the playfield to move the JOYSTICK anchor."
        } else {
            "Touch your BRAWLER to set the player collider."
        }
        invalidate()
    }

    private fun currentAnchors(): Anchors = Anchors(
        joystickX = joyX / displayWidthPx,
        joystickY = joyY / displayHeightPx,
        playerX = playerX / displayWidthPx,
        playerY = playerY / displayHeightPx,
        joystickRadiusNorm = (stickRadius / displayWidthPx).coerceIn(0.02f, 0.45f),
        calibrated = true,
        calibratedForWidth = displayWidthPx,
        calibratedForHeight = displayHeightPx
    )

    /** Compact, log-friendly summary. */
    fun describe(): String {
        val jx = (anchors.joystickX * 100f).roundToInt()
        val jy = (anchors.joystickY * 100f).roundToInt()
        val px = (anchors.playerX * 100f).roundToInt()
        val py = (anchors.playerY * 100f).roundToInt()
        return "Joy ${jx}%/${jy}% Player ${px}%/${py}%"
    }
}
