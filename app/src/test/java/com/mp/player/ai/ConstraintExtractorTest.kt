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

    @Test
    fun clockTimeIsNotBpm() {
        assertEquals(null, ConstraintExtractor.extract("Spiel was Ruhiges um 20 Uhr").targetBpm)
        assertEquals(null, ConstraintExtractor.extract("Mach Musik um 15 Minuten").targetBpm)
    }

    @Test
    fun implausibleBpmIgnored() {
        assertEquals(null, ConstraintExtractor.extract("Mach Techno mit 20 bpm").targetBpm)
        assertEquals(140, ConstraintExtractor.extract("Mach Techno mit 140 bpm").targetBpm)
    }

    @Test
    fun umlautsAndAsciiBothMatch() {
        assertTrue(ConstraintExtractor.extract("Mach was Düsteres").moods.contains(Mood.DARK))
        assertTrue(ConstraintExtractor.extract("Mach was Duesteres").moods.contains(Mood.DARK))
        assertTrue(ConstraintExtractor.extract("Ich will kräftigen Bass").wantDeepBass)
        assertTrue(ConstraintExtractor.extract("Ich will kraeftigen Bass").wantDeepBass)
        assertTrue(ConstraintExtractor.extract("Ich bin wütend").moods.contains(Mood.ANGRY))
    }

    @Test
    fun popAndClassicalStillRecognized() {
        assertTrue(ConstraintExtractor.extract("Spiel Pop").genres.contains("pop"))
        assertTrue(ConstraintExtractor.extract("Spiel was Klassik").genres.contains("classical"))
    }

    @Test
    fun blankTextGivesEmptyConstraints() {
        assertTrue(ConstraintExtractor.extract("   ").isEmpty())
    }
}
