package com.mp.player.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Negationen, Ausschluesse und BPM-Bereiche (Master-Prompt Teil 5.2 / 5.3). */
class NegationAndRangeTest {

    @Test
    fun withoutFrenchcore_isExcluded_notWanted() {
        val c = ConstraintExtractor.extract("Nimm wieder den Stil von vorhin, aber diesmal ohne Frenchcore")
        assertTrue(c.excludeGenres.contains("frenchcore"))
        assertFalse(c.genres.contains("frenchcore"))
    }

    @Test
    fun noRap_butDarkMusic() {
        val c = ConstraintExtractor.extract("Kein Rap, aber trotzdem dunkle Musik")
        assertTrue(c.excludeGenres.contains("rap"))
        assertFalse(c.genres.contains("rap"))
        assertTrue(c.moods.contains(Mood.DARK))
    }

    @Test
    fun twoNegations() {
        val c = ConstraintExtractor.extract("Ich will kein Rap und kein Techno")
        assertTrue(c.excludeGenres.containsAll(listOf("rap", "techno")))
        assertTrue(c.genres.isEmpty())
    }

    @Test
    fun negatedTempoPhrase_isNotAGenreExclusion() {
        val c = ConstraintExtractor.extract("Spiel Hardtekk, aber nicht zu schnell")
        assertTrue(c.genres.contains("hardtekk"))
        assertTrue(c.excludeGenres.isEmpty())
    }

    @Test
    fun negatedMood() {
        val c = ConstraintExtractor.extract("Nicht traurig, eher ruhig")
        assertTrue(c.excludeMoods.contains(Mood.SAD))
        assertFalse(c.moods.contains(Mood.SAD))
        assertTrue(c.moods.contains(Mood.CALM))
    }

    @Test
    fun bpmRangeBecomesTargetWithTolerance() {
        val c = ConstraintExtractor.extract("Irgendwas zwischen 170 und 190 BPM")
        assertEquals(180, c.targetBpm)
        assertEquals(10, c.bpmTolerance)
    }

    @Test
    fun rangeOfMinutesIsNotBpm() {
        val c = ConstraintExtractor.extract("Mach Techno zwischen 20 und 30 Minuten")
        assertNull(c.targetBpm)
    }

    @Test
    fun laterExclusionOverridesEarlierWish() {
        val a = ConstraintExtractor.extract("Mach Rap")
        val b = ConstraintExtractor.extract("diesmal ohne Rap")
        val m = a.mergeWith(b)
        assertTrue(m.excludeGenres.contains("rap"))
        assertFalse(m.genres.contains("rap"))
    }

    @Test
    fun laterWishOverridesEarlierExclusion() {
        val a = ConstraintExtractor.extract("ohne Rap")
        val b = ConstraintExtractor.extract("Mach doch Rap")
        val m = a.mergeWith(b)
        assertTrue(m.genres.contains("rap"))
        assertFalse(m.excludeGenres.contains("rap"))
    }

    @Test
    fun exclusionReachesQueueRequest() {
        val c = ConstraintExtractor.extract("Mach Musik ohne Hardtekk")
        val req = ReasoningCore.applyToRequest(QueueRequest(mood = null), c)
        assertTrue(req.avoidTerms.contains("hardtekk"))
        assertTrue(req.avoidTerms.contains("tekk"))
    }

    @Test
    fun bpmToleranceWidensNearZone() {
        val p = MusicalProfile(uri = "u", bpm = 185f)
        val narrow = AdaptiveMusic.constraintDelta(p, targetBpm = 180).first
        val wide = AdaptiveMusic.constraintDelta(p, targetBpm = 180, bpmTolerance = 10).first
        assertTrue("breite Toleranz muss 185 BPM als nah werten", wide > narrow)
    }
}
