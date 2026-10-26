package com.mp.player

import com.mp.player.ai.ConstraintExtractor
import com.mp.player.ai.Mood
import com.mp.player.ai.MusicConstraints
import com.mp.player.ai.ReasoningCore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-Constraint- und Reasoning-Tests (deterministisch, offline).
 */
class ConstraintReasoningTest {

    @Test
    fun melancholicRapNotTooSlowKeepHard_allThree() {
        val c = ConstraintExtractor.extract(
            "Mach die Playlist melancholisch, aber nicht zu langsam, und lass die harten Tracks drin."
        )
        assertTrue("melancholisch", c.moods.contains(Mood.SAD) || c.moods.contains(Mood.CALM))
        assertTrue("nicht zu langsam", c.notTooSlow)
        assertTrue("harte Tracks behalten", c.keepHardTracks)
    }

    @Test
    fun dussterHardtekk_genreAndMood() {
        val c = ConstraintExtractor.extract("Mach mir eine Playlist mit düsterem Hardtekk.")
        assertTrue(c.genres.any { it.contains("hardtekk") || it.contains("tekk") })
        assertTrue(c.moods.contains(Mood.DARK) || c.moods.isNotEmpty())
    }

    @Test
    fun notTooQuiet_flag() {
        val c = ConstraintExtractor.extract("Spiel melancholischen Rap, aber nicht zu leise.")
        assertTrue(c.notTooQuiet)
    }

    @Test
    fun merge_preservesNotTooSlow() {
        val a = ConstraintExtractor.extract("melancholisch bitte")
        val b = ConstraintExtractor.extract("aber nicht zu langsam")
        val m = a.mergeWith(b)
        assertTrue(m.notTooSlow)
        assertTrue(m.moods.isNotEmpty() || a.moods.isNotEmpty())
    }

    @Test
    fun reasoning_emptyLibrary_blocks() {
        val r = ReasoningCore.analyze("Spiel Rap", null, libraryEmpty = true)
        assertFalse(r.canAct)
        assertTrue(r.missingQuestions.any { it.contains("Bibliothek") })
    }

    @Test
    fun scoreAdjust_penalizesSlowWhenNotTooSlow() {
        val c = MusicConstraints(notTooSlow = true)
        val slow = TrackInfo(
            Track("u1", "Slow", "A", "A", "Al", 2020, 180000, "/m", 0L, "rap"),
            AnalysisResult("u1", 0L, 75f, -14f, -1f, -12f, 44100, 2, false, 0.8f),
            null, 0, false
        )
        val fast = TrackInfo(
            Track("u2", "Fast", "A", "A", "Al", 2020, 180000, "/m", 0L, "rap"),
            AnalysisResult("u2", 0L, 140f, -10f, -1f, -8f, 44100, 2, false, 0.8f),
            null, 0, false
        )
        assertTrue(ReasoningCore.scoreAdjust(slow, c) < ReasoningCore.scoreAdjust(fast, c))
    }
}
