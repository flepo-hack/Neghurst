package com.example.vision

import kotlin.math.roundToInt

/**
 * Screen anchors stored **normalised** and resolved against the live display.
 *
 * ## Why normalised, and why the display signature matters
 *
 * The previous code stored joystick and player positions as raw pixels in one
 * flat key, and separately kept a hard-coded `0.20 * screenWidth` fallback. On
 * rotation, on a foldable, or on a different device that fallback silently
 * replaced the user's calibration after a handful of missed detections, which is
 * precisely the "player and joystick are in the wrong place" symptom.
 *
 * Two rules follow, and both are enforced here:
 *
 *  1. Anchors are fractions of the display, so they survive resolution changes.
 *  2. An anchor is only valid for the display signature it was calibrated on.
 *     Rotating swaps width and height, so a `(0.2, 0.78)` joystick in landscape
 *     is not a `(0.2, 0.78)` joystick in portrait. Calibration records the
 *     orientation and is treated as invalid for the other one, which forces a
 *     re-calibration instead of a wrong guess.
 */
data class Anchors(
    /** Joystick base, as a fraction of the display. */
    val joystickX: Float,
    val joystickY: Float,
    /** Player collider centre, as a fraction of the display. */
    val playerX: Float,
    val playerY: Float,
    /** Joystick's own usable radius, as a fraction of display **width**. */
    val joystickRadiusNorm: Float,
    /** True once a calibration has actually been committed. */
    val calibrated: Boolean,
    /** Display width the calibration was taken on. */
    val calibratedForWidth: Int,
    val calibratedForHeight: Int
) {
    /**
     * True when these anchors were captured on a display of the given size.
     * A size mismatch means the fractions are still meaningful but the stick
     * radius in particular was guessed, so callers should re-calibrate.
     */
    fun matchesDisplay(width: Int, height: Int): Boolean =
        calibrated && calibratedForWidth == width && calibratedForHeight == height

    fun joystickPx(width: Int, height: Int): PointF = PointF(
        joystickX * width,
        joystickY * height
    )

    fun playerPx(width: Int, height: Int): PointF = PointF(
        playerX * width,
        playerY * height
    )

    fun joystickRadiusPx(width: Int): Float = joystickRadiusNorm * width

    companion object {
        /**
         * Landscape default, used only until the user calibrates. Brawl Stars
         * puts the movement stick at the bottom left (or bottom right in
         * left-handed mode) and keeps the brawler near the middle of the view.
         */
        fun landscapeDefault(width: Int, height: Int) = Anchors(
            joystickX = 0.17f,
            joystickY = 0.76f,
            playerX = 0.50f,
            playerY = 0.52f,
            joystickRadiusNorm = 0.13f,
            calibrated = false,
            calibratedForWidth = width,
            calibratedForHeight = height
        )

        fun portraitDefault(width: Int, height: Int) = Anchors(
            joystickX = 0.20f,
            joystickY = 0.80f,
            playerX = 0.50f,
            playerY = 0.55f,
            joystickRadiusNorm = 0.20f,
            calibrated = false,
            calibratedForWidth = width,
            calibratedForHeight = height
        )

        /**
         * Picks the orientation-appropriate default. Callers must pass the
         * *rotated* display size, i.e. the one the game is actually rendering
         * into.
         */
        fun defaultFor(width: Int, height: Int): Anchors =
            if (width > height) landscapeDefault(width, height)
            else portraitDefault(width, height)
    }
}

/** Minimal point so this file stays free of Android imports and unit testable. */
data class PointF(val x: Float, val y: Float) {
    fun coerceInside(width: Int, height: Int, marginPx: Float = 0f): PointF = PointF(
        x.coerceIn(marginPx, (width - marginPx).coerceAtLeast(marginPx)),
        y.coerceIn(marginPx, (height - marginPx).coerceAtLeast(marginPx))
    )
}

/** What the calibration overlay is currently editing. */
enum class AnchorTarget { JOYSTICK, PLAYER }

private const val STICK_RADIUS_OF_SHORT_EDGE = 0.22f
private const val PLAYER_MARKER_RADIUS_OF_SHORT_EDGE = 0.23f

/** An orientation, so calibration validity can be keyed to it explicitly. */
enum class ScreenOrientation { LANDSCAPE, PORTRAIT;

    companion object {
        fun of(width: Int, height: Int): ScreenOrientation =
            if (width > height) LANDSCAPE else PORTRAIT
    }
}

/**
 * Captures a calibration event from a touch and folds it into [Anchors].
 *
 * Kept as a pure function so the exact coordinate mapping can be unit tested
 * without a window, a service, or a device.
 */
object AnchorCalibrator {

    /**
     * Records [screenX]/[screenY] (screen pixels) as the anchor for [target].
     *
     * The stick radius is derived from the display rather than from the drag
     * distance, because the stick's usable radius is a property of the game UI
     * at a given resolution, not of how far the user happened to move.
     */
    fun applyTouch(
        anchors: Anchors,
        target: AnchorTarget,
        screenX: Float,
        screenY: Float,
        displayWidth: Int,
        displayHeight: Int,
        displayRotation: Int = 0
    ): Anchors {
        if (displayWidth <= 0 || displayHeight <= 0) return anchors

        val radiusNorm = when (target) {
            // The stick radius is a function of the display's short edge, which
            // is the dimension the game scales its HUD along in both
            // orientations. Expressed as a fraction of width so it stays
            // meaningful after a resolution change.
            AnchorTarget.JOYSTICK -> {
                val shortEdge = minOf(displayWidth, displayHeight)
                (shortEdge * STICK_RADIUS_OF_SHORT_EDGE) / displayWidth
            }
            AnchorTarget.PLAYER -> anchors.joystickRadiusNorm
        }

        // The anchor has to sit at least one stick radius from the edge, not a
        // flat 3% margin. With a large radius the small margin let the stick
        // base land near the border, where half the virtual stick is off screen
        // and the drag target is unreachable.
        val baseMargin = when (target) {
            AnchorTarget.JOYSTICK -> radiusNorm * displayWidth
            AnchorTarget.PLAYER -> minOf(displayWidth, displayHeight) * PLAYER_MARKER_RADIUS_OF_SHORT_EDGE
        }
        val marginX = (displayWidth * 0.03f).coerceAtLeast(baseMargin)
        val marginY = (displayHeight * 0.03f).coerceAtLeast(baseMargin)
        val clamped = PointF(screenX, screenY).coerceInside(displayWidth, displayHeight, 0f)
        val safe = PointF(
            clamped.x.coerceIn(marginX, (displayWidth - marginX).coerceAtLeast(marginX)),
            clamped.y.coerceIn(marginY, (displayHeight - marginY).coerceAtLeast(marginY))
        )

        return when (target) {
            AnchorTarget.JOYSTICK -> anchors.copy(
                joystickX = safe.x / displayWidth,
                joystickY = safe.y / displayHeight,
                joystickRadiusNorm = radiusNorm,
                calibrated = true,
                calibratedForWidth = displayWidth,
                calibratedForHeight = displayHeight
            )
            AnchorTarget.PLAYER -> anchors.copy(
                playerX = safe.x / displayWidth,
                playerY = safe.y / displayHeight,
                calibrated = true,
                calibratedForWidth = displayWidth,
                calibratedForHeight = displayHeight
            )
        }
    }

    /**
     * Returns anchors valid for [displayWidth] x [displayHeight].
     *
     * A calibration made in the other orientation is discarded rather than
     * reinterpreted, because rotating the device does not move the on-screen
     * stick to the same relative spot. The caller is expected to re-calibrate;
     * [Anchors.calibrated] is false in the returned value so the UI can say so.
     */
    fun adaptToDisplay(anchors: Anchors, displayWidth: Int, displayHeight: Int): Anchors =
        if (anchors.matchesDisplay(displayWidth, displayHeight)) {
            anchors
        } else if (!anchors.calibrated) {
            Anchors.defaultFor(displayWidth, displayHeight)
        } else {
            Anchors.defaultFor(displayWidth, displayHeight)
        }

    /** Normalised position of a screen pixel, for feeding the native mask. */
    fun normalise(x: Float, y: Float, displayWidth: Int, displayHeight: Int): PointF =
        PointF(x / displayWidth, y / displayHeight)

    /**
     * Suggests a joystick anchor from the geometry alone.
     *
     * Brawl Stars pins the movement stick to the bottom corner of the landscape
     * view, so the quadrant is not a guess. What the user actually has to supply
     * is the exact centre, and that is what the calibration overlay is for: this
     * function only removes the guesswork of where to put the crosshair, and it
     * is always overridable.
     *
     * Both orientations are handled, and left-handed layouts are supported by
     * passing [leftHanded], which mirrors the quadrant.
     */
    fun suggestJoystick(
        displayWidth: Int,
        displayHeight: Int,
        leftHanded: Boolean = false
    ): Anchors {
        val landscape = displayWidth > displayHeight
        val shortEdge = minOf(displayWidth, displayHeight)
        val radiusNorm = (shortEdge * STICK_RADIUS_OF_SHORT_EDGE) / displayWidth

        // Brawl Stars places the stick low and inboard of the corner. The exact
        // fraction varies slightly by aspect ratio, so it is derived from the
        // short edge rather than hard coded per resolution.
        // The inset has to be at least the stick radius, or the virtual stick
        // hangs off the edge and the drag target is unreachable. That is not a
        // theoretical concern: on a narrow portrait display the radius is derived
        // from the short edge while a 17% inset of that same narrow width is
        // smaller than the radius.
        val radiusPx = radiusNorm * displayWidth
        val insetX = (displayWidth * 0.17f).coerceAtLeast(radiusPx)
        val yFromBottom = (displayHeight * 0.24f).coerceAtLeast(radiusPx)
        val x = if (leftHanded) displayWidth - insetX else insetX
        val y = if (landscape) displayHeight - yFromBottom else displayHeight - yFromBottom * 0.8f

        // Belt and braces: the resulting circle must fit, whatever the input.
        val safeRadius = minOf(radiusPx, displayWidth / 2f, displayHeight / 2f)

        return Anchors(
            joystickX = x.coerceIn(safeRadius, displayWidth - safeRadius) / displayWidth,
            joystickY = y.coerceIn(safeRadius, displayHeight - safeRadius) / displayHeight,
            playerX = 0.5f,
            playerY = if (landscape) 0.52f else 0.55f,
            joystickRadiusNorm = radiusNorm,
            // A suggestion is not a calibration. Marking it calibrated would let
            // the vision engine trust an anchor nobody ever verified, which is
            // the class of bug this whole class exists to prevent.
            calibrated = false,
            calibratedForWidth = displayWidth,
            calibratedForHeight = displayHeight
        )
    }

    /** Whole percent, for compact HUD text. */
    fun describe(anchors: Anchors): String {
        val joyX = (anchors.joystickX * 100f).roundToInt()
        val joyY = (anchors.joystickY * 100f).roundToInt()
        val playerX = (anchors.playerX * 100f).roundToInt()
        val playerY = (anchors.playerY * 100f).roundToInt()
        val state = if (anchors.calibrated) "LOCKED" else "DEFAULT"
        return "Joy $joyX%/$joyY%  Player $playerX%/$playerY%  [$state]"
    }
}
