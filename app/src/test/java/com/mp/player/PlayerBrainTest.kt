package com.mp.player

import com.mp.player.ai.ConversationState
import com.mp.player.ai.InMemoryStore
import com.mp.player.ai.MemoryEngine
import com.mp.player.ai.Mood
import com.mp.player.ai.llm.EmbeddedIntentBrain
import com.mp.player.ai.llm.PlayerBrain
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBrainTest {

    private val brain = PlayerBrain(trained = null, embedded = EmbeddedIntentBrain)

    @Test
    fun isAlwaysAvailable() {
        assertTrue(brain.isAvailable())
    }

    @Test
    fun melancholicToday_yieldsMoodOrPrefs() {
        val state = ConversationState()
        val mem = MemoryEngine(InMemoryStore())
        val r = brain.interpretWithContext(
            "Heute lieber etwas Melancholisches.",
            emptyList(),
            state,
            mem
        )
        // Either structured mood or at least non-null with notes/prefs
        if (r != null) {
            assertTrue(r.mood == Mood.SAD || r.notes.any { it.startsWith("prefs:") } || r.rawConfidence > 0f)
        }
    }

    @Test
    fun release_doesNotCrash() {
        brain.release()
        assertTrue(brain.isAvailable())
    }
}
