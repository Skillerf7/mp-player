package com.mp.player.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstraintExtractorTest {
    @Test
    fun deepBassAndRap() {
        val c = ConstraintExtractor.extract("Mach Rap mit richtig tiefem Bass")
        assertTrue(c.wantDeepBass)
        assertTrue(c.genres.any { it.contains("rap") })
    }

    @Test
    fun bpmAround180() {
        val c = ConstraintExtractor.extract("Mach Tekk um die 180 BPM")
        assertEquals(180, c.targetBpm)
        assertTrue(c.genres.isNotEmpty())
    }

    @Test
    fun notTooHardKeepsBass() {
        val c = ConstraintExtractor.extract("Nicht so hektisch, aber trotzdem druckvoll")
        assertTrue(c.notTooHard)
        assertTrue(c.wantDeepBass)
    }
}
