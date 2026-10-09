package com.mp.player.ai

import com.mp.player.Track
import kotlin.math.abs

/*
 * Referenzen und Rueckfragen (rein lokal, ohne Android-Klassen -> testbar):
 *  - ReferenceParser: "den ersten", "der dritte ist scheisse", "mach den weg", "nimm den davor", "die ersten drei", "warum der?"
 *  - ReferenceResolver: ordnet so eine Referenz einem echten Titel zu (Queue / letzte Suchergebnisse / letzter Vorschlag)
 *  - SimilarFinder: Ersatz "such was aehnliches" aus echten Daten (Genre, Energie, Interpret), ohne Erfundenes
 *  - ChoiceExplainer: "Warum dieser Song?" nur mit tatsaechlich vorhandenen Daten
 *  - AnswerParser: Antworten auf Rueckfragen ("Druck." nach "Eher ruhig oder mit bisschen Druck?") mit Confidence
 */

enum class RefAction { PLAY, REMOVE, DISLIKE, SIMILAR, WHY }
enum class RefRel { CURRENT, PREVIOUS }

internal object ReferenceParser {
    private val ORD_VAL = mapOf("erst" to 1, "zweit" to 2, "dritt" to 3, "viert" to 4, "fuenft" to 5, "sechst" to 6, "siebt" to 7, "acht" to 8, "neunt" to 9, "zehnt" to 10)
    private val ORD = Regex("""\b(erst|zweit|dritt|viert|fuenft|sechst|siebt|acht|neunt|zehnt)(?:e|en|er|es|em)\b(?!\s+(?:mal|woche|runde))""")
    private val NUM = Regex("""\b(?:track|titel|song|nummer|nr|lied)\s*\.?\s*(\d{1,3})\b|\b(\d{1,3})\s*\.\s*(?:track|titel|song|lied)\b""")
    private val LAST = Regex("""\b(?:der|die|den|das)\s+letzte(?:n|r|s)?\b(?!\s+(?:woche|mal|nacht|zeit|jahr))""")
    private val FIRST_N = Regex("""\bdie\s+ersten\s+(zwei|drei|vier|fuenf|sechs|zehn|\d{1,2})\b""")
    private val COUNT_WORDS = mapOf("zwei" to 2, "drei" to 3, "vier" to 4, "fuenf" to 5, "sechs" to 6, "zehn" to 10)
    private val PREV = Regex("""\bdavor\b|\bvorherige[nrsm]?\b|\bvorige[nrsm]?\b""")
    private val CURRENT = Regex("""\b(?:den|die|das|der)\s+(?:da|hier)\b|\bdiese[nrsm]?\b|\baktuelle[nrsm]?\b|\bder\s+laeuft\b|\bden\s+jetzt\b|\bden\s+gerade\b""")
    private val EQ_NOUNS = setOf("mitte", "mitten", "bass", "hoehen", "luft", "treble", "mids", "eq", "equalizer", "lautstaerke")
    private val DEICTIC = Regex("""\b(?:den|die|das|ihn|sie|dem|der)\b""")
    private val SOUND_TALK = listOf("klingt", "klang", "bass", "laut", "leise", "hoehen", "sound", "dumpf", "verzerr", "schrill", "hall", "eq")

    private val REMOVE = Regex("""\bweg\b|\braus\b|\bentfern\w*|\brauswerfen\b|\brausnehmen\b|\bstreich\w*|\bkick\w*|\bwegmachen\b""")
    private val DISLIKE = Regex("""scheiss|schlecht|kacke|nervt|nervig|bloed|furchtbar|grausig|\bmies\b|langweilig|nicht\s+mein|mag\s+(?:ich|den|die|das)\s+nicht|cringe""")
    private val NOT_OBJ = Regex("""\bnicht\s+(?:den|die|das|der|dem)\b|\b(?:den|die|das|der|dem)\s+nicht\b""")
    private val PLAY = Regex("""\bspiel\w*|\bnimm\b|\bnehm\w*|\bstarte\w*|\bmach\s+\w+\s+an\b|\babspielen\b|\bplay\b|\bhoeren\b|\bstattdessen\b""")
    private val SIMILAR = Regex("""aehnlich\w*|\bso\s?was\s+wie\b|\bsowas\s+wie\b|\bersatz\b""")
    private val WHY = Regex("""\b(?:warum|wieso|weshalb)\b""")
    private val NOUN = Regex("""\b(?:song|track|titel|lied)\b|genommen|gewaehlt|ausgesucht|vorgeschlagen|empfohlen""")

    fun parse(raw: String): UserIntent.TrackRef? {
        val t = TextUtil.norm(raw).trim().trimEnd('.', '!', '?', ' ').trim()
        val words = t.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 12) return null
        // EQ-Saetze ("mach die Mitte weg", "Bass raus") sind keine Titel-Referenzen
        if (words.any { it in EQ_NOUNS }) return null
        // Playlist-/Wiedergabelisten-Referenzen gehoeren NICHT hierher (IntentEngine.playlistSimilarityIntent)
        if (t.contains("playlist") || t.contains("wiedergabeliste") || t.contains("wiedergabe liste")) return null

        val firstN = FIRST_N.find(t)?.groupValues?.get(1)?.let { COUNT_WORDS[it] ?: it.toIntOrNull() }
        var ordinal: Int? = null
        if (firstN == null) {
            ORD.find(t)?.let { ordinal = ORD_VAL[it.groupValues[1]] }
            if (ordinal == null) NUM.find(t)?.let { m -> ordinal = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() }
            if (ordinal == null && LAST.containsMatchIn(t)) ordinal = -1
        }
        val rel = when {
            PREV.containsMatchIn(t) -> RefRel.PREVIOUS
            CURRENT.containsMatchIn(t) -> RefRel.CURRENT
            else -> null
        }
        val deictic = DEICTIC.containsMatchIn(t)
        val actions = LinkedHashSet<RefAction>()
        if (WHY.containsMatchIn(t) && (NOUN.containsMatchIn(t) || ordinal != null || rel != null || DEICTIC.containsMatchIn(t)) && SOUND_TALK.none { t.contains(it) }) actions += RefAction.WHY
        if (REMOVE.containsMatchIn(t) && (deictic || ordinal != null || rel != null)) actions += RefAction.REMOVE
        if (SIMILAR.containsMatchIn(t)) actions += RefAction.SIMILAR
        val dislikeWord = DISLIKE.containsMatchIn(t)
        val notObj = NOT_OBJ.containsMatchIn(t)
        if (notObj || (dislikeWord && (ordinal != null || rel != null))) actions += RefAction.DISLIKE
        if (PLAY.containsMatchIn(t) && (ordinal != null || firstN != null || rel != null) && RefAction.DISLIKE !in actions && RefAction.REMOVE !in actions) actions += RefAction.PLAY

        val hasMarker = ordinal != null || firstN != null || rel != null
        if (actions.isEmpty()) {
            // "den ersten" / "die ersten drei" allein = abspielen
            if (hasMarker && words.size <= 4) actions += RefAction.PLAY else return null
        }
        // Reine Zeigewoerter ohne klaren Bezug nur bei eindeutigem Befehl; "das Lied ist scheisse" bleibt Feedback
        if (!hasMarker && actions.all { it == RefAction.DISLIKE } && !notObj) return null
        if (!hasMarker && !deictic && RefAction.SIMILAR !in actions) return null
        return UserIntent.TrackRef(actions, ordinal, rel, firstN)
    }
}

sealed class RefResult {
    data class One(val track: Track) : RefResult()
    data class Many(val tracks: List<Track>) : RefResult()
    data class None(val reason: String) : RefResult()
}

internal object ReferenceResolver {
    /**
     * [list] ist die Bezugsliste (laufende Queue, sonst letzte Suchergebnisse), [current] der laufende Titel,
     * [lastSuggested] der zuletzt einzeln vorgeschlagene/besprochene Titel. Ohne eindeutigen Bezug lieber None als raten.
     */
    fun resolve(ref: UserIntent.TrackRef, list: List<Track>, current: Track?, lastSuggested: Track?): RefResult {
        ref.countFirst?.let { n ->
            if (list.isEmpty()) return RefResult.None("Ich hab gerade keine Liste, auf die sich das beziehen könnte.")
            return RefResult.Many(list.take(n.coerceAtLeast(1)))
        }
        val o = ref.ordinal
        if (o != null) {
            if (list.isEmpty()) return RefResult.None("Ich hab gerade keine Liste, auf die sich das beziehen könnte.")
            val t = if (o == -1) list.last() else list.getOrNull(o - 1)
            return if (t != null) RefResult.One(t) else RefResult.None("So weit geht die Liste nicht, sie hat nur ${list.size} Titel.")
        }
        val anchor = lastSuggested ?: current
        return when (ref.rel) {
            RefRel.CURRENT -> current?.let { RefResult.One(it) } ?: RefResult.None("Gerade läuft nichts.")
            RefRel.PREVIOUS -> {
                val idx = anchor?.let { a -> list.indexOfFirst { it.uri == a.uri } } ?: -1
                if (idx > 0) RefResult.One(list[idx - 1]) else RefResult.None("Davor gibt es keinen Titel.")
            }
            null -> anchor?.let { RefResult.One(it) } ?: RefResult.None("Ich weiß nicht, welchen Titel du meinst.")
        }
    }
}

internal object SimilarFinder {
    class Pick(val info: TrackInfo, val reasons: List<String>)

    /** Aehnlichster Titel zu [seed] aus [pool], ohne bereits Gespielte/Abgelehnte. Gibt die tatsaechlich zutreffenden Gruende zurueck. */
    fun find(seed: TrackInfo, pool: List<TrackInfo>, avoidUris: Set<String>, mood: Mood?): Pick? {
        val seedEnergy = Recommender.energy(seed.analysis)
        val seedGenre = seed.track.genre.trim().lowercase()
        var best: Pick? = null
        var bestScore = 0.25f // darunter ist nichts wirklich aehnlich -> lieber nichts als Zufall
        for (c in pool) {
            if (c.track.uri == seed.track.uri || c.track.uri in avoidUris) continue
            var s = 0f
            val why = ArrayList<String>()
            val g = c.track.genre.trim().lowercase()
            if (seedGenre.isNotBlank() && g == seedGenre) { s += 0.4f; why += "gleiches Genre (${c.track.genre.trim()})" }
            val e = Recommender.energy(c.analysis)
            if (seedEnergy != null && e != null) {
                val close = 1f - abs(seedEnergy - e)
                if (close > 0.8f) { s += 0.3f * close; why += "ähnliche Energie" }
            }
            if (mood != null) {
                val m = Recommender.moodScore(c, mood)
                if (m > 0.5f) { s += 0.25f * m; why += "passt zu ${mood.label}" }
            }
            if (c.track.artist.isNotBlank() && c.track.artist == seed.track.artist) { s += 0.1f; why += "gleicher Interpret" }
            s -= Recommender.skipPenalty(c, System.currentTimeMillis())
            if (s > bestScore) { bestScore = s; best = Pick(c, why) }
        }
        return best
    }
}

internal object ChoiceExplainer {
    /** Erklaert die Wahl nur mit vorhandenen Daten. Gibt es keine, sagt es das ehrlich. */
    fun explain(info: TrackInfo?, track: Track, requested: UserIntent.PlayMood?, queueMood: Mood?): String {
        val parts = ArrayList<String>()
        val mood = requested?.mood ?: queueMood
        if (mood != null) parts += "du wolltest ${mood.label}"
        requested?.genreLabel?.let { parts += "du hast $it gewünscht" }
        if (info != null) {
            val a = info.analysis
            if (a != null) {
                val e = Recommender.energy(a)
                val level = when { e == null -> null; e < 0.35f -> "ruhig"; e < 0.65f -> "mittel"; else -> "energiegeladen" }
                if (level != null) parts += "er klingt laut Analyse $level" + (if (a.bpm > 0f) " (${a.bpm.toInt()} BPM)" else "")
            }
            info.lyricMood.entries.maxByOrNull { it.value }?.takeIf { it.value >= 0.4f }?.let { parts += "im Songtext geht es eher um ${it.key.label}" }
            if (info.favorite) parts += "er ist einer deiner Favoriten"
            if (info.playCount in 1..2) parts += "du hast ihn bisher erst selten gehört"
            if (info.playCount >= 5) parts += "du hörst ihn öfter"
        }
        val name = "\"${track.title}\""
        if (parts.isEmpty()) return "Zu $name hab ich keine Daten, mit denen ich die Wahl begründen könnte. Die Audioanalyse (Menü → Audioanalyse) würde dabei helfen."
        return "$name, weil " + parts.joinToString(", ") + "."
    }
}

enum class AskKind { MUSIC_OR_TALK, ENERGY, SAD_OR_UP, BORED }

data class AskAnswer(val choice: String?, val confidence: Float, val surprise: Boolean = false)

internal object AnswerParser {
    private val SURPRISE = listOf("such du", "such einfach", "mach einfach", "egal", "du entscheid", "ueberrasch", "wie du willst", "waehl du", "du darfst", "kp", "keine ahnung", "weiss nicht", "wayne")
    private val OPTIONS: Map<AskKind, Pair<Pair<String, List<String>>, Pair<String, List<String>>>> = mapOf(
        AskKind.MUSIC_OR_TALK to (("music" to listOf("musik", "mukke", "spiel", "abschalten", "ablenk", "beats", "lied", "songs", "play", "anmachen", "mach was", "runterkomm")) to
            ("talk" to listOf("reden", "quatsch", "labern", "erzaehl", "sprechen", "quasseln", "schreiben", "unterhalt"))),
        AskKind.ENERGY to (("calm" to listOf("ruhig", "chill", "entspannt", "sanft", "leise", "weich", "gemuetlich", "abschalten", "locker", "easy")) to
            ("push" to listOf("druck", "energie", "power", "hart", "bass", "wumms", "wumm", "laut", "schnell", "pump", "ballern", "kick", "gas", "fett", "dampf"))),
        AskKind.BORED to (("discover" to listOf("neu", "unbekannt", "entdeck", "ueberrasch", "anders", "raussuch", "raussuchen", "musik", "ja")) to
            ("talk" to listOf("reden", "quatsch", "labern", "erzaehl", "sprechen", "unterhalt"))),
        AskKind.SAD_OR_UP to (("sad" to listOf("traurig", "reinfuehl", "fuehlen", "melanchol", "runter", "sad", "heulen", "weinen", "emotional", "deep")) to
            ("up" to listOf("hochkomm", "hoch", "aufmunter", "besser", "froehlich", "positiv", "happy", "aufbau", "ablenk", "gute laune", "stimmung heben")))
    )

    fun parse(kind: AskKind, raw: String): AskAnswer {
        val t = TextUtil.norm(raw).trim()
        val (a, b) = OPTIONS.getValue(kind)
        val sa = a.second.count { t.contains(it) }
        val sb = b.second.count { t.contains(it) }
        val surprise = SURPRISE.any { t.contains(it) }
        if (sa == 0 && sb == 0) return AskAnswer(null, 0f, surprise)
        if (sa == sb) return AskAnswer(null, 0.2f, surprise)
        val win = if (sa > sb) a.first else b.first
        val margin = abs(sa - sb).toFloat() / (sa + sb)
        // Ein Einzelwort-Treffer ohne Gegenstimme ist klar genug; gemischte Treffer geben weniger Sicherheit
        val conf = (0.55f + 0.4f * margin).coerceAtMost(0.95f)
        return AskAnswer(win, conf, surprise)
    }
}
