package com.mp.player.ai

import com.mp.player.AnalysisResult
import com.mp.player.Dsp
import com.mp.player.HiResCapabilities
import com.mp.player.Track

/**
 * Die EINZIGE Verbindung zwischen KI und MusicPlayer. Die KI darf nur das hier, nichts sonst.
 *
 * Bewusst NICHT vorhanden: Dateien loeschen, Berechtigungen anfordern, Daten senden.
 * Dauerhafte Aenderungen (Playlist anlegen, Klang aendern) liegen im Block "bestaetigungspflichtig":
 * der [Assistant] ruft sie ausschliesslich nach einem ausdruecklichen "Ja" des Nutzers auf.
 *
 * Alle Aufrufe muessen auf dem Main-Thread erfolgen (MediaController); die suspend-Funktionen
 * wechseln fuer Datenbankzugriffe selbst auf einen Hintergrund-Thread.
 */
interface PlayerTools {
    // ---- Lesen ----
    fun getCurrentTrack(): Track?
    fun getQueue(): List<Track>
    fun searchLibrary(query: String, limit: Int = 30): List<Track>
    fun searchArtist(name: String, limit: Int = 200): List<Track>
    suspend fun getLibraryStats(): LibraryStats
    suspend fun getRecentlyPlayed(limit: Int = 10): List<Track>
    suspend fun getPlaybackHistory(limit: Int = 20): List<HistoryEntry>
    fun getAudioSettings(): AudioSettings
    fun getActiveAudioDevice(): HiResCapabilities?
    suspend fun getAnalysis(uri: String): AnalysisResult?
    fun getFavorites(): List<Track>
    fun getDuplicateGroups(): DuplicateReport
    suspend fun getMissingFiles(maxMillis: Long = 15_000L): MissingReport
    /** Alle Titel mit Analyse, Hoerverlauf und Favoriten-Status (fuer den Recommender). */
    suspend fun loadTrackInfos(): List<TrackInfo>
    suspend fun loadPlaylists(): Map<String, List<Track>>
    /** ML-Genre (GTZAN) je Titel als "label:konfidenz,...". Default: leer. */
    suspend fun loadMlGenres(): Map<String, String> = emptyMap()
    /** Persönliche Genre-Korrekturen (lokal). Default: leer. */
    suspend fun loadGenreOverrides(): Map<String, String> = emptyMap()
    /** Persönliche BPM-Korrekturen (lokal). Default: leer. */
    suspend fun loadBpmOverrides(): Map<String, Float> = emptyMap()
    /** Empfehlungs-Feedback accepted/rejected. Default: leer. */
    suspend fun loadRecFeedback(): Pair<Map<String, Int>, Map<String, Int>> = emptyMap<String, Int>() to emptyMap()

    // ---- Aktionen ohne bleibende Folgen ----
    fun addToQueue(trackIds: List<Long>)
    /** Entfernt kommende Titel (nie den laufenden). @return Anzahl entfernter Titel. */
    fun removeFromQueue(trackIds: Set<Long>): Int
    fun clearQueue()
    fun replaceQueueAndPlay(trackIds: List<Long>, shuffle: Boolean? = null)
    fun play()
    fun pause()
    fun skipNext()
    fun playTrack(trackId: Long)
    fun startSleepTimer(minutes: Int)
    /** Medien-Lautstaerke des Geraets in Prozent (0..100) oder null, wenn nicht lesbar. */
    fun getVolume(): Int?
    /** Setzt die Medien-Lautstaerke (0..100 %, auf die Geraetestufen gerundet). @return false, wenn es nicht ging. */
    fun setVolume(percent: Int): Boolean

    // ---- EQ/DSP: wirken nur fuer die laufende Sitzung (nichts wird gespeichert) ----
    /** Aktueller 32-Band-EQ-Zustand, so wie die Wiedergabe-Engine ihn benutzt. */
    fun getEqState(): Dsp
    /** Setzt den kompletten EQ-Stand (nur Sitzung) - fuer "Rueckgaengig" / "wie vorher". */
    fun setEqState(dsp: Dsp)
    /** Setzt den Bass-Regler (dB, -12..12). Schaltet den EQ ein, falls er aus war. */
    fun setBass(amount: Float)
    fun setTreble(amount: Float)
    /** Setzt das Band, das [frequencyHz] am naechsten liegt, auf [gainDb] (absolut, -12..12). @return false, wenn kein Band da ist. */
    fun adjustBand(frequencyHz: Float, gainDb: Float): Boolean
    /** Wendet ein integriertes oder eigenes Preset an (nur Sitzung). @return false, wenn es den Namen nicht gibt. */
    fun applyEqPreset(name: String): Boolean
    /** Alle Baender, Bass/Hoehen, Boost-Schalter und PreAmp auf neutral. */
    fun resetEq()

    // ---- Bestaetigungspflichtig (dauerhaft bzw. aufwendig) ----
    /** Speichert den aktuellen EQ-Stand dauerhaft: als Preset [presetName] oder (null) als Standard-Einstellung. */
    fun saveEq(presetName: String?): Boolean
    fun createPlaylist(name: String, trackIds: List<Long>): PlaylistOutcome
    /** Setzt Bass-Regler, Bass-Boost und angehobene Tiefen-Baender (<= 250 Hz) auf 0. */
    fun resetBass(): Boolean
    /** Startet den (nur lesenden) Duplikat-Scan. */
    fun startDuplicateScan()

    // ---- Analyse / Wissensbasis (lokal) ----
    suspend fun getAnalysisCoverage(): AnalysisCoverage
    suspend fun findTracksByFeatures(query: FeatureQuery): List<TrackAnalysisRow>
    suspend fun findSimilarByUri(uri: String, limit: Int = 12): List<Track>
    suspend fun getTrackAnalysisDetail(uri: String): TrackAnalysisDetail?
    fun requestLibraryAnalysis(): Boolean
}

data class AnalysisCoverage(val analysed: Int, val total: Int, val withLyrics: Int) {
    val ratio: Float get() = if (total == 0) 0f else analysed.toFloat() / total
}

data class FeatureQuery(
    val bpmMin: Float? = null,
    val bpmMax: Float? = null,
    val minBpmConfidence: Float? = null,
    val mood: Mood? = null,
    val genreContains: String? = null,
    val onlyFullyAnalysed: Boolean = false,
    val onlyUncertainGenre: Boolean = false,
    val limit: Int = 40
)

data class TrackAnalysisRow(
    val track: Track,
    val bpm: Float,
    val bpmConfidence: Float,
    val lufs: Float,
    val energy: Float?,
    val analysisLevel: String,
    val status: String
)

data class TrackAnalysisDetail(
    val track: Track,
    val measuredBpm: Float,
    val personalBpm: Float?,
    val bpmConfidence: Float,
    val lufs: Float,
    val peakDb: Float,
    val rmsDb: Float,
    val centroidHz: Float,
    val bass: Float,
    val mid: Float,
    val high: Float,
    val rhythmRegularity: Float,
    val energyIntro: Float,
    val energyMid: Float,
    val energyLate: Float,
    val analysisLevel: String,
    val featureVersion: Int,
    val status: String,
    val sources: List<String>
)
