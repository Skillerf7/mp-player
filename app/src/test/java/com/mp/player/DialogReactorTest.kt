package com.mp.player

import com.mp.player.ai.DialogReactor
import com.mp.player.ai.DialogTone
import com.mp.player.ai.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DialogReactorTest {

    @Test
    fun heavyTone_detected() {
        val c = DialogReactor.analyze("Ich bin total fertig und der Stress macht mich kaputt")
        assertEquals(DialogTone.HEAVY, c.tone)
    }

    @Test
    fun humorTone_detected() {
        val c = DialogReactor.analyze("haha das war so xd")
        assertEquals(DialogTone.HUMOR, c.tone)
    }

    @Test
    fun lightTone_detected() {
        val c = DialogReactor.analyze("Heute läuft's echt gut und ich bin happy")
        assertEquals(DialogTone.LIGHT, c.tone)
    }

    @Test
    fun questionTone_detected() {
        val c = DialogReactor.analyze("Warum kannst du das nicht?")
        assertTrue(c.isQuestion)
        assertEquals(DialogTone.QUESTION, c.tone)
    }

    @Test
    fun chatContinue_heavy_mayMentionSchwer_light_mustNot() {
        val p = Persona(Random(1))
        val heavy = p.chatContinue(null, DialogReactor.analyze("Alles ist hart und ich bin fertig"))
        val light = p.chatContinue(null, DialogReactor.analyze("Alles nice und super entspannt"))
        // light answers should not open with the old default heavy floskel as only option
        assertFalse(
            "Leichte Nachricht darf nicht pauschal 'Klingt schwer' sein: $light",
            light.startsWith("Klingt schwer") || light.startsWith("Mmm. Klingt schwer")
        )
        assertTrue(heavy.isNotBlank())
    }

    @Test
    fun funFactAllowed_inSupport_isListenNotFact() {
        val p = Persona(Random(0))
        val s = p.funFactAllowed(supportMode = true)
        assertFalse(s.startsWith("Fun Fact:"))
    }
}
