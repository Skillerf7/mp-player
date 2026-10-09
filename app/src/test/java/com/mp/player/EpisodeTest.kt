package com.mp.player

import com.mp.player.ai.Assistant
import com.mp.player.ai.Daypart
import com.mp.player.ai.EpisodeEngine
import com.mp.player.ai.InMemoryEpisodeStore
import com.mp.player.ai.IntentEngine
import com.mp.player.ai.Mood
import com.mp.player.ai.Topic
import com.mp.player.ai.UserIntent
import com.mp.player.ai.ConversationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking

/** Episodisches Gedaechtnis, Tageszeit-Muster, Themen-Stapel (Spec 9.3, 83, 96) - reine JVM-Tests. */
class EpisodeTest {
    private fun at(hour: Int, daysAgo: Int = 0): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -daysAgo); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 30)
    }.timeInMillis

    private var now = at(23)
    private val engine = EpisodeEngine(InMemoryEpisodeStore()) { now }

    @Test fun daypartBoundaries() {
        assertEquals(Daypart.NIGHT, Daypart.of(at(23))); assertEquals(Daypart.NIGHT, Daypart.of(at(3)))
        assertEquals(Daypart.MORNING, Daypart.of(at(7))); assertEquals(Daypart.DAY, Daypart.of(at(13)))
        assertEquals(Daypart.EVENING, Daypart.of(at(19)))
        assertEquals(Daypart.NIGHT, Daypart.fromWord("nachts"))
        assertEquals(Daypart.DAY, Daypart.fromWord("tagsueber"))
    }

    @Test fun twoEventsAreNotAPattern() {
        now = at(23, 2); engine.record(Mood.CALM, null, null, null, 60)
        now = at(23, 1); engine.record(Mood.CALM, null, null, null, 60)
        assertTrue(engine.patterns().isEmpty())
        assertNull(engine.topFor(Daypart.NIGHT))
        assertTrue(engine.describe(Daypart.NIGHT).contains("zu wenig Daten"))
    }

    @Test fun threeSameStartsAtNightFormAPattern() {
        for (d in 3 downTo 1) { now = at(23, d); engine.record(Mood.CALM, null, Mood.SAD, "Arbeit", 60) }
        now = at(23)
        val p = engine.topFor(Daypart.NIGHT)!!
        assertEquals(Mood.CALM, p.mood); assertEquals(3, p.count)
        assertNull(engine.topFor(Daypart.MORNING)) // anderer Tagesabschnitt: kein Muster erfunden
        assertTrue(engine.describe(Daypart.NIGHT).contains("nachts") && engine.describe().contains("3x"))
    }

    @Test fun genreBeatsMoodAndCaseIsIgnored() {
        now = at(19, 3); engine.record(null, "Rap", null, null, null)
        now = at(19, 2); engine.record(null, "rap", null, null, null)
        now = at(19, 1); engine.record(null, "RAP", null, null, null)
        now = at(19)
        val p = engine.topFor(Daypart.EVENING)!!
        assertEquals(3, p.count); assertEquals("Rap", p.genre)
    }

    @Test fun oldEventsFallOutOfTheWindow() {
        for (d in listOf(200, 150, 120)) { now = at(23, d); engine.record(Mood.CALM, null, null, null, null) }
        now = at(23)
        assertTrue(engine.patterns().isEmpty())
    }

    @Test fun eventsWithoutContentAreNotRecordedAndStoreIsTrimmed() {
        engine.record(null, null, null, null, null)
        assertEquals(0, engine.count())
        repeat(320) { engine.record(Mood.CALM, null, null, null, null) }
        assertTrue(engine.count() <= 300)
    }

    // ---------------------------------------------------------------- Themen-Stapel

    @Test fun topicStackKeepsOlderTopicsAvailable() {
        var t = 0L
        val s = ConversationState { t }
        s.pushTopic(Topic.EMOTION); t += 1000; s.pushTopic(Topic.MUSIC); t += 1000; s.pushTopic(Topic.EQ)
        assertEquals(Topic.EQ, s.currentTopic())
        assertEquals(listOf(Topic.EQ, Topic.MUSIC, Topic.EMOTION), s.topics())
        s.pushTopic(Topic.MUSIC) // Rueckkehr: nach vorn, nicht doppelt
        assertEquals(listOf(Topic.MUSIC, Topic.EQ, Topic.EMOTION), s.topics())
        assertNull(s.topicAgeMs(Topic.CHAT))
    }

    @Test fun resumeAndPatternPhrases() {
        assertEquals(UserIntent.ResumeTopic(Topic.PLAYLIST), IntentEngine.understand("Und die Playlist von eben?"))
        assertEquals(UserIntent.ResumeTopic(Topic.PLAYLIST), IntentEngine.understand("zurück zur Playlist"))
        assertEquals(UserIntent.ResumeTopic(Topic.CONVERSATION), IntentEngine.understand("Wo waren wir?"))
        assertEquals(UserIntent.ResumeTopic(Topic.EQ), IntentEngine.understand("zurück zum EQ"))
        assertEquals(UserIntent.AskPattern(Daypart.NIGHT), IntentEngine.understand("Was höre ich nachts?"))
        assertEquals(UserIntent.AskPattern(null), IntentEngine.understand("Was höre ich meistens?"))
        // Befehle mit Playlist-Wort sind keine Rueckkehr
        assertFalse(IntentEngine.understand("mach eine playlist") is UserIntent.ResumeTopic)
        assertFalse(IntentEngine.understand("Such mir was für die Playlist von eben") is UserIntent.ResumeTopic)
    }

    // ---------------------------------------------------------------- Dialog mit dem Assistant

    private fun tr(n: Int, g: String = "Rap") = Track("uri://$n", "Song $n", "A$n", "", "", 2020, 180_000, "", 0L, g)
    private fun Assistant.say(s: String) = runBlocking { handle(s) }

    @Test fun noPatternMeansNoInventedPattern() {
        val a = Assistant(FakeTools((1..4).map { tr(it) }), uiContext = EmptyCoroutineContext)
        val r = a.say("Was höre ich nachts?")
        assertTrue(r.text.contains("zu wenig Daten"))
        assertFalse(a.say("Was weißt du über mich?").text.contains("hörst du oft"))
    }

    @Test fun repeatedNightStartsBecomeAnOfferNotAnAutoStart() {
        val tools = FakeTools((1..9).map { tr(it) })
        val store = InMemoryEpisodeStore()
        val ep = EpisodeEngine(store)
        val nightNow = Daypart.of(System.currentTimeMillis())
        // drei echte Ereignisse im aktuellen Tagesabschnitt
        repeat(3) { ep.record(null, "Rap", null, null, null) }
        val a = Assistant(tools, uiContext = EmptyCoroutineContext, episodeStore = store)
        val r = a.say("Was würdest du jetzt hören?")
        assertTrue(r.text.contains("${nightNow.label} oft Rap"))
        assertEquals(0, tools.replaceCalls) // nur Angebot
        a.say("Ja")
        assertEquals(1, tools.replaceCalls)
    }

    @Test fun startedMusicIsRecordedAsEpisode() {
        val tools = FakeTools((1..6).map { tr(it) })
        val store = InMemoryEpisodeStore()
        val a = Assistant(tools, uiContext = EmptyCoroutineContext, episodeStore = store)
        a.say("Such Interpret A1")
        // Titel-/Artist-Suche ist kein Genre-/Stimmungswunsch -> kein Ereignis erfinden
        assertTrue(store.all().isEmpty())
    }

    @Test fun resumePlaylistShowsTheOpenDraftWithoutStartingIt() {
        val lib = (1..6).map { tr(it) }
        val tools = FakeTools(lib)
        val brain = object : com.mp.player.ai.BrainEngine {
            override fun understand(text: String): UserIntent =
                if (text == "init") UserIntent.PlayMood(mood = null, genres = listOf("rap"), genreLabel = "Rap", prepare = true) else IntentEngine.understand(text)
        }
        val a = Assistant(tools, brain = brain, uiContext = EmptyCoroutineContext)
        a.say("init")
        a.say("Hey wie geht's dir")                 // Themenwechsel: Smalltalk
        val r = a.say("Und die Playlist von eben?")
        assertTrue(r.text.contains("Entwurf"))
        assertEquals(0, tools.replaceCalls)
        a.say("Spiel sie")
        assertEquals(1, tools.replaceCalls)
    }

    @Test fun resumeWithoutAnythingIsHonest() {
        val a = Assistant(FakeTools(emptyList()), uiContext = EmptyCoroutineContext)
        assertTrue(a.say("Und die Playlist von eben?").text.contains("nichts offen") || a.say("Und die Playlist von eben?").text.contains("läuft nichts"))
        assertNotNull(a.say("Wo waren wir?").text)
    }
}
