package com.mp.player

import com.mp.player.ai.llm.TrainedIntentModel
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Held-out Intent-Sätze – absichtlich NICHT identisch zu den Trainingstemplates.
 * Misst Generalisierung des trainierten Multi-Label-Modells.
 */
class IntentHeldOutTest {
    private fun model(): TrainedIntentModel {
        val root = File("src/main/assets/ml")
        return TrainedIntentModel.fromBytes(
            File(root, "intent_model_meta.json").readText(),
            File(root, "intent_model.bin").readBytes()
        )
    }

    private data class Case(val text: String, val expectAny: List<String>)

    @Test fun heldOutNaturalLanguage() {
        val m = model()
        val cases = listOf(
            Case("mach mir was ähnliches wie meine Rap-Playlist", listOf("intent_similar_playlist", "slot_has_playlist_ref")),
            Case("bau mir sowas wie meine Chill-Rap-Wiedergabeliste", listOf("intent_similar_playlist", "slot_has_playlist_ref")),
            Case("mehr davon", listOf("intent_similar_track")),
            Case("noch trauriger", listOf("slot_mood_sad", "intent_refine")),
            Case("mehr BPM", listOf("slot_bpm_up", "intent_refine")),
            Case("weniger aggressiv", listOf("slot_energy_low", "intent_refine", "slot_mood_sad")),
            Case("mach an", listOf("intent_start")),
            Case("spiel rap", listOf("slot_genre_rap", "intent_play_mood")),
            Case("bock auf hardtekk", listOf("slot_genre_tekk", "intent_play_mood")),
            Case("etwas langsamer bitte", listOf("slot_bpm_down", "intent_refine")),
        )
        var ok = 0
        for (c in cases) {
            val scores = m.predict(c.text.lowercase())
            val hit = c.expectAny.any { (scores[it] ?: 0f) >= 0.40f }
            println("TEXT='${c.text}' hit=$hit scores=${scores.filter { it.value >= 0.35f }}")
            if (hit) ok++
        }
        val rate = ok.toDouble() / cases.size
        println("=== INTENT HELD-OUT ===")
        println("hit $ok / ${cases.size} (${"%.0f".format(rate * 100)}%)")
        // Ehrlich: wir fordern nicht 100 % – aber klar über Zufall
        assertTrue("Held-out hit rate too low: $rate", rate >= 0.5)
    }
}
