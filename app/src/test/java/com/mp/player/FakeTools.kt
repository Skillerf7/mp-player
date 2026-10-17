package com.mp.player

import com.mp.player.ai.AnalysisCoverage
import com.mp.player.ai.FeatureQuery
import com.mp.player.ai.TrackAnalysisRow

import com.mp.player.ai.AudioSettings
import com.mp.player.ai.DuplicateReport
import com.mp.player.ai.HistoryEntry
import com.mp.player.ai.LibraryStats
import com.mp.player.ai.MissingReport
import com.mp.player.ai.PlayerTools
import com.mp.player.ai.PlaylistOutcome
import com.mp.player.ai.TrackInfo

/** Einfache Test-Attrappe fuer PlayerTools: haelt Queue, Lautstaerke und Aufrufe, damit Dialoge ohne Android laufen. */
class FakeTools(var library: List<Track> = emptyList()) : PlayerTools {
    // Getter-Name aendern: sonst kollidiert getQueue() der Property mit PlayerTools.getQueue() (gleiche JVM-Signatur)
    var queueItems: MutableList<Track> = mutableListOf()
    var current: Track? = null
    var volume: Int = 50
    var eq: Dsp = Dsp()
    var infos: List<TrackInfo> = emptyList()
    var playlists: Map<String, List<Track>> = emptyMap()

    var replaceCalls = 0
    val played = mutableListOf<Long>()
    val removed = mutableListOf<Long>()
    val added = mutableListOf<Long>()
    var skips = 0

    override fun getCurrentTrack(): Track? = current
    override fun getQueue(): List<Track> = queueItems.toList()
    override fun searchLibrary(query: String, limit: Int): List<Track> {
        val q = query.lowercase()
        return library.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }.take(limit)
    }
    override fun searchArtist(name: String, limit: Int): List<Track> =
        library.filter { it.artist.lowercase().contains(name.lowercase()) }.take(limit)
    override suspend fun getLibraryStats() = LibraryStats(library.size, 0L, 0, 0, 0, 0, emptyList())
    override suspend fun getRecentlyPlayed(limit: Int): List<Track> = emptyList()
    override suspend fun getPlaybackHistory(limit: Int): List<HistoryEntry> = emptyList()
    override fun getAudioSettings() = AudioSettings(eq, 0f, 0f, 0, 0f, false)
    override fun getActiveAudioDevice(): HiResCapabilities? = null
    override suspend fun getAnalysis(uri: String): AnalysisResult? = infos.firstOrNull { it.track.uri == uri }?.analysis
    override fun getFavorites(): List<Track> = emptyList()
    override fun getDuplicateGroups() = DuplicateReport(false, false, emptyList())
    override suspend fun getMissingFiles(maxMillis: Long) = MissingReport(0, 0, emptyList(), true)
    override suspend fun loadPlaylists(): Map<String, List<Track>> = playlists
    override suspend fun loadTrackInfos(): List<TrackInfo> = if (infos.isNotEmpty()) infos else library.map { TrackInfo(it, null, null, 0, false) }

    override fun addToQueue(trackIds: List<Long>) {
        for (id in trackIds) library.firstOrNull { it.id == id }?.let { queueItems.add(it); added += id }
    }
    override fun removeFromQueue(trackIds: Set<Long>): Int {
        var n = 0
        val cur = current
        val it = queueItems.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (t.id in trackIds && t.uri != cur?.uri) { it.remove(); removed += t.id; n++ }
        }
        return n
    }
    override fun clearQueue() { queueItems.clear() }
    override fun replaceQueueAndPlay(trackIds: List<Long>, shuffle: Boolean?) {
        replaceCalls++
        queueItems = trackIds.mapNotNull { id -> library.firstOrNull { it.id == id } }.toMutableList()
        current = queueItems.firstOrNull()
    }
    override fun play() {}
    override fun pause() {}
    override fun skipNext() {
        skips++
        val i = queueItems.indexOfFirst { it.uri == current?.uri }
        current = queueItems.getOrNull(i + 1)
    }
    override fun playTrack(trackId: Long) { played += trackId; current = library.firstOrNull { it.id == trackId } }
    override fun startSleepTimer(minutes: Int) {}
    override fun getVolume(): Int? = volume
    override fun setVolume(percent: Int): Boolean { volume = percent.coerceIn(0, 100); return true }

    override fun getEqState(): Dsp = eq
    override fun setEqState(dsp: Dsp) { eq = dsp }
    override fun setBass(amount: Float) { eq = eq.copy(bass = amount) }
    override fun setTreble(amount: Float) { eq = eq.copy(treble = amount) }
    override fun adjustBand(frequencyHz: Float, gainDb: Float): Boolean = true
    override fun applyEqPreset(name: String): Boolean = false
    override fun resetEq() { eq = Dsp() }
    override fun saveEq(presetName: String?): Boolean = true
    override fun createPlaylist(name: String, trackIds: List<Long>) = PlaylistOutcome(name, trackIds.size)
    override fun resetBass(): Boolean = true
    override suspend fun getAnalysisCoverage() = AnalysisCoverage(0, 0, 0)
    override suspend fun findTracksByFeatures(query: FeatureQuery) = emptyList<TrackAnalysisRow>()
    override suspend fun findSimilarByUri(uri: String, limit: Int) = emptyList<com.mp.player.Track>()
    override suspend fun getTrackAnalysisDetail(uri: String) = null
    override fun requestLibraryAnalysis() = false
    override fun startDuplicateScan() {}
}
