package com.vcodec.smartencoder.analyzer

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoAnalyzerTest {

    @Test
    fun testBaseBitrateCalculations() {
        // 4K (pixels >= 3840 * 2160) target is 12,000,000
        val target4k = VideoAnalyzer.calculateSuggestedBitrate(
            width = 3840,
            height = 2160,
            frameRate = 30,
            isHdr = false,
            originalBitrate = 40_000_000,
            isHevc = false,
            isAv1 = false
        )
        // Expected = minOf(12M * 1.0 * 1.0, 40M * 0.5) = 12M, clamped to max 85% of original (34M), floor 800K
        assertEquals(12_000_000, target4k)

        // 1080p target is 4,000,000
        val target1080p = VideoAnalyzer.calculateSuggestedBitrate(
            width = 1920,
            height = 1080,
            frameRate = 30,
            isHdr = false,
            originalBitrate = 15_000_000,
            isHevc = false,
            isAv1 = false
        )
        // Expected = minOf(4M, 15M * 0.5 = 7.5M) = 4_000_000
        assertEquals(4_000_000, target1080p)

        // 720p target is 2,000,000
        val target720p = VideoAnalyzer.calculateSuggestedBitrate(
            width = 1280,
            height = 720,
            frameRate = 30,
            isHdr = false,
            originalBitrate = 8_000_000,
            isHevc = false,
            isAv1 = false
        )
        // Expected = minOf(2M, 8M * 0.5 = 4M) = 2_000_000
        assertEquals(2_000_000, target720p)
    }

    @Test
    fun testHighFramerateFactor() {
        // 1080p 60fps target is 4,000,000 * 1.4 = 5,600,000
        val target60fps = VideoAnalyzer.calculateSuggestedBitrate(
            width = 1920,
            height = 1080,
            frameRate = 60,
            isHdr = false,
            originalBitrate = 20_000_000,
            isHevc = false,
            isAv1 = false
        )
        assertEquals(5_600_000, target60fps)
    }

    @Test
    fun testHdrFactor() {
        // 1080p HDR 30fps target is 4,000,000 * 1.25 = 5_000_000
        val targetHdr = VideoAnalyzer.calculateSuggestedBitrate(
            width = 1920,
            height = 1080,
            frameRate = 30,
            isHdr = true,
            originalBitrate = 20_000_000,
            isHevc = false,
            isAv1 = false
        )
        assertEquals(5_000_000, targetHdr)
    }

    @Test
    fun testAlreadyCompressedDiscounts() {
        // If it's already HEVC, take 75% of original (or suggested target, whichever is lower)
        val targetHevc = VideoAnalyzer.calculateSuggestedBitrate(
            width = 1920,
            height = 1080,
            frameRate = 30,
            isHdr = false,
            originalBitrate = 3_000_000, // already low
            isHevc = true,
            isAv1 = false
        )
        // 3M * 0.75 = 2.25M (which is lower than suggest target of 4M)
        assertEquals(2_250_000, targetHevc)

        // If it's AV1, take 60% of original
        val targetAv1 = VideoAnalyzer.calculateSuggestedBitrate(
            width = 1920,
            height = 1080,
            frameRate = 30,
            isHdr = false,
            originalBitrate = 3_000_000,
            isHevc = false,
            isAv1 = true
        )
        // 3M * 0.60 = 1.8M
        assertEquals(1_800_000, targetAv1)
    }

    @Test
    fun testClampAndFloor() {
        // Test absolute floor of 800,000
        val targetFloor = VideoAnalyzer.calculateSuggestedBitrate(
            width = 640,
            height = 480,
            frameRate = 15,
            isHdr = false,
            originalBitrate = 500_000,
            isHevc = false,
            isAv1 = false
        )
        assertEquals(800_000, targetFloor)
    }
}
