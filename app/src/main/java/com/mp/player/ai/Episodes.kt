package com.mp.player.ai

import java.util.Calendar

/*
 * Episodisches Gedaechtnis + Tageszeit-Muster (rein lokal, testbar).
 * Gespeichert werden strukturierte Ereignisse ("abends, ruhig, nach Stress"), kein Chattext. Ein Muster entsteht erst
 * aus mehreren echten Ereignissen (>= 3 im selben Tagesabschnitt) - alles darunter ist Zufall und wird nicht behauptet.
 */

enum class Daypart(val label: String) {
    MORNING("morgens"), DAY("tagsüber"), EVENING("abends"), NIGHT("nachts");

    companion object {
        fun of(ts: Long): Daypart {
            val h = Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.HOUR_OF_DAY)
            return when {
                h in 5..10 -> MORNING
                h in 11..16 -> DAY
                h in 17..21 -> EVENING
                else -> NIGHT
            }
        }

        fun fromWord(w: String): Daypart? = when (w) {
            "nachts", "nacht", "spaet" -> NIGHT
            "abends", "abend" -> EVENING
            "morgens", "morgen", "frueh" -> MORNING
            "tagsueber", "mittags", "mittag", "nachmittags" -> DAY
            else -> null
        }
    }
}

data class Episode(
    val id: Long, val ts: Long, val daypart: Daypart,
    val mood: String?, val genre: String?, val emotion: String?, val topic: String?, val minutes: Int?
)

interface EpisodeStore {
    fun add(e: Episode)
    fun all(): List<Episode>
    /** Behaelt nur die neuesten [keep] Ereignisse. */
    fun trim(keep: Int)
}

class InMemoryEpisodeStore : EpisodeStore {
    private val items = ArrayList<Episode>()
    private var next = 1L
    override fun add(e: Episode) { items += e.copy(id = next++) }
    override fun all(): List<Episode> = items.toList()
    override fun trim(keep: Int) { while (items.size > keep) items.removeAt(0) }
}

data class Pattern(val daypart: Daypart, val genre: String?, val mood: Mood?, val count: Int, val last: Long) {
    val label: String get() = genre ?: mood?.label ?: "gemischt"
}

class EpisodeEngine(private val store: EpisodeStore, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private companion object {
        const val DAY_MS = 86_400_000L
        const val WINDOW_DAYS = 90
        const val MIN_COUNT = 3
        const val KEEP = 300
    }

    private inline fun <T> safe(default: T, block: () -> T): T = try { block() } catch (e: Exception) { default }

    /** Ein echter Musikstart = ein Ereignis. Fehler hier duerfen die Wiedergabe nie beruehren. */
    fun record(mood: Mood?, genre: String?, emotion: Mood?, topic: String?, minutes: Int?) = safe(Unit) {
        if (mood == null && genre.isNullOrBlank()) return@safe // ohne Inhalt kein Ereignis
        val now = clock()
        store.add(Episode(0L, now, Daypart.of(now), mood?.name, genre?.trim()?.takeIf { it.isNotEmpty() }, emotion?.name, topic, minutes))
        store.trim(KEEP)
    }

    fun count(): Int = safe(0) { store.all().size }

    /** Muster der letzten 90 Tage: gleicher Tagesabschnitt + gleiches Genre/gleiche Stimmung, mindestens 3 Mal. */
    fun patterns(): List<Pattern> = safe(emptyList()) {
        val since = clock() - WINDOW_DAYS * DAY_MS
        store.all().filter { it.ts >= since }
            .groupBy { Triple(it.daypart, it.genre?.lowercase(), if (it.genre == null) it.mood else null) }
            .mapNotNull { (k, list) ->
                if (list.size < MIN_COUNT) null
                else Pattern(k.first, list.firstNotNullOfOrNull { it.genre }, k.third?.let { runCatching { Mood.valueOf(it) }.getOrNull() }, list.size, list.maxOf { it.ts })
            }
            .sortedWith(compareByDescending<Pattern> { it.count }.thenByDescending { it.last })
    }

    fun topFor(daypart: Daypart): Pattern? = patterns().firstOrNull { it.daypart == daypart }

    /** Fuer "Was hoere ich nachts?" bzw. "Was weisst du ueber mich?". Nur echte Muster, sonst ehrlich "zu wenig Daten". */
    fun describe(daypart: Daypart? = null): String {
        val ps = patterns().filter { daypart == null || it.daypart == daypart }
        val n = count()
        if (ps.isEmpty()) {
            return if (daypart == null) "Für feste Hörmuster hab ich noch zu wenig Daten (bisher $n Musikstarts gemerkt, ab 3 gleichen im selben Tagesabschnitt erkenne ich ein Muster)."
            else "Für ${daypart.label} hab ich noch zu wenig Daten, um ein Muster zu sehen (ab 3 gleichen Starts erkenne ich eins)."
        }
        return ps.take(4).joinToString("\n") { "⏰ ${it.daypart.label} hörst du oft ${it.label} (${it.count}x)" }
    }

    fun hasPatterns(): Boolean = patterns().isNotEmpty()
}

/** Rueckkehr zu einem frueheren Thema und Fragen nach Hoermustern. */
internal object TopicParser {
    private val PLAYLIST = Regex("""\b(?:playlist|entwurf|liste|queue)\b""")
    private val BACK = Regex("""\b(?:eben|vorhin|von\s+eben|von\s+vorhin|zurueck|weiter\s+mit)\b|^(?:und\s+)?(?:jetzt\s+)?die\s+""")
    private val EQ_BACK = Regex("""\bzurueck\s+(?:zum|zur)\s+(?:eq|equalizer|klang|sound)\b|\bwie\s+(?:war|steht)\s+(?:das\s+mit\s+dem\s+|der\s+)?(?:eq|equalizer|klang)\b""")
    private val RECAP = Regex("""\bwo\s+waren\s+wir\b|\bworueber\s+(?:haben\s+wir|hatten\s+wir)\b|\bwas\s+hatten\s+wir\b""")
    private val MUSIC_BACK = Regex("""\bzurueck\s+(?:zur|zu\s+der)\s+musik\b""")
    private val PATTERN = Regex("""\bwas\s+hoer(?:e|st)\s+(?:ich|man)\s+(?:so\s+|eigentlich\s+)?(?:meistens\s+|oft\s+|immer\s+)?(nachts|abends|morgens|tagsueber|mittags|frueh|spaet)?\b""")

    fun parse(raw: String): UserIntent? {
        val t = TextUtil.norm(raw).trim().trimEnd('.', '!', '?', ' ').trim()
        val words = t.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 9) return null
        if (RECAP.containsMatchIn(t)) return UserIntent.ResumeTopic(Topic.CONVERSATION)
        if (EQ_BACK.containsMatchIn(t)) return UserIntent.ResumeTopic(Topic.EQ)
        if (MUSIC_BACK.containsMatchIn(t)) return UserIntent.ResumeTopic(Topic.MUSIC)
        if (PLAYLIST.containsMatchIn(t) && BACK.containsMatchIn(t) && words.none { it in setOf("mach", "mache", "spiel", "starte", "loesch", "such") }) return UserIntent.ResumeTopic(Topic.PLAYLIST)
        PATTERN.find(t)?.let { m ->
            val w = m.groupValues[1]
            if (w.isNotEmpty() || t.contains("meistens") || t.contains("oft") || t.contains("immer")) return UserIntent.AskPattern(if (w.isEmpty()) null else Daypart.fromWord(w))
        }
        return null
    }
}
