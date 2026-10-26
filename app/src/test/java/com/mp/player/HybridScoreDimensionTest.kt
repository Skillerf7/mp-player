package com.mp.player

import com.mp.player.ai.HybridScore
import com.mp.player.ai.PersonalSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridScoreDimensionTest {

    private fun analysis(bpm: Float = 120f, bass: Float = 0.5f) =
        AnalysisResult(
            uri = "u", modified = 0L, bpm = bpm, lufs = -14f, peakDb = -1f, rmsDb = -12f,
            sampleRate = 44100, channels = 2, partial = false, bpmConfidence = 0.8f,
            spectralCentroidHz = 2000f, bassEnergy = bass, midEnergy = 0.4f, highEnergy = 0.3f
        )

    @Test
    fun missingAnalysis_isNeutral() {
        val personal = PersonalSignals(dimensionAffinity = mapOf("bass" to 0.8f))
        assertEquals(0f, HybridScore.dimensionFit(null, personal), 1e-5f)
    }

    @Test
    fun missingAffinity_isNeutral() {
        assertEquals(0f, HybridScore.dimensionFit(analysis(), PersonalSignals()), 1e-5f)
    }

    @Test
    fun highBassAffinity_prefersHighBassTrack() {
        val personal = PersonalSignals(dimensionAffinity = mapOf("bass" to 0.9f))
        val high = HybridScore.dimensionFit(analysis(bass = 0.9f), personal)
        val low = HybridScore.dimensionFit(analysis(bass = 0.1f), personal)
        assertTrue(high > low)
        assertTrue(high <= 0.18f + 1e-5f)
    }

    @Test
    fun negativeEnergy_prefersSlower() {
        val personal = PersonalSignals(dimensionAffinity = mapOf("energy" to -0.8f))
        val slow = HybridScore.dimensionFit(analysis(bpm = 80f), personal)
        val fast = HybridScore.dimensionFit(analysis(bpm = 160f), personal)
        assertTrue(slow > fast)
    }

    @Test
    fun dimensionCap_notDominating() {
        val personal = PersonalSignals(
            dimensionAffinity = mapOf(
                "bass" to 1f, "energy" to 1f, "tempo" to 1f, "mood_party" to 1f
            )
        )
        val fit = HybridScore.dimensionFit(analysis(bpm = 140f, bass = 1f), personal)
        assertTrue(fit <= 0.18f + 1e-4f)
        assertTrue(fit >= -0.18f - 1e-4f)
    }

    @Test
    fun nullPersonal_safe() {
        assertEquals(0f, HybridScore.dimensionFit(analysis(), null), 1e-5f)
    }
}
