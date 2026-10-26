package com.mp.player.ai

import com.mp.player.AnalysisResult
import com.mp.player.Dsp
import com.mp.player.DupGroup
import com.mp.player.Track

/*
 * Offline-KI-Assistent: Datenmodelle.
 * Dieses Paket enthaelt KEINEN Netzwerkcode (die App hat keine INTERNET-Berechtigung) und greift
 * ausschliesslich ueber das Interface PlayerTools auf Player, Bibliothek und Datenbank zu.
 */

/** Stimmungen, die der Recommender aus BPM, Lautheit, Genre/Titel-Stichwoertern und Verlauf abschaetzt. */
enum class AnalysisQueryKind {
        COVERAGE, CURRENT_TRACK, BPM_RANGE, SIMILAR, REQUEST_ANALYSIS, UNCERTAIN, WHY_PLAYLIST, FEATURE_HELP
    }

enum class Mood(val label: String) {
    CALM("ruhig"),
    SAD("melancholisch"),
    ENERGETIC("energiegeladen"),
    AGGRESSIVE("aggressiv"),
    HAPPY("froehlich"),
    PARTY("Party"),
    FOCUS("Fokus"),
    SLEEP("zum Einschlafen"),
    ANGRY("wütend"),
    LONELY("einsam"),
    ROMANTIC("romantisch"),
    NOSTALGIC("nostalgisch"),
    DARK("düster"),
    MOTIVATED("motivierend")
}

/** Ein Titel samt allem, was die lokale Datenbank ueber ihn weiss. */
data class TrackInfo(
    val track: Track,
    val analysis: AnalysisResult?,
    val lastPlayedAt: Long?,
    val playCount: Int,
    val favorite: Boolean,
    /** Wie oft der Titel kurz nach dem Start weitergeklickt wurde. */
    val skipCount: Int = 0,
    val lastSkippedAt: Long? = null,
    /** Ein eingebetteter Songtext wurde gelesen (auch wenn er keine erkennbaren Gefuehlswoerter enthaelt). */
    val hasLyrics: Boolean = false,
    /** Stimmungen laut Songtext, 0..1 (siehe LyricsMood). Leer = kein Text oder neutral. */
    val lyricMood: Map<Mood, Float> = emptyMap()
)

data class HistoryEntry(val track: Track, val playedAt: Long)

data class LibraryStats(
    val trackCount: Int,
    val totalDurationMs: Long,
    val artistCount: Int,
    val albumCount: Int,
    val analyzedCount: Int,
    val neverPlayedCount: Int,
    val topGenres: List<Pair<String, Int>>
)

/** Aktueller Klangzustand, wie ihn die Wiedergabe-Engine wirklich benutzt. */
data class AudioSettings(
    val dsp: Dsp,
    val bassGainDb: Float,      // effektive Bass-Anhebung (Regler + Boost)
    val trebleGainDb: Float,
    val replayGainMode: Int,    // 0 = aus, 1 = Titel, 2 = Album
    val replayGainDb: Float,    // aktuell angewendeter ReplayGain-Wert
    val floatOutput: Boolean
)

data class DuplicateReport(val scanned: Boolean, val running: Boolean, val groups: List<DupGroup>)

data class MissingReport(val checked: Int, val total: Int, val missing: List<Track>, val complete: Boolean)

data class PlaylistOutcome(val name: String?, val added: Int)

/** Gespraechsthemen fuer den Themen-Stapel ("und die Playlist von eben?" holt ein altes Thema zurueck). */
enum class Topic { EMOTION, MUSIC, PLAYLIST, EQ, VOLUME, CHAT, CONVERSATION }

enum class ChatKind { WHY, REALLY, AND, HM, NO_PROBLEM, UNDECIDED, BORED, TELL, DISTRACT, AFFECTION, HOW_ARE_YOU, WHO_ARE_YOU, TALK, GOODBYE, LAUGH, CONTINUE }

enum class AnalysisField { BPM, LOUDNESS, PEAK, RMS, ALL }

/** Was der Nutzer will - Ergebnis der Sprach-/Textanalyse (BrainEngine). */
sealed class UserIntent {
    object Greeting : UserIntent()
    object Thanks : UserIntent()
    object Help : UserIntent()
    object Pause : UserIntent()
    object Resume : UserIntent()
    object Next : UserIntent()
    object ClearQueue : UserIntent()
    object WhatsPlaying : UserIntent()
    object ShowStats : UserIntent()
    object ShowHistory : UserIntent()
    object CheckDuplicates : UserIntent()
    object CheckMissing : UserIntent()
    object Crisis : UserIntent()
    object Unknown : UserIntent()
    /** "noch bisschen" / "mehr" - mehr vom Letzten (EQ-Schritt oder weitere Titel). */
    object More : UserIntent()
    /** "bisschen weniger" - Gegenteil der letzten EQ-Aenderung. */
    object Less : UserIntent()
    /** "such du aus" / "egal" / "ueberrasch mich" - Auswahl dem Assistenten ueberlassen. */
    object Surprise : UserIntent()
    /** "und die Playlist von eben?", "wo waren wir?", "zurueck zum EQ". */
    data class ResumeTopic(val topic: Topic) : UserIntent()
    /** "Was hoere ich nachts?" (null = insgesamt). */
    data class AskPattern(val daypart: Daypart?) : UserIntent()
    /** "2 Stunden was Trauriges und danach was Ruhiges": mehrere Phasen in EINER Queue. [intro] = vorangestellter Gefuehlssatz. */
    data class MultiPhase(val phases: List<PlayMood>, val intro: String?, val prepare: Boolean, val splitNote: String?) : UserIntent()
    /** "mach sie laenger / kuerzer" - bezieht sich auf die aktuelle Queue bzw. den Entwurf. */
    data class Refine(val kind: RefineKind) : UserIntent()
    /** Bezug auf einen Titel: "den ersten", "der dritte ist scheisse", "mach den weg", "nimm den davor", "die ersten drei". */
    data class TrackRef(val actions: Set<RefAction>, val ordinal: Int?, val rel: RefRel?, val countFirst: Int?) : UserIntent()
    /** "lauter" / "etwas leiser" (delta in Prozentpunkten) oder "Lautstaerke auf 10" (absolute in %). */
    data class Volume(val delta: Int?, val absolute: Int?) : UserIntent()

    data class Confirm(val yes: Boolean) : UserIntent()
    /** Reine Dauer ("30 min", "ne Stunde") - wird mit dem letzten Musikwunsch kombiniert. */
    data class Minutes(val minutes: Int) : UserIntent()
    data class EqChange(val cmds: List<EqCommand>) : UserIntent()
    data class EqPreset(val name: String) : UserIntent()
    data class EqSave(val name: String?) : UserIntent()
    data class StartSleep(val minutes: Int) : UserIntent()
    data class ExplainSound(val topic: String) : UserIntent()
    /** Analyse-/Wissensfragen über Library und Track-Features. */
    data class AnalysisQuery(val kind: AnalysisQueryKind, val bpmAround: Float? = null, val raw: String = "") : UserIntent()

    data class SavePlaylist(val name: String?) : UserIntent()
    data class PlayFavorites(val minutes: Int?, val append: Boolean) : UserIntent()
    data class PlayUnheard(val minutes: Int?, val append: Boolean) : UserIntent()
    data class SearchPlay(val query: String) : UserIntent()
    /** "Such Interpret X" / "alles von X": nur im Artist-Feld suchen. */
    data class SearchArtist(val name: String) : UserIntent()
    /** Laufende Audioanalyse abfragen, z. B. BPM/LUFS/Peak/RMS. */
    data class AskTrackAnalysis(val fields: Set<AnalysisField>) : UserIntent()
    /** Weitere Titel anhand des Profils des aktuell laufenden Titels suchen. */
    object SimilarToCurrent : UserIntent()
    data class SimilarToPlaylist(val name: String) : UserIntent()
    /** Gefuehlsaeusserung mit Kontext ("Arbeit hat mich fertig gemacht"): erst reden, nicht sofort Musik starten. */
    data class Emotion(val feel: Mood, val topic: String?, val strong: Boolean, val intensity: Float = if (strong) 0.9f else 0.6f) : UserIntent()
    /** "Das ist geil" / "nicht meins" zum laufenden Titel. */
    data class Feedback(val positive: Boolean) : UserIntent()
    /** "wie vorher" / "mach das rueckgaengig" / "wie gestern" / "wie letztens"; tellOnly = nur erzaehlen, nichts tun. */
    data class Restore(val which: RestoreWhen, val tellOnly: Boolean = false) : UserIntent()
    /** "Was hast du dir gerade gemerkt?" */
    object RecentMemory : UserIntent()
    data class Chat(val kind: ChatKind) : UserIntent()
    /** Merkenswerte Aussage ("merk dir ...", "ich mag ..."); [text] ist der Originaltext. */
    data class Remember(val text: String) : UserIntent()
    /** topic == null -> alles Gelernte vergessen. */
    data class Forget(val topic: String?) : UserIntent()
    object WhatDoYouKnow : UserIntent()
    /** "Was wuerdest du jetzt hoeren?" */
    object Recommend : UserIntent()
    data class Exclude(val moods: Set<Mood>) : UserIntent()
    data class PlayMood(
        val mood: Mood?,
        val exclude: Set<Mood> = emptySet(),
        val genreLabel: String? = null,
        val genres: List<String> = emptyList(),
        val minutes: Int? = null,
        val noRepeat: Boolean = false,
        val shuffle: Boolean? = null,
        val append: Boolean = false,
        val support: Boolean = false,
        val session: Boolean = false,
        /** Kombinierte Befehle: "chilliger Rap mit bisschen mehr Bass" -> Queue UND EQ-Aenderung. */
        val eq: List<EqCommand> = emptyList(),
        /** "Such mir ...": nur einen Entwurf vorbereiten und zeigen, noch nicht starten. */
        val prepare: Boolean = false,
        /** Teil eines mehrphasigen Wunsches: Dramaturgie auch beim Anhaengen anwenden. */
        val phased: Boolean = false
    ) : UserIntent()
}

/**
 * Das "Gehirn". Version 1: regelbasierte Absichtserkennung (IntentEngine), komplett offline.
 * Spaeter kann hier ein On-Device-Modell (z. B. kleines LLM) eingehaengt werden, das ebenfalls nur
 * einen [UserIntent] liefert - alles andere (Recommender, Tools, Sicherheitsregeln) bleibt unveraendert.
 */
interface BrainEngine {
    fun understand(text: String): UserIntent
}

internal object TextUtil {
    /** Kleinschreibung + Umlaute ausschreiben, damit "fühl" und "fuehl" gleich behandelt werden. */
    fun norm(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s.lowercase()) {
            when (ch) {
                'ä' -> sb.append("ae")
                'ö' -> sb.append("oe")
                'ü' -> sb.append("ue")
                'ß' -> sb.append("ss")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
