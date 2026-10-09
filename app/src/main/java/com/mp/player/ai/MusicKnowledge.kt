package com.mp.player.ai

import android.content.Context
import org.json.JSONObject

/** Lokale Wissensbasis aus APK-Assets – offline, kein Training. */
object MusicKnowledge {
    @Volatile private var cache: JSONObject? = null

    fun load(context: Context): JSONObject {
        cache?.let { return it }
        return try {
            val text = context.assets.open("knowledge/music_kb.json").bufferedReader().readText()
            JSONObject(text).also { cache = it }
        } catch (_: Exception) {
            JSONObject()
        }
    }

    fun featureExplain(context: Context, key: String): String? {
        val f = load(context).optJSONObject("features") ?: return null
        return f.optString(key).takeIf { it.isNotBlank() }
    }

    fun genreHint(context: Context, name: String): String? {
        val g = load(context).optJSONObject("genres") ?: return null
        val key = name.lowercase().replace(" ", "")
        val keys = g.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (key.contains(k) || k.contains(key.take(4))) {
                val o = g.getJSONObject(k)
                val bpm = o.optJSONArray("typicalBpm")
                val range = if (bpm != null && bpm.length() >= 2) "${bpm.getInt(0)}–${bpm.getInt(1)} BPM" else "?"
                return "$k: typisch $range, Energie ${o.optString("energy")}"
            }
        }
        return null
    }

    fun limits(context: Context): List<String> {
        val arr = load(context).optJSONArray("limits") ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }
}
