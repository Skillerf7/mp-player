package com.mp.player.ai.llm

import com.mp.player.ai.Mood
import com.mp.player.ai.UserIntent

/**
 * Strukturierte Interpretation einer komplexen Musik-Anfrage.
 * Wird vom optionalen lokalen LLM (oder Heuristik) gefüllt und danach
 * von der bestehenden Assistant-/Recommender-Logik ausgeführt.
 * Das LLM wählt KEINE Tracks und führt KEINE Aktionen aus.
 */
data class StructuredMusicIntent(
    val referencePlaylist: String? = null,
    val genres: List<String> = emptyList(),
    val mood: Mood? = null,
    val energyHint: EnergyHint? = null,
    val artists: List<String> = emptyList(),
    val refineQuieter: Boolean = false,
    val refineHarder: Boolean = false,
    val prepareOnly: Boolean = true,
    val rawConfidence: Float = 0f,
    val notes: List<String> = emptyList()
) {
    enum class EnergyHint { LOW, MID, HIGH }

    /** Mappt auf bestehende UserIntent-Typen – keine neue Action-Engine. */
    fun toUserIntent(): UserIntent {
        if (!referencePlaylist.isNullOrBlank()) {
            return UserIntent.SimilarToPlaylist(referencePlaylist.trim())
        }
        val mood = this.mood
        val genres = this.genres
        if (mood != null || genres.isNotEmpty()) {
            return UserIntent.PlayMood(
                mood = mood,
                genres = genres,
                prepare = prepareOnly,
                support = mood == Mood.SAD || mood == Mood.LONELY || mood == Mood.CALM
            )
        }
        return UserIntent.Unknown
    }
}

/**
 * Optionaler lokaler Interpreter. Implementierungen müssen offline bleiben,
 * dürfen keine Dateien/Playlists löschen und keine Netzwerkaufrufe machen.
 */
interface OptionalLlmInterpreter {
    /** true wenn Modell/Heuristik grundsätzlich nutzbar ist (RAM, Datei, …). */
    fun isAvailable(): Boolean

    /**
     * Interpretiert [text] strukturiert. null = kein Ergebnis / Fehler → Caller fallback.
     * Darf blockieren, sollte aber Timeout-freundlich bleiben.
     */
    fun interpret(text: String, hintPlaylists: List<String> = emptyList()): StructuredMusicIntent?

    /** Ressourcen freigeben (Modell aus RAM). Idempotent. */
    fun release()
}
