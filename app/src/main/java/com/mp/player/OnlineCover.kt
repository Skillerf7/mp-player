package com.mp.player

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class CoverCandidate(
    val artistName: String,
    val collectionName: String,
    val trackName: String,
    val year: String,
    val artworkUrl: String,
    val score: Float,
    val exactish: Boolean
)

object OnlineCover {
    private const val TAG = "OnlineCover"

    private fun dir(ctx: Context): File = File(ctx.cacheDir, "covers_online").apply { mkdirs() }

    private fun key(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9äöüß]+"), " ").trim()

    private fun scoreMatch(
        qArtist: String, qAlbum: String, qTitle: String,
        aArtist: String, aAlbum: String, aTrack: String
    ): Pair<Float, Boolean> {
        val na = normalize(qArtist)
        val nb = normalize(qAlbum)
        val nt = normalize(qTitle)
        val ra = normalize(aArtist)
        val rb = normalize(aAlbum)
        val rt = normalize(aTrack)
        var s = 0f
        if (na.isNotEmpty() && ra.isNotEmpty()) {
            when {
                ra == na || ra.contains(na) || na.contains(ra) -> s += 0.45f
                ra.split(" ").any { it.length > 2 && na.contains(it) } -> s += 0.2f
            }
        }
        if (nb.isNotEmpty() && rb.isNotEmpty()) {
            when {
                rb == nb || rb.contains(nb) || nb.contains(rb) -> s += 0.4f
                else -> s += 0.05f
            }
        } else if (nt.isNotEmpty() && (rt.contains(nt) || rb.contains(nt))) {
            s += 0.25f
        }
        if (nt.isNotEmpty() && rt.isNotEmpty() && (rt.contains(nt) || nt.contains(rt))) s += 0.15f
        return s.coerceIn(0f, 1f) to (s >= 0.7f)
    }

    fun searchCandidates(artist: String, album: String, title: String, limit: Int = 8): List<CoverCandidate> {
        val qArtist = artist.trim().takeIf { it.isNotBlank() && !it.startsWith("<") } ?: ""
        val qAlbum = album.trim().takeIf { it.isNotBlank() } ?: ""
        val qTitle = title.trim().takeIf { it.isNotBlank() } ?: ""
        val term = listOf(qArtist, qAlbum.ifBlank { qTitle }).filter { it.isNotEmpty() }.joinToString(" ")
        if (term.length < 2) return emptyList()
        return try {
            val enc = URLEncoder.encode(term, "UTF-8")
            val urls = listOf(
                "https://itunes.apple.com/search?term=$enc&entity=album&limit=$limit",
                "https://itunes.apple.com/search?term=$enc&entity=song&limit=$limit"
            )
            val seen = HashSet<String>()
            val out = ArrayList<CoverCandidate>()
            for (apiUrl in urls) {
                if (out.size >= limit) break
                val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 8000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "SecretPlayer/1.0 (Android)")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val results = JSONObject(body).optJSONArray("results") ?: continue
                for (i in 0 until results.length()) {
                    if (out.size >= limit) break
                    val o = results.getJSONObject(i)
                    var art = o.optString("artworkUrl100", "")
                    if (art.isBlank()) continue
                    art = art.replace("100x100bb", "600x600bb").replace("100x100", "600x600")
                    if (!seen.add(art)) continue
                    val aName = o.optString("artistName", "")
                    val cName = o.optString("collectionName", o.optString("trackName", ""))
                    val tName = o.optString("trackName", "")
                    val year = o.optString("releaseDate", "").take(4)
                    val (sc, exact) = scoreMatch(qArtist, qAlbum, qTitle, aName, cName, tName)
                    out += CoverCandidate(aName, cName, tName, year, art, sc, exact)
                }
            }
            out.sortedByDescending { it.score }
        } catch (e: Exception) {
            Log.w(TAG, "search failed: ${e.message}")
            emptyList()
        }
    }

    fun downloadToFile(ctx: Context, artworkUrl: String, trackUri: String): File? {
        return try {
            val cacheKey = key("pick|$trackUri|${artworkUrl.takeLast(48)}")
            val out = File(dir(ctx), "$cacheKey.jpg")
            if (out.exists() && out.length() > 512) return out
            val imgConn = (URL(artworkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 10000
                setRequestProperty("User-Agent", "SecretPlayer/1.0")
            }
            imgConn.inputStream.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            imgConn.disconnect()
            if (out.length() < 512) {
                out.delete(); null
            } else out
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${e.message}")
            null
        }
    }

    fun fetchToCache(
        ctx: Context, artist: String, album: String, title: String, force: Boolean = false
    ): File? {
        val qArtist = artist.trim().takeIf { it.isNotBlank() && !it.startsWith("<") } ?: ""
        val qAlbum = album.trim().takeIf { it.isNotBlank() } ?: ""
        val qTitle = title.trim().takeIf { it.isNotBlank() } ?: ""
        val term = listOf(qArtist, qAlbum.ifBlank { qTitle }).filter { it.isNotEmpty() }.joinToString(" ")
        if (term.length < 2) return null
        val cacheKey = key("itunes|$term")
        val out = File(dir(ctx), "$cacheKey.jpg")
        if (!force && out.exists() && out.length() > 512) return out
        val none = File(dir(ctx), "$cacheKey.none")
        if (force && none.exists()) none.delete()
        if (!force && none.exists()) {
            val age = System.currentTimeMillis() - none.lastModified()
            if (age < 3_600_000L) return null
            none.delete()
        }
        val candidates = searchCandidates(artist, album, title, limit = 6)
        val best = candidates.firstOrNull { it.score >= 0.55f } ?: candidates.firstOrNull()
        if (best == null) {
            none.writeText("empty")
            return null
        }
        if (force && !best.exactish && best.score < 0.55f) return null
        val file = downloadToFile(ctx, best.artworkUrl, "auto|$term") ?: run {
            none.writeText("dlfail"); return null
        }
        file.copyTo(out, overwrite = true)
        return out
    }

    fun searchForPicker(artist: String, album: String, title: String): List<CoverCandidate> =
        searchCandidates(artist, album, title, limit = 10)
}
