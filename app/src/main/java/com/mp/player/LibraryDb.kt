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
    val partial: Boolean,  // true = nur die ersten Minuten analysiert (lange Titel)
    /** 0..1 – wie sicher die BPM-Schätzung ist (Half/Double-Time berücksichtigt). */
    val bpmConfidence: Float = 0f,
    /** LIGHT spectral (0 if not computed). */
    val spectralCentroidHz: Float = 0f,
    val spectralRolloffHz: Float = 0f,
    val spectralFlux: Float = 0f,
    val spectralFlatness: Float = 0f,
    val bassEnergy: Float = 0f,
    val midEnergy: Float = 0f,
    val highEnergy: Float = 0f,
    /** AnalysisVersions.FEATURE_VERSION at write time. */
    val featureVersion: Int = AnalysisVersions.FEATURE_VERSION,
    val analysisLevel: String = AnalysisLevel.LIGHT.name,
    val rhythmRegularity: Float = 0f,
    val energyIntro: Float = 0f,
    val energyMid: Float = 0f,
    val energyLate: Float = 0f,
    val analysisStatus: String = "ok" // ok | failed | pending
) {
    /** Crest factor approx from peak/RMS in linear domain. */
    fun dynamicRangeDb(): Float {
        if (peakDb.isNaN() || rmsDb.isNaN()) return Float.NaN
        return (peakDb - rmsDb).coerceAtLeast(0f)
    }
}

/**
 * Lokale Datenbank (Android-eigenes SQLite, keine Zusatz-Bibliothek -> kein Build-Risiko).
 * Haelt Bibliothek + Analyse. Die Bibliothek ist damit nach einem Neustart sofort da, ohne ein riesiges
 * JSON aus den SharedPreferences zu parsen. Jede Methode faengt Fehler ab - die DB darf nie die App abstuerzen lassen.
 */
class LibraryDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "library.db", null, 9) {

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
                "sample_rate INTEGER, channels INTEGER, partial INTEGER, bpm_confidence REAL, " +
                "centroid REAL, rolloff REAL, flux REAL, flatness REAL, bass REAL, mid REAL, high_e REAL, " +
                "feature_version INTEGER, analysis_level TEXT, rhythm REAL, e_intro REAL, e_mid REAL, e_late REAL, status TEXT)"
        )
        createHistory(db)
        createSkips(db)
        createLyrics(db)
        createPersonalLearning(db)
    }

    /** Eingebettete Songtexte + daraus berechnetes Stimmungsprofil (nur lokal, aus den Datei-Tags). */
    private fun createLyrics(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS lyrics (uri TEXT PRIMARY KEY NOT NULL, modified INTEGER, has_text INTEGER, profile TEXT, text TEXT)")
    }

    /** Ueberspringen (Weiter-Taste kurz nach dem Start) - Signal fuer "mag ich gerade nicht". */
    private fun createSkips(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS play_skips (id INTEGER PRIMARY KEY AUTOINCREMENT, uri TEXT NOT NULL, skipped_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_skips_uri ON play_skips(uri)")
    }

    /** Hoerverlauf (fuer "lange nicht gehoert", "zuletzt gespielt" ...). Eine Zeile pro Wiedergabe-Start. */
    private fun createHistory(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS play_history (id INTEGER PRIMARY KEY AUTOINCREMENT, uri TEXT NOT NULL, played_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_uri ON play_history(uri)")
    }

    private fun createPersonalLearning(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS personal_genre (uri TEXT PRIMARY KEY NOT NULL, genre TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS personal_bpm (uri TEXT PRIMARY KEY NOT NULL, bpm REAL NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS personal_rec_feedback (uri TEXT NOT NULL, accepted INTEGER NOT NULL, at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS analysis_attempt (uri TEXT PRIMARY KEY NOT NULL, attempts INTEGER NOT NULL, last_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS ml_genre (uri TEXT PRIMARY KEY NOT NULL, model TEXT NOT NULL, label TEXT NOT NULL, confidence REAL NOT NULL, labels_json TEXT, at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 -> 2: nur neue Tabelle, bestehende Bibliothek und Analyse bleiben unveraendert
        if (oldVersion < 2) createHistory(db)
        // Version 2 -> 3: nur neue Tabelle fuer Skips
        if (oldVersion < 3) createSkips(db)
        // Version 3 -> 4: nur neue Tabelle fuer Songtexte
        if (oldVersion < 4) createLyrics(db)
        if (oldVersion < 5) {
            try { db.execSQL("ALTER TABLE analysis ADD COLUMN bpm_confidence REAL DEFAULT 0") } catch (_: Exception) {}
        }
        if (oldVersion < 6) createPersonalLearning(db)
        if (oldVersion < 7) {
            for (col in listOf(
                "centroid REAL DEFAULT 0", "rolloff REAL DEFAULT 0", "flux REAL DEFAULT 0",
                "flatness REAL DEFAULT 0", "bass REAL DEFAULT 0", "mid REAL DEFAULT 0", "high_e REAL DEFAULT 0",
                "feature_version INTEGER DEFAULT 1", "analysis_level TEXT DEFAULT 'LIGHT'"
            )) {
                try { db.execSQL("ALTER TABLE analysis ADD COLUMN $col") } catch (_: Exception) {}
            }
        }
        if (oldVersion < 9) {
            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS ml_genre (uri TEXT PRIMARY KEY NOT NULL, model TEXT NOT NULL, label TEXT NOT NULL, confidence REAL NOT NULL, labels_json TEXT, at INTEGER NOT NULL)")
            } catch (_: Exception) {}
        }
        if (oldVersion < 8) {
            for (col in listOf(
                "rhythm REAL DEFAULT 0", "e_intro REAL DEFAULT 0", "e_mid REAL DEFAULT 0", "e_late REAL DEFAULT 0",
                "status TEXT DEFAULT 'ok'"
            )) {
                try { db.execSQL("ALTER TABLE analysis ADD COLUMN $col") } catch (_: Exception) {}
            }
            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS analysis_attempt (uri TEXT PRIMARY KEY NOT NULL, attempts INTEGER NOT NULL, last_at INTEGER NOT NULL)")
            } catch (_: Exception) {}
        }
    }

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
                    db.delete("play_history", "uri=?", arrayOf(u))
                    db.delete("play_skips", "uri=?", arrayOf(u))
                    db.delete("lyrics", "uri=?", arrayOf(u))
                    db.delete("ml_genre", "uri=?", arrayOf(u))
                    db.delete("analysis_attempt", "uri=?", arrayOf(u))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) { }
    }

    // ---------------- Hoerverlauf ----------------

    private val historyIo = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "history-db").apply { isDaemon = true }
    }

    /** Vom Player-Service aufgerufen: schreibt im Hintergrund, blockiert nie die Wiedergabe. */
    fun recordPlayAsync(uri: String, at: Long = System.currentTimeMillis()) {
        historyIo.execute { recordPlay(uri, at) }
    }

    @Synchronized
    fun recordPlay(uri: String, at: Long) {
        try {
            val db = writableDatabase
            db.execSQL("INSERT INTO play_history(uri, played_at) VALUES (?, ?)", arrayOf<Any>(uri, at))
            // Verlauf klein halten: die letzten 20000 Wiedergaben reichen voellig
            db.execSQL("DELETE FROM play_history WHERE id <= (SELECT MAX(id) FROM play_history) - 20000")
        } catch (e: Exception) { }
    }

    /** Vom Player-Service aufgerufen: schreibt im Hintergrund, blockiert nie die Wiedergabe. */
    fun recordSkipAsync(uri: String, at: Long = System.currentTimeMillis()) {
        historyIo.execute { recordSkip(uri, at) }
    }

    @Synchronized
    fun recordSkip(uri: String, at: Long) {
        try {
            val db = writableDatabase
            db.execSQL("INSERT INTO play_skips(uri, skipped_at) VALUES (?, ?)", arrayOf<Any>(uri, at))
            db.execSQL("DELETE FROM play_skips WHERE id <= (SELECT MAX(id) FROM play_skips) - 20000")
        } catch (e: Exception) { }
    }

    @Synchronized
    fun skipCountsAll(): Map<String, Int> = try {
        readableDatabase.rawQuery("SELECT uri, COUNT(*) FROM play_skips GROUP BY uri", null).use { c ->
            val m = HashMap<String, Int>(c.count)
            while (c.moveToNext()) m[c.getString(0)] = c.getInt(1)
            m
        }
    } catch (e: Exception) { emptyMap() }

    @Synchronized
    fun lastSkippedAll(): Map<String, Long> = try {
        readableDatabase.rawQuery("SELECT uri, MAX(skipped_at) FROM play_skips GROUP BY uri", null).use { c ->
            val m = HashMap<String, Long>(c.count)
            while (c.moveToNext()) m[c.getString(0)] = c.getLong(1)
            m
        }
    } catch (e: Exception) { emptyMap() }

    /** Neueste zuerst: (URI, Zeitpunkt). */
    @Synchronized
    fun recentPlays(limit: Int): List<Pair<String, Long>> = try {
        readableDatabase.rawQuery(
            "SELECT uri, played_at FROM play_history ORDER BY id DESC LIMIT ${limit.coerceIn(1, 5000)}", null
        ).use { c ->
            val out = ArrayList<Pair<String, Long>>()
            while (c.moveToNext()) out.add(c.getString(0) to c.getLong(1))
            out
        }
    } catch (e: Exception) { emptyList() }

    @Synchronized
    fun lastPlayedAll(): Map<String, Long> = try {
        readableDatabase.rawQuery("SELECT uri, MAX(played_at) FROM play_history GROUP BY uri", null).use { c ->
            val m = HashMap<String, Long>(c.count)
            while (c.moveToNext()) m[c.getString(0)] = c.getLong(1)
            m
        }
    } catch (e: Exception) { emptyMap() }

    @Synchronized
    fun playCountsAll(): Map<String, Int> = try {
        readableDatabase.rawQuery("SELECT uri, COUNT(*) FROM play_history GROUP BY uri", null).use { c ->
            val m = HashMap<String, Int>(c.count)
            while (c.moveToNext()) m[c.getString(0)] = c.getInt(1)
            m
        }
    } catch (e: Exception) { emptyMap() }

    
    // ---------------- Personal Learning (lokal, kein Upload) ----------------

    @Synchronized
    fun setGenreOverride(uri: String, genre: String) {
        try {
            writableDatabase.execSQL(
                "INSERT OR REPLACE INTO personal_genre(uri, genre) VALUES (?, ?)",
                arrayOf<Any>(uri, genre)
            )
        } catch (_: Exception) {}
    }

    @Synchronized
    fun genreOverridesAll(): Map<String, String> = try {
        readableDatabase.rawQuery("SELECT uri, genre FROM personal_genre", null).use { c ->
            val m = HashMap<String, String>()
            while (c.moveToNext()) m[c.getString(0)] = c.getString(1) ?: ""
            m
        }
    } catch (_: Exception) { emptyMap() }

    @Synchronized
    fun setBpmOverride(uri: String, bpm: Float) {
        try {
            writableDatabase.execSQL(
                "INSERT OR REPLACE INTO personal_bpm(uri, bpm) VALUES (?, ?)",
                arrayOf<Any>(uri, bpm)
            )
        } catch (_: Exception) {}
    }

    @Synchronized
    fun bpmOverridesAll(): Map<String, Float> = try {
        readableDatabase.rawQuery("SELECT uri, bpm FROM personal_bpm", null).use { c ->
            val m = HashMap<String, Float>()
            while (c.moveToNext()) m[c.getString(0)] = c.getFloat(1)
            m
        }
    } catch (_: Exception) { emptyMap() }

    @Synchronized
    fun addRecFeedback(uri: String, accepted: Boolean) {
        try {
            writableDatabase.execSQL(
                "INSERT INTO personal_rec_feedback(uri, accepted, at) VALUES (?, ?, ?)",
                arrayOf<Any>(uri, if (accepted) 1 else 0, System.currentTimeMillis())
            )
        } catch (_: Exception) {}
    }

    @Synchronized
    fun recFeedbackCounts(): Pair<Map<String, Int>, Map<String, Int>> = try {
        val acc = HashMap<String, Int>()
        val rej = HashMap<String, Int>()
        readableDatabase.rawQuery(
            "SELECT uri, accepted, COUNT(*) FROM personal_rec_feedback GROUP BY uri, accepted", null
        ).use { c ->
            while (c.moveToNext()) {
                val uri = c.getString(0)
                val accepted = c.getInt(1) == 1
                val n = c.getInt(2)
                if (accepted) acc[uri] = n else rej[uri] = n
            }
        }
        acc to rej
    } catch (_: Exception) { emptyMap<String, Int>() to emptyMap() }


    // ---------------- Songtexte ----------------

    /** Was ueber einen Titel bekannt ist - ohne den Text selbst (spart Speicher beim Laden der ganzen Bibliothek). */
    data class LyricsRow(val uri: String, val modified: Long, val hasText: Boolean, val profile: String)

    /** [text] = null: Datei enthaelt keinen eingebetteten Text (wird trotzdem gemerkt, damit nicht bei jedem Lauf neu gesucht wird). */
    @Synchronized
    fun saveLyrics(uri: String, modified: Long, text: String?, profile: String) {
        try {
            val st = writableDatabase.compileStatement("INSERT OR REPLACE INTO lyrics VALUES (?,?,?,?,?)")
            st.bindString(1, uri); st.bindLong(2, modified); st.bindLong(3, if (text != null) 1 else 0)
            st.bindString(4, profile)
            if (text != null) st.bindString(5, text) else st.bindNull(5)
            st.executeInsert()
        } catch (e: Exception) { }
    }

    @Synchronized
    fun lyricsAll(): Map<String, LyricsRow> = try {
        readableDatabase.rawQuery("SELECT uri, modified, has_text, profile FROM lyrics", null).use { c ->
            val m = HashMap<String, LyricsRow>(c.count)
            while (c.moveToNext()) {
                m[c.getString(0)] = LyricsRow(c.getString(0), c.getLong(1), c.getInt(2) == 1, c.getString(3) ?: "")
            }
            m
        }
    } catch (e: Exception) { emptyMap() }

    @Synchronized
    fun lyricsText(uri: String): String? = try {
        readableDatabase.rawQuery("SELECT text FROM lyrics WHERE uri=?", arrayOf(uri)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (e: Exception) { null }

    // ---------------- Analyse ----------------

    @Synchronized
    fun saveAnalysis(r: AnalysisResult) {
        try {
            val st = writableDatabase.compileStatement(
                "INSERT OR REPLACE INTO analysis(uri,modified,bpm,lufs,peak,rms,sample_rate,channels,partial,bpm_confidence," +
                    "centroid,rolloff,flux,flatness,bass,mid,high_e,feature_version,analysis_level," +
                    "rhythm,e_intro,e_mid,e_late,status) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            )
            st.bindString(1, r.uri); st.bindLong(2, r.modified); st.bindDouble(3, r.bpm.toDouble())
            if (r.lufs.isNaN()) st.bindNull(4) else st.bindDouble(4, r.lufs.toDouble())
            st.bindDouble(5, r.peakDb.toDouble()); st.bindDouble(6, r.rmsDb.toDouble())
            st.bindLong(7, r.sampleRate.toLong()); st.bindLong(8, r.channels.toLong()); st.bindLong(9, if (r.partial) 1 else 0)
            st.bindDouble(10, r.bpmConfidence.toDouble())
            st.bindDouble(11, r.spectralCentroidHz.toDouble())
            st.bindDouble(12, r.spectralRolloffHz.toDouble())
            st.bindDouble(13, r.spectralFlux.toDouble())
            st.bindDouble(14, r.spectralFlatness.toDouble())
            st.bindDouble(15, r.bassEnergy.toDouble())
            st.bindDouble(16, r.midEnergy.toDouble())
            st.bindDouble(17, r.highEnergy.toDouble())
            st.bindLong(18, r.featureVersion.toLong())
            st.bindString(19, r.analysisLevel)
            st.bindDouble(20, r.rhythmRegularity.toDouble())
            st.bindDouble(21, r.energyIntro.toDouble())
            st.bindDouble(22, r.energyMid.toDouble())
            st.bindDouble(23, r.energyLate.toDouble())
            st.bindString(24, r.analysisStatus)
            st.executeInsert()
            // Erfolg: Attempt-Zähler löschen
            try { writableDatabase.delete("analysis_attempt", "uri=?", arrayOf(r.uri)) } catch (_: Exception) {}
        } catch (e: Exception) { }
    }

    private fun readAnalysis(c: android.database.Cursor) = AnalysisResult(
        uri = c.getString(0), modified = c.getLong(1), bpm = c.getFloat(2),
        lufs = if (c.isNull(3)) Float.NaN else c.getFloat(3),
        peakDb = c.getFloat(4), rmsDb = c.getFloat(5), sampleRate = c.getInt(6), channels = c.getInt(7), partial = c.getInt(8) == 1,
        bpmConfidence = if (c.columnCount > 9 && !c.isNull(9)) c.getFloat(9) else 0f,
        spectralCentroidHz = col(c, "centroid"),
        spectralRolloffHz = col(c, "rolloff"),
        spectralFlux = col(c, "flux"),
        spectralFlatness = col(c, "flatness"),
        bassEnergy = col(c, "bass"),
        midEnergy = col(c, "mid"),
        highEnergy = col(c, "high_e"),
        featureVersion = colInt(c, "feature_version", 1),
        analysisLevel = colStr(c, "analysis_level", "LIGHT"),
        rhythmRegularity = col(c, "rhythm"),
        energyIntro = col(c, "e_intro"),
        energyMid = col(c, "e_mid"),
        energyLate = col(c, "e_late"),
        analysisStatus = colStr(c, "status", "ok")
    )

    private fun col(c: android.database.Cursor, name: String): Float {
        val i = c.getColumnIndex(name)
        return if (i >= 0 && !c.isNull(i)) c.getFloat(i) else 0f
    }
    private fun colInt(c: android.database.Cursor, name: String, def: Int): Int {
        val i = c.getColumnIndex(name)
        return if (i >= 0 && !c.isNull(i)) c.getInt(i) else def
    }
    private fun colStr(c: android.database.Cursor, name: String, def: String): String {
        val i = c.getColumnIndex(name)
        return if (i >= 0 && !c.isNull(i)) c.getString(i) ?: def else def
    }

    @Synchronized
    fun analysisFor(uri: String): AnalysisResult? = try {
        readableDatabase.rawQuery("SELECT * FROM analysis WHERE uri=?", arrayOf(uri))
            .use { c -> if (c.moveToFirst()) readAnalysis(c) else null }
    } catch (e: Exception) { null }

    @Synchronized
    fun analysisAll(): Map<String, AnalysisResult> = try {
        readableDatabase.rawQuery("SELECT * FROM analysis", null).use { c ->
            val m = HashMap<String, AnalysisResult>(c.count)
            while (c.moveToNext()) { val r = readAnalysis(c); m[r.uri] = r }
            m
        }
    } catch (e: Exception) { emptyMap() }

    /** Fehlversuch merken; true = weiterer Retry erlaubt (max 3, Backoff 6h). */
    @Synchronized
    fun recordAnalysisFailure(uri: String): Boolean {
        return try {
            val now = System.currentTimeMillis()
            val row = readableDatabase.rawQuery(
                "SELECT attempts, last_at FROM analysis_attempt WHERE uri=?", arrayOf(uri)
            ).use { c -> if (c.moveToFirst()) c.getInt(0) to c.getLong(1) else null }
            val attempts = (row?.first ?: 0) + 1
            writableDatabase.execSQL(
                "INSERT OR REPLACE INTO analysis_attempt(uri, attempts, last_at) VALUES (?,?,?)",
                arrayOf<Any>(uri, attempts, now)
            )
            attempts < 3 && (row == null || now - row.second > 6L * 3600_000)
        } catch (_: Exception) { true }
    }

    @Synchronized
    fun saveMlGenre(uri: String, model: String, topLabel: String, confidence: Float, allJson: String) {
        try {
            writableDatabase.execSQL(
                "INSERT OR REPLACE INTO ml_genre(uri, model, label, confidence, labels_json, at) VALUES (?,?,?,?,?,?)",
                arrayOf<Any>(uri, model, topLabel, confidence, allJson, System.currentTimeMillis())
            )
        } catch (_: Exception) {}
    }

    @Synchronized
    fun mlGenre(uri: String): Triple<String, Float, String>? = try {
        readableDatabase.rawQuery("SELECT label, confidence, labels_json FROM ml_genre WHERE uri=?", arrayOf(uri)).use { c ->
            if (c.moveToFirst()) Triple(c.getString(0), c.getFloat(1), c.getString(2) ?: "") else null
        }
    } catch (_: Exception) { null }

    /** uri -> "label:konfidenz,..." fuer alle Titel mit ML-Ergebnis dieses Modells (leere Marker ausgenommen). */
    @Synchronized
    fun mlGenresAll(model: String): Map<String, String> = try {
        readableDatabase.rawQuery("SELECT uri, labels_json FROM ml_genre WHERE model=? AND label<>''", arrayOf(model)).use { c ->
            val m = HashMap<String, String>(c.count)
            while (c.moveToNext()) m[c.getString(0)] = c.getString(1) ?: ""
            m
        }
    } catch (_: Exception) { emptyMap() }

    /** Titel, die fuer dieses Modell schon verarbeitet wurden (auch "kein Ergebnis"-Marker), damit nichts endlos neu laeuft. */
    @Synchronized
    fun mlGenreDoneUris(model: String): Set<String> = try {
        readableDatabase.rawQuery("SELECT uri FROM ml_genre WHERE model=?", arrayOf(model)).use { c ->
            val s = HashSet<String>(c.count)
            while (c.moveToNext()) s.add(c.getString(0))
            s
        }
    } catch (_: Exception) { emptySet() }

    fun canRetryAnalysis(uri: String): Boolean {
        return try {
            readableDatabase.rawQuery(
                "SELECT attempts, last_at FROM analysis_attempt WHERE uri=?", arrayOf(uri)
            ).use { c ->
                if (!c.moveToFirst()) return true
                val attempts = c.getInt(0)
                val last = c.getLong(1)
                attempts < 3 && System.currentTimeMillis() - last > 6L * 3600_000
            }
        } catch (_: Exception) { true }
    }
}
