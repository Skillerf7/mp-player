package com.mp.player

import com.mp.player.AnalysisInsight
import com.mp.player.AnalysisResult
import com.mp.player.ValueKind
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisInsightTest {
    @Test fun nullAnalysis_unknown() {
        val n = AnalysisInsight.notes(null)
        assertTrue(n.any { it.kind == ValueKind.UNKNOWN })
    }

    @Test fun measuredLufs_marked() {
        val a = AnalysisResult(
            uri = "u", modified = 0L, bpm = 128f, lufs = -12f, peakDb = -1f, rmsDb = -10f,
            sampleRate = 44100, channels = 2, partial = false, bpmConfidence = 0.7f,
            bassEnergy = 0.4f, subBassEnergy = 0.2f
        )
        val n = AnalysisInsight.notes(a)
        assertTrue(n.any { it.name == "LUFS" && it.kind == ValueKind.MEASURED })
        assertTrue(AnalysisInsight.summary(a).contains("BPM"))
    }
}
