package com.mp.player

import com.mp.player.ai.AdaptiveMusic
import com.mp.player.ai.MusicalProfile
import com.mp.player.ai.PersonalLearning
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveMusicTest {
    @Test
    fun profileSimilarity_closeEnergy() {
        val a = MusicalProfile("a", bpm = 128f, bpmConfidence = 0.8f, energy = 0.7f, bassWeight = 0.5f)
        val b = MusicalProfile("b", bpm = 130f, bpmConfidence = 0.8f, energy = 0.72f, bassWeight = 0.48f)
        val s = AdaptiveMusic.profileSimilarity(a, b)
        assertTrue(s > 0.6f)
    }

    @Test
    fun constraint_bassBoostsHeavyBass() {
        val heavy = MusicalProfile("h", bassWeight = 0.8f, energy = 0.6f)
        val light = MusicalProfile("l", bassWeight = 0.15f, energy = 0.6f)
        val (dh, _) = AdaptiveMusic.constraintDelta(heavy, wantBass = true)
        val (dl, _) = AdaptiveMusic.constraintDelta(light, wantBass = true)
        assertTrue(dh > dl)
    }

    @Test
    fun notTooSlow_penalizes() {
        val slow = MusicalProfile("s", bpm = 75f, bpmConfidence = 0.7f)
        val (d, r) = AdaptiveMusic.constraintDelta(slow, notTooSlow = true)
        assertTrue(d < 0f)
        assertTrue(r.any { it.contains("langsam") })
    }

    @Test
    fun softSkipWeight_repeatedPlays() {
        // strength function smoke
        assertTrue(PersonalLearning.strength(1) < PersonalLearning.strength(5))
        assertTrue(PersonalLearning.strength(10) < 1f)
    }

    @Test
    fun diversityPenalty_highWhenSimilar() {
        val a = MusicalProfile("a", energy = 0.5f, bassWeight = 0.5f, bpm = 120f, bpmConfidence = 1f)
        val b = MusicalProfile("b", energy = 0.51f, bassWeight = 0.5f, bpm = 121f, bpmConfidence = 1f)
        val pen = AdaptiveMusic.diversityPenalty(b, listOf(a), threshold = 0.85f)
        assertTrue(pen <= 0f)
    }

    @Test
    fun mergeDim_prefersSession() {
        val m = PersonalLearning.mergeDimAffinities(mapOf("bass" to 0.8f), mapOf("bass" to -0.5f))
        assertTrue(m["bass"]!! > 0f)
    }
}
