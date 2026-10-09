package com.mp.player.ai

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.Player
import com.mp.player.AnalysisResult
import com.mp.player.Dsp
import com.mp.player.DuplicateState
import com.mp.player.Eq
import com.mp.player.Engine
import com.mp.player.HiRes
import com.mp.player.HiResCapabilities
import com.mp.player.LibraryDb
import com.mp.player.LibraryState
import com.mp.player.PlayerBridge
import com.mp.player.PlaybackState
import com.mp.player.PlaylistResult
import com.mp.player.PlaylistStore
import com.mp.player.SleepTimer
import com.mp.player.Store
import com.mp.player.AnalysisState
import com.mp.player.Track
import com.mp.player.TrackOps
import com.mp.player.enqueueTracks
import com.mp.player.playTracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ln

/**
 * Echte Anbindung an den vorhandenen MusicPlayer: gleiche Bibliothek (LibraryState + SQLite-LibraryDb),
 * gleiche Queue/Player (MediaController -> PlayerService), gleiche DSP-Kette (Engine). Keine eigene Datenbank.
 * Jede Funktion faengt Fehler ab - ein Problem hier darf nie die Wiedergabe beeinflussen.
 */
class PlayerToolsImpl(context: Context) : PlayerTools {

    private val app: Context = context.applicationContext

    private fun controller() = PlayerBridge.controller

    private fun tracksById(): Map<Long, Track> = LibraryState.tracks.associateBy { it.id }

    private fun resolve(ids: List<Long>): List<Track> {
        val map = tracksById()
        return ids.mapNotNull { map[it] }
    }

    // ------------------------------------------------------------------ Lesen

    override fun getCurrentTrack(): Track? = try { PlaybackState.currentTrack() } catch (e: Exception) { null }

    override fun getQueue(): List<Track> {
        val c = controller() ?: return emptyList()
        return try {
            val byUri = LibraryState.tracks.associateBy { it.uri }
            (0 until c.mediaItemCount).mapNotNull { byUri[c.getMediaItemAt(it).mediaId] }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun searchLibrary(query: String, limit: Int): List<Track> {
        val words = TextUtil.norm(query).split(' ', '-', ',').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return try {
            LibraryState.tracks.filter { t ->
                val h = TextUtil.norm("${t.title} ${t.artist} ${t.album} ${t.genre} ${t.fileName}")
                words.all { h.contains(it) }
            }.take(limit)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun getVolume(): Int? = try {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        if (max <= 0) null else Math.round(am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100f / max)
    } catch (e: Exception) { null }

    override fun setVolume(percent: Int): Boolean = try {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.round(percent.coerceIn(0, 100) / 100f * max), 0)
        true
    } catch (e: Exception) { false }

    override fun searchArtist(name: String, limit: Int): List<Track> =
        try { ArtistSearch.find(LibraryState.tracks, name, limit) } catch (e: Exception) { emptyList() }

    override suspend fun getLibraryStats(): LibraryStats {
        val tracks = LibraryState.tracks
        return withContext(Dispatchers.IO) {
            val db = LibraryDb.get(app)
            val analyzed = db.analysisAll().keys
            val played = db.lastPlayedAll().keys
            LibraryStats(
                trackCount = tracks.size,
                totalDurationMs = tracks.sumOf { it.durationMs },
                artistCount = tracks.map { it.artist }.distinct().size,
                albumCount = tracks.map { it.album }.distinct().size,
                analyzedCount = tracks.count { it.uri in analyzed },
                neverPlayedCount = tracks.count { it.uri !in played },
                topGenres = tracks.filter { it.genre.isNotBlank() }
                    .groupingBy { it.genre }.eachCount().entries
                    .sortedByDescending { it.value }.take(5).map { it.key to it.value }
            )
        }
    }

    override suspend fun getRecentlyPlayed(limit: Int): List<Track> {
        val byUri = LibraryState.tracks.associateBy { it.uri }
        return withContext(Dispatchers.IO) {
            LibraryDb.get(app).recentPlays(400).map { it.first }.distinct()
                .mapNotNull { byUri[it] }.take(limit)
        }
    }

    override suspend fun getPlaybackHistory(limit: Int): List<HistoryEntry> {
        val byUri = LibraryState.tracks.associateBy { it.uri }
        return withContext(Dispatchers.IO) {
            LibraryDb.get(app).recentPlays(limit).mapNotNull { (uri, at) ->
                byUri[uri]?.let { HistoryEntry(it, at) }
            }
        }
    }

    override fun getAudioSettings(): AudioSettings {
        val d = Engine.proc.cfg
        return AudioSettings(
            dsp = d,
            bassGainDb = Eq.bassGain(d),
            trebleGainDb = Eq.trebleGain(d),
            replayGainMode = Store(app).rgMode,
            replayGainDb = Engine.proc.replayGainDb,
            floatOutput = Engine.proc.floatOutput
        )
    }

    override fun getActiveAudioDevice(): HiResCapabilities? = try { HiRes.activeOutput(app) } catch (e: Exception) { null }

    override suspend fun getAnalysis(uri: String): AnalysisResult? =
        withContext(Dispatchers.IO) { LibraryDb.get(app).analysisFor(uri) }

    override fun getFavorites(): List<Track> {
        val ids = Store(app).favs()
        return LibraryState.tracks.filter { it.id in ids }
    }

    override fun getDuplicateGroups(): DuplicateReport =
        DuplicateReport(DuplicateState.finished, DuplicateState.running, DuplicateState.groups)

    override suspend fun getMissingFiles(maxMillis: Long): MissingReport {
        val tracks = LibraryState.tracks
        return withContext(Dispatchers.IO) {
            val start = SystemClock.elapsedRealtime()
            val missing = ArrayList<Track>()
            var checked = 0
            for (t in tracks) {
                ensureActive()
                if (SystemClock.elapsedRealtime() - start > maxMillis) break
                if (!TrackOps.exists(app, t.uri)) missing.add(t)
                checked++
            }
            MissingReport(checked, tracks.size, missing, checked >= tracks.size)
        }
    }

    override suspend fun loadPlaylists(): Map<String, List<Track>> = try {
        PlaylistStore.ensureLoaded(app)
        PlaylistStore.playlists.keys.associateWith { PlaylistStore.tracksOf(it) }
    } catch (e: Exception) { emptyMap() }

    override suspend fun loadMlGenres(): Map<String, String> =
        withContext(Dispatchers.IO) { LibraryDb.get(app).mlGenresAll(GenreOnnxClassifier.MODEL_VERSION) }

    override suspend fun loadGenreOverrides(): Map<String, String> =
        withContext(Dispatchers.IO) { LibraryDb.get(app).genreOverridesAll() }

    override suspend fun loadBpmOverrides(): Map<String, Float> =
        withContext(Dispatchers.IO) { LibraryDb.get(app).bpmOverridesAll() }

    override suspend fun loadRecFeedback(): Pair<Map<String, Int>, Map<String, Int>> =
        withContext(Dispatchers.IO) { LibraryDb.get(app).recFeedbackCounts() }

    override suspend fun loadTrackInfos(): List<TrackInfo> {
        val tracks = LibraryState.tracks
        val favs = Store(app).favs()
        return withContext(Dispatchers.IO) {
            val db = LibraryDb.get(app)
            val analysis = db.analysisAll()
            val last = db.lastPlayedAll()
            val counts = db.playCountsAll()
            val skips = db.skipCountsAll()
            val lastSkip = db.lastSkippedAll()
            val lyrics = db.lyricsAll()
            tracks.map {
                val ly = lyrics[it.uri]
                val has = ly != null && ly.hasText
                TrackInfo(
                    it, analysis[it.uri], last[it.uri], counts[it.uri] ?: 0, it.id in favs, skips[it.uri] ?: 0, lastSkip[it.uri],
                    hasLyrics = has, lyricMood = if (has) LyricsProfile.decode(ly?.profile) else emptyMap()
                )
            }
        }
    }

    // ------------------------------------------------------------------ Aktionen

    override fun addToQueue(trackIds: List<Long>) {
        val list = resolve(trackIds)
        if (list.isNotEmpty()) enqueueTracks(list, next = false)
    }

    override fun removeFromQueue(trackIds: Set<Long>): Int {
        val c = controller() ?: return 0
        var removed = 0
        try {
            val byUri = LibraryState.tracks.associateBy { it.uri }
            val current = c.currentMediaItemIndex
            for (i in c.mediaItemCount - 1 downTo 0) {
                if (i == current) continue // laufenden Titel nie anfassen
                val t = byUri[c.getMediaItemAt(i).mediaId] ?: continue
                if (t.id in trackIds) {
                    c.removeMediaItem(i)
                    removed++
                }
            }
        } catch (e: Exception) { }
        return removed
    }

    override fun clearQueue() {
        try { controller()?.clearMediaItems() } catch (e: Exception) { }
    }

    override fun replaceQueueAndPlay(trackIds: List<Long>, shuffle: Boolean?) {
        val list = resolve(trackIds)
        if (list.isNotEmpty()) playTracks(list, 0, shuffle)
    }

    override fun play() {
        try {
            controller()?.let {
                if (it.playbackState == Player.STATE_IDLE) it.prepare()
                it.play()
            }
        } catch (e: Exception) { }
    }

    override fun pause() {
        try { controller()?.pause() } catch (e: Exception) { }
    }

    override fun skipNext() {
        try { controller()?.seekToNextMediaItem() } catch (e: Exception) { }
    }

    override fun playTrack(trackId: Long) {
        val t = LibraryState.tracks.firstOrNull { it.id == trackId } ?: return
        playTracks(listOf(t), 0)
    }

    override fun startSleepTimer(minutes: Int) {
        try { SleepTimer.startMinutes(minutes.coerceIn(1, 600)) } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ EQ (nur Sitzung: Engine.proc.cfg, kein Store.saveDsp)

    override fun getEqState(): Dsp = Engine.proc.cfg

    override fun setEqState(dsp: Dsp) {
        try {
            Engine.proc.cfg = dsp
            Engine.sessionChanged = true
        } catch (e: Exception) { }
    }

    override fun setBass(amount: Float) {
        try {
            val d = Engine.proc.cfg
            Engine.proc.cfg = d.copy(on = true, bass = amount.coerceIn(-Eq.MAX_GAIN, Eq.MAX_GAIN))
            Engine.sessionChanged = true
        } catch (e: Exception) { }
    }

    override fun setTreble(amount: Float) {
        try {
            val d = Engine.proc.cfg
            Engine.proc.cfg = d.copy(on = true, treble = amount.coerceIn(-Eq.MAX_GAIN, Eq.MAX_GAIN))
            Engine.sessionChanged = true
        } catch (e: Exception) { }
    }

    override fun adjustBand(frequencyHz: Float, gainDb: Float): Boolean {
        return try {
            val d = Engine.proc.cfg
            val idx = d.bands.indices.minByOrNull { abs(ln(d.bands[it].f / frequencyHz)) } ?: return false
            val bands = d.bands.toMutableList()
            bands[idx] = bands[idx].copy(g = gainDb.coerceIn(-Eq.MAX_GAIN, Eq.MAX_GAIN))
            Engine.proc.cfg = d.copy(on = true, bands = bands)
            Engine.sessionChanged = true
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun applyEqPreset(name: String): Boolean {
        return try {
            val own = Store(app).presets()
            val dsp = Eq.BUILT_IN.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
                ?: own.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
                ?: return false
            Engine.proc.cfg = dsp.copy(on = true)
            Engine.sessionChanged = true
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun resetEq() {
        try {
            val d = Engine.proc.cfg
            Engine.proc.cfg = d.copy(pre = 0f, bands = Eq.flatBands(), bass = 0f, treble = 0f, bassBoost = false, trebleBoost = false)
            Engine.sessionChanged = true
        } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ Nur nach Bestaetigung

    override fun saveEq(presetName: String?): Boolean {
        return try {
            val store = Store(app)
            val d = Engine.proc.cfg
            if (presetName == null) store.saveDsp(d) else store.savePresets(store.presets() + (presetName to d))
            if (presetName == null) Engine.sessionChanged = false
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun createPlaylist(name: String, trackIds: List<Long>): PlaylistOutcome {
        return try {
            val tracks = resolve(trackIds)
            if (tracks.isEmpty()) return PlaylistOutcome(null, 0)
            PlaylistStore.ensureLoaded(app)
            val base = name.trim().ifEmpty { "KI-Mix" }
            var finalName = base
            var n = 2
            while (PlaylistStore.playlists.containsKey(finalName)) {
                finalName = "$base $n"
                n++
            }
            if (PlaylistStore.create(app, finalName) != PlaylistResult.OK) return PlaylistOutcome(null, 0)
            PlaylistOutcome(finalName, PlaylistStore.addTracks(app, finalName, tracks))
        } catch (e: Exception) {
            PlaylistOutcome(null, 0)
        }
    }

    override fun resetBass(): Boolean {
        return try {
            val d = Engine.proc.cfg
            val fresh = d.copy(
                bass = 0f,
                bassBoost = false,
                bands = d.bands.map { if (it.f <= 250f && it.g > 0f) it.copy(g = 0f) else it }
            )
            Store(app).saveDsp(fresh)
            Engine.proc.cfg = fresh
            Engine.sessionChanged = false
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun startDuplicateScan() {
        try { DuplicateState.start(app) } catch (e: Exception) { }
    }

    override suspend fun getAnalysisCoverage(): AnalysisCoverage = withContext(Dispatchers.IO) {
        val tracks = LibraryState.tracks
        val analysis = LibraryDb.get(app).analysisAll()
        val lyrics = LibraryDb.get(app).lyricsAll()
        val usable = tracks.count { tr ->
            val a = analysis[tr.uri]
            a != null && a.sampleRate > 0 && (a.bpm > 0f || !a.lufs.isNaN())
        }
        val withLy = tracks.count { lyrics[it.uri]?.hasText == true }
        AnalysisCoverage(usable, tracks.size, withLy)
    }

    override suspend fun findTracksByFeatures(query: FeatureQuery): List<TrackAnalysisRow> = withContext(Dispatchers.IO) {
        val analysis = LibraryDb.get(app).analysisAll()
        val personalBpm = LibraryDb.get(app).bpmOverridesAll()
        val out = ArrayList<TrackAnalysisRow>()
        for (tr in LibraryState.tracks) {
            val a = analysis[tr.uri]
            if (query.onlyFullyAnalysed && (a == null || a.sampleRate <= 0)) continue
            val measured = a?.bpm ?: 0f
            val bpm = personalBpm[tr.uri] ?: measured
            if (query.bpmMin != null && (bpm <= 0f || bpm < query.bpmMin)) continue
            if (query.bpmMax != null && (bpm <= 0f || bpm > query.bpmMax)) continue
            if (query.minBpmConfidence != null && (a?.bpmConfidence ?: 0f) < query.minBpmConfidence) continue
            if (query.genreContains != null) {
                val g = (tr.genre + " " + tr.title + " " + tr.artist).lowercase()
                if (!g.contains(query.genreContains.lowercase())) continue
            }
            if (query.onlyUncertainGenre) {
                if (tr.genre.isNotBlank()) continue
            }
            val energy = a?.let {
                val loud = if (!it.lufs.isNaN()) ((it.lufs + 24f) / 16f).coerceIn(0f, 1f) else null
                val eBpm = if (bpm > 0f) ((bpm - 60f) / 120f).coerceIn(0f, 1f) else null
                when {
                    loud != null && eBpm != null -> 0.5f * loud + 0.5f * eBpm
                    loud != null -> loud
                    else -> eBpm
                }
            }
            out += TrackAnalysisRow(
                track = tr,
                bpm = bpm,
                bpmConfidence = a?.bpmConfidence ?: 0f,
                lufs = a?.lufs ?: Float.NaN,
                energy = energy,
                analysisLevel = a?.analysisLevel ?: "NONE",
                status = a?.analysisStatus ?: "missing"
            )
            if (out.size >= query.limit) break
        }
        out
    }

    override suspend fun findSimilarByUri(uri: String, limit: Int): List<com.mp.player.Track> = withContext(Dispatchers.IO) {
        val analysis = LibraryDb.get(app).analysisAll()
        val personal = PersonalLearning.build(
            playCounts = emptyMap(),
            skipCounts = emptyMap(),
            genreOverrides = LibraryDb.get(app).genreOverridesAll(),
            bpmOverrides = LibraryDb.get(app).bpmOverridesAll(),
            acceptedRecs = emptyMap(),
            rejectedRecs = emptyMap(),
            trackByUri = LibraryState.tracks.associateBy { it.uri }
        )
        val sourceTrack = LibraryState.tracks.find { it.uri == uri } ?: return@withContext emptyList()
        val sourceInfo = TrackInfo(sourceTrack, analysis[uri], null, 0, false)
        val candidates = LibraryState.tracks.filter { it.uri != uri }.map {
            TrackInfo(it, analysis[it.uri], null, 0, false)
        }
        TrackSimilarity.rank(sourceInfo, candidates, limit = limit, personal = personal).map { it.track }
    }

    override suspend fun getTrackAnalysisDetail(uri: String): TrackAnalysisDetail? = withContext(Dispatchers.IO) {
        val tr = LibraryState.tracks.find { it.uri == uri } ?: return@withContext null
        val a = LibraryDb.get(app).analysisAll()[uri]
        val personalBpm = LibraryDb.get(app).bpmOverridesAll()[uri]
        val sources = ArrayList<String>()
        if (a != null) sources += "Audioanalyse (${a.analysisLevel})"
        if (personalBpm != null) sources += "Persönliche BPM-Korrektur"
        if (tr.genre.isNotBlank()) sources += "Dateitag Genre"
        if (a == null) sources += "Noch nicht analysiert"
        TrackAnalysisDetail(
            track = tr,
            measuredBpm = a?.bpm ?: 0f,
            personalBpm = personalBpm,
            bpmConfidence = a?.bpmConfidence ?: 0f,
            lufs = a?.lufs ?: Float.NaN,
            peakDb = a?.peakDb ?: Float.NaN,
            rmsDb = a?.rmsDb ?: Float.NaN,
            centroidHz = a?.spectralCentroidHz ?: 0f,
            bass = a?.bassEnergy ?: 0f,
            mid = a?.midEnergy ?: 0f,
            high = a?.highEnergy ?: 0f,
            rhythmRegularity = a?.rhythmRegularity ?: 0f,
            energyIntro = a?.energyIntro ?: 0f,
            energyMid = a?.energyMid ?: 0f,
            energyLate = a?.energyLate ?: 0f,
            analysisLevel = a?.analysisLevel ?: "NONE",
            featureVersion = a?.featureVersion ?: 0,
            status = a?.analysisStatus ?: "missing",
            sources = sources
        )
    }

    override fun requestLibraryAnalysis(): Boolean {
        return try {
            AnalysisState.start(app)
            true
        } catch (e: Exception) {
            false
        }
    }
}

