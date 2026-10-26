package com.mp.player

import com.mp.player.ai.Assistant
import com.mp.player.ai.ChatKind
import com.mp.player.ai.IntentEngine
import com.mp.player.ai.UserIntent
import kotlinx.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mehrstufige Dialog-Tests: Emotion → Weitererzählen → kein Fun-Fact.
 * Nutzt FakeTools; kein Android SDK nötig.
 */
class ConversationFlowTest {

    private fun assistant() = Assistant(FakeTools(emptyList()), uiContext = EmptyCoroutineContext)

    @Test
    fun tellIntent_isRecognizedWithoutPlay() {
        val i = IntentEngine.understand("erzähl mir was")
        assertTrue("Expected Chat TELL or TALK, got $i", i is UserIntent.Chat)
    }

    @Test
    fun emotionalThenTell_noFunFact() = runBlocking {
        val a = assistant()
        a.handle("Mir geht's richtig scheiße heute")
        val r2 = a.handle("erzähl mir was")
        assertFalse(
            "Im emotionalen Kontext kein Musik-Fun-Fact: ${r2.text}",
            r2.text.startsWith("Fun Fact:")
        )
    }

    @Test
    fun lightShare_notAlwaysKlingtSchwer() = runBlocking {
        val a = assistant()
        a.handle("Lass quatschen")
        val r = a.handle("Heute war alles nice und entspannt auf der Arbeit")
        assertFalse(
            "Leichte Story nicht mit 'Klingt schwer' abwürgen: ${r.text}",
            r.text.contains("Klingt schwer")
        )
    }

    @Test
    fun topicSwitch_musicAfterChat() = runBlocking {
        val a = assistant()
        a.handle("Lass reden")
        val r = a.handle("Spiel was Ruhiges")
        // sollte nicht stuck in pure chat-unknown bleiben
        assertTrue(r.text.isNotBlank())
    }

    @Test
    fun multiTurn_doesNotCrash() = runBlocking {
        val a = assistant()
        val turns = listOf(
            "Hey",
            "Mir ist irgendwie mulmig",
            "Wegen der Arbeit",
            "Nee, lieber nur reden",
            "Ok danke"
        )
        for (t in turns) {
            val r = a.handle(t)
            assertTrue("Leere Antwort bei: $t", r.text.isNotBlank())
        }
    }
}
