package com.vcodec.smartencoder.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DimensionCalculationTest {

    @Test
    fun test4kLandscapeScaling() {
        // 3840x2160 landscape -> 1080p target should be 1920x1080
        val (w1080, h1080) = VideoAnalyzer.calculateTargetDimensions(
            origWidth = 3840,
            origHeight = 2160,
            rotation = 0,
            targetResStr = "1080p"
        )
        assertEquals(1920, w1080)
        assertEquals(1080, h1080)

        // 3840x2160 landscape -> 720p target should be 1280x720
        val (w720, h720) = VideoAnalyzer.calculateTargetDimensions(
            origWidth = 3840,
            origHeight = 2160,
            rotation = 0,
            targetResStr = "720p"
        )
        assertEquals(1280, w720)
        assertEquals(720, h720)
    }

    @Test
    fun testPortraitVideoRotationSwapping() {
        // Source dimensions reported as 3840x2160 with rotation 90 (logical portrait: 2160x3840)
        val (w1080, h1080) = VideoAnalyzer.calculateTargetDimensions(
            origWidth = 3840,
            origHeight = 2160,
            rotation = 90,
            targetResStr = "1080p"
        )
        // Max dimension is height (1920), width scales down to 1080
        assertEquals(1080, w1080)
        assertEquals(1920, h1080)

        // Source dimensions reported as 3840x2160 with rotation 270
        val (w720, h720) = VideoAnalyzer.calculateTargetDimensions(
            origWidth = 3840,
            origHeight = 2160,
            rotation = 270,
            targetResStr = "720p"
        )
        assertEquals(720, w720)
        assertEquals(1280, h720)
    }

    @Test
    fun testEvenDimensionRounding() {
        // Odd dimensions: 1921x1081 -> should be rounded down to even integers (1920x1080)
        val (w, h) = VideoAnalyzer.calculateTargetDimensions(
            origWidth = 1921,
            origHeight = 1081,
            rotation = 0,
            targetResStr = "Original"
        )
        assertTrue("Width must be even for encoder compatibility", w % 2 == 0)
        assertTrue("Height must be even for encoder compatibility", h % 2 == 0)
        assertEquals(1920, w)
        assertEquals(1080, h)
    }

    @Test
    fun testOriginalResolutionPreserved() {
        val (w, h) = VideoAnalyzer.calculateTargetDimensions(
            origWidth = 1920,
            origHeight = 1080,
            rotation = 0,
            targetResStr = "Original"
        )
        assertEquals(1920, w)
        assertEquals(1080, h)
    }

    @Test
    fun testSmallerSourceNotUpscaled() {
        // 720p source requested at 1080p should not be upscaled
        val (w, h) = VideoAnalyzer.calculateTargetDimensions(
            origWidth = 1280,
            origHeight = 720,
            rotation = 0,
            targetResStr = "1080p"
        )
        assertEquals(1280, w)
        assertEquals(720, h)
    }
}
