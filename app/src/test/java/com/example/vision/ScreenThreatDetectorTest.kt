package com.example.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.example.model.DodgeProfile
import com.example.model.EntityType
import com.example.model.ThreatLevel
import com.example.vision.deterministic.CannyEdgeAndHealthBarDetector
import com.example.vision.deterministic.CollisionDodgeEngine
import com.example.vision.deterministic.DeterministicFrameBuffer
import com.example.vision.deterministic.GlobalMotionCompensator
import com.example.vision.deterministic.HoughCircleDetector
import com.example.vision.deterministic.KalmanTrajectoryTracker
import com.example.vision.deterministic.LowLatencyTouchController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenThreatDetectorTest {

    @Test
    fun testAutoCalibrateFromFrame_fallbackDefaults() {
        val detector = ScreenThreatDetector()
        val width = 640
        val height = 360
        val blankBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val (joy, player) = detector.autoCalibrateFromFrame(
            frame = blankBitmap,
            screenWidth = 1920,
            screenHeight = 1080,
            activeWidth = width,
            activeHeight = height
        )

        // Fallback positions should be reasonable landscape anchors
        assertEquals(0.20f * 1920, joy.first, 1.0f)
        assertEquals(0.78f * 1080, joy.second, 1.0f)
        assertEquals(0.50f * 1920, player.first, 1.0f)
        assertEquals(0.50f * 1080, player.second, 1.0f)
    }

    @Test
    fun testAnalyzeFrame_createsDebugEntities() {
        val detector = ScreenThreatDetector()
        val width = 640
        val height = 360
        val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Set manual anchors
        detector.setManualJoystickCalibration(200f, 600f)
        detector.setManualPlayerCalibration(500f, 400f)

        val profile = DodgeProfile.brawlStars()

        // First frame initializes baseline luminance
        detector.analyzeFrame(
            frame = frame,
            profile = profile,
            screenWidth = 1080,
            screenHeight = 800,
            activeWidth = width,
            activeHeight = height
        )

        // Second frame produces full FrameAnalysisResult
        val result = detector.analyzeFrame(
            frame = frame,
            profile = profile,
            screenWidth = 1080,
            screenHeight = 800,
            activeWidth = width,
            activeHeight = height
        )

        assertNotNull(result)
        val analysis = result!!
        assertEquals(200f, analysis.joystickX, 1.0f)
        assertEquals(600f, analysis.joystickY, 1.0f)
        assertEquals(500f, analysis.playerX, 1.0f)
        assertEquals(400f, analysis.playerY, 1.0f)

        // Verify debug entities include Player and Joystick
        val entities = analysis.debugEntities
        assertTrue(entities.any { it.type == EntityType.PLAYER })
        assertTrue(entities.any { it.type == EntityType.JOYSTICK })
    }

    @Test
    fun testGlobalMotionCompensation_detectsCameraShiftAndCancelsMotion() {
        val w = 80
        val h = 48
        val gmc = GlobalMotionCompensator(w, h, maxShiftPx = 6)

        // Create a synthetic reference texture with high gradient contrast in reference zones
        val frame1 = ByteArray(w * h)
        val frame2 = ByteArray(w * h)

        val shiftDx = 2
        val shiftDy = -1

        for (y in 0 until h) {
            for (x in 0 until w) {
                // High contrast checker/hash pattern
                val base = if (((x / 4) + (y / 4)) % 2 == 0) 180 else 40
                frame1[y * w + x] = base.toByte()

                val shiftedX = (x + shiftDx).coerceIn(0, w - 1)
                val shiftedY = (y + shiftDy).coerceIn(0, h - 1)
                val shiftedBase = if (((shiftedX / 4) + (shiftedY / 4)) % 2 == 0) 180 else 40
                frame2[y * w + x] = shiftedBase.toByte()
            }
        }

        val motion = gmc.computeCameraMotion(currentGrayscale = frame2, prevGrayscale = frame1)
        assertTrue("Camera motion should be detected", motion.isCameraMoving || motion.confidence > 0f)

        // Verify alignment and subtraction cancels the background
        val aligned = ByteArray(w * h)
        val diff = ByteArray(w * h)
        gmc.compensateAndSubtract(frame2, frame1, aligned, diff, motion, noiseFloor = 20)

        // Calculate residual energy
        var nonZeroDiff = 0
        for (b in diff) {
            if ((b.toInt() and 0xFF) > 0) nonZeroDiff++
        }
        val diffRatio = nonZeroDiff.toFloat() / (w * h)
        assertTrue("Compensated difference should be mostly clean (< 35% residual): $diffRatio", diffRatio < 0.35f)
    }

    @Test
    fun testCannyEdgeAndHealthBarDetector_identifies4to1Rectangle() {
        val w = 80
        val h = 48
        val detector = CannyEdgeAndHealthBarDetector(w, h, lowThreshold = 20, highThreshold = 50)

        val testImg = ByteArray(w * h)

        // Paint a distinct 4:1 health bar in center (width: 32px, height: 8px -> ratio 4.0)
        val barLeft = 24
        val barTop = 20
        val barW = 32
        val barH = 8

        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x in barLeft until (barLeft + barW) && y in barTop until (barTop + barH)) {
                    // Bright health bar
                    testImg[y * w + x] = 220.toByte()
                } else {
                    // Dark background
                    testImg[y * w + x] = 30.toByte()
                }
            }
        }

        detector.processFrame(testImg, screenCenterX = w / 2f, screenCenterY = h / 2f)

        val playerBar = detector.playerBar
        assertNotNull("Should detect 4:1 health bar", playerBar)
        assertTrue("Aspect ratio should be ~4:1", playerBar!!.aspectRatio in 2.8f..5.5f)
    }

    @Test
    fun testHoughCircleDetector_detectsCircle() {
        val w = 80
        val h = 48
        val hough = HoughCircleDetector(w, h, minRadius = 10, maxRadius = 24, radiusStep = 2)

        val edgeMap = ByteArray(w * h)
        val gradDir = ByteArray(w * h)

        // Draw a circle centered at (40, 24) with radius 14
        val cx = 40
        val cy = 24
        val r = 14

        for (angleDeg in 0 until 360 step 10) {
            val rad = Math.toRadians(angleDeg.toDouble())
            val px = (cx + r * cos(rad)).toInt()
            val py = (cy + r * sin(rad)).toInt()
            if (px in 0 until w && py in 0 until h) {
                edgeMap[py * w + px] = 2.toByte() // Strong edge

                // Gradient direction points radially outward
                val dir: Byte = if (abs(cos(rad)) > 2 * abs(sin(rad))) {
                    2.toByte() // Horizontal gradient (vertical edge)
                } else if (abs(sin(rad)) > 2 * abs(cos(rad))) {
                    0.toByte() // Vertical gradient (horizontal edge)
                } else if ((cos(rad) > 0 && sin(rad) > 0) || (cos(rad) < 0 && sin(rad) < 0)) {
                    1.toByte() // 45°
                } else {
                    3.toByte() // 135°
                }
                gradDir[py * w + px] = dir
            }
        }

        hough.detectCircles(edgeMap, gradDir, minVoteThreshold = 6)
        assertTrue("Hough should detect at least 1 circle", hough.detectedCount >= 1)
        val circle = hough.detectedCircles[0]
        assertEquals(cx.toFloat(), circle.centerX, 6f)
        assertEquals(cy.toFloat(), circle.centerY, 6f)
    }

    @Test
    fun testKalmanTrajectoryTracker_identifiesHighVelocityProjectile() {
        val tracker = KalmanTrajectoryTracker()
        val now = 1000000L
        val dtSec = 0.020f // 50 FPS

        // Simulate incoming rocket flying horizontally from right to left at 600 px/s
        var projX = 800f
        val projY = 300f
        val speedPxSec = 600f

        for (frame in 0 until 6) {
            val t = now + (frame * 20)
            projX -= speedPxSec * dtSec

            tracker.processObservations(
                observedX = floatArrayOf(projX),
                observedY = floatArrayOf(projY),
                observedCount = 1,
                nowTimestamp = t,
                dtSec = dtSec
            )
        }

        val tracks = tracker.getActiveTracks()
        assertTrue("Should have active track", tracks.isNotEmpty())
        val target = tracks[0]
        assertTrue("Should be classified as projectile due to constant high speed", target.isProjectile)
        assertTrue("Speed should be near 600 px/s", target.speed > 400f)
    }

    @Test
    fun testCollisionDodgeEngine_computesPerpendicularEvasionAngle() {
        val engine = CollisionDodgeEngine()
        val playerX = 400f
        val playerY = 300f
        val playerRadius = 55f

        // Create an incoming projectile moving directly towards player from right: vx = -500, vy = 0
        val projectile = KalmanTrajectoryTracker.TrackedTarget(
            id = 1,
            x = 445f, // 45px to the right of player -> impact in 90ms (LETHAL)
            y = 300f, // same Y
            vx = -500f, // heading directly towards player
            vy = 0f,
            speed = 500f,
            isProjectile = true,
            hits = 4
        )

        val solution = engine.evaluateCollision(
            playerX = playerX,
            playerY = playerY,
            playerRadius = playerRadius,
            projectiles = listOf(projectile),
            screenWidth = 1080f,
            screenHeight = 720f
        )

        assertTrue("Collision should be detected as imminent", solution.isImminentCollision)
        assertNotNull("Threat vector must be generated", solution.threat)
        assertTrue(
            "Threat level should be high (LETHAL or IMMINENT_DANGER)",
            solution.threat!!.threatLevel == ThreatLevel.LETHAL || solution.threat!!.threatLevel == ThreatLevel.IMMINENT_DANGER
        )

        // Trajectory is purely horizontal (180°), so perpendicular evasion must be either ~90° (down) or ~270° (up)
        val dodgeDeg = solution.dodgeAngleDeg
        assertNotNull(dodgeDeg)
        val isPerpendicular = abs(dodgeDeg!! - 90f) < 5f || abs(dodgeDeg - 270f) < 5f
        assertTrue("Dodge angle must be perpendicular (90° or 270°), was $dodgeDeg", isPerpendicular)
    }

    @Test
    fun testLowLatencyTouchController_calculatesVirtualJoystickStroke() {
        val controller = LowLatencyTouchController()
        val joyCenterX = 300f
        val joyCenterY = 700f
        val joyRadius = 140f
        val dodgeAngleDeg = 90f // Steer straight down

        val touchCmd = controller.calculateJoystickStroke(
            joyCenterX = joyCenterX,
            joyCenterY = joyCenterY,
            joyRadius = joyRadius,
            dodgeAngleDeg = dodgeAngleDeg,
            strokeFactor = 0.9f,
            screenWidth = 1920f,
            screenHeight = 1080f,
            durationMs = 20L
        )

        assertEquals(joyCenterX, touchCmd.startX, 0.1f)
        assertEquals(joyCenterY, touchCmd.startY, 0.1f)
        assertEquals(joyCenterX, touchCmd.endX, 1.0f) // Cos(90°) = 0
        assertEquals(joyCenterY + (joyRadius * 0.9f), touchCmd.endY, 1.0f) // Sin(90°) = 1
        assertEquals(20L, touchCmd.durationMs)
    }
}
