package com.vcodec.smartencoder.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoAnalyzerTest {

    @Test
    fun testCalculateTargetDimensions_standardLandscape() {
        // 4K Landscape (3840x2160) to 1080p -> should be 1920x1080
        val (w1080, h1080) = VideoAnalyzer.calculateTargetDimensions(
            origWidth = 3840,
            origHeight = 2160,
            rotation = 0,
            targetResStr = "1080p"
        )
        assertEquals(1920, w1080)
        assertEquals(1080, h1080)
        assertEquals(0, w1080 % 2)
        assertEquals(0, h1080 % 2)

        // 4K Landscape to 720p -> should be 1280x720
        val (w720, h720) = VideoAnalyzer.calculateTargetDimensions(
            origWidth = 3840,
            origHeight = 2160,
            rotation = 0,
            targetResStr = "720p"
        )
        assertEquals(1280, w720)
        assertEquals(720, h720)
        assertEquals(0, w720 % 2)
        assertEquals(0, h720 % 2)

        // 4K Landscape to Original -> should keep 3840x2160
        val (wOrig, hOrig) = VideoAnalyzer.calculateTargetDimensions(
            origWidth = 3840,
            origHeight = 2160,
            rotation = 0,
            targetResStr = "Original"
        )
        assertEquals(3840, wOrig)
        assertEquals(2160, hOrig)
    }

    @Test
    fun testCalculateTargetDimensions_portraitWithRotation() {
        // Camera raw buffer is 3840x2160 with rotation 90 (logical 2160x3840)
        val (w1080Rot, h1080Rot) = VideoAnalyzer.calculateTargetDimensions(
            origWidth = 3840,
            origHeight = 2160,
            rotation = 90,
            targetResStr = "1080p"
        )
        // For portrait, max dimension is height (1920), width scales to 1080
        assertEquals(1080, w1080Rot)
        assertEquals(1920, h1080Rot)
        assertEquals(0, w1080Rot % 2)
        assertEquals(0, h1080Rot % 2)

        // Logical portrait with rotation 270
        val (w720Rot, h720Rot) = VideoAnalyzer.calculateTargetDimensions(
            origWidth = 3840,
            origHeight = 2160,
            rotation = 270,
            targetResStr = "720p"
        )
        assertEquals(720, w720Rot)
        assertEquals(1280, h720Rot)
        assertEquals(0, w720Rot % 2)
        assertEquals(0, h720Rot % 2)
    }

    @Test
    fun testCalculateTargetDimensions_invalidDimensions() {
        val (w, h) = VideoAnalyzer.calculateTargetDimensions(
            origWidth = 0,
            origHeight = 0,
            rotation = 0,
            targetResStr = "1080p"
        )
        assertEquals(0, w)
        assertEquals(0, h)
    }

    @Test
    fun testCalculateSuggestedBitrate_4kH264ToHevc() {
        val originalBitrate = 40_000_000 // 40 Mbps
        val suggested = VideoAnalyzer.calculateSuggestedBitrate(
            width = 3840,
            height = 2160,
            frameRate = 30,
            isHdr = false,
            originalBitrate = originalBitrate,
            isHevc = false,
            isAv1 = false
        )
        // Base for 4K is 12 Mbps, H.264 50% discount = 20 Mbps, clamped to min(12M, 20M) = 12M
        assertEquals(12_000_000, suggested)
    }

    @Test
    fun testCalculateSuggestedBitrate_60fpsAndHdrScaling() {
        val originalBitrate = 60_000_000
        val suggested = VideoAnalyzer.calculateSuggestedBitrate(
            width = 3840,
            height = 2160,
            frameRate = 60,
            isHdr = true,
            originalBitrate = originalBitrate,
            isHevc = false,
            isAv1 = false
        )
        // 12M * 1.4 (60fps) * 1.25 (HDR) = 21M
        assertEquals(21_000_000, suggested)
    }

    @Test
    fun testCalculateSuggestedBitrate_hevcRecompression() {
        val originalBitrate = 10_000_000 // 10 Mbps HEVC 4K
        val suggested = VideoAnalyzer.calculateSuggestedBitrate(
            width = 3840,
            height = 2160,
            frameRate = 30,
            isHdr = false,
            originalBitrate = originalBitrate,
            isHevc = true,
            isAv1 = false
        )
        // Base 12M vs original 10M * 0.75 = 7.5M -> takes 7.5M
        assertEquals(7_500_000, suggested)
    }

    @Test
    fun testCalculateSuggestedBitrate_minimumFloor() {
        val originalBitrate = 500_000 // Very low bitrate
        val suggested = VideoAnalyzer.calculateSuggestedBitrate(
            width = 640,
            height = 480,
            frameRate = 30,
            isHdr = false,
            originalBitrate = originalBitrate,
            isHevc = false,
            isAv1 = false
        )
        // Clamped by floor of 800_000
        assertTrue(suggested >= 800_000)
    }
}
