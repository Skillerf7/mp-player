package com.mp.player.ai

import com.mp.player.AnalysisResult
import kotlin.math.sqrt

/**
 * Festes Audio-Feature-Embedding (kein externes DNN, keine Cloud).
 * Vektor aus gemessenen Analysewerten – reproduzierbar, versioniert, offline.
 *
 * Dimension 16, L2-normalisiert. Cosine-Ähnlichkeit für TrackSimilarity.
 * Das ist **kein** OpenL3/PANNs-Embedding; Herkunft = lokale DSP-Pipeline.
 */
object AudioEmbedding {
    const val VERSION = 1
    const val DIM = 16

    /**
     * @return null wenn keine brauchbare Analyse
     */
    fun from(a: AnalysisResult?, personalBpm: Float? = null): FloatArray? {
        if (a == null || a.sampleRate <= 0) return null
        val bpm = when {
            personalBpm != null && personalBpm > 0f -> personalBpm
            a.bpm > 0f -> a.bpm
            else -> 0f
        }
        val loud = when {
            !a.lufs.isNaN() -> ((a.lufs + 30f) / 24f).coerceIn(0f, 1f)
            a.rmsDb > -119f -> ((a.rmsDb + 50f) / 50f).coerceIn(0f, 1f)
            else -> 0.5f
        }
        val peak = if (a.peakDb > -120f) ((a.peakDb + 20f) / 20f).coerceIn(0f, 1f) else 0.5f
        val dyn = (peak - loud + 1f) / 2f // grobe Dynamik
        val v = FloatArray(DIM)
        v[0] = if (bpm > 0f) ((bpm - 60f) / 140f).coerceIn(0f, 1f) else 0.4f
        v[1] = a.bpmConfidence.coerceIn(0f, 1f)
        v[2] = loud
        v[3] = peak
        v[4] = dyn.coerceIn(0f, 1f)
        v[5] = ((a.spectralCentroidHz) / 8000f).coerceIn(0f, 1f)
        v[6] = ((a.spectralRolloffHz) / 12000f).coerceIn(0f, 1f)
        v[7] = a.spectralFlux.coerceIn(0f, 1f)
        v[8] = a.spectralFlatness.coerceIn(0f, 1f)
        v[9] = a.bassEnergy.coerceIn(0f, 1f)
        v[10] = a.midEnergy.coerceIn(0f, 1f)
        v[11] = a.highEnergy.coerceIn(0f, 1f)
        v[12] = a.rhythmRegularity.coerceIn(0f, 1f)
        v[13] = a.energyIntro.coerceIn(0f, 1f)
        v[14] = a.energyMid.coerceIn(0f, 1f)
        v[15] = a.energyLate.coerceIn(0f, 1f)
        return l2(v)
    }

    fun cosine(a: FloatArray?, b: FloatArray?): Float? {
        if (a == null || b == null || a.size != b.size) return null
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        // bereits L2-normalisiert → dot = cosine
        return dot.coerceIn(-1f, 1f)
    }

    /** Ähnlichkeit 0..1 für Ranking. */
    fun similarity01(a: AnalysisResult?, b: AnalysisResult?, personalA: Float? = null, personalB: Float? = null): Float? {
        val ca = cosine(from(a, personalA), from(b, personalB)) ?: return null
        return ((ca + 1f) / 2f).coerceIn(0f, 1f)
    }

    private fun l2(v: FloatArray): FloatArray {
        var s = 0f
        for (x in v) s += x * x
        val n = sqrt(s)
        if (n < 1e-8f) return v
        for (i in v.indices) v[i] /= n
        return v
    }
}

/**
 * ONNX Genre-Runtime-Status. Modell: assets/ml/model_quantized.onnx (Apache-2.0).
 */
object LocalOnnxBridge {
    const val ASSET_GENRE = "ml/model_quantized.onnx"

    @Volatile private var genreAsset = false
    @Volatile private var genreRuntime = false

    fun refresh(context: android.content.Context) {
        genreAsset = try {
            context.assets.open(ASSET_GENRE).close()
            true
        } catch (_: Exception) { false }
        if (genreAsset) GenreOnnxClassifier.init(context)
        genreRuntime = GenreOnnxClassifier.isReady()
    }

    fun markGenreRuntimeReady(ok: Boolean) { genreRuntime = ok }

    fun isGenreModelPresent(): Boolean = genreAsset
    fun canRunOnnxInference(): Boolean = genreRuntime && GenreOnnxClassifier.isReady()

    fun statusLine(): String =
        "ONNX Genre: asset=${if (genreAsset) "ja" else "nein"}, runtime=${if (canRunOnnxInference()) "bereit" else "nein"}" +
            (GenreOnnxClassifier.lastError()?.let { " ($it)" } ?: "")
}
