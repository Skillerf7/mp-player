package com.mp.player

import com.mp.player.ai.AnalysisField
import com.mp.player.ai.Assistant
import com.mp.player.ai.TrackInfo
import com.mp.player.ai.UserIntent
import com.mp.player.ai.InMemoryStore
import com.mp.player.ai.InMemorySnapshotStore
import com.mp.player.ai.InMemoryEpisodeStore
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantAnalysisTest {
    private fun info(title: String, artist: String, genre: String, bpm: Float, lufs: Float): TrackInfo {
        val uri = "content://analysis/$title"
        val track = Track(uri, title, artist, artist, "Album", 2020, 180_000L, "Music", 0L, genre)
        val analysis = AnalysisResult(uri, 0L, bpm, lufs, -1f, -20f, 44_100, 2, false)
        return TrackInfo(track, analysis, null, 0, false)
    }

    @Test fun bpmIsReadFromStoredAnalysis() = runBlocking {
        val source = info("Current", "A", "Rap", 146.5f, -11f)
        val tools = FakeTools(listOf(source.track))
        tools.current = source.track
        tools.infos = listOf(source)
        val assistant = Assistant(tools, memoryStore = InMemoryStore(), snapshots = InMemorySnapshotStore(), uiContext = EmptyCoroutineContext, episodeStore = InMemoryEpisodeStore())
        val r = assistant.handle("Lies die BPM aus")
        assertTrue(r.text.contains("146,5 BPM"))
    }

    @Test fun similarTracksAreAddedUsingAnalysisProfile() = runBlocking {
        val source = info("Current", "A", "Rap", 150f, -10f)
        val close = info("Close", "B", "Trap", 152f, -10.5f)
        val far = info("Far", "C", "Rap", 90f, -10.5f)
        val tools = FakeTools(listOf(source.track, close.track, far.track))
        tools.current = source.track
        tools.queueItems.add(source.track)
        tools.infos = listOf(source, close, far)
        val assistant = Assistant(tools, memoryStore = InMemoryStore(), snapshots = InMemorySnapshotStore(), uiContext = EmptyCoroutineContext, episodeStore = InMemoryEpisodeStore())
        val r = assistant.handle("Ich möchte mehr solcher Tracks")
        assertEquals(1, tools.added.size)
        assertEquals(close.track.id, tools.added.single())
        assertTrue(r.text.contains("Close"))
    }

    @Test fun generalAnalysisRequestReturnsAllStoredValues() = runBlocking {
        val source = info("Current", "A", "Rap", 150f, -10f)
        val tools = FakeTools(listOf(source.track))
        tools.current = source.track
        tools.infos = listOf(source)
        val assistant = Assistant(tools, memoryStore = InMemoryStore(), snapshots = InMemorySnapshotStore(), uiContext = EmptyCoroutineContext, episodeStore = InMemoryEpisodeStore())
        assertTrue(assistant.handle("Analysedaten").text.contains("BPM"))
        assertEquals(UserIntent.AskTrackAnalysis(setOf(AnalysisField.ALL)), com.mp.player.ai.IntentEngine.understand("Analysedaten"))
    }
}
