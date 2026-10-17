package com.mp.player.ai

import com.mp.player.AnalysisResult
import kotlin.math.abs

data class ScoreBreakdown(
    val total: Float,
    val audioEnergy: Float,
    val moodFit: Float,
    val genreFit: Float,
    val personal: Float,
    val dimensionFit: Float,
    val novelty: Float,
    val skipPenalty: Float,
    val reasons: List<String>
)

/**
 * Hybrid-Ranking mit Dimensions-Affinitäten (Bass/Energy/Tempo/Mood).
 *
 * Regeln:
 * - Fehlende Analyse oder fehlende Affinität → neutral (0).
 * - Einzelne Dimension max. ±0.12 Beitrag (keine Dominanz).
 * - Skip-Penalty bleibt weich (kein automatisches „hasst den Song“).
 * - Rückwärtskompatibel: ohne personal.dimensionAffinity identisch zum alten Verhalten (+0).
 */
object HybridScore {

    /** Max. absoluter Beitrag aller Dimensionen zusammen. */
    private const val DIM_CAP = 0.18f
    /** Max. pro einzelner Dimension. */
    private const val DIM_EACH = 0.10f

    fun explain(
        info: TrackInfo,
        req: QueueRequest,
        nowMs: Long,
        rnd: Float
    ): ScoreBreakdown {
        val reasons = ArrayList<String>()
        val char = AudioCharacter.from(info.analysis, req.personal?.bpmOverride?.get(info.track.uri))
        val audioEnergy = if (char != null) {
            val base = when (req.mood) {
                Mood.SLEEP, Mood.CALM, Mood.SAD, Mood.LONELY, Mood.DARK -> 1f - char.energy
                Mood.AGGRESSIVE, Mood.ANGRY, Mood.PARTY, Mood.ENERGETIC, Mood.MOTIVATED -> char.energy
                else -> 0.4f
            }
            reasons += "Audio-Energie (Konfidenz ${"%.0f".format(char.confEnergy * 100)}%)"
            base * char.confEnergy
        } else {
            reasons += "Keine Audioanalyse – Energie neutral"
            0.35f
        }

        val moodFit = if (req.mood != null) {
            val m = Recommender.moodScore(info, req.mood).coerceIn(0f, 1f)
            if (m >= 0.55f) reasons += "Stimmung ${req.mood.label}"
            m
        } else 0.35f

        val genreFit = if (req.genres.isNotEmpty() && req.genreProfile != null) {
            val g = req.genreProfile.matches(info, req.genres)
            if (g >= 0.5f) reasons += "Genre-Fit"
            g
        } else 0f

        val personal = req.personal?.scoreBonus(info)?.coerceIn(-0.3f, 0.3f) ?: 0f
        if (personal > 0.05f) reasons += "Persönliche Vorliebe"
        if (personal < -0.05f) reasons += "Persönliches Vermeiden"

        val dimFit = dimensionFit(info.analysis, req.personal, reasons)

        val skip = Recommender.skipPenalty(info, nowMs)
        if (skip > 0.05f) reasons += "Oft übersprungen"

        val novelty = rnd * 0.15f
        val fav = if (info.favorite) 0.08f else 0f

        val total = (
            0.20f * audioEnergy +
                0.26f * moodFit +
                0.28f * genreFit +
                0.14f * (0.5f + personal) +
                dimFit +
                novelty + fav - skip
            ).coerceIn(0f, 1.5f)

        return ScoreBreakdown(total, audioEnergy, moodFit, genreFit, personal, dimFit, novelty, skip, reasons.distinct())
    }

    /**
     * Dimensions-Fit: Affinität × Track-Feature, nur wenn beides vorhanden.
     * Feature-Skalen 0..1 aus AnalysisResult / BPM-Heuristik.
     */
    fun dimensionFit(
        analysis: AnalysisResult?,
        personal: PersonalSignals?,
        reasons: MutableList<String>? = null
    ): Float {
        val dim = personal?.dimensionAffinity ?: return 0f
        if (dim.isEmpty()) return 0f
        if (analysis == null) {
            reasons?.add("Dimensionen: keine Analyse – neutral")
            return 0f
        }

        var sum = 0f
        var used = 0

        // Bass: bassEnergy 0..1
        dim["bass"]?.let { aff ->
            if (abs(aff) > 0.05f && analysis.bassEnergy > 0f) {
                val feature = analysis.bassEnergy.coerceIn(0f, 1f)
                // hohe Affinität mag hohen Bass; negative Affinität mag niedrigen Bass
                val aligned = if (aff >= 0f) feature else (1f - feature)
                val contrib = (aff * aligned * DIM_EACH).coerceIn(-DIM_EACH, DIM_EACH)
                sum += contrib
                used++
                if (abs(contrib) > 0.03f) reasons?.add("Bass-Fit")
            }
        }

        // Energy: aus AudioCharacter-Heuristik (BPM+Lautheit), hier nur BPM-basiert
        dim["energy"]?.let { aff ->
            if (abs(aff) > 0.05f) {
                val bpm = analysis.bpm
                val feature = when {
                    bpm <= 0f -> return@let // neutral, kein Wert
                    bpm < 90f -> 0.25f
                    bpm < 120f -> 0.55f
                    bpm < 150f -> 0.8f
                    else -> 0.95f
                }
                val aligned = if (aff >= 0f) feature else (1f - feature)
                val contrib = (aff * aligned * DIM_EACH).coerceIn(-DIM_EACH, DIM_EACH)
                sum += contrib
                used++
                if (abs(contrib) > 0.03f) reasons?.add("Energy-Fit")
            }
        }

        // Tempo: ähnlich, feiner an BPM
        dim["tempo"]?.let { aff ->
            if (abs(aff) > 0.05f && analysis.bpm > 0f) {
                val feature = ((analysis.bpm - 60f) / 120f).coerceIn(0f, 1f)
                val aligned = if (aff >= 0f) feature else (1f - feature)
                val contrib = (aff * aligned * DIM_EACH).coerceIn(-DIM_EACH, DIM_EACH)
                sum += contrib
                used++
                if (abs(contrib) > 0.03f) reasons?.add("Tempo-Fit")
            }
        }

        // Mood-Dims: nutzen spectral/valence-ähnlich nur wenn wir energy/centroid haben
        val moodKeys = listOf("mood_sad", "mood_calm", "mood_party", "mood_aggressive")
        for (key in moodKeys) {
            val aff = dim[key] ?: continue
            if (abs(aff) < 0.05f) continue
            val bpm = analysis.bpm
            if (bpm <= 0f && analysis.bassEnergy <= 0f) continue
            val lowEnergyFeature = when {
                bpm in 1f..100f -> 0.75f
                bpm in 100f..130f -> 0.45f
                bpm > 130f -> 0.2f
                else -> 0.5f
            }
            val feature = when (key) {
                "mood_sad", "mood_calm" -> lowEnergyFeature
                "mood_party", "mood_aggressive" -> 1f - lowEnergyFeature
                else -> 0.5f
            }
            val aligned = if (aff >= 0f) feature else (1f - feature)
            val contrib = (aff * aligned * (DIM_EACH * 0.8f)).coerceIn(-DIM_EACH, DIM_EACH)
            sum += contrib
            used++
            if (abs(contrib) > 0.03f) reasons?.add("Mood-Fit")
            break // nur stärkste Mood-Dim
        }

        if (used == 0) return 0f
        return sum.coerceIn(-DIM_CAP, DIM_CAP)
    }
}
