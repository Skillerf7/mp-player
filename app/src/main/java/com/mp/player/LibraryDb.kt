package com.mp.player

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Analyse-Ergebnis eines Titels (Audioanalyse im Hintergrund, siehe Analysis.kt). 0 / NaN = nicht ermittelt. */
data class AnalysisResult(
    val uri: String,
    val modified: Long,
    val bpm: Float,        // 0 = nicht erkannt
    val lufs: Float,       // Integrierte Lautheit (BS.1770, mit Gating); NaN = nicht messbar (Stille)
    val peakDb: Float,     // Spitzenpegel in dBFS (Sample-Peak)
    val rmsDb: Float,      // RMS in dBFS
    val sampleRate: Int,
    val channels: Int,
    val partial: Boolean   // true = nur die ersten Minuten analysiert (lange Titel)
)

/**
 * Lokale Datenbank (Android-eigenes SQLite, keine Zusatz-Bibliothek -> kein Build-Risiko).
 * Haelt Bibliothek + Analyse. Die Bibliothek ist damit nach einem Neustart sofort da, ohne ein riesiges
 * JSON aus den SharedPreferences zu parsen. Jede Methode faengt Fehler ab - die DB darf nie die App abstuerzen lassen.
 */
class LibraryDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "library.db", null, 1) {

    companion object {
        @Volatile private var inst: LibraryDb? = null
        fun get(ctx: Context): LibraryDb = inst ?: synchronized(this) {
            inst ?: LibraryDb(ctx.applicationContext).also { inst = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE tracks (uri TEXT PRIMARY KEY NOT NULL, title TEXT, artist TEXT, album_artist TEXT, album TEXT, " +
                "year INTEGER, dur INTEGER, folder TEXT, modified INTEGER, genre TEXT, track_no INTEGER, disc_no INTEGER, file_name TEXT)"
        )
        db.execSQL(
            "CREATE TABLE analysis (uri TEXT PRIMARY KEY NOT NULL, modified INTEGER, bpm REAL, lufs REAL, peak REAL, rms REAL, " +
                "sample_rate INTEGER, channels INTEGER, partial INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { /* noch keine Migration noetig */ }

    @Synchronized
    fun loadTracks(): List<Track> = try {
        readableDatabase.rawQuery(
            "SELECT uri,title,artist,album_artist,album,year,dur,folder,modified,genre,track_no,disc_no,file_name FROM tracks", null
        ).use { c ->
            val out = ArrayList<Track>(c.count)
            while (c.moveToNext()) {
                out.add(
                    Track(
                        uri = c.getString(0), title = c.getString(1) ?: "", artist = c.getString(2) ?: "",
                        albumArtist = c.getString(3) ?: "", album = c.getString(4) ?: "", year = c.getInt(5),
                        durationMs = c.getLong(6), folder = c.getString(7) ?: "", dateModified = c.getLong(8),
                        genre = c.getString(9) ?: "", trackNo = c.getInt(10), discNo = c.getInt(11), fileName = c.getString(12) ?: ""
                    )
                )
            }
            out
        }
    } catch (e: Exception) { emptyList() }

    /** Ersetzt die komplette Bibliothek in EINER Transaktion (alles oder nichts). */
    @Synchronized
    fun replaceAll(list: List<Track>) {
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.delete("tracks", null, null)
                val st = db.compileStatement("INSERT OR REPLACE INTO tracks VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")
                for (t in list) {
                    st.clearBindings()
                    st.bindString(1, t.uri); st.bindString(2, t.title); st.bindString(3, t.artist)
                    st.bindString(4, t.albumArtist); st.bindString(5, t.album); st.bindLong(6, t.year.toLong())
                    st.bindLong(7, t.durationMs); st.bindString(8, t.folder); st.bindLong(9, t.dateModified)
                    st.bindString(10, t.genre); st.bindLong(11, t.trackNo.toLong()); st.bindLong(12, t.discNo.toLong())
                    st.bindString(13, t.fileName)
                    st.executeInsert()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) { /* Bibliothek bleibt im Speicher nutzbar; naechster Scan schreibt neu */ }
    }

    @Synchronized
    fun deleteUris(uris: Collection<String>) {
        if (uris.isEmpty()) return
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (u in uris) {
                    db.delete("tracks", "uri=?", arrayOf(u))
                    db.delete("analysis", "uri=?", arrayOf(u))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) { }
    }

    // ---------------- Analyse ----------------

    @Synchronized
    fun saveAnalysis(r: AnalysisResult) {
        try {
            val st = writableDatabase.compileStatement("INSERT OR REPLACE INTO analysis VALUES (?,?,?,?,?,?,?,?,?)")
            st.bindString(1, r.uri); st.bindLong(2, r.modified); st.bindDouble(3, r.bpm.toDouble())
            if (r.lufs.isNaN()) st.bindNull(4) else st.bindDouble(4, r.lufs.toDouble())
            st.bindDouble(5, r.peakDb.toDouble()); st.bindDouble(6, r.rmsDb.toDouble())
            st.bindLong(7, r.sampleRate.toLong()); st.bindLong(8, r.channels.toLong()); st.bindLong(9, if (r.partial) 1 else 0)
            st.executeInsert()
        } catch (e: Exception) { }
    }

    private fun readAnalysis(c: android.database.Cursor) = AnalysisResult(
        uri = c.getString(0), modified = c.getLong(1), bpm = c.getFloat(2),
        lufs = if (c.isNull(3)) Float.NaN else c.getFloat(3),
        peakDb = c.getFloat(4), rmsDb = c.getFloat(5), sampleRate = c.getInt(6), channels = c.getInt(7), partial = c.getInt(8) == 1
    )

    @Synchronized
    fun analysisFor(uri: String): AnalysisResult? = try {
        readableDatabase.rawQuery(
            "SELECT uri,modified,bpm,lufs,peak,rms,sample_rate,channels,partial FROM analysis WHERE uri=?", arrayOf(uri)
        ).use { c -> if (c.moveToFirst()) readAnalysis(c) else null }
    } catch (e: Exception) { null }

    @Synchronized
    fun analysisAll(): Map<String, AnalysisResult> = try {
        readableDatabase.rawQuery(
            "SELECT uri,modified,bpm,lufs,peak,rms,sample_rate,channels,partial FROM analysis", null
        ).use { c ->
            val m = HashMap<String, AnalysisResult>(c.count)
            while (c.moveToNext()) { val r = readAnalysis(c); m[r.uri] = r }
            m
        }
    } catch (e: Exception) { emptyMap() }
}
