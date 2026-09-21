package com.example.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.example.model.DodgeProfile
import com.example.model.EntityType
import com.example.model.ThreatLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
    fun testAutoCalibrateFromFrame_withSyntheticPlayerAndJoystick() {
        val detector = ScreenThreatDetector()
        val width = 640
        val height = 360
        val testBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Paint bright blue joystick cluster at (120, 260)
        val blueColor = Color.rgb(40, 140, 240)
        for (dx in -15..15) {
            for (dy in -15..15) {
                testBitmap.setPixel(120 + dx, 260 + dy, blueColor)
            }
        }

        // Paint neon green player ring cluster at (320, 180)
        val greenColor = Color.rgb(15, 230, 120)
        for (dx in -12..12) {
            for (dy in -12..12) {
                testBitmap.setPixel(320 + dx, 180 + dy, greenColor)
            }
        }

        val (joy, player) = detector.autoCalibrateFromFrame(
            frame = testBitmap,
            screenWidth = 1920,
            screenHeight = 1080,
            activeWidth = width,
            activeHeight = height
        )

        // Verify mapped scaling to 1920x1080
        val expectedJoyX = (120f / width.toFloat()) * 1920f
        val expectedJoyY = (260f / height.toFloat()) * 1080f
        val expectedPlayerX = (320f / width.toFloat()) * 1920f
        val expectedPlayerY = (180f / height.toFloat()) * 1080f

        assertEquals(expectedJoyX, joy.first, 50f)
        assertEquals(expectedJoyY, joy.second, 50f)
        assertEquals(expectedPlayerX, player.first, 50f)
        assertEquals(expectedPlayerY, player.second, 50f)
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
}
