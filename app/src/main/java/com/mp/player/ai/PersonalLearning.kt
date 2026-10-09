package com.mp.player.ai

import com.mp.player.Track
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Sichere Personal-Learning-Schicht.
 * Verändert NIEMALS assets/ml/intent_model.bin.
 *
 * Gewichtung:
 * - 1 Skip/Play = schwach
 * - wiederholte Signale = stärker (logarithmisch)
 * - Playlist-Mitgliedschaft = positiv, multipliziert mit Anzahl Listen
 * - Explizite Genre-/BPM-Overrides = höchste Priorität
 */
data class PersonalSignals(
    val genreOverride: Map<String, String> = emptyMap(),
    val bpmOverride: Map<String, Float> = emptyMap(),
    val trackAffinity: Map<String, Float> = emptyMap(),
    val artistAffinity: Map<String, Float> = emptyMap(),
    val genreAffinity: Map<String, Float> = emptyMap(),
    val acceptedRecs: Map<String, Int> = emptyMap(),
    val rejectedRecs: Map<String, Int> = emptyMap()
) {
    fun effectiveBpm(uri: String, measured: Float): Float =
        bpmOverride[uri]?.takeIf { it > 0f } ?: measured

    fun effectiveGenre(uri: String, metadataGenre: String): String =
        genreOverride[uri]?.takeIf { it.isNotBlank() } ?: metadataGenre

    /** Zusätzlicher Score für Ranking (−0.30…+0.30). */
    fun scoreBonus(info: TrackInfo): Float {
        val t = trackAffinity[info.track.uri] ?: 0f
        val a = artistAffinity[TextUtil.norm(info.track.artist)] ?: 0f
        val gKey = GenreProfile.normalizeGenre(effectiveGenre(info.track.uri, info.track.genre))
        val g = genreAffinity[gKey] ?: 0f
        val acc = (acceptedRecs[info.track.uri] ?: 0).toFloat()
        val rej = (rejectedRecs[info.track.uri] ?: 0).toFloat()
        val rec = if (acc + rej < 2f) 0f // Mindestsignale
        else ((acc - rej) / (acc + rej)).coerceIn(-1f, 1f) * 0.12f
        return (0.14f * t + 0.09f * a + 0.06f * g + rec).coerceIn(-0.30f, 0.30f)
    }
}

object PersonalLearning {
    /** Ab wie vielen Events Affinität überhaupt greift. */
    const val MIN_EVENTS = 2

    /**
     * Log-Sättigung: 1 Event ≈ 0.0–0.2, 10 Events ≈ ~0.7, nie 1.0 aus einem Klick.
     */
    internal fun strength(count: Int): Float {
        if (count <= 0) return 0f
        return (ln(1.0 + count) / ln(1.0 + 20.0)).toFloat().coerceIn(0f, 1f)
    }

    fun build(
        playCounts: Map<String, Int>,
        skipCounts: Map<String, Int>,
        genreOverrides: Map<String, String>,
        bpmOverrides: Map<String, Float>,
        acceptedRecs: Map<String, Int>,
        rejectedRecs: Map<String, Int>,
        playlistMemberships: Map<String, List<Track>> = emptyMap(),
        trackByUri: Map<String, Track> = emptyMap()
    ): PersonalSignals {
        val trackAff = HashMap<String, Float>()
        val artistAff = HashMap<String, Float>()
        val genreAff = HashMap<String, Float>()

        val uris = (playCounts.keys + skipCounts.keys).toSet()
        for (uri in uris) {
            val plays = playCounts[uri] ?: 0
            val skips = skipCounts[uri] ?: 0
            if (plays + skips < MIN_EVENTS) continue // Einzel-Skip/Play ignorieren
            val pos = strength(plays)
            val neg = strength(skips)
            val raw = (pos - neg).coerceIn(-1f, 1f)
            if (raw == 0f) continue
            trackAff[uri] = raw
            val tr = trackByUri[uri] ?: continue
            val ak = TextUtil.norm(tr.artist)
            if (ak.isNotBlank()) artistAff[ak] = (artistAff[ak] ?: 0f) + raw * 0.4f
            val gk = GenreProfile.normalizeGenre(tr.genre)
            if (gk.isNotBlank()) genreAff[gk] = (genreAff[gk] ?: 0f) + raw * 0.3f
        }

        // Playlist-Evidenz: Anzahl Listen, in denen der Track vorkommt
        val playlistHits = HashMap<String, Int>()
        for ((_, tracks) in playlistMemberships) {
            for (tr in tracks) {
                playlistHits[tr.uri] = (playlistHits[tr.uri] ?: 0) + 1
            }
        }
        for ((uri, n) in playlistHits) {
            val boost = strength(n) * 0.55f // 1 Liste schwach, mehrere stärker
            trackAff[uri] = ((trackAff[uri] ?: 0f) + boost).coerceIn(-1f, 1f)
            val tr = trackByUri[uri] ?: continue
            val ak = TextUtil.norm(tr.artist)
            if (ak.isNotBlank()) artistAff[ak] = ((artistAff[ak] ?: 0f) + boost * 0.25f).coerceIn(-1f, 1f)
        }

        fun normalize(m: MutableMap<String, Float>) {
            val peak = m.values.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(1e-3f) ?: return
            for ((k, v) in m.toMap()) m[k] = (v / peak).coerceIn(-1f, 1f)
        }
        normalize(artistAff)
        normalize(genreAff)

        return PersonalSignals(
            genreOverride = genreOverrides,
            bpmOverride = bpmOverrides,
            trackAffinity = trackAff,
            artistAffinity = artistAff,
            genreAffinity = genreAff,
            acceptedRecs = acceptedRecs,
            rejectedRecs = rejectedRecs
        )
    }
}
