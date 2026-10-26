package com.mp.player.ai

import com.mp.player.AnalysisResult
import com.mp.player.DeepSnapshot
import com.mp.player.LibraryDb
import kotlin.math.abs
import kotlin.math.exp

/**
 * Einheitliches musikalisches Profil aus Basis- + Deep-Analyse.
 * Fehlende Werte → null (nicht erfunden).
 */
data class MusicalProfile(
    val uri: String,
    val bpm: Float? = null,
    val bpmConfidence: Float = 0f,
    val energy: Float? = null,          // 0..1
    val bassWeight: Float? = null,      // 0..1 sub+bass
    val brightness: Float? = null,
    val punch: Float? = null,
    val groove: Float? = null,
    val dynamicRange: Float? = null,    // normalized crest
    val aggressiveness: Float? = null,  // ESTIMATED from energy+punch+high
    val melancholy: Float? = null,      // ESTIMATED low energy + lower brightness
    val onsetDensity: Float? = null,
    val deepQuality: Float = 0f,
    val hasDeep: Boolean = false
) {
    companion object {
        fun from(info: TrackInfo, deep: DeepSnapshot? = null): MusicalProfile {
            val a = info.analysis
            val bpm = a?.bpm?.takeIf { it > 0f }
            val conf = a?.bpmConfidence ?: 0f
            val energy = when {
                a == null -> null
                else -> {
                    val parts = ArrayList<Float>()
                    if (bpm != null) parts += ((bpm - 70f) / 90f).coerceIn(0f, 1f)
                    if (a.rmsDb > -119f) parts += ((a.rmsDb + 28f) / 24f).coerceIn(0f, 1f)
                    deep?.let {
                        if (it.onsetDensity > 0f) parts += (it.onsetDensity / 8f).coerceIn(0f, 1f)
                        if (it.punchIndex > 0f) parts += it.punchIndex * 0.5f
                    }
                    if (parts.isEmpty()) null else parts.average().toFloat()
                }
            }
            val bc = com.mp.player.BassCharacter.from(a, deep)
            val bass = when {
                bc.measured && bc.conf >= 0.3f -> bc.pressure
                a == null -> null
                a.subBassEnergy > 0f || a.bassEnergy > 0f ->
                    (0.45f * a.subBassEnergy + 0.55f * a.bassEnergy).coerceIn(0f, 1f)
                deep != null && deep.frames > 0 ->
                    (0.5f * deep.bandSub + 0.5f * deep.bandBass).coerceIn(0f, 1f)
                else -> null
            }
            val bright = deep?.brightness?.takeIf { deep.frames > 0 }
                ?: a?.highEnergy?.takeIf { it > 0f }
            val punch = deep?.punchIndex?.takeIf { deep.frames > 0 }
            val groove = deep?.grooveScore?.takeIf { deep.frames > 0 }
                ?: a?.rhythmRegularity?.takeIf { it > 0f }
            val dr = a?.dynamicRangeDb()?.takeIf { !it.isNaN() }?.let { (it / 18f).coerceIn(0f, 1f) }
            val aggr = if (energy != null || punch != null) {
                val e = energy ?: 0.5f
                val p = punch ?: 0.3f
                val h = a?.highEnergy ?: 0.3f
                (0.45f * e + 0.35f * p + 0.2f * h).coerceIn(0f, 1f)
            } else null
            val mel = if (energy != null) {
                val invE = 1f - energy
                val lowB = if ((bright ?: 0.5f) < 0.4f) 0.15f else 0f
                (invE * 0.85f + lowB).coerceIn(0f, 1f)
            } else null
            return MusicalProfile(
                uri = info.track.uri,
                bpm = bpm,
                bpmConfidence = conf,
                energy = energy,
                bassWeight = bass,
                brightness = bright,
                punch = punch,
                groove = groove,
                dynamicRange = dr,
                aggressiveness = aggr,
                melancholy = mel,
                onsetDensity = deep?.onsetDensity?.takeIf { deep.frames > 0 },
                deepQuality = deep?.quality ?: 0f,
                hasDeep = deep != null && (deep.frames > 0)
            )
        }
    }
}

/**
 * Adaptive Empfehlungs-Helfer: Similarity, Constraints, Diversity, Erklärungen.
 */
object AdaptiveMusic {
    fun loadDeepMap(db: LibraryDb): Map<String, com.mp.player.DeepSnapshot> =
        try { db.deepAnalysisAll() } catch (_: Exception) { emptyMap() }

    fun profile(info: TrackInfo, deepMap: Map<String, com.mp.player.DeepSnapshot>): MusicalProfile =
        MusicalProfile.from(info, deepMap[info.track.uri])

    /** Similarity 0..1 aus MusicalProfile (nur vorhandene Dimensionen). */
    fun profileSimilarity(a: MusicalProfile, b: MusicalProfile): Float {
        val parts = ArrayList<Pair<Float, Float>>() // weight to sim
        fun dim(x: Float?, y: Float?, w: Float) {
            if (x == null || y == null) return
            parts += w to exp(-abs(x - y) / 0.28f)
        }
        dim(a.energy, b.energy, 0.22f)
        dim(a.bassWeight, b.bassWeight, 0.18f)
        dim(a.brightness, b.brightness, 0.12f)
        dim(a.punch, b.punch, 0.12f)
        dim(a.groove, b.groove, 0.10f)
        dim(a.aggressiveness, b.aggressiveness, 0.12f)
        dim(a.melancholy, b.melancholy, 0.10f)
        if (a.bpm != null && b.bpm != null && a.bpm > 0f && b.bpm > 0f) {
            val d = minOf(abs(a.bpm - b.bpm), abs(a.bpm * 2 - b.bpm), abs(a.bpm - b.bpm * 2))
            val conf = minOf(a.bpmConfidence, b.bpmConfidence).coerceAtLeast(0.3f)
            parts += 0.2f to (exp(-d / 28f) * conf)
        }
        if (parts.isEmpty()) return 0.35f
        val wSum = parts.sumOf { it.first.toDouble() }.toFloat()
        return (parts.sumOf { (it.first * it.second).toDouble() }.toFloat() / wSum).coerceIn(0f, 1f)
    }

    /**
     * Soft-Constraints für typische Nutzerwünsche.
     * @return score delta -0.4..+0.4 plus reason strings
     */
    fun constraintDelta(
        p: MusicalProfile,
        wantBass: Boolean = false,
        wantMelancholy: Boolean = false,
        notTooHard: Boolean = false,
        notTooSlow: Boolean = false,
        preferUnheard: Boolean = false,
        playCount: Int = 0,
        targetBpm: Int? = null,
        bpmTolerance: Int? = null
    ): Pair<Float, List<String>> {
        var s = 0f
        val reasons = ArrayList<String>()
        if (wantBass && p.bassWeight != null) {
            s += (p.bassWeight - 0.35f) * 0.35f
            if (p.bassWeight >= 0.45f) reasons += "kräftiger Bass (gemessen)"
        }
        if (wantMelancholy && p.melancholy != null) {
            s += (p.melancholy - 0.4f) * 0.3f
            if (p.melancholy >= 0.5f) reasons += "melancholische Energie (geschätzt)"
        }
        if (notTooHard && p.aggressiveness != null) {
            if (p.aggressiveness > 0.7f) {
                s -= 0.25f
                reasons += "zu aggressiv abgewertet"
            }
        }
        if (notTooSlow && p.bpm != null) {
            if (p.bpm < 90f) {
                s -= 0.22f
                reasons += "zu langsam (BPM)"
            } else if (p.bpm >= 110f) {
                s += 0.06f
            }
        }
        if (preferUnheard && playCount == 0) {
            s += 0.08f
            reasons += "wenig gehört"
        } else if (preferUnheard && playCount > 8) {
            s -= 0.1f
        }
        if (targetBpm != null && p.bpm != null && p.bpm > 0f) {
            val d = kotlin.math.min(
                kotlin.math.abs(p.bpm - targetBpm),
                kotlin.math.min(kotlin.math.abs(p.bpm * 2 - targetBpm), kotlin.math.abs(p.bpm - targetBpm * 2f))
            )
            val near = (bpmTolerance ?: 6).coerceIn(3, 40).toFloat()
            when {
                d <= near -> { s += 0.18f; reasons += "BPM nahe $targetBpm" }
                d <= near + 9f -> s += 0.06f
                d > near + 29f -> { s -= 0.2f; reasons += "BPM weit von $targetBpm" }
            }
        }
        return s.coerceIn(-0.4f, 0.4f) to reasons
    }

    /** Diversität: bestrafe zu ähnliche Nachbarn in der bisherigen Auswahl. */
    fun diversityPenalty(
        candidate: MusicalProfile,
        chosen: List<MusicalProfile>,
        threshold: Float = 0.92f
    ): Float {
        if (chosen.isEmpty()) return 0f
        var maxSim = 0f
        for (c in chosen.takeLast(8)) {
            val s = profileSimilarity(candidate, c)
            if (s > maxSim) maxSim = s
        }
        return if (maxSim >= threshold) -(maxSim - threshold) * 0.8f else 0f
    }

    fun explainSimilar(source: MusicalProfile, cand: MusicalProfile): List<String> {
        val r = ArrayList<String>()
        if (source.bpm != null && cand.bpm != null) {
            val d = abs(source.bpm - cand.bpm)
            if (d < 12f || abs(source.bpm * 2 - cand.bpm) < 12f || abs(source.bpm - cand.bpm * 2) < 12f)
                r += "ähnliches Tempo"
        }
        if (source.energy != null && cand.energy != null && abs(source.energy - cand.energy) < 0.15f)
            r += "ähnliche Energie"
        if (source.bassWeight != null && cand.bassWeight != null && abs(source.bassWeight - cand.bassWeight) < 0.12f)
            r += "ähnlicher Bass"
        if (source.groove != null && cand.groove != null && abs(source.groove - cand.groove) < 0.15f)
            r += "ähnlicher Groove"
        if (r.isEmpty()) r += "grobe klangliche Nähe (begrenzte Merkmale)"
        return r
    }
}
