package com.mp.player

import org.junit.Assert.assertTrue
import org.junit.Test

class AudioHealthTest {
    @Test
    fun flagsClippingPeak() {
        val a = AnalysisResult(
            uri = "u", modified = 0, bpm = 120f, lufs = -12f,
            peakDb = -0.1f, rmsDb = -8f, sampleRate = 44100, channels = 2, partial = false
        )
        val r = AudioHealth.assess(a)
        assertTrue(r.issues.any { it.contains("Clipping") || it.contains("Peak") })
    }

    @Test
    fun quietFile() {
        val a = AnalysisResult(
            uri = "u", modified = 0, bpm = 90f, lufs = -32f,
            peakDb = -12f, rmsDb = -28f, sampleRate = 44100, channels = 2, partial = false
        )
        val r = AudioHealth.assess(a)
        assertTrue(r.issues.any { it.contains("leise") })
    }
}
