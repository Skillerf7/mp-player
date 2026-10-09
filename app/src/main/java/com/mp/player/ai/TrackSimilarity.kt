package com.mp.player.ai

import com.mp.player.AnalysisResult
import com.mp.player.Track
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** Lokales Profiling fuer "mehr solche Tracks". */
object TrackSimilarity {
    private const val DEFAULT_LIMIT = 12

    fun rank(
        source: TrackInfo,
        candidates: List<TrackInfo>,
        excludedUris: Set<String> = emptySet(),
        limit: Int = DEFAULT_LIMIT,
        genreProfile: GenreProfile? = null,
        minScore: Float = 0f,
        personal: PersonalSignals? = null
    ): List<TrackInfo> = candidates.asSequence()
        .filter { it.track.uri != source.track.uri && it.track.uri !in excludedUris }
        .map { it to score(source, it, genreProfile, personal) }
        .filter { it.second >= minScore }
        .sortedByDescending { it.second }
        .take(limit.coerceIn(1, 50))
        .map { it.first }
        .toList()

    internal fun score(source: TrackInfo, candidate: TrackInfo, genreProfile: GenreProfile? = null, personal: PersonalSignals? = null): Float {
        val parts = ArrayList<Pair<Float, Float>>(8)
        val personalGenre = genreProfile?.let { personalGenreSimilarity(source, candidate, it) }
        (personalGenre ?: genreSimilarity(source.track, candidate.track))?.let { parts += 0.36f to it }
        artistSimilarity(source.track, candidate.track)?.let { parts += 0.06f to it }
        AudioEmbedding.similarity01(
            source.analysis, candidate.analysis,
            personal?.bpmOverride?.get(source.track.uri),
            personal?.bpmOverride?.get(candidate.track.uri)
        )?.let { parts += 0.22f to it }
        bpmSimilarity(source, candidate, personal)?.let { parts += 0.12f to it }
        energySimilarity(source.analysis, candidate.analysis)?.let { parts += 0.16f to it }
        loudnessSimilarity(source.analysis, candidate.analysis)?.let { parts += 0.07f to it }
        spectralSimilarity(source.analysis, candidate.analysis)?.let { parts += 0.10f to it }
        rhythmSimilarity(source.analysis, candidate.analysis)?.let { parts += 0.06f to it }
        lyricSimilarity(source.lyricMood, candidate.lyricMood)?.let { parts += 0.08f to it }
        durationSimilarity(source.track, candidate.track)?.let { parts += 0.03f to it }
        yearSimilarity(source.track, candidate.track)?.let { parts += 0.02f to it }
        if (parts.isEmpty()) return 0f
        val weight = parts.sumOf { it.first.toDouble() }.toFloat()
        var s = parts.sumOf { (it.first * it.second).toDouble() }.toFloat() / weight
        // Personal Learning: leichte Korrektur, kein Genre aus BPM
        if (personal != null) s = (s + personal.scoreBonus(candidate) * 0.5f).coerceIn(0f, 1f)
        return s
    }

    private fun personalGenreSimilarity(a: TrackInfo, b: TrackInfo, profile: GenreProfile): Float? {
        val source = profile.byUri[a.track.uri].orEmpty()
        if (source.isEmpty()) return null
        var best = 0f
        for (e in source) {
            val candidate = profile.evidence(b, e.genre).confidence
            best = maxOf(best, e.confidence * candidate)
        }
        return best.coerceIn(0f, 1f)
    }

    private fun genreSimilarity(a: Track, b: Track): Float? {
        val ag = tokens(a.genre)
        val bg = tokens(b.genre)
        if (ag.isEmpty() || bg.isEmpty()) return null
        if (ag.any { it in bg } || ag.any { x -> bg.any { y -> x.contains(y) || y.contains(x) } }) return 1f
        return 0f
    }

    private fun artistSimilarity(a: Track, b: Track): Float? {
        val aa = TextUtil.norm(a.artist).trim()
        val ba = TextUtil.norm(b.artist).trim()
        if (aa.isBlank() || ba.isBlank()) return null
        return if (aa == ba) 1f else 0f
    }

    private fun spectralSimilarity(a: AnalysisResult?, b: AnalysisResult?): Float? {
        if (a == null || b == null) return null
        if (a.spectralCentroidHz <= 0f || b.spectralCentroidHz <= 0f) return null
        val c = exp(-abs(a.spectralCentroidHz - b.spectralCentroidHz) / 2500f)
        val bass = 1f - abs(a.bassEnergy - b.bassEnergy)
        val high = 1f - abs(a.highEnergy - b.highEnergy)
        return (0.5f * c + 0.25f * bass + 0.25f * high).coerceIn(0f, 1f)
    }

    private fun rhythmSimilarity(a: AnalysisResult?, b: AnalysisResult?): Float? {
        if (a == null || b == null) return null
        if (a.rhythmRegularity <= 0f && b.rhythmRegularity <= 0f) return null
        return (1f - abs(a.rhythmRegularity - b.rhythmRegularity)).coerceIn(0f, 1f)
    }

    private fun bpmSimilarity(a: TrackInfo, b: TrackInfo, personal: PersonalSignals?): Float? {
        val xa = a.analysis?.bpm ?: 0f
        val xb = b.analysis?.bpm ?: 0f
        val x = personal?.effectiveBpm(a.track.uri, xa) ?: xa
        val y = personal?.effectiveBpm(b.track.uri, xb) ?: xb
        if (x <= 0f || y <= 0f) return null
        // Half/Double: 85 vs 170 zählen als verwandt
        val d = minOf(abs(x - y), abs(x * 2 - y), abs(x - y * 2))
        val conf = minOf(a.analysis?.bpmConfidence ?: 0.5f, b.analysis?.bpmConfidence ?: 0.5f)
        val sim = exp(-d / 32f)
        return if (conf < 0.3f) sim * 0.7f else sim
    }

    private fun energySimilarity(a: AnalysisResult?, b: AnalysisResult?): Float? {
        val ea = Recommender.energy(a) ?: return null
        val eb = Recommender.energy(b) ?: return null
        return exp(-abs(ea - eb) / 0.22f)
    }

    private fun loudnessSimilarity(a: AnalysisResult?, b: AnalysisResult?): Float? {
        val x = a?.lufs ?: Float.NaN
        val y = b?.lufs ?: Float.NaN
        if (x.isNaN() || y.isNaN()) return null
        return exp(-abs(x - y) / 5f)
    }

    private fun lyricSimilarity(a: Map<Mood, Float>, b: Map<Mood, Float>): Float? {
        if (a.isEmpty() || b.isEmpty()) return null
        var dot = 0f
        var aa = 0f
        var bb = 0f
        for (m in Mood.values()) {
            val x = a[m] ?: 0f
            val y = b[m] ?: 0f
            dot += x * y
            aa += x * x
            bb += y * y
        }
        if (aa <= 0f || bb <= 0f) return null
        return (dot / sqrt(aa * bb)).coerceIn(0f, 1f)
    }

    private fun durationSimilarity(a: Track, b: Track): Float? {
        if (a.durationMs <= 0L || b.durationMs <= 0L) return null
        return exp(-abs(a.durationMs - b.durationMs).toFloat() / 150_000f)
    }

    private fun yearSimilarity(a: Track, b: Track): Float? {
        if (a.year <= 0 || b.year <= 0) return null
        return exp(-abs(a.year - b.year).toFloat() / 10f)
    }

    private fun tokens(s: String): Set<String> = TextUtil.norm(s)
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 2 }
        .toSet()
}
