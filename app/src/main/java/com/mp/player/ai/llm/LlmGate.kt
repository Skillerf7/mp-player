package com.mp.player.ai.llm

import com.mp.player.ai.TextUtil
import com.mp.player.ai.UserIntent

/**
 * Entscheidet, ob die optionale LLM-Schicht überhaupt gefragt werden soll.
 * Eindeutige Regel-Treffer → kein LLM. Komplex / unklar → optional.
 */
object LlmGate {

    /** Mindest-RAM (geschätzt), unter dem kein Modell geladen wird. */
    const val MIN_RAM_MB = 512

    fun needsHelp(parsed: UserIntent, raw: String): Boolean {
        // Eindeutige Befehle: nie LLM
        when (parsed) {
            is UserIntent.Confirm,
            is UserIntent.Thanks,
            is UserIntent.Greeting,
            is UserIntent.Pause,
            is UserIntent.Resume,
            is UserIntent.Next,
            is UserIntent.ClearQueue,
            is UserIntent.Help,
            is UserIntent.Crisis,
            is UserIntent.EqChange,
            is UserIntent.Volume,
            is UserIntent.TrackRef,
            is UserIntent.Refine,
            is UserIntent.StartSleep,
            is UserIntent.AskTrackAnalysis,
            is UserIntent.SimilarToCurrent -> return false
            else -> Unit
        }
        // Bereits sauber erkannt (einfache Mood/Genre/Playlist)
        if (parsed is UserIntent.PlayMood) {
            val simple = parsed.genres.size <= 1 && parsed.exclude.isEmpty() && !isComplexPhrase(raw)
            if (simple) return false
            // Mehrere Constraints in einem Satz → LLM darf nachschärfen
            if (isComplexPhrase(raw)) return true
            return false
        }
        if (parsed is UserIntent.SimilarToPlaylist && !isComplexPhrase(raw)) return false
        if (parsed is UserIntent.SearchPlay || parsed is UserIntent.SearchArtist) return false

        if (parsed is UserIntent.Unknown) return isMusicLooking(raw) || isComplexPhrase(raw)
        return isComplexPhrase(raw)
    }

    fun isComplexPhrase(raw: String): Boolean {
        val t = TextUtil.norm(raw)
        val multiConstraint = listOf(
            "aber", "allerdings", "jedoch", "und zwar", "mit mehr", "mit weniger",
            "ungefahr", "ungefaehr", "so aehnlich", "aehnlich wie", "klingt wie",
            "aber etwas", "aber bisschen", "aber nicht", "dazu noch"
        ).count { t.contains(it) }
        val longish = t.split(Regex("\\s+")).size >= 10
        return multiConstraint >= 1 && longish || multiConstraint >= 2
    }

    private fun isMusicLooking(raw: String): Boolean {
        val t = TextUtil.norm(raw)
        return listOf("musik", "playlist", "wiedergabeliste", "rap", "song", "track", "spiel", "mach", "bau", "hoer")
            .any { t.contains(it) }
    }
}
