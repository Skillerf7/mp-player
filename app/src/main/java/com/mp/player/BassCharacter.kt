package com.mp.player

/**
 * Relative Bass-Merkmale (nicht kalibrierte physikalische Leistung).
 * Werte 0..1 sofern nicht anders vermerkt – Vergleich innerhalb der Bibliothek.
 */
data class BassCharacter(
    /** Anteil Energie ~20–60 Hz (relativ). */
    val subRelative: Float = 0f,
    /** Anteil Energie ~60–120 Hz. */
    val upperBassRelative: Float = 0f,
    /** Transienten-/Punch-Indikator 0..1. */
    val kickPunch: Float = 0f,
    /** Crest-Faktor normalisiert 0..1. */
    val dynamics: Float = 0f,
    /** Kombinierter „Druck“-Score 0..1. */
    val pressure: Float = 0f,
    val conf: Float = 0f,
    val measured: Boolean = false
) {
    companion object {
        fun from(a: AnalysisResult?, deep: DeepSnapshot?): BassCharacter {
            if (a == null && (deep == null || deep.frames == 0)) {
                return BassCharacter()
            }
            val sub = when {
                deep != null && deep.frames > 0 -> deep.bandSub
                a != null -> a.subBassEnergy
                else -> 0f
            }
            val upper = when {
                deep != null && deep.frames > 0 -> deep.bandBass
                a != null -> a.bassEnergy
                else -> 0f
            }
            val punch = deep?.punchIndex?.takeIf { deep.frames > 0 } ?: 0f
            val crest = a?.dynamicRangeDb()?.takeIf { !it.isNaN() }?.let { (it / 16f).coerceIn(0f, 1f) } ?: 0.4f
            val pressure = (
                0.35f * sub + 0.30f * upper + 0.25f * punch + 0.10f * (1f - crest * 0.5f)
            ).coerceIn(0f, 1f)
            val conf = when {
                deep != null && deep.frames >= 20 -> 0.75f * deep.quality.coerceIn(0.3f, 1f)
                a != null && (a.subBassEnergy > 0f || a.bassEnergy > 0f) -> 0.55f
                else -> 0.25f
            }
            return BassCharacter(
                subRelative = sub.coerceIn(0f, 1f),
                upperBassRelative = upper.coerceIn(0f, 1f),
                kickPunch = punch.coerceIn(0f, 1f),
                dynamics = crest,
                pressure = pressure,
                conf = conf,
                measured = a != null || (deep != null && deep.frames > 0)
            )
        }
    }
}
