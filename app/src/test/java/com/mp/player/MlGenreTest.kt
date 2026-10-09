package com.mp.player

import com.mp.player.ai.GenreOnnxClassifier
import com.mp.player.ai.GenreProfile
import com.mp.player.ai.GenreSource
import com.mp.player.ai.MlGenre
import com.mp.player.ai.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MlGenreTest {
    private fun info(id: Long, genre: String = ""): TrackInfo {
        val t = Track("content://ml/$id", "T$id", "Artist$id", "Artist$id", "A", 2020, 180000, "Music", id, genre)
        val a = AnalysisResult(t.uri, 0, 100f, -10f, -1f, -20f, 44100, 2, false)
        return TrackInfo(t, a, null, 0, false)
    }

    @Test fun parseIgnoresBrokenParts() {
        val p = MlGenre.parse("rock:0.812,kaputt,metal:abc,jazz:0.101")
        assertEquals(listOf("rock", "jazz"), p.map { it.first })
        assertEquals(0.812f, p[0].second, 0.0001f)
    }

    @Test fun weightIsScaledAndCapped() {
        assertEquals(0.72f, MlGenre.weight(1f), 0.0001f)
        assertEquals(0.40f, MlGenre.weight(0.5f), 0.0001f)
    }

    @Test fun hiphopLabelMapsToRapWithMlReason() {
        val a = info(1)
        val p = GenreProfile.build(listOf(a), emptyMap(), null, mapOf(a.track.uri to "hiphop:0.900,pop:0.050"))
        val e = p.evidence(a, "Rap")
        assertEquals(0.72f, e.confidence, 0.001f)
        assertTrue(e.reasons.any { it.startsWith("Audio-ML") })
        assertEquals(0f, p.evidence(a, "pop").confidence, 0.001f) // unter MIN_RAW
    }

    @Test fun fileTagBeatsMl() {
        val a = info(2, genre = "Jazz")
        val p = GenreProfile.build(listOf(a), emptyMap(), null, mapOf(a.track.uri to "rock:0.800"))
        assertEquals(1f, p.evidence(a, "jazz").confidence, 0.001f)
        assertEquals(0.64f, p.evidence(a, "rock").confidence, 0.001f)
    }

    @Test fun onlyVerySureMlPassesStrictGenreThreshold() {
        val a = info(3)
        val weak = GenreProfile.build(listOf(a), emptyMap(), null, mapOf(a.track.uri to "rock:0.600"))
        val strong = GenreProfile.build(listOf(a), emptyMap(), null, mapOf(a.track.uri to "metal:0.900"))
        assertTrue(weak.evidence(a, "rock").confidence < 0.68f)
        assertTrue(strong.evidence(a, "metal").confidence >= 0.68f)
    }

    @Test fun withoutMlNothingChanges() {
        val a = info(4)
        val p = GenreProfile.build(listOf(a), emptyMap())
        assertTrue(p.mlByUri.isEmpty())
        assertEquals(0f, p.evidence(a, "rock").confidence, 0.001f)
    }

    @Test fun attributionsCarryAudioMlSource() {
        val a = info(5, genre = "Rock")
        val p = GenreProfile.build(listOf(a), emptyMap(), null, mapOf(a.track.uri to "rock:0.700"))
        val sources = p.attributions(a).map { it.source }
        assertTrue(GenreSource.FILE_TAG in sources)
        assertTrue(GenreSource.AUDIO_ML in sources)
    }

    @Test fun analysisWindowStartsInTheMiddleForLongTracks() {
        assertEquals(0L, GenreOnnxClassifier.chooseStartUs(20_000_000L, 30))
        assertEquals(0L, GenreOnnxClassifier.chooseStartUs(0L, 30))
        assertEquals(135_000_000L, GenreOnnxClassifier.chooseStartUs(300_000_000L, 30))
    }
}
