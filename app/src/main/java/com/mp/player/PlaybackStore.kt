package com.mp.player

import android.content.Context
import android.util.AtomicFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Minimaler Queue-Eintrag, der auch ohne geladene Bibliothek wiederherstellbar ist. */
data class SavedItem(val uri: String, val title: String, val artist: String, val album: String)

/** Zustand (klein, wird oft geschrieben). */
data class SavedState(
    val mediaId: String?,
    val index: Int,
    val positionMs: Long,
    val wasPlaying: Boolean,
    val repeatMode: Int,
    val shuffle: Boolean
)

data class SavedPlayback(val items: List<SavedItem>, val state: SavedState)

/** Einzige Stelle, die MediaItems baut -> Queue, Wiederherstellung und Benachrichtigung sind identisch. */
object MediaItems {
    fun build(uri: String, title: String, artist: String, album: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(uri)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    // Cover wird lazy ueber den CoverProvider aus den eingebetteten Tags geladen
                    .setArtworkUri(Covers.artworkUri(uri))
                    .build()
            )
            .build()

    fun from(t: Track): MediaItem = build(t.uri, t.title, t.artist, t.album)
    fun from(s: SavedItem): MediaItem = build(s.uri, s.title, s.artist, s.album)

    fun toSaved(item: MediaItem): SavedItem = SavedItem(
        uri = item.mediaId.ifEmpty { item.localConfiguration?.uri?.toString() ?: "" },
        title = item.mediaMetadata.title?.toString() ?: "",
        artist = item.mediaMetadata.artist?.toString() ?: "",
        album = item.mediaMetadata.albumTitle?.toString() ?: ""
    )
}

/**
 * Crash-sichere Speicherung des Wiedergabezustands.
 *
 * - Zwei Dateien: Queue (selten geschrieben) und State (Index/Position/Flags, oft geschrieben).
 * - Jede Datei wird ueber android.util.AtomicFile geschrieben (Schreiben in neue Datei,
 *   dann atomarer Wechsel, vorherige Version bleibt als Backup) -> ein abgebrochener
 *   Schreibvorgang kann den letzten guten Stand nicht zerstoeren.
 * - Alle Schreibzugriffe laufen seriell auf einem eigenen Thread (kein Blockieren der UI,
 *   keine parallelen Zugriffe auf dieselbe Datei).
 */
class PlaybackStore(context: Context) {
    private val queueFile = AtomicFile(File(context.filesDir, "playback_queue.json"))
    private val stateFile = AtomicFile(File(context.filesDir, "playback_state.json"))
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "playback-store").apply { isDaemon = true } }

    fun saveQueueAsync(items: List<SavedItem>) {
        val json = JSONObject().put("v", 1).put("items", JSONArray().also { a ->
            items.forEach {
                a.put(JSONObject().put("uri", it.uri).put("title", it.title).put("artist", it.artist).put("album", it.album))
            }
        })
        io.execute { write(queueFile, json) }
    }

    fun saveStateAsync(s: SavedState) {
        val json = toJson(s)
        io.execute { write(stateFile, json) }
    }

    /** Fuer onTaskRemoved/onDestroy: wartet kurz, bis alles auf der Platte ist. */
    fun saveStateBlocking(s: SavedState) {
        val json = toJson(s)
        try {
            io.submit(Callable { write(stateFile, json) }).get(1500, TimeUnit.MILLISECONDS)
        } catch (ignored: Exception) { /* Zeitueberschreitung: der async-Stand bleibt gueltig */ }
    }

    private fun toJson(s: SavedState) = JSONObject()
        .put("v", 1)
        .put("mediaId", s.mediaId ?: JSONObject.NULL)
        .put("index", s.index)
        .put("pos", s.positionMs)
        .put("playing", s.wasPlaying)
        .put("repeat", s.repeatMode)
        .put("shuffle", s.shuffle)
        .put("savedAt", System.currentTimeMillis())

    private fun write(f: AtomicFile, json: JSONObject) {
        var out: FileOutputStream? = null
        try {
            out = f.startWrite()
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            if (out != null) f.failWrite(out)
        }
    }

    private fun read(f: AtomicFile): JSONObject? = try {
        JSONObject(String(f.readFully(), Charsets.UTF_8))
    } catch (e: Exception) {
        null // fehlt oder beschaedigt -> wie "nicht vorhanden" behandeln
    }

    /** Liest den letzten Stand. Beschaedigte/fehlende Dateien fuehren nie zu einer Exception. */
    fun load(): SavedPlayback? {
        val q = read(queueFile)
        val s = read(stateFile)
        if (q == null && s == null) return null

        val items = ArrayList<SavedItem>()
        q?.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val uri = o.optString("uri")
                if (uri.isNotBlank()) items.add(SavedItem(uri, o.optString("title"), o.optString("artist"), o.optString("album")))
            }
        }
        val state = SavedState(
            mediaId = if (s == null || s.isNull("mediaId")) null else s.optString("mediaId").ifEmpty { null },
            index = s?.optInt("index", 0) ?: 0,
            positionMs = s?.optLong("pos", 0L) ?: 0L,
            wasPlaying = s?.optBoolean("playing", false) ?: false,
            repeatMode = (s?.optInt("repeat", 0) ?: 0).coerceIn(0, 2),
            shuffle = s?.optBoolean("shuffle", false) ?: false
        )
        return SavedPlayback(items, state)
    }
}
