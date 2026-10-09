package com.mp.player.ai

import com.mp.player.AnalysisResult
import com.mp.player.Track

/**
 * Zentrale Sicht auf einen Track: Metadaten + Analyse + optionale persönliche Overrides.
 * Keine Fake-Embeddings – nur vorhandene reale Felder.
 */
data class TrackProfile(
    val track: Track,
    val analysis: AnalysisResult?,
    val lyricMood: Map<Mood, Float> = emptyMap(),
    val playCount: Int = 0,
    val skipCount: Int = 0,
    val favorite: Boolean = false,
    val personalGenre: String? = null,
    val personalBpm: Float? = null,
    val genreEvidence: List<GenreEvidence> = emptyList()
) {
    val effectiveGenre: String
        get() = personalGenre?.takeIf { it.isNotBlank() } ?: track.genre

    val effectiveBpm: Float
        get() = personalBpm?.takeIf { it > 0f } ?: (analysis?.bpm ?: 0f)

    val bpmConfidence: Float
        get() = analysis?.bpmConfidence ?: 0f

    val lufs: Float get() = analysis?.lufs ?: Float.NaN
    val peakDb: Float get() = analysis?.peakDb ?: Float.NaN
    val rmsDb: Float get() = analysis?.rmsDb ?: Float.NaN
    val spectralCentroidHz: Float get() = analysis?.spectralCentroidHz ?: 0f
    val bassEnergy: Float get() = analysis?.bassEnergy ?: 0f
    val midEnergy: Float get() = analysis?.midEnergy ?: 0f
    val highEnergy: Float get() = analysis?.highEnergy ?: 0f
    val analysisLevel: String get() = analysis?.analysisLevel ?: "NONE"

    /** Einfache Energie-Heuristik aus vorhandenen Messwerten (kein ML). */
    fun energyScore(): Float? {
        val bpm = effectiveBpm
        val parts = ArrayList<Float>()
        if (bpm > 0f) parts += ((bpm - 70f) / 90f).coerceIn(0f, 1f)
        if (!rmsDb.isNaN()) parts += ((rmsDb + 30f) / 25f).coerceIn(0f, 1f)
        if (parts.isEmpty()) return null
        return parts.average().toFloat()
    }

    companion object {
        fun from(
            info: TrackInfo,
            personal: PersonalSignals? = null,
            genreProfile: GenreProfile? = null
        ): TrackProfile {
            val uri = info.track.uri
            val genres = genreProfile?.byUri?.get(uri).orEmpty() + genreProfile?.mlByUri?.get(uri).orEmpty()
            return TrackProfile(
                track = info.track,
                analysis = info.analysis,
                lyricMood = info.lyricMood,
                playCount = info.playCount,
                skipCount = info.skipCount,
                favorite = info.favorite,
                personalGenre = personal?.genreOverride?.get(uri),
                personalBpm = personal?.bpmOverride?.get(uri),
                genreEvidence = genres
            )
        }
    }
}
