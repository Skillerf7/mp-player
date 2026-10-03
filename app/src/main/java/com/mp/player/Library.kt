package com.mp.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

// Punkt 5/6/19 aus der Anforderung: mehrere Ordner als Musikquellen, echter
// Scan/Sync (nicht doppelt, entfernte Dateien erkennen), persistent gespeichert.
// Läuft über Storage Access Framework (SAF) statt roher Dateipfade, weil das
// unter Scoped Storage (Android 11+) der einzige zuverlässige Weg ist, um
// beliebige vom Nutzer gewählte Ordner (SD-Karte, interner Speicher, ...)
// dauerhaft lesen zu dürfen.

data class Track(
    val uri: String,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val year: Int,
    val durationMs: Long,
    val folder: String,
    val dateModified: Long,
    val genre: String = "",
    val trackNo: Int = 0,
    val discNo: Int = 0,
    val fileName: String = ""
) {
    val id: Long get() = uri.hashCode().toLong()
}

data class FolderRef(val treeUri: String, val displayName: String)

object Library {

    /** Nutzer hat einen Ordner über den SAF-Picker gewählt -> dauerhafte Leserechte sichern. */
    fun takeFolder(context: Context, treeUri: Uri): FolderRef {
        // Lesen UND Schreiben dauerhaft sichern (sonst scheitert "Löschen" nach einem Neustart).
        // Bietet der Anbieter kein Schreiben an, reicht Lesen - das darf nie zum Absturz fuehren.
        val rw = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, rw)
        } catch (e: Exception) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e2: Exception) { /* Berechtigung evtl. schon vorhanden bzw. nicht persistierbar */ }
        }
        val doc = DocumentFile.fromTreeUri(context, treeUri)
        val name = doc?.name ?: treeUri.lastPathSegment ?: "Ordner"
        // "primary:Music" -> "Music (Interner Speicher)", "1234-ABCD:Music" -> "Music (SD-Karte)"
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val storageLabel = if (docId.startsWith("primary:")) "Interner Speicher" else "SD-Karte"
        return FolderRef(treeUri.toString(), "$name ($storageLabel)")
    }

    /**
     * Läuft alle gewählten Ordner rekursiv ab, liest Metadaten nur für neue/
     * geänderte Dateien aus (dateModified-Vergleich gegen den Cache), entfernt
     * Einträge zu nicht mehr vorhandenen Dateien. [onProgress] optional für eine
     * Fortschrittsanzeige beim manuellen "Neu einlesen".
     */
    fun scan(
        context: Context,
        folders: List<FolderRef>,
        cached: List<Track>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<Track> {
        val cacheByUri = cached.associateBy { it.uri }
        val seen = LinkedHashSet<String>()
        val seenDocs = HashSet<String>() // physische Datei (Document-ID): dieselbe Datei ueber ueberlappende Ordner nur einmal
        val result = mutableListOf<Track>()
        val allDocs = mutableListOf<Pair<DocumentFile, String>>() // Datei + relativer Ordnerpfad
        val unreadable = mutableListOf<String>() // Ordner, die gerade nicht gelesen werden konnten

        for (f in folders) {
            try {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(f.treeUri))
                if (root == null || !root.canRead()) { unreadable.add(f.displayName); continue }
                collect(root, f.displayName, allDocs)
            } catch (e: Exception) {
                unreadable.add(f.displayName) // z. B. SD-Karte entfernt / Berechtigung weg
            }
        }

        allDocs.forEachIndexed { i, (doc, folderPath) ->
            onProgress(i + 1, allDocs.size)
            val uriStr = doc.uri.toString()
            // Ueberlappende Ordner (Ordner + Unterordner gewaehlt) duerfen keine Doppelte erzeugen
            if (!seen.add(uriStr)) return@forEachIndexed
            val docKey = try { DocumentsContract.getDocumentId(doc.uri) } catch (e: Exception) { uriStr }
            if (!seenDocs.add(docKey)) return@forEachIndexed
            val cachedTrack = cacheByUri[uriStr]
            // fileName leer = alter Cache-Eintrag ohne Genre/Dateiname -> einmal neu einlesen
            if (cachedTrack != null && cachedTrack.dateModified == doc.lastModified() && cachedTrack.fileName.isNotEmpty()) {
                result.add(cachedTrack) // unverändert -> nicht erneut auslesen
            } else {
                readMetadata(context, doc, folderPath)?.let { result.add(it) }
            }
        }
        // Ordner, die nicht gelesen werden konnten (SD-Karte kurz weg, Berechtigung fehlt), behalten ihre
        // bisherigen Titel - sonst wuerde ein voruebergehender Fehler die Bibliothek leeren.
        if (unreadable.isNotEmpty()) {
            for (c in cached) {
                if (c.uri !in seen && unreadable.any { c.folder == it || c.folder.startsWith("$it/") }) {
                    seen.add(c.uri)
                    result.add(c)
                }
            }
        }
        // Alles andere, was im Cache war, aber nicht mehr gefunden wurde, faellt raus (nicht mehr vorhandene Dateien).
        return result
    }

    private fun collect(dir: DocumentFile, path: String, out: MutableList<Pair<DocumentFile, String>>) {
        val children = try { dir.listFiles() } catch (e: Exception) { emptyArray<DocumentFile>() }
        for (child in children) {
            when {
                child.isDirectory -> collect(child, "$path/${child.name}", out)
                child.isFile && (child.type?.startsWith("audio/") == true ||
                    child.name?.substringAfterLast('.', "")?.lowercase() in AUDIO_EXT) ->
                    out.add(child to path)
            }
        }
    }

    private val AUDIO_EXT = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma")

    private fun readMetadata(context: Context, doc: DocumentFile, folder: String): Track? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, doc.uri)
            fun g(key: Int) = mmr.extractMetadata(key)
            Track(
                uri = doc.uri.toString(),
                title = g(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: (doc.name ?: "Unbekannt"),
                artist = g(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unbekannter Interpret",
                albumArtist = g(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    ?: g(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unbekannter Interpret",
                album = g(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Unbekanntes Album",
                year = g(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull() ?: 0,
                durationMs = g(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                folder = folder,
                dateModified = doc.lastModified(),
                genre = g(MediaMetadataRetriever.METADATA_KEY_GENRE)?.trim() ?: "",
                trackNo = g(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0,
                discNo = g(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0,
                fileName = doc.name ?: ""
            )
        } catch (e: Exception) {
            null
        } finally {
            mmr.release()
        }
    }
}

// --- Persistenz: Ordnerliste + gecachte Bibliothek in der bestehenden Store-Klasse (Dsp.kt) ---

fun Store.folders(): List<FolderRef> = try {
    val a = JSONArray(rawFolders())
    (0 until a.length()).map {
        val o = a.getJSONObject(it)
        FolderRef(o.getString("uri"), o.getString("name"))
    }
} catch (e: Exception) { emptyList() }

fun Store.saveFolders(list: List<FolderRef>) {
    val a = JSONArray()
    list.forEach { a.put(JSONObject().put("uri", it.treeUri).put("name", it.displayName)) }
    saveRawFolders(a.toString())
}

/**
 * Bibliothek liegt jetzt in der SQLite-Datenbank (LibraryDb). Beim ersten Start nach dem Update wird die
 * alte JSON-Bibliothek einmalig uebernommen und danach aus den SharedPreferences entfernt.
 */
fun Store.library(): List<Track> {
    val db = LibraryDb.get(appContext)
    val fromDb = db.loadTracks()
    if (fromDb.isNotEmpty()) return fromDb
    val legacy = legacyLibrary()
    if (legacy.isNotEmpty()) {
        db.replaceAll(legacy)
        saveRawLibrary("[]")
    }
    return legacy
}

fun Store.saveLibrary(list: List<Track>) {
    LibraryDb.get(appContext).replaceAll(list)
}

private fun Store.legacyLibrary(): List<Track> = try {
    val a = JSONArray(rawLibrary())
    (0 until a.length()).mapNotNull {
        try {
            val o = a.getJSONObject(it)
            Track(
                o.getString("uri"), o.getString("title"), o.getString("artist"),
                o.getString("albumArtist"), o.getString("album"), o.getInt("year"),
                o.getLong("dur"), o.getString("folder"), o.getLong("mod")
            )
        } catch (e: Exception) { null } // einzelner beschaedigter Eintrag ueberspringt nur sich selbst
    }
} catch (e: Exception) { emptyList() }
