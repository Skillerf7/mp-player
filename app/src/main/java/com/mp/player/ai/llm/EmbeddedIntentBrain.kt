package com.mp.player.ai.llm

import com.mp.player.ai.Mood
import com.mp.player.ai.TextUtil

/**
 * FEST IN DER APK EINGEBETTETER Offline-Intent-Brain.
 *
 * Kein Download, keine Cloud, keine separate Modelldatei vom Nutzer.
 * Der „Modell“-Anteil ist kompiliert in der APK (dieser Code + Vokabular).
 *
 * Warum kein GGUF/Gemma in der APK?
 * - TinyLlama/Qwen 0.5B Q4 ≈ 300–500 MB → APK unbenutzbar groß
 * - MediaPipe Gemma 2B ≈ 1 GB+
 * - Play Store / Download-Größe / RAM auf Mittelklasse-Geräten
 *
 * Für Secret Player reicht ein spezialisierter Slot-Filler:
 * Playlist-Referenz, Genre, Mood, Energy, Verfeinerungen.
 * Track-Auswahl bleibt bei GenreProfile / TrackSimilarity / Recommender.
 *
 * Aktivierung: nur wenn [LlmGate.needsHelp] true ist.
 * Sonst wird diese Klasse gar nicht erst befragt.
 */
object EmbeddedIntentBrain : OptionalLlmInterpreter {

    /** Immer verfügbar – ist Teil der APK, kein Laden von Dateien nötig. */
    override fun isAvailable(): Boolean = true

    override fun release() {
        // Stateless – nichts im RAM zu halten außer kurzlebigen Locals
    }

    override fun interpret(text: String, hintPlaylists: List<String>): StructuredMusicIntent? {
        val t = TextUtil.norm(text).trim()
        if (t.length < 6) return null

        val ref = extractPlaylistRef(t, hintPlaylists)
        val genres = extractGenres(t)
        val mood = extractMood(t)
        val aggressionLower = listOf(
            "weniger aggressiv", "nicht aggressiv", "ohne aggression", "weniger hart", "weicher"
        ).any { t.contains(it) }
        val quieter = aggressionLower || listOf(
            "ruhiger", "leiser", "sanfter", "weniger energie", "chilliger", "entspannter", "weniger laut"
        ).any { t.contains(it) }
        val harder = !aggressionLower && listOf(
            "haerter", "aggressiver", "mehr energie", "treibender", "schneller", "mehr druck"
        ).any { t.contains(it) }
        val energy = when {
            quieter -> StructuredMusicIntent.EnergyHint.LOW
            harder -> StructuredMusicIntent.EnergyHint.HIGH
            else -> null
        }

        // Effektive Stimmung: explizit > aus Verfeinerung
        val effectiveMood = mood ?: when {
            quieter && genres.any { it == "rap" } -> Mood.SAD
            quieter -> Mood.CALM
            harder -> Mood.ENERGETIC
            else -> null
        }

        if (ref == null && genres.isEmpty() && effectiveMood == null && energy == null) return null

        return StructuredMusicIntent(
            referencePlaylist = ref,
            genres = genres,
            mood = effectiveMood,
            energyHint = energy,
            refineQuieter = quieter,
            refineHarder = harder,
            prepareOnly = true,
            rawConfidence = score(ref, genres, effectiveMood, quieter || harder),
            notes = buildList {
                if (ref != null) add("reference:$ref")
                if (quieter) add("quieter")
                if (harder) add("harder")
                if (aggressionLower) add("less_aggressive")
            }
        )
    }

    private fun score(ref: String?, genres: List<String>, mood: Mood?, refined: Boolean): Float {
        var s = 0.35f
        if (ref != null) s += 0.35f
        if (genres.isNotEmpty()) s += 0.15f
        if (mood != null) s += 0.1f
        if (refined) s += 0.05f
        return s.coerceIn(0f, 1f)
    }

    private fun extractPlaylistRef(t: String, hints: List<String>): String? {
        val patterns = listOf(
            Regex("""(?:klingt\s+wie|wie|als)\s+(?:meine\s+|die\s+)?(.+?)(?:\s+(?:playlist|wiedergabeliste|liste)\b)"""),
            Regex("""(?:playlist|wiedergabeliste)\s+(?:wie\s+)?(?:meine\s+|die\s+)?([a-z0-9][a-z0-9\s\-]{1,40})"""),
            Regex("""meine\s+([a-z0-9][a-z0-9\s\-]{1,30})\s*(?:playlist|wiedergabeliste|liste)""")
        )
        val fromRegex = patterns.firstNotNullOfOrNull { it.find(t) }?.groupValues?.get(1)?.trim()?.trim('-', ' ')
        if (!fromRegex.isNullOrBlank() && fromRegex.length >= 2) {
            val cleaned = fromRegex
                .replace(Regex("""\b(?:playlist|wiedergabeliste|liste|so|etwas|sowas|ungefaehr|ungefahr)\b"""), "")
                .trim()
            if (cleaned.length >= 2) {
                // An echte Playlist-Namen anreichern
                val hit = hints.firstOrNull {
                    val n = TextUtil.norm(it)
                    n == cleaned || n.contains(cleaned) || cleaned.contains(n)
                }
                return hit ?: cleaned
            }
        }
        return hints.map { it to TextUtil.norm(it) }
            .firstOrNull { (_, n) -> n.length >= 3 && t.contains(n) }
            ?.first
    }

    private fun extractGenres(t: String): List<String> {
        val map = listOf(
            "deutschrap" to "rap", "hip hop" to "rap", "hiphop" to "rap", "rap" to "rap", "trap" to "rap",
            "hardtekk" to "hardtekk", "hardtek" to "hardtekk", "tekk" to "hardtekk",
            "techno" to "techno", "hardcore" to "hardcore", "hardstyle" to "hardstyle",
            "rock" to "rock", "metal" to "metal", "pop" to "pop", "house" to "house",
            "drum and bass" to "drum & bass", "drum & bass" to "drum & bass", "dnb" to "drum & bass"
        )
        return map.filter { t.contains(it.first) }.map { it.second }.distinct()
    }

    private fun extractMood(t: String): Mood? = when {
        listOf("melanchol", "traurig", "sad", "depri").any { t.contains(it) } -> Mood.SAD
        listOf("einsam", "lonely").any { t.contains(it) } -> Mood.LONELY
        listOf("chill", "ruhig", "entspann", "calm").any { t.contains(it) } -> Mood.CALM
        listOf("party", "feier").any { t.contains(it) } -> Mood.PARTY
        listOf("aggressiv", "wuetend", "angry").any { t.contains(it) } -> Mood.ANGRY
        listOf("duster", "dark", "duester").any { t.contains(it) } -> Mood.DARK
        listOf("gluecklich", "happy", "froh").any { t.contains(it) } -> Mood.HAPPY
        else -> null
    }
}
