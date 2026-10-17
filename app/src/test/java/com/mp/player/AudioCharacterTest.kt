
package com.mp.player

import com.mp.player.ai.AudioCharacter
import com.mp.player.ai.HybridScore
import com.mp.player.ai.Mood
import com.mp.player.ai.QueueRequest
import com.mp.player.ai.TrackInfo
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCharacterTest {
    private fun analysis(bpm: Float, lufs: Float, bass: Float = 0.4f, high: Float = 0.3f, rhythm: Float = 0.7f) =
        AnalysisResult(
            uri = "u", modified = 0L, bpm = bpm, lufs = lufs, peakDb = -1f, rmsDb = -12f,
            sampleRate = 44100, channels = 2, partial = false, bpmConfidence = 0.7f,
            spectralCentroidHz = 2000f, bassEnergy = bass, midEnergy = 0.4f, highEnergy = high,
            rhythmRegularity = rhythm
        )

    @Test fun energeticTrackScoresHigherEnergy() {
        val calm = AudioCharacter.from(analysis(80f, -18f, bass = 0.5f, high = 0.15f, rhythm = 0.5f))!!
        val hard = AudioCharacter.from(analysis(170f, -8f, bass = 0.7f, high = 0.6f, rhythm = 0.85f))!!
        assertTrue("hard=${hard.energy} calm=${calm.energy}", hard.energy > calm.energy)
    }

    @Test fun valenceConfidenceIsLow() {
        val c = AudioCharacter.from(analysis(120f, -12f))!!
        assertTrue(c.confValence <= 0.5f)
    }

    @Test fun hybridScoreProducesReasons() {
        val tr = Track("u", "Sad Song", "A", "A", "Al", 2020, 180000, "/m", 0L, "rap")
        val info = TrackInfo(tr, analysis(90f, -16f), null, 0, false)
        val bd = HybridScore.explain(info, QueueRequest(mood = Mood.SAD, minutes = 30, seed = 1L), 0L, 0.1f)
        assertTrue(bd.reasons.isNotEmpty())
        assertTrue(bd.total >= 0f)
    }
}
