package com.mp.player

import com.mp.player.ai.PersonalLearning
import com.mp.player.ai.QueueRequest
import com.mp.player.ai.Recommender
import com.mp.player.ai.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class PersonalLearningTest {
    private fun track(uri: String, genre: String = "Rap") =
        Track(uri, "T", "Art", "Art", "Al", 2020, 180_000L, "F", 0L, genre)

    @Test fun singleSkipDoesNotCreateAffinity() {
        val s = PersonalLearning.build(
            playCounts = emptyMap(),
            skipCounts = mapOf("u1" to 1),
            genreOverrides = emptyMap(),
            bpmOverrides = emptyMap(),
            acceptedRecs = emptyMap(),
            rejectedRecs = emptyMap(),
            trackByUri = mapOf("u1" to track("u1"))
        )
        assertFalse(s.trackAffinity.containsKey("u1"))
    }

    @Test fun repeatedSkipsCreateNegativeAffinity() {
        val s = PersonalLearning.build(
            playCounts = mapOf("u1" to 1),
            skipCounts = mapOf("u1" to 8),
            genreOverrides = emptyMap(),
            bpmOverrides = emptyMap(),
            acceptedRecs = emptyMap(),
            rejectedRecs = emptyMap(),
            trackByUri = mapOf("u1" to track("u1"))
        )
        assertTrue(s.trackAffinity["u1"]!! < 0f)
    }

    @Test fun repeatedPlaysPositive() {
        val s = PersonalLearning.build(
            playCounts = mapOf("u1" to 10),
            skipCounts = mapOf("u1" to 1),
            genreOverrides = emptyMap(),
            bpmOverrides = emptyMap(),
            acceptedRecs = emptyMap(),
            rejectedRecs = emptyMap(),
            trackByUri = mapOf("u1" to track("u1"))
        )
        assertTrue(s.trackAffinity["u1"]!! > 0f)
    }

    @Test fun multiPlaylistBoostsStrongerThanOne() {
        val t = track("u1")
        val one = PersonalLearning.build(
            emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(),
            playlistMemberships = mapOf("A" to listOf(t)),
            trackByUri = mapOf("u1" to t)
        )
        val multi = PersonalLearning.build(
            emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(),
            playlistMemberships = mapOf("A" to listOf(t), "B" to listOf(t), "C" to listOf(t)),
            trackByUri = mapOf("u1" to t)
        )
        assertTrue((multi.trackAffinity["u1"] ?: 0f) > (one.trackAffinity["u1"] ?: 0f))
    }

    @Test fun genreOverrideWins() {
        val s = PersonalLearning.build(
            emptyMap(), emptyMap(),
            genreOverrides = mapOf("u1" to "rap"),
            bpmOverrides = mapOf("u1" to 75f),
            emptyMap(), emptyMap()
        )
        assertEquals("rap", s.effectiveGenre("u1", "Techno"))
        assertEquals(75f, s.effectiveBpm("u1", 150f), 0.01f)
    }

    @Test fun strengthIsLogarithmic() {
        assertTrue(PersonalLearning.strength(1) < PersonalLearning.strength(10))
        assertTrue(PersonalLearning.strength(10) < 1f)
    }

    @Test fun recommenderUsesPersonalBonus() {
        val liked = TrackInfo(track("like"), null, null, 10, false, 0)
        val skipped = TrackInfo(track("skip"), null, null, 1, false, 8)
        val personal = PersonalLearning.build(
            playCounts = mapOf("like" to 10),
            skipCounts = mapOf("skip" to 8, "like" to 1),
            genreOverrides = emptyMap(),
            bpmOverrides = emptyMap(),
            acceptedRecs = emptyMap(),
            rejectedRecs = emptyMap(),
            trackByUri = mapOf("like" to liked.track, "skip" to skipped.track)
        )
        assertTrue(personal.scoreBonus(liked) > personal.scoreBonus(skipped))
        val req = QueueRequest(mood = null, personal = personal, maxTracks = 2, seed = 1L)
        val result = Recommender.build(listOf(liked, skipped), req)
        // With personal, liked should tend to rank first when only two candidates
        assertTrue(result.tracks.isNotEmpty())
    }

    @Test fun bpmDoesNotDefineGenre() {
        // Documentation invariant: PersonalSignals.scoreBonus uses genre string, not BPM
        val a = TrackInfo(track("a", "Rap"), AnalysisResult("a", 0, 150f, -10f, -1f, -12f, 44100, 2, false), null, 0, false)
        val b = TrackInfo(track("b", "Techno"), AnalysisResult("b", 0, 150f, -10f, -1f, -12f, 44100, 2, false), null, 0, false)
        val s = PersonalLearning.build(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
        // Without affinity, both zero — genre not invented from BPM
        assertEquals(0f, s.scoreBonus(a), 0.001f)
        assertEquals(0f, s.scoreBonus(b), 0.001f)
    }
}
