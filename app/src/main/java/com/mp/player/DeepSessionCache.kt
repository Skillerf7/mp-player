package com.mp.player

import java.util.concurrent.ConcurrentHashMap

/**
 * Sitzungs-Cache für Deep-Analyse (begrenzt, thread-sicher).
 * Verhindert, dass jede Empfehlung die gesamte analysis_deep-Tabelle parst.
 */
object DeepSessionCache {
    private const val MAX = 400
    private val map = ConcurrentHashMap<String, DeepSnapshot>()
    @Volatile private var loadedAll = false

    fun get(uri: String): DeepSnapshot? = map[uri]

    fun put(uri: String, d: DeepSnapshot) {
        if (map.size >= MAX && !map.containsKey(uri)) {
            // simple eviction: clear half
            val keys = map.keys().toList().take(MAX / 2)
            keys.forEach { map.remove(it) }
        }
        map[uri] = d
    }

    fun putAll(m: Map<String, DeepSnapshot>) {
        for ((k, v) in m) {
            if (map.size >= MAX) break
            map[k] = v
        }
        loadedAll = true
    }

    fun ensureLoaded(db: LibraryDb) {
        if (loadedAll) return
        synchronized(this) {
            if (loadedAll) return
            try {
                putAll(db.deepAnalysisAll())
            } catch (_: Exception) {
            }
            loadedAll = true
        }
    }

    fun clear() {
        map.clear()
        loadedAll = false
    }

    fun size(): Int = map.size
}
