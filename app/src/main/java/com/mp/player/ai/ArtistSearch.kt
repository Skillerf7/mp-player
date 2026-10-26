package com.mp.player.ai

import com.mp.player.Track

/**
 * Interpret-Suche: schaut in erster Linie ins Artist-/Album-Artist-Feld (Gross-/Kleinschreibung,
 * Umlaute, Leerzeichen und Sonderzeichen egal). Nur wenn das Artist-Feld nichts hergibt (viele Dateien
 * haben "Unbekannter Interpret"), wird zusaetzlich der Titel/Dateiname nach "Name - Titel" durchsucht.
 * Bewusst KEIN Genre-/Stimmungs-Fallback.
 */
internal object ArtistSearch {
    private fun squash(s: String) = TextUtil.norm(s).filter { it.isLetterOrDigit() }

    fun find(tracks: List<Track>, query: String, limit: Int = 200): List<Track> {
        val q = squash(query)
        if (q.length < 2) return emptyList()
        val byArtist = tracks.filter { squash(it.artist).contains(q) || squash(it.albumArtist).contains(q) }
        if (byArtist.isNotEmpty()) return byArtist.take(limit)
        // Sekundaer: "Name - Titel" im Titel bzw. Dateinamen (nur wenn es im Artist-Feld keinen Treffer gibt)
        return tracks.filter { t ->
            listOf(t.title, t.fileName).any { s ->
                val n = TextUtil.norm(s).trimStart()
                val idx = n.indexOf(" - ")
                idx > 0 && squash(n.substring(0, idx)).contains(q)
            }
        }.take(limit)
    }
}
