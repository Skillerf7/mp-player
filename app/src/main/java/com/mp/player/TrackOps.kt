package com.mp.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile

/**
 * Echtes Loeschen von Titeln UND Bereinigen aller Verweise (Bibliothek, Datenbank, Favoriten, Playlists,
 * laufende Queue). Datei loeschen darf im Hintergrund laufen ([deleteFile]); Verweise entfernen
 * ([removeReferencesBatch]) gehoert auf den Main-Thread (Queue/Compose-State).
 */
object TrackOps {

    fun exists(context: Context, uri: String): Boolean = try {
        context.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) {
        false
    }

    /** Loescht nur die Datei. @return true, wenn sie weg ist (geloescht oder existierte schon nicht mehr). */
    fun deleteFile(context: Context, track: Track): Boolean {
        val deleted = try {
            DocumentFile.fromSingleUri(context, Uri.parse(track.uri))?.delete() ?: false
        } catch (e: Exception) {
            false
        }
        return deleted || !exists(context, track.uri)
    }

    /**
     * @return true, wenn die Datei geloescht wurde (oder schon nicht mehr existierte) und alle
     * Verweise entfernt wurden; false, wenn das Loeschen nicht moeglich war (dann bleibt alles unveraendert).
     */
    fun deleteEverywhere(context: Context, track: Track): Boolean {
        if (!deleteFile(context, track)) return false
        removeReferences(context, track)
        return true
    }

    fun removeReferences(context: Context, track: Track) = removeReferencesBatch(context, listOf(track))

    fun removeReferencesBatch(context: Context, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val uris = tracks.map { it.uri }.toSet()
        val ids = tracks.map { it.id }.toSet()
        // Laufende Queue: Eintraege mit diesen URIs entfernen (Player springt selbst zum naechsten Titel)
        PlayerBridge.controller?.let { c ->
            try {
                for (i in c.mediaItemCount - 1 downTo 0) {
                    if (c.getMediaItemAt(i).mediaId in uris) c.removeMediaItem(i)
                }
            } catch (ignored: Exception) { }
        }
        val st = Store(context)
        LibraryState.tracks = LibraryState.tracks.filter { it.uri !in uris }
        LibraryDb.get(context).deleteUris(uris)
        st.saveFavs(st.favs() - ids)
        // Nur der Verweis aus den Playlists wird entfernt (die Dateien sind hier bereits geloescht)
        PlaylistStore.removeTrackIds(context, ids)
    }
}
