package com.mp.player

import com.mp.player.ai.Mood
import com.mp.player.ai.QueueRequest
import com.mp.player.ai.Recommender
import com.mp.player.ai.TrackInfo
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommenderStrictTest {
    private fun tr(id: String, title: String) = Track(
        uri = "content://$id",
        title = title,
        artist = "Artist",
        albumArtist = "Artist",
        album = "Album",
        year = 2020,
        durationMs = 180_000L,
        folder = "/music",
        dateModified = 0L,
        genre = ""
    )

    private fun info(id: String, title: String, bpm: Float = 0f, lufs: Float = Float.NaN) = TrackInfo(
        track = tr(id, title),
        analysis = if (bpm > 0f || !lufs.isNaN())
            AnalysisResult("content://$id", 0L, bpm, lufs, -1f, -12f, 44100, 2, false)
        else null,
        lastPlayedAt = null,
        playCount = 0,
        favorite = false
    )

    @Test fun smallPoolWithEmptyStrictDoesNotCollapseToEmpty() {
        val all = listOf(info("1", "xyz unknown track"), info("2", "abc qwerty"))
        val r = Recommender.build(all, QueueRequest(mood = Mood.SAD, minutes = 30, seed = 1L))
        assertTrue("expected non-empty soft fallback, got ${r.tracks.size}", r.tracks.isNotEmpty())
    }

    @Test fun strictKeepsMatchingTracks() {
        val all = listOf(
            info("1", "sad lonely night melancholic", 90f, -14f),
            info("2", "party dance club banger", 128f, -8f)
        )
        val r = Recommender.build(all, QueueRequest(mood = Mood.SAD, minutes = 30, seed = 2L))
        assertTrue(r.tracks.isNotEmpty())
    }
}
