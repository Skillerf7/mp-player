package com.mp.player.ai

/**
 * Strukturierte Musik-Präferenzsignale aus Freitext (Gespräch → Lernen).
 * Kein generatives Modell — robuste, testbare Extraktion mit Konfidenz.
 *
 * Beispiele:
 *  "Der Bass ist geil, aber der Rest ist mir zu hektisch."
 *   → bass:+ , energy/tempo:-
 *  "Heute lieber etwas Melancholisches."
 *   → mood:SAD, scope:EPHEMERAL
 */
data class PreferenceSignal(
    val dimension: Dimension,
    val polarity: Float,          // -1..+1
    val confidence: Float,        // 0..1
    val scope: MemScope,
    val label: String,
    val key: String
) {
    enum class Dimension {
        BASS, TREBLE, KICK, TEMPO, ENERGY, MOOD_CALM, MOOD_SAD, MOOD_PARTY,
        MOOD_AGGRESSIVE, VOCAL, INSTRUMENTAL, GENRE, BRIGHTNESS, DYNAMICS
    }
}

object PreferenceExtractor {

    private val HOUR = 3_600_000L
    private val DAY = 86_400_000L

    fun extract(raw: String): List<PreferenceSignal> {
        val t = TextUtil.norm(raw)
        if (t.length < 3) return emptyList()
        val out = ArrayList<PreferenceSignal>()
        val today = listOf("heute", "gerade", "jetzt", "im moment", "diesmal", "tonight", "heute abend")
            .any { t.contains(it) }
        val scope = if (today) MemScope.EPHEMERAL else MemScope.LONG
        val ttlBoost = if (today) 0.15f else 0f

        // --- Bass ---
        val bassPos = listOf(
            "bass ist geil", "bass geil", "geiler bass", "fetter bass", "viel bass", "mehr bass",
            "bass porno", "bass hammer", "tiefer bass", "druckvoller bass", "bass hat druck",
            "bass sitzt", "liebe bass", "mag bass", "bass ist gut", "bass top"
        ).any { t.contains(it) }
        val bassNeg = listOf(
            "bass zu schwach", "wenig bass", "kein bass", "bass fehlt", "bass matsch",
            "bass zu laut", "bass verzerr", "bass nervt", "weniger bass"
        ).any { t.contains(it) }
        if (bassPos) out += sig(PreferenceSignal.Dimension.BASS, 0.75f, 0.7f - ttlBoost, scope, "Bass positiv", "bass")
        if (bassNeg) out += sig(PreferenceSignal.Dimension.BASS, -0.6f, 0.65f - ttlBoost, scope, "Bass negativ", "bass")

        // --- Hektik / Tempo / Energy ---
        val hectic = listOf(
            "zu hektisch", "zu stressig", "zu schnell", "zu wild", "zu aggressiv",
            "zu viel energie", "zu treibend", "hetzt", "raserei"
        ).any { t.contains(it) }
        val chill = listOf(
            "ruhiger", "entspannter", "chilliger", "langsamer", "weniger hektisch",
            "gemuetlich", "gemutlich", "laid back", "lofi", "melancholisch", "melancholisches"
        ).any { t.contains(it) }
        val moreEnergy = listOf(
            "mehr druck", "mehr energie", "haerter", "härter", "treibender", "aggressiver",
            "richtig druck", "mit druck"
        ).any { t.contains(it) } && !hectic

        if (hectic) {
            out += sig(PreferenceSignal.Dimension.ENERGY, -0.7f, 0.72f, scope, "weniger Energie", "energy")
            out += sig(PreferenceSignal.Dimension.TEMPO, -0.55f, 0.6f, scope, "langsamer", "tempo")
        }
        if (chill) {
            out += sig(PreferenceSignal.Dimension.ENERGY, -0.5f, 0.65f, scope, "ruhiger", "energy")
            out += sig(PreferenceSignal.Dimension.MOOD_CALM, 0.7f, 0.7f, scope, "ruhig/chill", "mood_calm")
        }
        if (moreEnergy) {
            out += sig(PreferenceSignal.Dimension.ENERGY, 0.7f, 0.7f, scope, "mehr Druck", "energy")
            out += sig(PreferenceSignal.Dimension.BASS, 0.35f, 0.45f, scope, "Druck (Bass)", "bass")
        }

        // --- Mood explicit ---
        when {
            listOf("melancholisch", "traurig", "sad", "wehmütig", "wehmutig").any { t.contains(it) } ->
                out += sig(PreferenceSignal.Dimension.MOOD_SAD, 0.75f, 0.7f, scope, "melancholisch", "mood_sad")
            listOf("party", "feiern", "aufdrehen", "banger").any { t.contains(it) } ->
                out += sig(PreferenceSignal.Dimension.MOOD_PARTY, 0.75f, 0.7f, scope, "Party", "mood_party")
            listOf("wuetend", "wütend", "aggressiv", "angry").any { t.contains(it) } ->
                out += sig(PreferenceSignal.Dimension.MOOD_AGGRESSIVE, 0.7f, 0.65f, scope, "aggressiv", "mood_aggressive")
        }

        // --- Vocal / Instrumental ---
        if (listOf("ohne gesang", "instrumental", "kein text", "nur beat").any { t.contains(it) })
            out += sig(PreferenceSignal.Dimension.INSTRUMENTAL, 0.7f, 0.7f, scope, "Instrumental", "instrumental")
        if (listOf("mit gesang", "vocals", "stimme hoeren", "stimme hören").any { t.contains(it) })
            out += sig(PreferenceSignal.Dimension.VOCAL, 0.65f, 0.65f, scope, "mit Gesang", "vocal")

        // Kontrast "bass gut, rest hektisch"
        if (bassPos && hectic) {
            // schon beide Signale; Konfidenz leicht erhöhen
            return out.map {
                if (it.dimension == PreferenceSignal.Dimension.BASS || it.dimension == PreferenceSignal.Dimension.ENERGY)
                    it.copy(confidence = (it.confidence + 0.08f).coerceAtMost(0.9f))
                else it
            }
        }
        return out.distinctBy { it.key + it.polarity }
    }

    private fun sig(
        d: PreferenceSignal.Dimension, pol: Float, conf: Float, scope: MemScope, label: String, key: String
    ) = PreferenceSignal(d, pol.coerceIn(-1f, 1f), conf.coerceIn(0f, 1f), scope, label, key)

    /** Wandelt Signale in Memory-Kandidaten (LIKE/DISLIKE/FACT). */
    internal fun toMemoryCandidates(signals: List<PreferenceSignal>): List<MemCandidate> =
        signals.map { s ->
            val kind = when {
                s.dimension.name.startsWith("MOOD") -> MemKind.FACT
                s.polarity >= 0.15f -> MemKind.LIKE
                s.polarity <= -0.15f -> MemKind.DISLIKE
                else -> MemKind.FACT
            }
            val ttl = when (s.scope) {
                MemScope.EPHEMERAL -> 12 * HOUR
                MemScope.SESSION -> 6 * HOUR
                MemScope.LONG -> null
            }
            MemCandidate(
                kind = kind,
                label = s.label,
                key = s.key,
                scope = s.scope,
                confidence = s.confidence,
                importance = kotlin.math.abs(s.polarity) * 0.8f,
                explicit = s.confidence >= 0.7f,
                ttlMs = ttl
            )
        }
}
