package com.mp.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepAnalysisTest {
    @Test
    fun jsonRoundTrip() {
        val d = DeepSnapshot(
            bandSub = 0.1f, bandBass = 0.2f, onsetDensity = 3.5f,
            keyGuess = "Am?", keyConfidence = 0.2f, quality = 0.8f, frames = 40
        )
        val back = DeepSnapshot.fromJson(d.toJson().toString())
        assertTrue(back != null)
        assertEquals(0.1f, back!!.bandSub, 0.001f)
        assertEquals("Am?", back.keyGuess)
        assertEquals(40, back.frames)
    }

    @Test
    fun insightMentionsDeep() {
        val a = AnalysisResult(
            uri = "u", modified = 0, bpm = 128f, lufs = -12f, peakDb = -1f, rmsDb = -10f,
            sampleRate = 44100, channels = 2, partial = false, bpmConfidence = 0.8f
        )
        val d = DeepSnapshot(onsetDensity = 2f, punchIndex = 0.5f, frames = 20, quality = 0.7f)
        val s = AnalysisInsight.summary(a, d)
        assertTrue(s.contains("Onsets"))
        val report = AnalysisInsight.deepReport(a, d)
        assertTrue(report.contains("DEEP DSP"))
    }
}
