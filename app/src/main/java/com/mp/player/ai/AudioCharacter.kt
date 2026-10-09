package com.mp.player.ai

import com.mp.player.AnalysisResult
import kotlin.math.abs

/**
 * Abgeleitete musikalische Charakter-Schätzungen aus gemessenen Audiofeatures.
 * Kein ML-Modell, keine Cloud – Heuristik mit begrenzter Konfidenz.
 */
data class AudioCharacter(
    val energy: Float,
    val valence: Float,
    val danceability: Float,
    val confEnergy: Float,
    val confValence: Float,
    val confDance: Float,
    val sources: List<String>
) {
    companion object {
        fun from(a: AnalysisResult?, personalBpm: Float? = null): AudioCharacter? {
            if (a == null || a.sampleRate <= 0) return null
            val sources = ArrayList<String>()
            sources += "audio_features"

            val bpm = when {
                personalBpm != null && personalBpm > 0f -> {
                    sources += "personal_bpm"
                    personalBpm
                }
                a.bpm > 0f -> {
                    sources += "measured_bpm"
                    a.bpm
                }
                else -> 0f
            }
            val confBpm = if (personalBpm != null) 0.9f else a.bpmConfidence.coerceIn(0f, 1f)

            val loud = when {
                !a.lufs.isNaN() -> {
                    sources += "lufs"
                    ((a.lufs + 24f) / 16f).coerceIn(0f, 1f)
                }
                a.rmsDb > -119f -> {
                    sources += "rms"
                    ((a.rmsDb + 40f) / 40f).coerceIn(0f, 1f)
                }
                else -> 0.45f
            }
            val eBpm = if (bpm > 0f) ((bpm - 60f) / 120f).coerceIn(0f, 1f) else 0.4f
            val eHigh = a.highEnergy.coerceIn(0f, 1f)
            val energy = (0.45f * loud + 0.35f * eBpm + 0.20f * eHigh).coerceIn(0f, 1f)
            val confEnergy = (0.4f + 0.4f * (if (!a.lufs.isNaN() || a.rmsDb > -119f) 1f else 0.3f) + 0.2f * confBpm)
                .coerceIn(0.2f, 0.85f)

            val bright = ((a.spectralCentroidHz - 800f) / 4000f).coerceIn(0f, 1f)
            val lessBass = 1f - a.bassEnergy.coerceIn(0f, 1f)
            val valence = (0.5f * bright + 0.3f * lessBass + 0.2f * (1f - abs(energy - 0.5f))).coerceIn(0f, 1f)
            val confValence = 0.35f
            if (a.spectralCentroidHz > 0f) sources += "spectral"

            val bpmDance = when {
                bpm <= 0f -> 0.4f
                bpm in 95f..140f -> 1f
                bpm in 80f..160f -> 0.75f
                bpm in 70f..180f -> 0.5f
                else -> 0.3f
            }
            val rhythm = a.rhythmRegularity.coerceIn(0f, 1f)
            if (rhythm > 0f) sources += "rhythm"
            val danceability = (0.55f * bpmDance + 0.35f * rhythm + 0.10f * energy).coerceIn(0f, 1f)
            val confDance = (0.35f + 0.35f * confBpm + 0.3f * (if (rhythm > 0f) 1f else 0.2f)).coerceIn(0.2f, 0.8f)

            return AudioCharacter(energy, valence, danceability, confEnergy, confValence, confDance, sources.distinct())
        }
    }
}

enum class GenreSource {
    USER_CORRECTION, FILE_TAG, PLAYLIST, ARTIST_PRIOR, AUDIO_HEURISTIC, AUDIO_ML, UNKNOWN
}

data class GenreAttribution(
    val genre: String,
    val confidence: Float,
    val source: GenreSource,
    val reasons: List<String>
)
