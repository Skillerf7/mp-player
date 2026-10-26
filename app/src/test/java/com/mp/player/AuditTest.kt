package com.mp.player

import com.mp.player.ai.Assistant
import com.mp.player.ai.ChatKind
import com.mp.player.ai.EqKind
import com.mp.player.ai.IntentEngine
import com.mp.player.ai.Mood
import com.mp.player.ai.UserIntent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Ausfuehrbare Spec-Matrix (Abschnitte 77-80, 120, 122): jeder Beispielsatz der Spezifikation gegen den ECHTEN Parser.
 * Schlaegt hier etwas fehl, zeigt die Meldung den Satz und was stattdessen erkannt wurde.
 */
class AuditTest {
    private fun u(s: String) = IntentEngine.understand(s)
    private fun chat(s: String, k: ChatKind) = assertEquals(s, UserIntent.Chat(k), u(s))
    private fun emo(s: String) = assertTrue("$s -> ${u(s)}", u(s) is UserIntent.Emotion)

    // ---- Small Talk (Spec 77/120): nie ein unbekannter Musikbefehl
    @Test fun smallTalk() {
        chat("Was geht?", ChatKind.HOW_ARE_YOU); chat("Echt?", ChatKind.REALLY); chat("Warum bro?", ChatKind.WHY)
        chat("Und?", ChatKind.AND); chat("Hm.", ChatKind.HM); chat("xd", ChatKind.LAUGH); chat("lol", ChatKind.LAUGH)
        chat("Hab dich lieb.", ChatKind.AFFECTION); chat("Kein Ding.", ChatKind.NO_PROBLEM)
        assertEquals(UserIntent.Thanks, u("Danke"))
        assertEquals(UserIntent.Confirm(true), u("Ok")); assertEquals(UserIntent.Confirm(true), u("Ja"))
        assertEquals(UserIntent.Confirm(false), u("Nein")); assertEquals(UserIntent.Greeting, u("Bro?"))
        assertEquals(UserIntent.Greeting, u("yo bro"))
    }

    @Test fun freeConversation() {
        chat("Mir ist langweilig.", ChatKind.BORED); chat("Ich weiß nicht was ich machen soll.", ChatKind.UNDECIDED)
        chat("Keine Ahnung.", ChatKind.UNDECIDED); chat("Erzähl irgendwas.", ChatKind.TELL)
        chat("Kannst du mich ablenken?", ChatKind.DISTRACT); chat("Ich will einfach bisschen labern.", ChatKind.TALK)
        emo("Ich hab keinen Bock."); emo("Heute ist irgendwie komisch.")
    }

    // ---- Emotionen (Spec 78): nie automatisch Musik
    @Test fun emotions() {
        for (s in listOf("Mir geht's scheiße.", "Ich bin komplett durch.", "Heute war einfach scheiße.", "Heute war mies.", "Alles ist gerade nervig.",
            "Bin traurig.", "Ich weiß nicht was mit mir los ist.", "Keine Ahnung, bin irgendwie durch.")) emo(s)
        for (s in listOf("Bin happy.", "Mir geht's super.", "Bin glücklich.")) {
            val i = u(s); assertTrue("$s -> $i", i is UserIntent.Emotion && i.feel == Mood.HAPPY)
        }
    }

    // ---- Musikwuensche (Spec 79)
    @Test fun musicIntents() {
        for (s in listOf("Mach Musik.", "Spiel was.", "Such was.", "Mach traurig.", "Mach chillig.", "Bock auf Party.", "Ich brauch Musik.",
            "Mach traurige Musik.", "Mach was zum Abschalten.", "Mach eine Playlist.")) assertTrue("$s -> ${u(s)}", u(s) is UserIntent.PlayMood)
        assertEquals(UserIntent.Surprise, u("Such du aus."))
        assertEquals(Mood.PARTY, (u("Bock auf Party.") as UserIntent.PlayMood).mood)
        assertTrue((u("Mach eine Playlist.") as UserIntent.PlayMood).prepare)
    }

    // ---- Kontext (Spec 120)
    @Test fun contextPhrases() {
        assertEquals(UserIntent.More, u("Mehr davon.")); assertEquals(UserIntent.Less, u("Weniger davon."))
        assertEquals(UserIntent.Resume, u("Mach weiter.")); assertEquals(UserIntent.More, u("Nochmal."))
        for (s in listOf("Nicht den.", "Der davor.", "Den ersten.")) assertTrue("$s -> ${u(s)}", u(s) is UserIntent.TrackRef)
        assertEquals(Mood.SAD, (u("Noch trauriger.") as UserIntent.PlayMood).mood)
    }

    // ---- EQ (Spec 74/120)
    @Test fun eqPhrases() {
        for (s in listOf("Mehr Bass.", "Bisschen mehr Bass.", "Mitte tiefer.", "Höhen bisschen höher.", "Oben mehr Luft.", "Unten mehr.", "Mitte weg.", "Nicht so schrill."))
            assertTrue("$s -> ${u(s)}", u(s) is UserIntent.EqChange)
    }

    // ---- Suche (Spec 76/120)
    @Test fun searchPhrases() {
        for (s in listOf("Such Interpret Hetzer.", "Alles von Hetzer.", "Spiel alle Songs von Hetzer.")) assertTrue("$s -> ${u(s)}", u(s) is UserIntent.SearchArtist)
        assertTrue("Such Maytrixx -> ${u("Such Maytrixx.")}", u("Such Maytrixx.") is UserIntent.SearchPlay)
    }

    // ---- Playlist (Spec 120)
    @Test fun playlistPhrases() {
        assertTrue(u("Mach sie länger.") is UserIntent.Refine); assertTrue(u("Mach sie kürzer.") is UserIntent.Refine)
        assertTrue(u("Der dritte ist scheiße.") is UserIntent.TrackRef); assertTrue(u("Mach den weg.") is UserIntent.TrackRef)
        assertTrue(u("Such was ähnliches.") is UserIntent.TrackRef)
    }

    // ---- Widerspruch (Spec 80/120)
    @Test fun conflicts() {
        val sad = u("Mach traurig, aber nicht zu traurig.") as UserIntent.PlayMood
        assertEquals(Mood.SAD, sad.mood); assertTrue(Mood.SAD in sad.exclude) // Assistant schwaecht ab
        val party = u("Mach Party, aber nicht zu aggressiv.") as UserIntent.PlayMood
        assertEquals(Mood.PARTY, party.mood); assertTrue(Mood.AGGRESSIVE in party.exclude)
        val chill = u("Ich will chillen, aber nicht einschlafen.") as UserIntent.PlayMood
        assertEquals(Mood.CALM, chill.mood); assertTrue(Mood.SLEEP in chill.exclude)
        val bass = (u("Mehr Bass, aber nicht übertrieben.") as UserIntent.EqChange).cmds
        assertEquals(1, bass.size); assertEquals(EqKind.BASS_UP, bass[0].kind)
        assertEquals(com.mp.player.ai.EqSize.SMALL, bass[0].size)
    }

    // ---- Edge Cases (Spec 122) mit dem echten Assistant
    private fun tr(n: Int) = Track("uri://$n", "Song $n", "A$n", "", "", 2020, 180_000, "", 0L, "Rap")
    private fun Assistant.say(s: String) = runBlocking { handle(s) }

    @Test fun edgeCasesAndNeverTheOldFailureText() {
        val a = Assistant(FakeTools((1..6).map { tr(it) }), uiContext = EmptyCoroutineContext)
        val inputs = listOf("", "😂", "😂😂😂", "xd", "bro", "mehr", "mach", "ÄÖÜ ß", "!!!", "Maytrixx", "mach Muuusik", "chilllig", "bissl mehr bas", "a".repeat(5000), "Echt?", "Warum bro?", "Und?", "Hm.")
        for (s in inputs) {
            val r = a.say(s).text
            assertFalse("[$s] leer", r.isBlank())
            assertFalse("[$s] alte Fehlermeldung", r.contains("komm ich nicht mit") || r.contains("Kapier ich grad nicht") || r.contains("Bitte formuliere"))
        }
    }

    @Test fun druckWhileMusicRunsIsBassNotANewQueue() {
        val tools = FakeTools((1..6).map { tr(it) })
        val brain = object : com.mp.player.ai.BrainEngine {
            override fun understand(text: String): UserIntent =
                if (text == "init") UserIntent.PlayMood(mood = null, genres = listOf("rap"), genreLabel = "Rap") else IntentEngine.understand(text)
        }
        val a = Assistant(tools, brain = brain, uiContext = EmptyCoroutineContext)
        a.say("init")
        val calls = tools.replaceCalls
        a.say("Mehr Druck")
        assertEquals(calls, tools.replaceCalls)          // keine neue Queue
        assertTrue(tools.eq.bass > 0f)                    // untenrum mehr
    }

    @Test fun happyDoesNotStartMusicButOffers() {
        val tools = FakeTools((1..6).map { tr(it) })
        val a = Assistant(tools, uiContext = EmptyCoroutineContext)
        val r = a.say("Mir geht's super")
        assertEquals(0, tools.replaceCalls)
        assertTrue(r.text.contains("freut") || r.text.contains("Fröhliches") || r.text.contains("Passendes"))
    }

    @Test fun boredOffersDiscoveryAndWhyIsHonestWithoutContext() {
        val tools = FakeTools((1..6).map { tr(it) })
        val a = Assistant(tools, uiContext = EmptyCoroutineContext)
        val bored = a.say("Mir ist langweilig").text
        assertTrue(bored.contains("Unbekanntes") || bored.contains("Neues"))
        assertEquals(0, tools.replaceCalls)
        val b = Assistant(FakeTools(emptyList()), uiContext = EmptyCoroutineContext)
        assertTrue(b.say("Warum?").text.contains("Warum") || b.say("Warum?").text.contains("Worauf"))
    }
}
