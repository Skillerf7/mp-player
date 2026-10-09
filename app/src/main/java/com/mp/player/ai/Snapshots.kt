package com.mp.player.ai

import com.mp.player.Dsp
import java.io.File
import java.util.TimeZone
import kotlin.math.abs

/*
 * Session-Snapshots: Zustand einer Musiksession (Stimmung, Genre, Dauer, EQ). Damit werden Aussagen wie
 * "mach wieder wie gestern" / "wie letztens" aufloesbar. Alles lokal; faellt die Datei aus, arbeitet der
 * Assistent mit einer Kopie im Speicher weiter (die Wiedergabe bleibt unberuehrt).
 */

/** Worauf sich "wie ..." bezieht. */
enum class RestoreWhen { PREVIOUS, YESTERDAY, EARLIER }

/** Kompakter EQ-Stand (ohne Android-Klassen, damit testbar). */
data class EqSnap(val pre: Float, val bass: Float, val treble: Float, val gains: List<Float>) {
    fun isNeutral(): Boolean = abs(bass) < 0.01f && abs(treble) < 0.01f && gains.all { abs(it) < 0.01f }

    fun maxAbs(): Float = maxOf(abs(bass), abs(treble), gains.maxOfOrNull { abs(it) } ?: 0f)

    /** Uebernimmt die gespeicherten Werte auf den aktuellen Dsp (andere Bandzahl -> Baender bleiben unveraendert). */
    fun applyTo(d: Dsp): Dsp {
        val bands = if (gains.size == d.bands.size) d.bands.mapIndexed { i, b -> b.copy(g = gains[i]) } else d.bands
        return d.copy(on = true, pre = pre, bass = bass, treble = treble, bands = bands)
    }

    companion object {
        fun of(d: Dsp) = EqSnap(d.pre, d.bass, d.treble, d.bands.map { it.g })
    }
}

data class SessionSnapshot(
    val at: Long,
    val mood: Mood?,
    val genreLabel: String?,
    val genres: List<String>,
    val minutes: Int?,
    val eq: EqSnap?
) {
    /** Kurzname fuer Anzeige und Unterscheidung (\"Hardtekk\", \"ruhig\"). */
    fun label(): String = genreLabel ?: mood?.label ?: "gemischt"
}

interface SnapshotStore {
    fun all(): List<SessionSnapshot>
    fun add(s: SessionSnapshot)
    fun clear()
}

class InMemorySnapshotStore : SnapshotStore {
    private val items = ArrayList<SessionSnapshot>()
    override fun all(): List<SessionSnapshot> = items.toList()
    override fun add(s: SessionSnapshot) { items.add(s); SnapshotCodec.trim(items) }
    override fun clear() { items.clear() }
}

/** Textformat: eine Zeile pro Snapshot, Felder per Tab getrennt. Kaputte Zeilen werden ignoriert. */
object SnapshotCodec {
    const val MAX_ITEMS = 50
    const val MAX_AGE_MS = 60L * 86_400_000L

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').replace('|', '/')

    fun encode(s: SessionSnapshot): String {
        val eq = s.eq?.let { e -> "${e.pre};${e.bass};${e.treble};" + e.gains.joinToString(",") } ?: "-"
        return listOf(
            s.at.toString(),
            s.mood?.name ?: "-",
            s.genreLabel?.let { clean(it) } ?: "-",
            if (s.genres.isEmpty()) "-" else s.genres.joinToString("|") { clean(it) },
            s.minutes?.toString() ?: "-",
            eq
        ).joinToString("\t")
    }

    fun decode(line: String): SessionSnapshot? {
        return try {
            val p = line.split('\t')
            if (p.size != 6) return null
            val at = p[0].toLongOrNull() ?: return null
            val mood = if (p[1] == "-") null else Mood.valueOf(p[1])
            val label = p[2].takeIf { it != "-" }
            val genres: List<String> = if (p[3] == "-") emptyList() else p[3].split('|')
            val minutes = if (p[4] == "-") null else (p[4].toIntOrNull() ?: return null)
            val eq = if (p[5] == "-") null else {
                val q = p[5].split(';')
                if (q.size != 4) return null
                val gains: List<Float> = if (q[3].isBlank()) emptyList() else q[3].split(',').map { it.toFloatOrNull() ?: return null }
                EqSnap(q[0].toFloatOrNull() ?: return null, q[1].toFloatOrNull() ?: return null, q[2].toFloatOrNull() ?: return null, gains)
            }
            SessionSnapshot(at, mood, label, genres, minutes, eq)
        } catch (e: Exception) {
            null
        }
    }

    /** Behaelt die neuesten [MAX_ITEMS] und wirft Eintraege aelter als [MAX_AGE_MS] raus. */
    fun trim(list: MutableList<SessionSnapshot>, now: Long = System.currentTimeMillis()) {
        list.removeAll { now - it.at > MAX_AGE_MS }
        while (list.size > MAX_ITEMS) list.removeAt(0)
    }
}

class FileSnapshotStore(private val file: File) : SnapshotStore {
    private val fallback = InMemorySnapshotStore()
    private var useFallback = false

    override fun all(): List<SessionSnapshot> {
        if (useFallback) return fallback.all()
        return try {
            if (!file.exists()) emptyList()
            else file.readLines().mapNotNull { SnapshotCodec.decode(it) }
        } catch (e: Exception) {
            useFallback = true
            fallback.all()
        }
    }

    override fun add(s: SessionSnapshot) {
        if (useFallback) { fallback.add(s); return }
        try {
            val list = all().toMutableList()
            list.add(s)
            SnapshotCodec.trim(list)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(list.joinToString("\n") { SnapshotCodec.encode(it) } + "\n")
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        } catch (e: Exception) {
            useFallback = true
            fallback.add(s)
        }
    }

    override fun clear() {
        fallback.clear()
        try { file.delete() } catch (e: Exception) { }
    }
}

sealed class SnapshotChoice {
    object None : SnapshotChoice()
    data class One(val snap: SessionSnapshot) : SnapshotChoice()
    /** Mehrere unterschiedliche Kandidaten -> kurz nachfragen. */
    data class Ask(val options: List<SessionSnapshot>) : SnapshotChoice()
}

object SnapshotResolver {
    private const val DAY = 86_400_000L
    /** Alles juenger als das gilt noch als \"die aktuelle Session\" und ist bei \"letztens\" nicht gemeint. */
    private const val SESSION_MS = 3L * 3_600_000L

    private fun dayOf(t: Long, zone: TimeZone) = (t + zone.getOffset(t)) / DAY

    fun resolve(all: List<SessionSnapshot>, which: RestoreWhen, now: Long, zone: TimeZone = TimeZone.getDefault()): SnapshotChoice {
        if (which == RestoreWhen.YESTERDAY) {
            val y = dayOf(now, zone) - 1
            val cands = all.filter { dayOf(it.at, zone) == y }.sortedByDescending { it.at }
            if (cands.isEmpty()) return SnapshotChoice.None
            val distinct = cands.distinctBy { it.label().lowercase() }
            return if (distinct.size == 1) SnapshotChoice.One(distinct[0]) else SnapshotChoice.Ask(distinct.take(3))
        }
        if (which == RestoreWhen.EARLIER) {
            val latest = all.filter { now - it.at > SESSION_MS }.maxByOrNull { it.at } ?: return SnapshotChoice.None
            return SnapshotChoice.One(latest)
        }
        return SnapshotChoice.None // PREVIOUS laeuft ueber den EQ-Undo-Stack des Assistenten
    }
}
