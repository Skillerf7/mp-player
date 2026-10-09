package com.mp.player

import com.mp.player.ai.llm.TrainedIntentModel
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lädt das trainierte Modell aus dem gleichen Asset-Pfad (im Test: aus dem Source-Tree).
 */
class TrainedIntentModelTest {
    private fun loadModel(): TrainedIntentModel {
        val root = File("src/main/assets/ml")
        val meta = File(root, "intent_model_meta.json").readText()
        val bytes = File(root, "intent_model.bin").readBytes()
        return TrainedIntentModel.fromBytes(meta, bytes)
    }

    @Test fun modelLoadsAndPredictsPlaylistIntent() {
        val m = loadModel()
        val scores = m.predict("mach mir wieder sowas wie meine schillah playlist")
        assertTrue("intent_similar_playlist should fire: $scores",
            (scores["intent_similar_playlist"] ?: 0f) >= 0.45f ||
                (scores["slot_has_playlist_ref"] ?: 0f) >= 0.45f)
    }

    @Test fun sadRefineFires() {
        val m = loadModel()
        val scores = m.predict("noch trauriger")
        assertTrue((scores["slot_mood_sad"] ?: 0f) >= 0.4f || (scores["intent_refine"] ?: 0f) >= 0.4f)
    }

    @Test fun interpretReturnsStructured() {
        val m = loadModel()
        val r = m.interpret(
            "mach mir was das ungefaehr so klingt wie meine schillah playlist aber trauriger",
            listOf("Schillah")
        )
        assertTrue("expected structured intent, got $r", r != null)
        assertTrue(r!!.rawConfidence >= 0.4f)
    }
}
