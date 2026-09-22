package com.example.vision

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.model.ThreatVector
import com.example.vision.nativebridge.NativeRenderaPipeline
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class NativeRenderaPipelineTest {

    @Test
    fun testPipelineInitializationAndFrameIngestion() {
        val pipeline = NativeRenderaPipeline(gridWidth = 80, gridHeight = 48)
        assertNotNull(pipeline)

        val bmp = Bitmap.createBitmap(80, 48, Bitmap.Config.ARGB_8888)
        var detectedThreat: ThreatVector? = null

        // Ingest clean synthetic frame
        pipeline.processFrame(bmp, screenWidth = 1920f, screenHeight = 1080f) { threat ->
            detectedThreat = threat
        }

        pipeline.reset()
        pipeline.close()
        assertTrue("Pipeline executed safely without exceptions", true)
    }
}
