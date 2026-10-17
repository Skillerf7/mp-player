package com.mp.player.ai

import com.mp.player.Track
import kotlin.math.abs

/*
 * Playlist-Dramaturgie und Aenderungswuensche (rein lokal, testbar).
 * Die Reihenfolge folgt einer Energiekurve - ABER nur, wenn genug echte Analysedaten da sind. Ohne Daten bleibt die
 * Reihenfolge des Recommenders und der Assistent behauptet keine Dramaturgie.
 */

enum class Arc { FLAT, FALL, SUPPORT, PEAK }
enum class RefineKind { LONGER, SHORTER }

internal object PlaylistPlanner {
    class Plan(val tracks: List<Track>, val applied: Boolean)

    fun arcFor(mood: Mood?, support: Boolean): Arc = when (mood) {
        null -> Arc.FLAT
        Mood.SLEEP -> Arc.FALL
        Mood.CALM -> if (support) Arc.SUPPORT else Arc.FALL
        Mood.SAD, Mood.LONELY -> Arc.SUPPORT
        Mood.PARTY, Mood.ENERGETIC, Mood.HAPPY, Mood.AGGRESSIVE, Mood.MOTIVATED, Mood.ANGRY -> Arc.PEAK
        else -> Arc.FLAT
    }

    /** Ziel-Energie (0..1, als Rang unter den Titeln) an Position p (0..1) der Playlist. */
    internal fun target(arc: Arc, p: Float): Float = when (arc) {
        Arc.FLAT -> 0.5f
        Arc.FALL -> 0.85f - 0.7f * p                                   // CHILL/SLEEP: Energie runter
        Arc.PEAK -> if (p < 0.75f) 0.3f + 0.6f * (p / 0.75f) else 0.9f - 1.2f * (p - 0.75f) // aufbauen, Gipfel, kurz ausklingen
        Arc.SUPPORT -> if (p < 0.6f) 0.2f + 0.7f * (p / 0.6f) else 0.9f - 1.75f * (p - 0.6f) // sanft rein, intensiver, wieder ruhig
    }

    fun order(tracks: List<Track>, infos: Map<String, TrackInfo>, arc: Arc): Plan {
        if (arc == Arc.FLAT || tracks.size < 5) return Plan(tracks, false)
        val energies = tracks.map { t -> infos[t.uri]?.let { Recommender.energy(it.analysis) } }
        val known = energies.filterNotNull().sorted()
        if (known.size * 2 < tracks.size) return Plan(tracks, false) // zu wenig Analyse: keine erfundene Dramaturgie
        fun rank(e: Float): Float = if (known.size < 2) 0.5f else known.indexOfFirst { it >= e }.coerceAtLeast(0).toFloat() / (known.size - 1)
        val ranks = energies.map { e -> if (e == null) 0.5f else rank(e) }
        // Eine FALL-Kurve soll wirklich monoton von energiegeladen nach ruhig laufen.
        // Das fruehere greedy Target-Matching konnte den letzten hohen Titel uebrig lassen
        // und damit die Kurve am Ende wieder hochziehen.
        if (arc == Arc.FALL && known.size == tracks.size) {
            val ordered = tracks.indices.sortedByDescending { ranks[it] }.map { tracks[it] }
            return Plan(ordered, true)
        }
        val remaining = tracks.indices.toMutableList()
        val out = ArrayList<Track>(tracks.size)
        for (pos in tracks.indices) {
            val tg = target(arc, pos.toFloat() / (tracks.size - 1))
            val best = remaining.minByOrNull { abs(ranks[it] - tg) } ?: break
            remaining.remove(best)
            out += tracks[best]
        }
        return Plan(out, true)
    }

    private val PREPARE_RE = Regex("""^(?:bitte\s+)?(?:such|suche|stell|stelle|bereite|plane|zeig|zeige)\b""")
    private val START_WORDS = Regex("""\b(?:spiel|spiele|starte|start|abspielen|play|anmachen|los)\b|\bmach\s+(?:\w+\s+)?an\b""")

    /** "Such mir traurige Musik" bereitet einen Entwurf vor, startet aber nicht ("Spiel sie" startet). */
    fun isPrepareRequest(raw: String): Boolean {
        val t = TextUtil.norm(raw).trim()
        return PREPARE_RE.containsMatchIn(t) && !START_WORDS.containsMatchIn(t)
    }

    // Kurze Start-Befehle fuer einen wartenden Playlist-Entwurf (kein neuer Suchbegriff!)
    private val START_DRAFT = Regex(
        """^(?:ja\s+)?(?:spiel|spiele|starte|start)\s*(?:sie|es|das|die|den|ihn|mal)?(?:\s+an)?$"""
        + """|^(?:ja\s+)?(?:mach|mache|los|leg\s+los)(?:\s+(?:sie|es|das|die))?(?:\s+an)?$"""
        + """|^(?:mach|mache)\s+an$"""
        + """|^(?:anmachen|abspielen|play)$"""
        + """|^(?:ok|okay|jo|jap|jep|klar|passt|genau|los|gerne|gern|sicher|bitte)$"""
    )
    fun isStartDraft(raw: String): Boolean {
        val t = TextUtil.norm(raw).trim().trimEnd('.', '!', '?', ' ')
        if (t.isEmpty()) return false
        return START_DRAFT.matches(t)
    }

    private val REFINE = Regex("""^(?:(?:bitte\s+)?mach\s+(?:sie|das|es|die\s+playlist|die\s+queue|die\s+liste)\s+)?(?:die\s+(?:playlist|queue|liste)\s+)?(?:noch\s+)?(?:ein\s+)?(?:bisschen\s+|etwas\s+|bissl\s+)?(laenger|kuerzer)$""")
    fun refineIntent(raw: String): UserIntent? {
        val t = TextUtil.norm(raw).trim().trimEnd('.', '!', '?', ' ')
        val m = REFINE.find(t) ?: return null
        return UserIntent.Refine(if (m.groupValues[1] == "laenger") RefineKind.LONGER else RefineKind.SHORTER)
    }

    /** Widerspruch "traurig, aber nicht zu traurig": die Stimmung wird abgeschwaecht statt ausgeschlossen. */
    fun soften(m: Mood): Mood? = when (m) {
        Mood.SAD, Mood.LONELY -> Mood.NOSTALGIC
        Mood.AGGRESSIVE, Mood.ANGRY -> Mood.ENERGETIC
        Mood.DARK -> Mood.NOSTALGIC
        else -> null
    }
}

/** Mehrphasige Wuensche ("... und danach was Ruhiges"). Jede Phase wird mit dem normalen Parser gelesen. */
internal object PhaseParser {
    private val SPLIT = Regex("""\s*,?\s*(?:und\s+)?\b(?:danach|dann|anschliessend|hinterher|zum\s+schluss|spaeter|im\s+anschluss)\b\s*,?\s*""")
    private val MUSIC_WORDS = Regex("""\b(?:mach|mache|spiel|spiele|gib|musik|stunden?|minuten|play|stell|such|suche)\b""")

    /** Nur "erste Phase hat Zeit, die anderen nicht" gilt als Gesamtzeit und wird gleichmaessig geteilt; sonst bleibt alles wie gesagt. */
    fun resolveMinutes(phases: List<UserIntent.PlayMood>): Pair<List<UserIntent.PlayMood>, String?> {
        val given = phases.map { it.minutes }
        if (given.all { it != null } || given.all { it == null }) return phases to null
        if (given.first() != null && given.drop(1).all { it == null }) {
            val total = given.first()!!
            val per = (total / phases.size).coerceAtLeast(5)
            val note = "Ich teile die $total Minuten auf: " + phases.indices.joinToString(" + ") { "$per" } + " Min."
            return phases.map { it.copy(minutes = per) } to note
        }
        return phases to null
    }

    fun parse(raw: String, understand: (String) -> UserIntent): UserIntent.MultiPhase? {
        val t = TextUtil.norm(raw).trim().trimEnd('.', '!', '?', ' ')
        if (t.length > 300) return null
        val parts = SPLIT.split(t).map { it.trim().trim(',').trim() }.filter { it.length >= 3 }.toMutableList()
        if (parts.size !in 2..4) return null
        // Vorangestellter Gefuehlssatz ("mir geht's scheisse, mach ...")
        var intro: String? = null
        val comma = parts[0].indexOf(',')
        if (comma > 0) {
            val head = parts[0].substring(0, comma).trim()
            if (!MUSIC_WORDS.containsMatchIn(head)) { intro = head; parts[0] = parts[0].substring(comma + 1).trim() }
        }
        val phases = ArrayList<UserIntent.PlayMood>()
        for (seg in parts) {
            val i = understand(seg)
            if (i !is UserIntent.PlayMood) return null // "dann lauter", "dann pause" ist kein Phasenwunsch
            if (i.mood == null && i.genres.isEmpty()) return null
            phases += i
        }
        val (resolved, note) = resolveMinutes(phases)
        return UserIntent.MultiPhase(resolved, intro, PlaylistPlanner.isPrepareRequest(raw), note)
    }
}
