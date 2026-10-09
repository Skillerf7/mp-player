package com.mp.player

import com.mp.player.ai.AudioEmbedding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEmbeddingTest {
    private fun ar(bpm: Float, lufs: Float, bass: Float, high: Float, centroid: Float, rhythm: Float) =
        AnalysisResult(
            "u", 0L, bpm, lufs, -1f, -12f, 44100, 2, false, 0.8f,
            spectralCentroidHz = centroid, bassEnergy = bass, midEnergy = 0.4f, highEnergy = high,
            rhythmRegularity = rhythm, energyIntro = 0.3f, energyMid = 0.6f, energyLate = 0.4f
        )

    @Test fun similarTracksHaveHigherCosine() {
        val a = ar(170f, -9f, 0.7f, 0.55f, 2500f, 0.85f)
        val b = ar(168f, -9.5f, 0.68f, 0.52f, 2400f, 0.82f)
        val c = ar(75f, -18f, 0.3f, 0.15f, 900f, 0.4f)
        val simAb = AudioEmbedding.similarity01(a, b)!!
        val simAc = AudioEmbedding.similarity01(a, c)!!
        assertTrue("simAb=$simAb simAc=$simAc", simAb > simAc)
    }

    @Test fun embeddingDimAndNorm() {
        val e = AudioEmbedding.from(ar(120f, -12f, 0.4f, 0.3f, 1800f, 0.6f))!!
        assertEquals(AudioEmbedding.DIM, e.size)
        var n = 0.0
        for (x in e) n += x * x
        assertTrue(kotlin.math.abs(n - 1.0) < 1e-3)
    }

    @Test fun onnxBridgeWithoutAssetsIsDisabled() {
        assertTrue(!com.mp.player.ai.LocalOnnxBridge.canRunOnnxInference())
    }
}
