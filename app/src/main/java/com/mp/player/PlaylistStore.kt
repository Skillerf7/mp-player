package com.mp.player

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class PlaylistResult { OK, EMPTY_NAME, NAME_EXISTS, NOT_FOUND }

/**
 * Die EINE Quelle fuer alle Wiedergabelisten (Uebersicht, Detailansicht, "Zur Wiedergabeliste
 * hinzufuegen", Loeschen von Titeln ...). Der Zustand ist ein Compose-State: Jede Aenderung ist
 * sofort in allen Ansichten sichtbar.
 *
 * Gespeichert wird crash-sicher in "playlists.json" ueber AtomicFile (neue Datei schreiben, dann
 * atomar austauschen, die vorherige Version bleibt als Sicherung). Jede Aenderung wird auf die
 * Platte geschrieben, BEVOR die Funktion zurueckkehrt - es gibt kein "spaeter speichern", das bei
 * einem Absturz verloren gehen koennte.
 *
 * Eine Playlist besteht nur aus Verweisen (Titel-IDs). Playlist loeschen oder Titel entfernen
 * beruehrt NIE die Musikdateien oder die Bibliothek.
 */
object PlaylistStore {

    /** Name -> Titel-IDs in Abspielreihenfolge (Reihenfolge der Playlists = Erstellreihenfolge). */
    var playlists by mutableStateOf<Map<String, List<Long>>>(emptyMap())
        private set

    /** true, sobald die Playlists von der Platte gelesen wurden (vorher ist "leer" nur ein Platzhalter). */
    var ready by mutableStateOf(false)
        private set

    private var file: AtomicFile? = null
    private var loaded = false
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "playlist-store").apply { isDaemon = true }
    }

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        val app = context.applicationContext
        val f = AtomicFile(File(app.filesDir, "playlists.json"))
        file = f

        val data: Map<String, List<Long>> = if (f.baseFile.exists()) {
            readFile(f) ?: emptyMap()
        } else {
            // Erster Start mit der neuen Verwaltung: bisherige Playlists uebernehmen und sofort sichern.
            val legacy: Map<String, List<Long>> = try { Store(app).pls() } catch (e: Exception) { emptyMap() }
            if (legacy.isNotEmpty()) writeBlocking(f, toJson(legacy))
            legacy
        }
        playlists = data
        loaded = true
        ready = true
    }

    // ------------------------------------------------------------------
    // Abfragen
    // ------------------------------------------------------------------

    /** Die aufloesbaren Titel einer Playlist (in Playlist-Reihenfolge). Fehlende Dateien werden uebersprungen. */
    fun tracksOf(name: String): List<Track> {
        val ids = playlists[name] ?: return emptyList()
        val byId = LibraryState.tracks.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    // ------------------------------------------------------------------
    // Aenderungen
    // ------------------------------------------------------------------

    @Synchronized
    fun create(context: Context, rawName: String): PlaylistResult {
        ensureLoaded(context)
        val name = rawName.trim()
        if (name.isEmpty()) return PlaylistResult.EMPTY_NAME
        if (playlists.keys.any { it.equals(name, ignoreCase = true) }) return PlaylistResult.NAME_EXISTS
        commit(playlists + (name to emptyList<Long>()))
        return PlaylistResult.OK
    }

    @Synchronized
    fun delete(context: Context, name: String): Boolean {
        ensureLoaded(context)
        if (name !in playlists) return false
        commit(playlists - name)
        return true
    }

    @Synchronized
    fun rename(context: Context, oldName: String, rawNewName: String): PlaylistResult {
        ensureLoaded(context)
        val newName = rawNewName.trim()
        if (newName.isEmpty()) return PlaylistResult.EMPTY_NAME
        if (oldName !in playlists) return PlaylistResult.NOT_FOUND
        if (!newName.equals(oldName, ignoreCase = true) &&
            playlists.keys.any { it.equals(newName, ignoreCase = true) }
        ) return PlaylistResult.NAME_EXISTS
        val renamed = LinkedHashMap<String, List<Long>>()
        for ((k, v) in playlists) renamed[if (k == oldName) newName else k] = v
        commit(renamed)
        return PlaylistResult.OK
    }

    /** Fuegt Titel hinzu; Titel, die schon enthalten sind, werden uebersprungen. Liefert die Zahl der neu hinzugefuegten. */
    @Synchronized
    fun addTracks(context: Context, name: String, tracks: List<Track>): Int {
        ensureLoaded(context)
        val current = playlists[name] ?: return 0
        val have = current.toHashSet()
        val added = ArrayList<Long>()
        for (t in tracks) if (have.add(t.id)) added.add(t.id)
        if (added.isEmpty()) return 0
        commit(playlists + (name to (current + added)))
        return added.size
    }

    /** Entfernt NUR die Zuordnung zur Playlist. Dateien und Bibliothek bleiben unveraendert. */
    @Synchronized
    fun removeTracks(context: Context, name: String, ids: Set<Long>): Int {
        ensureLoaded(context)
        val current = playlists[name] ?: return 0
        val kept = current.filter { it !in ids }
        val removed = current.size - kept.size
        if (removed > 0) commit(playlists + (name to kept))
        return removed
    }

    /** Wird nach dem echten Loeschen von Dateien aufgerufen: Verweise aus allen Playlists entfernen. */
    @Synchronized
    fun removeTrackIds(context: Context, ids: Set<Long>) {
        ensureLoaded(context)
        var changed = false
        val updated = LinkedHashMap<String, List<Long>>()
        for ((k, v) in playlists) {
            val kept = v.filter { it !in ids }
            if (kept.size != v.size) changed = true
            updated[k] = kept
        }
        if (changed) commit(updated)
    }

    // ------------------------------------------------------------------
    // Speicherung
    // ------------------------------------------------------------------

    private fun commit(new: Map<String, List<Long>>) {
        playlists = new
        file?.let { writeBlocking(it, toJson(new)) }
    }

    private fun toJson(map: Map<String, List<Long>>): String {
        val lists = JSONArray()
        for ((name, ids) in map) {
            lists.put(JSONObject().put("name", name).put("ids", JSONArray(ids)))
        }
        return JSONObject().put("v", 1).put("lists", lists).toString()
    }

    private fun readFile(f: AtomicFile): Map<String, List<Long>>? = try {
        val o = JSONObject(String(f.readFully(), Charsets.UTF_8))
        val arr = o.getJSONArray("lists")
        val result = LinkedHashMap<String, List<Long>>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val name = e.optString("name", "")
            if (name.isEmpty()) continue
            val idsArr = e.optJSONArray("ids")
            val ids = ArrayList<Long>()
            if (idsArr != null) for (j in 0 until idsArr.length()) ids.add(idsArr.optLong(j))
            result[name] = ids
        }
        result
    } catch (e: Exception) {
        null
    }

    private fun writeBlocking(f: AtomicFile, json: String) {
        try {
            io.submit(Callable {
                var out: FileOutputStream? = null
                try {
                    out = f.startWrite()
                    out.write(json.toByteArray(Charsets.UTF_8))
                    f.finishWrite(out)
                } catch (e: Exception) {
                    if (out != null) f.failWrite(out)
                }
            }).get(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Zeitueberschreitung: der Schreibvorgang laeuft im Hintergrund weiter
        }
    }
}
