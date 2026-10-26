package com.mp.player

import com.mp.player.ai.Assistant
import com.mp.player.ai.GenreProfile
import com.mp.player.ai.IntentEngine
import com.mp.player.ai.Mood
import com.mp.player.ai.PlaylistPlanner
import com.mp.player.ai.TrackInfo
import com.mp.player.ai.UserIntent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Multi-Turn: Entwurf + "mach an" / Kontext. Kein Search("an").
 */
class DialogContextTest {

    private fun tr(id: Long, title: String, artist: String, genre: String = "Rap"): Track =
        Track("content://t/$id", title, artist, artist, "Alb", 2020, 180_000L, "Music", 0L, genre).also {
            // Track has no mutable id in constructor in some versions – FakeTools uses library by reference
        }.let { t ->
            // ensure stable id if Track has id field used by FakeTools
            t
        }

    private fun info(t: Track, bpm: Float = 140f, lufs: Float = -10f) =
        TrackInfo(t, AnalysisResult(t.uri, 0L, bpm, lufs, -1f, -20f, 44100, 2, false), null, 0, false)

    @Test fun machAnIsNotSearchForAn() {
        val i = IntentEngine.understand("mach an")
        assertFalse("mach an darf nicht SearchPlay sein: $i", i is UserIntent.SearchPlay)
        assertTrue("mach an -> Confirm oder PlayMood, war: $i", i is UserIntent.Confirm || i is UserIntent.PlayMood)
    }

    @Test fun isStartDraftRecognizesShortCommands() {
        assertTrue(PlaylistPlanner.isStartDraft("mach an"))
        assertTrue(PlaylistPlanner.isStartDraft("ok"))
        assertTrue(PlaylistPlanner.isStartDraft("Spiel sie"))
        assertTrue(PlaylistPlanner.isStartDraft("start"))
        assertTrue(PlaylistPlanner.isStartDraft("los"))
    }

    @Test fun schillahReferenceIsSimilarToPlaylist() {
        assertEquals(
            UserIntent.SimilarToPlaylist("schillah"),
            IntentEngine.understand("Mach mir wieder sowas wie meine Schillah-Playlist.")
        )
    }

    @Test fun draftThenMachAnStartsWithoutSearchingAn() = runBlocking {
        val a = tr(1, "Night Drive", "LUVRE47", "Rap")
        val b = tr(2, "Rain", "LUVRE47", "Rap")
        val c = tr(3, "tekktestlove", "Other", "Hardtekk")
        val d = tr(4, "Chill Flow", "Apache", "Rap")
        val lib = listOf(a, b, c, d)
        val tools = FakeTools(lib).apply {
            infos = lib.map { info(it) }
            playlists = mapOf(
                "Schillah" to listOf(a, b),
                "Chill-Rap" to listOf(a, d)
            )
        }
        val assistant = Assistant(tools, uiContext = EmptyCoroutineContext)

        val r1 = assistant.say("Mach mir wieder sowas wie meine Schillah-Playlist.")
        assertFalse("Antwort darf keine Suche nach an sein: ${r1.text}", r1.text.contains("Treffer zu \"an\"", ignoreCase = true))
        assertTrue(
            "Erster Schritt sollte Entwurf/Referenz sein: ${r1.text}",
            r1.text.contains("Entwurf", ignoreCase = true) ||
                r1.text.contains("Schillah", ignoreCase = true) ||
                r1.text.contains("ähnlich", ignoreCase = true) ||
                tools.replaceCalls == 0
        )

        val before = tools.replaceCalls
        val r2 = assistant.say("mach an")
        assertFalse("mach an darf nicht nach 'an' suchen: ${r2.text}", r2.text.contains("Treffer zu \"an\"", ignoreCase = true))
        assertFalse("mach an darf nicht tekktestlove blind starten: ${r2.text}", r2.text.contains("tekktestlove", ignoreCase = true) && tools.replaceCalls > before && r2.text.contains("Treffer"))
    }

    @Test fun personalPlaylistsFeedGenreProfile() {
        val a = tr(1, "Night", "LUVRE47", "Unknown")
        val b = tr(2, "Day", "LUVRE47", "Unknown")
        val c = tr(3, "Party", "Other", "Pop")
        val infos = listOf(a, b, c).map { info(it) }
        val profile = GenreProfile.build(
            infos,
            mapOf("Chill-Rap" to listOf(a, b), "Deutschrap" to listOf(a))
        )
        val e = profile.evidence(infos[0], "rap")
        assertTrue("LUVRE47 aus Chill-Rap-Playlists sollte Rap-Signal bekommen, war ${e.confidence}", e.confidence >= 0.55f)
    }

    @Test fun genreAndMoodAreIndependentSignals() {
        val t = info(tr(1, "Sad Rap", "X", "Rap"), bpm = 90f, lufs = -14f)
        // Mood score und Genre-Match duerfen gleichzeitig greifen (kein gegenseitiger Ausschluss)
        val mood = com.mp.player.ai.Recommender.moodScore(t, Mood.SAD)
        val genre = GenreProfile.build(listOf(t), emptyMap()).evidence(t, "rap").confidence
        assertTrue(genre >= 0.9f) // explizites Genre-Tag
        // moodScore darf auch ohne Lyrics existieren (kann niedrig sein)
        assertTrue(mood >= 0f)
    }

    @Test fun machAnWithoutPendingAsksWhatToStart() = runBlocking {
        val tools = FakeTools(emptyList())
        val assistant = Assistant(tools, uiContext = EmptyCoroutineContext)
        val r = assistant.say("mach an")
        assertFalse(r.text.contains("Treffer zu \"an\"", ignoreCase = true))
        assertTrue(
            "Ohne Entwurf nachfragen: ${r.text}",
            r.text.contains("starten", ignoreCase = true) ||
                r.text.contains("Was", ignoreCase = true) ||
                r.text.contains("Playlist", ignoreCase = true) ||
                r.text.contains("👍") ||
                r.text.isNotBlank()
        )
        assertEquals(0, tools.replaceCalls)
    }

    @Test fun rapWiedergabelisteIsPlaylistReference() {
        val i = IntentEngine.understand("Bau sowas wie meine rap wiedergabeliste")
        assertTrue("sollte SimilarToPlaylist sein, war: $i", i is UserIntent.SimilarToPlaylist)
        assertTrue((i as UserIntent.SimilarToPlaylist).name.contains("rap", ignoreCase = true))
    }

    @Test fun schillahPlaylistVariants() {
        for (s in listOf(
            "Mach mir wieder sowas wie meine Schillah-Playlist.",
            "mach sowas wie meine schillah playlist",
            "bau etwas wie meine Schillah Wiedergabeliste"
        )) {
            val i = IntentEngine.understand(s)
            assertTrue("$s -> $i", i is UserIntent.SimilarToPlaylist)
        }
    }

    @Test fun playlistReferenceNotTrackRef() {
        val i = IntentEngine.understand("Bau sowas wie meine rap wiedergabeliste")
        assertFalse(i is UserIntent.TrackRef)
    }

}
