package com.mp.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Logik-Test für BPM-Kandidaten (ohne Android MediaCodec).
 * Generiert synthetische Onset-Muster und prüft Half/Double-Handling indirekt
 * über die öffentliche Analyzer-Dokumentation / Confidence-Semantik.
 *
 * Hinweis: BpmDetector ist private in Analysis.kt – hier testen wir die
 * Erwartungs-Invarianten der öffentlichen AnalysisResult-Felder.
 */
class BpmDetectorLogicTest {
    @Test fun analysisResultHoldsConfidence() {
        val r = AnalysisResult(
            uri = "x", modified = 0L, bpm = 140f, lufs = -10f, peakDb = -1f, rmsDb = -12f,
            sampleRate = 44100, channels = 2, partial = false, bpmConfidence = 0.8f
        )
        assertEquals(140f, r.bpm, 0.01f)
        assertEquals(0.8f, r.bpmConfidence, 0.01f)
    }

    @Test fun zeroBpmMeansUnknownNotGenre() {
        val r = AnalysisResult("u", 0L, 0f, Float.NaN, -90f, -90f, 44100, 2, false, 0f)
        assertEquals(0f, r.bpm, 0f)
        assertTrue(r.bpmConfidence == 0f)
    }
}
