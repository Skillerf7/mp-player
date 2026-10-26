package com.mp.player.ai

import com.mp.player.AnalysisResult
import com.mp.player.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartDjTest {
    private fun track(i: Int, title: String) = Track(
        uri = "uri$i",
        title = title,
        artist = "A",
        albumArtist = "A",
        album = "Al",
        year = 2020,
        durationMs = 180_000L,
        folder = "f",
        dateModified = 0L,
        genre = "g"
    )

    @Test
    fun ordersNonEmpty() {
        val items = (1..5).map { i ->
            val a = AnalysisResult(
                uri = "uri$i", modified = 0, bpm = 100f + i * 10, lufs = -12f,
                peakDb = -3f, rmsDb = -12f, sampleRate = 44100, channels = 2, partial = false
            )
            TrackInfo(track(i, "T$i"), a, null, 0, false)
        }
        val o = SmartDj.smoothEnergyOrder(items)
        assertEquals(5, o.tracks.size)
    }
}
