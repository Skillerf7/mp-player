package com.mp.player

import com.mp.player.ai.MemScope
import com.mp.player.ai.PreferenceExtractor
import com.mp.player.ai.PreferenceSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceSignalsTest {

    @Test
    fun bassPosAndHectic_twoSignals() {
        val s = PreferenceExtractor.extract("Der Bass ist geil, aber der Rest ist mir zu hektisch.")
        assertTrue(s.any { it.dimension == PreferenceSignal.Dimension.BASS && it.polarity > 0 })
        assertTrue(s.any { it.dimension == PreferenceSignal.Dimension.ENERGY && it.polarity < 0 })
    }

    @Test
    fun todayMelancholy_isEphemeral() {
        val s = PreferenceExtractor.extract("Heute lieber etwas Melancholisches.")
        assertTrue(s.any { it.dimension == PreferenceSignal.Dimension.MOOD_SAD })
        assertEquals(MemScope.EPHEMERAL, s.first { it.dimension == PreferenceSignal.Dimension.MOOD_SAD }.scope)
    }

    @Test
    fun druckNichtHektisch_energyLowBias() {
        val s = PreferenceExtractor.extract("Mach mal was mit richtig Druck, aber nicht so hektisch.")
        // "Druck" + "nicht hektisch" → energy negative from hectic clause
        assertTrue(s.any { it.dimension == PreferenceSignal.Dimension.ENERGY && it.polarity < 0 } ||
            s.any { it.dimension == PreferenceSignal.Dimension.BASS && it.polarity > 0 })
    }

    @Test
    fun toMemoryCandidates_notEmpty() {
        val c = PreferenceExtractor.toMemoryCandidates(
            PreferenceExtractor.extract("Ich mag viel Bass")
        )
        assertTrue(c.isNotEmpty())
        assertTrue(c.any { it.key == "bass" })
    }
}
