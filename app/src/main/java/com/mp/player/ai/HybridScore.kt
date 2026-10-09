package com.mp.player.ai

data class ScoreBreakdown(
    val total: Float,
    val audioEnergy: Float,
    val moodFit: Float,
    val genreFit: Float,
    val personal: Float,
    val novelty: Float,
    val skipPenalty: Float,
    val reasons: List<String>
)

object HybridScore {
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

        val skip = Recommender.skipPenalty(info, nowMs)
        if (skip > 0.05f) reasons += "Oft übersprungen"

        val novelty = rnd * 0.15f
        val fav = if (info.favorite) 0.08f else 0f

        val total = (
            0.22f * audioEnergy +
                0.28f * moodFit +
                0.30f * genreFit +
                0.15f * (0.5f + personal) +
                novelty + fav - skip
            ).coerceIn(0f, 1.5f)

        return ScoreBreakdown(total, audioEnergy, moodFit, genreFit, personal, novelty, skip, reasons.distinct())
    }
}
