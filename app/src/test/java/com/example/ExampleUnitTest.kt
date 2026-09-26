package com.example

import com.example.vision.Anchors
import com.example.vision.ScreenOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Small sanity tests that do not need Android.
 */
class ExampleUnitTest {

    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun anchors_default_to_a_usable_but_uncalibrated_state() {
        val anchors = Anchors.defaultFor(2400, 1080)
        assertNotNull(anchors)
        assertFalse(
            "an uncalibrated default must never look calibrated",
            anchors.calibrated
        )
        assertEquals(ScreenOrientation.LANDSCAPE, ScreenOrientation.of(2400, 1080))
    }

    @Test
    fun a_detector_can_be_constructed_and_closed_without_a_device() {
        val detector = com.example.vision.ScreenThreatDetector(80, 48, 1920, 1080)
        try {
            assertNotNull(detector)
        } finally {
            detector.close()
        }
    }
}
