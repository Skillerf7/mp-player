package com.mp.player

import com.mp.player.ai.Mood
import com.mp.player.ai.UserIntent
import com.mp.player.ai.llm.EmbeddedIntentBrain
import com.mp.player.ai.llm.HeuristicLlmInterpreter
import com.mp.player.ai.llm.LlmCoordinator
import com.mp.player.ai.llm.LlmGate
import com.mp.player.ai.llm.NativeLlmSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmGateTest {

    @Test fun simpleRapDoesNotNeedLlm() {
        val parsed = UserIntent.PlayMood(mood = null, genres = listOf("rap"))
        assertFalse(LlmGate.needsHelp(parsed, "Spiel Rap"))
    }

    @Test fun machAnDoesNotNeedLlm() {
        assertFalse(LlmGate.needsHelp(UserIntent.Confirm(true), "mach an"))
        assertFalse(LlmGate.needsHelp(UserIntent.Confirm(true), "ok"))
    }

    @Test fun bpmAnalysisDoesNotNeedLlm() {
        assertFalse(
            LlmGate.needsHelp(
                UserIntent.AskTrackAnalysis(setOf(com.mp.player.ai.AnalysisField.BPM)),
                "Lies die BPM aus."
            )
        )
    }

    @Test fun complexPhraseNeedsHelp() {
        val text = "Mach mir was, das ungefähr so klingt wie meine Schillah-Playlist, aber etwas ruhiger und mit mehr melancholischem Rap."
        assertTrue(LlmGate.isComplexPhrase(text))
        assertTrue(LlmGate.needsHelp(UserIntent.Unknown, text))
    }

    @Test fun embeddedBrainIsAlwaysAvailableInApk() {
        assertTrue(EmbeddedIntentBrain.isAvailable())
    }

    @Test fun embeddedExtractsPlaylistMoodGenre() {
        val r = EmbeddedIntentBrain.interpret(
            "Mach mir was, das ungefähr so klingt wie meine Schillah-Playlist, aber etwas ruhiger und mit mehr melancholischem Rap.",
            hintPlaylists = listOf("Schillah", "Chill-Rap")
        )
        assertNotNull(r)
        assertNotNull(r!!.referencePlaylist)
        assertTrue(r.referencePlaylist!!.contains("schillah", ignoreCase = true) || r.referencePlaylist == "Schillah")
        assertTrue(r.genres.any { it.contains("rap", ignoreCase = true) })
        assertEquals(Mood.SAD, r.mood)
        assertTrue(r.refineQuieter)
        val intent = r.toUserIntent()
        assertTrue(intent is UserIntent.SimilarToPlaylist)
    }

    @Test fun lessAggressiveIsUnderstood() {
        val r = EmbeddedIntentBrain.interpret(
            "Mach wieder sowas wie meine Schillah Playlist aber diesmal etwas trauriger und weniger aggressiv",
            listOf("Schillah")
        )
        assertNotNull(r)
        assertTrue(r!!.refineQuieter)
        assertEquals(Mood.SAD, r.mood)
    }

    @Test fun nativeSlotWithoutModelReturnsNull() {
        val n = NativeLlmSlot(modelPathProvider = { null })
        assertFalse(n.isAvailable())
        assertNull(n.interpret("Spiel Rap"))
    }

    @Test fun coordinatorUsesEmbeddedWithoutInternet() {
        val c = LlmCoordinator()
        val r = c.tryInterpret(
            "Bau mir etwas wie meine Schillah Playlist aber ruhiger und melancholisch mit Rap",
            UserIntent.Unknown,
            listOf("Schillah")
        )
        assertNotNull(r)
        assertTrue(r!!.referencePlaylist != null || r.genres.isNotEmpty() || r.mood != null)
    }

    @Test fun coordinatorNeverThrowsOnBrokenInterpreter() {
        val broken = object : com.mp.player.ai.llm.OptionalLlmInterpreter {
            override fun isAvailable() = true
            override fun interpret(text: String, hintPlaylists: List<String>) = error("boom")
            override fun release() = Unit
        }
        val c = LlmCoordinator(embedded = HeuristicLlmInterpreter(), native = broken)
        val r = c.tryInterpret(
            "komplexe anfrage mit aber und melancholisch und playlist und rap und ruhiger extra worte hier",
            UserIntent.Unknown
        )
        assertTrue(r == null || r.rawConfidence >= 0f)
    }

    @Test fun structuredIntentDoesNotDeleteAnything() {
        val s = com.mp.player.ai.llm.StructuredMusicIntent(
            referencePlaylist = "Schillah",
            genres = listOf("rap"),
            mood = Mood.SAD
        )
        val i = s.toUserIntent()
        assertTrue(i is UserIntent.SimilarToPlaylist || i is UserIntent.PlayMood)
        assertFalse(i is UserIntent.TrackRef)
    }

    @Test fun simpleSearchDoesNotInvokeGate() {
        assertFalse(LlmGate.needsHelp(UserIntent.SearchPlay("Rolexz"), "Rolexz"))
    }
}
