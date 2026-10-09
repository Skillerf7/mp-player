package com.mp.player

import com.mp.player.ai.AnalysisField
import com.mp.player.ai.IntentEngine
import com.mp.player.ai.Mood
import com.mp.player.ai.TrackInfo
import com.mp.player.ai.TrackSimilarity
import com.mp.player.ai.UserIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSimilarityTest {
    private fun info(
        title: String,
        artist: String = "Artist",
        genre: String = "Rap",
        bpm: Float = 0f,
        lufs: Float = Float.NaN
    ): TrackInfo {
        val uri = "content://similar/$title"
        val track = Track(uri, title, artist, artist, "Album", 2020, 180_000L, "Music", 0L, genre)
        val analysis = if (bpm > 0f || !lufs.isNaN()) AnalysisResult(uri, 0L, bpm, lufs, -1f, -20f, 44_100, 2, false) else null
        return TrackInfo(track, analysis, null, 0, false)
    }

    @Test fun bpmRequestIsRecognized() {
        assertEquals(UserIntent.AskTrackAnalysis(setOf(AnalysisField.BPM)), IntentEngine.understand("Lies die BPM aus."))
    }

    @Test fun similarRequestUsesCurrentTrackIntent() {
        assertEquals(UserIntent.SimilarToCurrent, IntentEngine.understand("Ich möchte mehr solcher Tracks."))
        assertEquals(UserIntent.SimilarToCurrent, IntentEngine.understand("Noch mehr davon"))
    }

    @Test fun closeBpmWinsInsideSameGenre() {
        val source = info("Current", bpm = 150f, lufs = -10f)
        val close = info("Close", artist = "B", bpm = 152f, lufs = -10.5f)
        val far = info("Far", artist = "C", bpm = 95f, lufs = -10.5f)
        val ranked = TrackSimilarity.rank(source, listOf(source, far, close))
        assertEquals("Close", ranked.first().track.title)
    }

    @Test fun differentGenreDoesNotEraseStrongAudioMatch() {
        val source = info("Current", genre = "Rap", bpm = 150f, lufs = -10f)
        val sameGenreWrongTempo = info("Wrong", artist = "B", genre = "Rap", bpm = 80f, lufs = -10f)
        val differentGenreSameTempo = info("Right", artist = "C", genre = "Trap", bpm = 151f, lufs = -10f)
        val ranked = TrackSimilarity.rank(source, listOf(source, sameGenreWrongTempo, differentGenreSameTempo))
        assertEquals("Right", ranked.first().track.title)
    }
}
