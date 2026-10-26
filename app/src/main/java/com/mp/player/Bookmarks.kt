package com.mp.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lokale Lesezeichen: Track-URI + Position (ms) + optionaler Name.
 * SharedPreferences, kein Cloud.
 */
data class Bookmark(
    val id: Long,
    val uri: String,
    val title: String,
    val artist: String,
    val positionMs: Long,
    val label: String,
    val createdAt: Long
)

object Bookmarks {
    private const val PREF = "bookmarks_v1"
    private const val KEY = "items"
    private const val MAX = 200

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Bookmark> = try {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = ArrayList<Bookmark>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += Bookmark(
                id = o.optLong("id", i.toLong()),
                uri = o.optString("uri"),
                title = o.optString("title"),
                artist = o.optString("artist"),
                positionMs = o.optLong("pos", 0L),
                label = o.optString("label", ""),
                createdAt = o.optLong("at", 0L)
            )
        }
        out.sortedByDescending { it.createdAt }
    } catch (_: Exception) {
        emptyList()
    }

    fun add(ctx: Context, track: Track, positionMs: Long, label: String = ""): Bookmark {
        val b = Bookmark(
            id = System.currentTimeMillis(),
            uri = track.uri,
            title = track.title,
            artist = track.artist,
            positionMs = positionMs.coerceAtLeast(0L),
            label = label.ifBlank { formatPos(positionMs) },
            createdAt = System.currentTimeMillis()
        )
        val list = (listOf(b) + all(ctx)).take(MAX)
        save(ctx, list)
        return b
    }

    fun remove(ctx: Context, id: Long) {
        save(ctx, all(ctx).filter { it.id != id })
    }

    fun forTrack(ctx: Context, uri: String): List<Bookmark> =
        all(ctx).filter { it.uri == uri }

    private fun save(ctx: Context, list: List<Bookmark>) {
        val arr = JSONArray()
        for (b in list) {
            arr.put(JSONObject().apply {
                put("id", b.id)
                put("uri", b.uri)
                put("title", b.title)
                put("artist", b.artist)
                put("pos", b.positionMs)
                put("label", b.label)
                put("at", b.createdAt)
            })
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun formatPos(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}
