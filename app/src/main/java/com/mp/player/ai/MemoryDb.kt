package com.mp.player.ai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Eigene Datei memory.db (getrennt von library.db): geht hier etwas kaputt, laeuft der Player trotzdem.
 * Jede Operation ist abgesichert; bei Fehlern arbeitet der Store mit einer Kopie im Speicher weiter.
 */
/** Eine Datei, versionierte Migration: v1 = memory, v2 = + episodes. Bestehende Daten bleiben erhalten. */
internal class MemoryHelper(c: Context) : SQLiteOpenHelper(c, "memory.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS memory (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, key TEXT, label TEXT, scope TEXT, " +
                "confidence REAL, importance REAL, created_at INTEGER, updated_at INTEGER, last_used_at INTEGER, " +
                "expires_at INTEGER, explicit INTEGER)"
        )
        createEpisodes(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) createEpisodes(db)
    }
    private fun createEpisodes(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS episodes (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, daypart TEXT, mood TEXT, " +
                "genre TEXT, emotion TEXT, topic TEXT, minutes INTEGER)"
        )
    }
}

class SqliteMemoryStore(ctx: Context) : MemoryStore {
    private val helper: MemoryHelper? = try { MemoryHelper(ctx.applicationContext) } catch (e: Exception) { null }
    private val fallback = InMemoryStore()

    private fun values(i: MemoryItem) = ContentValues().apply {
        put("kind", i.kind.name); put("key", i.key); put("label", i.label); put("scope", i.scope.name)
        put("confidence", i.confidence); put("importance", i.importance)
        put("created_at", i.createdAt); put("updated_at", i.updatedAt); put("last_used_at", i.lastUsedAt)
        if (i.expiresAt != null) put("expires_at", i.expiresAt) else putNull("expires_at")
        put("explicit", if (i.explicit) 1 else 0)
    }

    override fun all(): List<MemoryItem> {
        val h = helper ?: return fallback.all()
        return try {
            h.readableDatabase.rawQuery(
                "SELECT id, kind, key, label, scope, confidence, importance, created_at, updated_at, last_used_at, expires_at, explicit FROM memory", null
            ).use { c ->
                val out = ArrayList<MemoryItem>()
                while (c.moveToNext()) {
                    out.add(
                        MemoryItem(
                            c.getLong(0), MemKind.valueOf(c.getString(1)), c.getString(2), c.getString(3), MemScope.valueOf(c.getString(4)),
                            c.getFloat(5), c.getFloat(6), c.getLong(7), c.getLong(8), c.getLong(9),
                            if (c.isNull(10)) null else c.getLong(10), c.getInt(11) == 1
                        )
                    )
                }
                out
            }
        } catch (e: Exception) { fallback.all() }
    }

    override fun put(item: MemoryItem): MemoryItem {
        val h = helper ?: return fallback.put(item)
        return try {
            val db = h.writableDatabase
            if (item.id == 0L) {
                val id = db.insertOrThrow("memory", null, values(item))
                item.copy(id = id)
            } else {
                db.update("memory", values(item), "id = ?", arrayOf(item.id.toString()))
                item
            }
        } catch (e: Exception) { fallback.put(item) }
    }

    override fun delete(id: Long) {
        try { helper?.writableDatabase?.delete("memory", "id = ?", arrayOf(id.toString())) } catch (e: Exception) {}
        fallback.delete(id)
    }

    override fun clear() {
        try { helper?.writableDatabase?.delete("memory", null, null) } catch (e: Exception) {}
        fallback.clear()
    }
}

/** Episoden in derselben memory.db; bei Fehlern arbeitet der Store im Speicher weiter (Player bleibt unberuehrt). */
class SqliteEpisodeStore(ctx: Context) : EpisodeStore {
    private val helper: MemoryHelper? = try { MemoryHelper(ctx.applicationContext) } catch (e: Exception) { null }
    private val fallback = InMemoryEpisodeStore()

    override fun add(e: Episode) {
        val h = helper
        if (h == null) { fallback.add(e); return }
        try {
            val v = ContentValues().apply {
                put("ts", e.ts); put("daypart", e.daypart.name)
                if (e.mood != null) put("mood", e.mood) else putNull("mood")
                if (e.genre != null) put("genre", e.genre) else putNull("genre")
                if (e.emotion != null) put("emotion", e.emotion) else putNull("emotion")
                if (e.topic != null) put("topic", e.topic) else putNull("topic")
                if (e.minutes != null) put("minutes", e.minutes) else putNull("minutes")
            }
            h.writableDatabase.insertOrThrow("episodes", null, v)
        } catch (ex: Exception) { fallback.add(e) }
    }

    override fun all(): List<Episode> {
        val h = helper ?: return fallback.all()
        return try {
            h.readableDatabase.rawQuery("SELECT id, ts, daypart, mood, genre, emotion, topic, minutes FROM episodes ORDER BY ts ASC", null).use { c ->
                val out = ArrayList<Episode>()
                while (c.moveToNext()) {
                    out.add(
                        Episode(
                            c.getLong(0), c.getLong(1), Daypart.valueOf(c.getString(2)),
                            if (c.isNull(3)) null else c.getString(3), if (c.isNull(4)) null else c.getString(4),
                            if (c.isNull(5)) null else c.getString(5), if (c.isNull(6)) null else c.getString(6),
                            if (c.isNull(7)) null else c.getInt(7)
                        )
                    )
                }
                out
            } + fallback.all()
        } catch (ex: Exception) { fallback.all() }
    }

    override fun trim(keep: Int) {
        try {
            helper?.writableDatabase?.execSQL(
                "DELETE FROM episodes WHERE id NOT IN (SELECT id FROM episodes ORDER BY ts DESC LIMIT $keep)"
            )
        } catch (ex: Exception) { /* Aufraeumen ist optional */ }
        fallback.trim(keep)
    }
}
