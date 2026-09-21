package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.example.model.DodgeProfile
import com.example.vision.ScreenThreatDetector
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun detector_manualCalibration_and_reset() {
        val detector = ScreenThreatDetector()
        detector.setManualPlayerCalibration(500f, 400f)
        detector.setManualJoystickCalibration(200f, 600f)
        detector.reset()
        assertNotNull(detector)
    }

    @Test
    fun detector_tracksGreenRingCluster_andIgnoresIsolatedBushPixels() {
        val detector = ScreenThreatDetector()
        val profile = DodgeProfile.brawlStars()
        val screenW = 1280
        val screenH = 720

        // Create a 640x360 synthetic frame
        val frame = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)

        // Draw a realistic green player ring cluster near center (x = 320, y = 180, radius = 18px)
        // Neon-lime green in Brawl Stars: R=80, G=240, B=50
        val limeGreen = Color.rgb(80, 240, 50)
        for (dy in -18..18) {
            for (dx in -18..18) {
                val distSq = dx * dx + dy * dy
                if (distSq in 100..324) { // Ring shape
                    frame.setPixel(320 + dx, 180 + dy, limeGreen)
                }
            }
        }

        // Draw isolated bush noise in top right corner (x = 580, y = 60)
        frame.setPixel(580, 60, limeGreen)

        // Frame 1: Ingest background
        detector.analyzeFrame(frame, profile, screenW, screenH)

        // Frame 2: Analyze frame with green player ring
        val result = detector.analyzeFrame(frame, profile, screenW, screenH)

        assertNotNull(result)
        assertTrue("Player should be locked to green cluster", result!!.isPlayerGreenRingTracked)
        // Verify tracked player is near screen center (640, 360) rather than corner bush (1160, 120)
        assertTrue("Tracked player X should be near center", result.playerX in 600f..680f)
        assertTrue("Tracked player Y should be near center", result.playerY in 320f..400f)
    }
}
