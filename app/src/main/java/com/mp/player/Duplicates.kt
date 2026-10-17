package com.mp.player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Duplikaterkennung. WICHTIG: Es wird NIE automatisch etwas geloescht - diese Datei liefert nur
 * Gruppen mit Fakten; geloescht wird ausschliesslich auf ausdruecklichen Wunsch (Dialog in DuplicatesScreen).
 *
 * Zwei Stufen:
 *  1. IDENTICAL: gleiche Dateigroesse UND gleicher SHA-256 ueber den gesamten Dateiinhalt (bitgenau gleich).
 *     Gehasht wird nur, wo die Groesse schon uebereinstimmt -> kaum I/O. Ergebnisse werden gecacht.
 *  2. SIMILAR: gleicher Titel + Interpret (normalisiert) und fast gleiche Dauer, aber andere Datei
 *     (z. B. MP3 vs. FLAC, andere Bitrate). Das ist nur ein Hinweis - der Nutzer entscheidet.
 */
enum class DupKind { IDENTICAL, SIMILAR }

data class DupFile(
    val track: Track,
    val path: String,
    val sizeBytes: Long,
    val bitrateKbps: Int,   // 0 = nicht erkannt
    val sampleRate: Int,    // 0 = nicht erkannt
    val channels: Int,      // 0 = nicht erkannt
    val bitDepth: Int,      // 0 = nicht erkannt (nur bei PCM-Formaten gemeldet)
    val format: String
)

data class DupGroup(val id: String, val kind: DupKind, val files: List<DupFile>)

object DuplicateState {
    var groups by mutableStateOf<List<DupGroup>>(emptyList())
    var running by mutableStateOf(false)
    var finished by mutableStateOf(false)
    var status by mutableStateOf("")
    @Volatile var cancelled = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun start(context: Context) {
        if (running) return
        val app = context.applicationContext
        val snapshot = LibraryState.tracks
        running = true; finished = false; cancelled = false; status = "Starte…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Duplicates.find(app, snapshot, { status = it }, { cancelled })
                } catch (e: Exception) {
                    null
                }
            }
            if (result != null && !cancelled) groups = result
            finished = !cancelled
            running = false
            status = ""
        }
    }

    fun cancel() { cancelled = true }

    /** Nach dem Loeschen einer Datei: aus allen Gruppen entfernen; Gruppen mit < 2 Dateien sind keine Duplikate mehr. */
    fun removeFile(uri: String) {
        groups = groups
            .map { g -> g.copy(files = g.files.filter { it.track.uri != uri }) }
            .filter { it.files.size >= 2 }
    }
}

object Duplicates {

    /** Dieselbe physische Datei kann ueber ueberlappende Ordner mit zwei URIs auftauchen -> gleiche Document-ID. */
    fun physicalKey(uri: String): String = try {
        DocumentsContract.getDocumentId(Uri.parse(uri))
    } catch (e: Exception) {
        uri
    }

    fun displayPath(uri: String): String = try {
        val id = DocumentsContract.getDocumentId(Uri.parse(uri)) // z. B. "primary:Music/Album/a.mp3"
        val vol = id.substringBefore(':')
        val rel = id.substringAfter(':', id)
        (if (vol == "primary") "Interner Speicher/" else "SD-Karte ($vol)/") + rel
    } catch (e: Exception) {
        uri
    }

    fun find(
        context: Context,
        tracks: List<Track>,
        onStatus: (String) -> Unit,
        isCancelled: () -> Boolean
    ): List<DupGroup> {
        val cache = HashCache(File(context.filesDir, "hashcache.json")).also { it.load() }
        // eine Zeile pro physischer Datei - sonst wuerde dieselbe Datei als "Duplikat ihrer selbst" erscheinen
        val unique = tracks.distinctBy { physicalKey(it.uri) }

        // 1) Dateigroessen
        unique.forEachIndexed { i, t ->
            if (isCancelled()) return emptyList()
            if (i % 40 == 0) onStatus("Dateigrößen lesen… $i / ${unique.size}")
            val e = cache.entry(t)
            if (e.size < 0) e.size = sizeOf(context, t.uri)
        }

        // 2) identische Dateien: gleiche Groesse -> SHA-256 ueber den ganzen Inhalt
        val sameSize = unique.groupBy { cache.entry(it).size }.filter { it.key > 0 && it.value.size >= 2 }
        val totalToHash = sameSize.values.sumOf { it.size }
        var hashed = 0
        val identicalLists = mutableListOf<List<Track>>()
        for ((_, list) in sameSize) {
            val byHash = HashMap<String, MutableList<Track>>()
            for (t in list) {
                if (isCancelled()) return emptyList()
                onStatus("Dateien vergleichen… ${++hashed} / $totalToHash")
                val e = cache.entry(t)
                val h = e.hash ?: sha256(context, t.uri, isCancelled)?.also { e.hash = it }
                if (h != null) byHash.getOrPut(h) { mutableListOf() }.add(t)
            }
            byHash.values.filter { it.size >= 2 }.forEach { identicalLists.add(it) }
        }
        cache.save()

        // 3) aehnliche Titel (andere Datei, gleiche Metadaten + fast gleiche Dauer)
        onStatus("Ähnliche Titel suchen…")
        val groupOfUri = HashMap<String, Int>()
        identicalLists.forEachIndexed { gi, l -> l.forEach { groupOfUri[it.uri] = gi } }
        val similarLists = mutableListOf<List<Track>>()
        val byKey = unique.filter { it.durationMs > 0 && norm(it.title).length >= 3 }
            .groupBy { norm(it.title) + "|" + norm(it.artist) }
        for ((_, list) in byKey) {
            if (list.size < 2) continue
            val unknownArtist = norm(list.first().artist).let { it.isEmpty() || it == norm("Unbekannter Interpret") }
            // Ohne Interpret nur bei laengerem Titel und sehr enger Dauer - sonst zu viele Fehltreffer
            if (unknownArtist && norm(list.first().title).length < 6) continue
            val tol = if (unknownArtist) 1000L else 2000L
            val sorted = list.sortedBy { it.durationMs }
            var cluster = mutableListOf(sorted.first())
            fun flush() {
                if (cluster.size >= 2) {
                    val gs = cluster.map { groupOfUri[it.uri] }
                    val sameIdenticalGroup = gs.all { it != null } && gs.toSet().size == 1
                    if (!sameIdenticalGroup) similarLists.add(cluster.toList())
                }
            }
            for (t in sorted.drop(1)) {
                if (t.durationMs - cluster.last().durationMs <= tol) cluster.add(t) else { flush(); cluster = mutableListOf(t) }
            }
            flush()
        }

        // 4) Details nur fuer die Gruppenmitglieder (Bitrate, Sample Rate, Format ...) - nur erkannte Werte
        val detail = HashMap<String, DupFile>()
        fun describeCached(t: Track): DupFile =
            detail.getOrPut(t.uri) { describe(context, t, cache.entry(t).size) }
        val total = identicalLists.sumOf { it.size } + similarLists.sumOf { it.size }
        var d = 0
        fun build(kind: DupKind, lists: List<List<Track>>): List<DupGroup> = lists.mapIndexed { i, l ->
            val files = l.map {
                if (isCancelled()) return emptyList()
                if (++d % 5 == 0) onStatus("Details lesen… $d / $total")
                describeCached(it)
            }
            DupGroup("${kind.name}-$i-${l.first().uri.hashCode()}", kind, files.sortedBy { it.path })
        }
        val result = build(DupKind.IDENTICAL, identicalLists.sortedByDescending { cache.entry(it.first()).size }) +
            build(DupKind.SIMILAR, similarLists.sortedBy { norm(it.first().title) })
        cache.save()
        return result
    }

    private fun norm(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun sizeOf(context: Context, uri: String): Long = try {
        context.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        } ?: 0L
    } catch (e: Exception) {
        0L
    }

    private fun sha256(context: Context, uri: String, isCancelled: () -> Boolean): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                if (isCancelled()) return null
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) {
        null
    }

    private fun describe(context: Context, t: Track, size: Long): DupFile {
        var bitrate = 0; var sr = 0; var ch = 0; var depth = 0; var mime: String? = null
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, Uri.parse(t.uri), null)
            if (ex.trackCount > 0) {
                val f = ex.getTrackFormat(0)
                mime = if (f.containsKey(MediaFormat.KEY_MIME)) f.getString(MediaFormat.KEY_MIME) else null
                if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (f.containsKey(MediaFormat.KEY_BIT_RATE)) bitrate = f.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
                if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    depth = when (f.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                        AudioFormat.ENCODING_PCM_8BIT -> 8
                        AudioFormat.ENCODING_PCM_16BIT -> 16
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                        AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
                        else -> 0
                    }
                }
            }
        } catch (e: Exception) {
            // nicht lesbar -> Werte bleiben "nicht erkannt"
        } finally {
            try { ex.release() } catch (ignored: Exception) { }
        }
        if (bitrate <= 0) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, Uri.parse(t.uri))
                bitrate = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0) / 1000
            } catch (e: Exception) {
            } finally {
                try { mmr.release() } catch (ignored: Exception) { }
            }
        }
        val path = displayPath(t.uri)
        return DupFile(t, path, size, bitrate.coerceAtLeast(0), sr, ch, depth, formatName(mime, path))
    }

    private fun formatName(mime: String?, path: String): String = when (mime) {
        "audio/mpeg" -> "MP3"
        "audio/flac" -> "FLAC"
        "audio/mp4a-latm" -> "AAC/M4A"
        "audio/vorbis" -> "OGG Vorbis"
        "audio/opus" -> "Opus"
        "audio/raw" -> "WAV/PCM"
        else -> path.substringAfterLast('.', "").uppercase().ifBlank { "?" }
    }

    // ---------------------------------------------------------------
    // Hash-Cache: (Groesse, SHA-256) je Datei, gueltig solange "zuletzt geaendert" gleich bleibt
    // ---------------------------------------------------------------

    private class Entry(var size: Long, var hash: String?)

    private class HashCache(private val file: File) {
        private val map = HashMap<String, Pair<Long, Entry>>()

        fun entry(t: Track): Entry {
            val cur = map[t.uri]
            if (cur != null && cur.first == t.dateModified) return cur.second
            val e = Entry(-1L, null)
            map[t.uri] = t.dateModified to e
            return e
        }

        fun load() {
            try {
                if (!file.exists()) return
                val o = JSONObject(file.readText())
                for (k in o.keys()) {
                    val v = o.getJSONObject(k)
                    map[k] = v.getLong("m") to Entry(v.getLong("s"), if (v.has("h")) v.getString("h") else null)
                }
            } catch (e: Exception) {
                map.clear() // beschaedigter Cache -> einfach neu aufbauen
            }
        }

        fun save() {
            try {
                val o = JSONObject()
                for ((k, v) in map) {
                    if (v.second.size < 0) continue
                    val j = JSONObject().put("m", v.first).put("s", v.second.size)
                    v.second.hash?.let { j.put("h", it) }
                    o.put(k, j)
                }
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(o.toString())
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            } catch (e: Exception) { /* Cache ist nur eine Beschleunigung */ }
        }
    }
}
