package com.mp.player

import com.mp.player.ai.AnswerParser
import com.mp.player.ai.Arc
import com.mp.player.ai.BrainEngine
import com.mp.player.ai.Mood
import com.mp.player.ai.PhaseParser
import com.mp.player.ai.PlaylistPlanner
import com.mp.player.ai.RefineKind
import com.mp.player.ai.TrackInfo
import com.mp.player.ai.AskKind
import com.mp.player.ai.Assistant
import com.mp.player.ai.IntentEngine
import com.mp.player.ai.RefAction
import com.mp.player.ai.RefRel
import com.mp.player.ai.RefResult
import com.mp.player.ai.ReferenceResolver
import com.mp.player.ai.UserIntent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/** Dialog- und Referenz-Tests (Spec 72, 73, 121). Laufen als reine JVM-Tests - ohne Android. */
class DialogTest {
    private fun tr(n: Int, artist: String = "Artist$n", genre: String = "") =
        Track("uri://$n", "Song $n", artist, "", "", 2020, 180_000, "", 0L, genre)

    private fun setup(n: Int = 5): Pair<Assistant, FakeTools> {
        val lib = (1..n).map { tr(it) }
        val tools = FakeTools(lib)
        tools.queueItems = lib.toMutableList()
        tools.current = lib.first()
        return Assistant(tools, uiContext = EmptyCoroutineContext) to tools
    }

    private fun Assistant.say(s: String) = runBlocking { handle(s) }

    // ---------------------------------------------------------------- Parser

    private fun ref(s: String) = IntentEngine.understand(s) as UserIntent.TrackRef

    @Test fun referencePhrasesParse() {
        assertEquals(setOf(RefAction.DISLIKE), ref("Der dritte Song ist scheiße").actions)
        assertEquals(3, ref("Der dritte Song ist scheiße").ordinal)
        assertEquals(setOf(RefAction.REMOVE, RefAction.SIMILAR), ref("Mach den weg und such was ähnliches").actions)
        assertEquals(RefRel.PREVIOUS, ref("Nimm stattdessen den davor").rel)
        assertEquals(setOf(RefAction.PLAY), ref("Nimm stattdessen den davor").actions)
        assertEquals(3, ref("Die ersten drei").countFirst)
        assertEquals(1, ref("Den ersten").ordinal)
        assertEquals(setOf(RefAction.DISLIKE), ref("Nee, den nicht").actions)
        assertEquals(setOf(RefAction.WHY), ref("Warum hast du den genommen?").actions)
    }

    @Test fun referenceParserLeavesOtherThingsAlone() {
        assertEquals(UserIntent.Feedback(false), IntentEngine.understand("das lied ist scheiße"))
        assertFalse(IntentEngine.understand("Mitte weg") is UserIntent.TrackRef)
        assertFalse(IntentEngine.understand("mach die Mitte weg") is UserIntent.TrackRef)
        assertFalse(IntentEngine.understand("Warum klingt das so bassig?") is UserIntent.TrackRef)
        assertFalse(IntentEngine.understand("das erste mal") is UserIntent.TrackRef)
        assertFalse(IntentEngine.understand("Mach Musik") is UserIntent.TrackRef)
    }

    // ---------------------------------------------------------------- Resolver

    @Test fun resolverUsesRealPositions() {
        val list = (1..5).map { tr(it) }
        fun r(o: Int?, rel: RefRel? = null, n: Int? = null, sug: Track? = null, cur: Track? = list[0]) =
            ReferenceResolver.resolve(UserIntent.TrackRef(setOf(RefAction.PLAY), o, rel, n), list, cur, sug)
        assertEquals(RefResult.One(list[2]), r(3))
        assertEquals(RefResult.One(list[4]), r(-1))
        assertTrue(r(9) is RefResult.None)
        assertEquals(RefResult.Many(list.take(3)), r(null, n = 3))
        // "der davor" zaehlt ab dem zuletzt besprochenen Titel, nicht irgendeinem
        assertEquals(RefResult.One(list[2]), r(null, RefRel.PREVIOUS, sug = list[3]))
        assertTrue(r(null, RefRel.PREVIOUS, sug = list[0]) is RefResult.None)
        // ohne Bezugsliste nichts erfinden
        assertTrue(ReferenceResolver.resolve(UserIntent.TrackRef(setOf(RefAction.PLAY), 2, null, null), emptyList(), null, null) is RefResult.None)
    }

    // ---------------------------------------------------------------- Antworten auf Rueckfragen

    @Test fun answersAreReadInTheLightOfTheQuestion() {
        assertEquals("push", AnswerParser.parse(AskKind.ENERGY, "Druck.").choice)
        assertEquals("calm", AnswerParser.parse(AskKind.ENERGY, "eher ruhig").choice)
        assertEquals("music", AnswerParser.parse(AskKind.MUSIC_OR_TALK, "Musik.").choice)
        assertEquals("talk", AnswerParser.parse(AskKind.MUSIC_OR_TALK, "lass reden").choice)
        assertEquals("up", AnswerParser.parse(AskKind.SAD_OR_UP, "lieber hochkommen").choice)
        assertTrue(AnswerParser.parse(AskKind.ENERGY, "such du").surprise)
        // beides gleichzeitig = unsicher, nicht raten
        assertNull(AnswerParser.parse(AskKind.ENERGY, "ruhig aber mit druck").choice)
        assertNull(AnswerParser.parse(AskKind.ENERGY, "hmm").choice)
    }

    // ---------------------------------------------------------------- Dialoge mit dem echten Assistant

    @Test fun badMoodDoesNotStartMusicAndAsksFirst() {
        val (a, t) = setup()
        val r = a.say("Mir geht's scheiße.")
        assertEquals(0, t.replaceCalls)
        assertTrue(r.text.contains("erzählen") || r.text.contains("reden"))
        val r2 = a.say("Musik.")
        assertEquals(0, t.replaceCalls) // erst noch die Richtung klaeren
        assertTrue(r2.text.contains("traurig") || r2.text.contains("hochkommen"))
    }

    @Test fun talkInsteadOfMusic() {
        val (a, t) = setup()
        a.say("Ich bin komplett durch")
        val r = a.say("Reden")
        assertEquals(0, t.replaceCalls)
        assertFalse(r.text.isBlank())
    }

    @Test fun unknownTextAsksMusicOrChatInsteadOfFailing() {
        val (a, t) = setup()
        val r = a.say("blubberbla")
        assertFalse(r.text.contains("komm ich nicht mit"))
        assertTrue(r.text.contains("Musik") && (r.text.contains("quatschen") || r.text.contains("reden")))
        a.say("Quatschen")
        assertEquals(0, t.replaceCalls)
    }

    @Test fun dislikeThenConfirmRemoval() {
        val (a, t) = setup()
        val r = a.say("Der dritte Song ist scheiße")
        assertTrue(r.text.contains("Song 3"))
        assertTrue(t.removed.isEmpty()) // Urteil allein entfernt nichts
        a.say("Ja")
        assertEquals(listOf(tr(3).id), t.removed)
        assertEquals(4, t.queueItems.size)
    }

    @Test fun removeRefersToTheLastDiscussedTrack() {
        val (a, t) = setup()
        a.say("Der dritte Song ist scheiße")
        a.say("Mach den weg")
        assertEquals(listOf(tr(3).id), t.removed)
    }

    @Test fun removeRunningTrackSkipsFirst() {
        val (a, t) = setup()
        a.say("Der erste ist scheiße")
        a.say("Mach den weg")
        assertEquals(1, t.skips)
        assertTrue(t.removed.contains(tr(1).id))
    }

    @Test fun noThanksToAnOfferIsNotATrackReference() {
        val (a, t) = setup()
        a.say("Was würdest du jetzt hören?")
        a.say("nee, das nicht")
        assertTrue(t.removed.isEmpty())
        assertEquals(0, t.skips)
    }

    @Test fun ordinalPlaysTheRightTrackAndIsHonestWhenOutOfRange() {
        val (a, t) = setup()
        a.say("Mach den dritten an")
        assertEquals(listOf(tr(3).id), t.played)
        val r = a.say("Spiel den neunten")
        assertTrue(r.text.contains("5")) // sagt, wie lang die Liste wirklich ist
        assertEquals(1, t.played.size)
    }

    @Test fun firstThreeOfTheLastSearch() {
        val lib = (1..8).map { tr(it, artist = "Hetzer") }
        val tools = FakeTools(lib)
        val a = Assistant(tools, uiContext = EmptyCoroutineContext)
        a.say("Such Interpret Hetzer")
        assertEquals(8, tools.queueItems.size)
        a.say("Die ersten drei")
        assertEquals(3, tools.queueItems.size)
    }

    @Test fun whyIsAnsweredFromRealDataOnly() {
        val (a, _) = setup()
        val r = a.say("Warum dieser Song?")
        // ohne Analyse/Verlauf: ehrlich sagen, dass keine Daten da sind - nichts erfinden
        assertTrue(r.text.contains("keine Daten"))
    }

    @Test fun similarReplacementComesFromTheLibraryAndIsHonest() {
        val lib = listOf(tr(1, genre = "Rap"), tr(2, genre = "Rap"), tr(3, genre = "Techno"), tr(4, genre = "Rap"))
        val tools = FakeTools(lib)
        tools.queueItems = mutableListOf(lib[0], lib[2])
        tools.current = lib[0]
        val a = Assistant(tools, uiContext = EmptyCoroutineContext)
        val r = a.say("Der zweite ist scheiße und such was ähnliches")
        assertNotNull(r)
        assertTrue(tools.removed.isEmpty() || tools.removed.contains(lib[2].id))
    }

    @Test fun volumeDialog() {
        val (a, t) = setup()
        a.say("lauter"); assertEquals(60, t.volume)
        a.say("etwas leiser"); assertEquals(55, t.volume)
        a.say("Lautstärke auf 10"); assertEquals(10, t.volume)
    }

    @Test fun longConversationKeepsContext() {
        val (a, t) = setup()
        a.say("Bro was geht")
        a.say("Keine Ahnung, bin irgendwie durch")
        repeat(12) { a.say("hm") }
        a.say("lauter")
        assertEquals(60, t.volume)
        val r = a.say("Was weißt du über mich?")
        assertFalse(r.text.isBlank())
    }

    @Test fun edgeCasesNeverCrash() {
        val (a, _) = setup()
        for (s in listOf("", "   ", "😂😂", "xd", "bro", "mehr", "mach", "Ja", "Nein", "a".repeat(2000), "!!!???", "Maytrixx", "der")) {
            assertFalse(s, a.say(s).text.isBlank())
        }
    }

    // ---------------------------------------------------------------- Dramaturgie (Spec 22)

    private fun analyzed(n: Int, lufs: Float) = TrackInfo(
        tr(n), AnalysisResult("uri://$n", 0L, 100f, lufs, -1f, -20f, 44100, 2, false), null, 0, false
    )

    @Test fun fallArcSortsEnergyDownwardsWhenDataExists() {
        // lufs hoeher (z. B. -8) = lauter/energiereicher
        val infos = listOf(-8f, -20f, -12f, -25f, -16f, -10f).mapIndexed { i, l -> analyzed(i + 1, l) }
        val plan = PlaylistPlanner.order(infos.map { it.track }, infos.associateBy { it.track.uri }, Arc.FALL)
        assertTrue(plan.applied)
        val energy = plan.tracks.map { t -> infos.first { it.track.uri == t.uri }.analysis!!.lufs }
        assertTrue(energy.first() > energy.last())
        assertEquals(infos.size, plan.tracks.map { it.uri }.toSet().size) // nichts verloren, nichts doppelt
    }

    @Test fun peakArcPutsTheLoudestNearTheEnd() {
        val infos = (1..8).map { analyzed(it, -30f + it * 2f) } // 1 leise ... 8 laut
        val plan = PlaylistPlanner.order(infos.map { it.track }, infos.associateBy { it.track.uri }, Arc.PEAK)
        assertTrue(plan.applied)
        val peakPos = plan.tracks.indexOfFirst { it.uri == "uri://8" }
        assertTrue("Hoehepunkt gegen Ende, nicht am Anfang", peakPos >= 4 && peakPos < 8)
        assertTrue(plan.tracks.first().uri != "uri://8")
    }

    @Test fun noInventedDramaturgyWithoutData() {
        val tracks = (1..8).map { tr(it) }
        val plan = PlaylistPlanner.order(tracks, tracks.associate { it.uri to TrackInfo(it, null, null, 0, false) }, Arc.FALL)
        assertFalse(plan.applied)
        assertEquals(tracks, plan.tracks)
        assertEquals(Arc.FLAT, PlaylistPlanner.arcFor(null, false))
    }

    @Test fun arcsMatchSituations() {
        assertEquals(Arc.PEAK, PlaylistPlanner.arcFor(Mood.PARTY, false))
        assertEquals(Arc.FALL, PlaylistPlanner.arcFor(Mood.SLEEP, false))
        assertEquals(Arc.SUPPORT, PlaylistPlanner.arcFor(Mood.SAD, true))
        assertEquals(Arc.FALL, PlaylistPlanner.arcFor(Mood.CALM, false))
    }

    // ---------------------------------------------------------------- Entwurf: vorbereiten, aendern, starten

    @Test fun prepareVsStartPhrases() {
        assertTrue(PlaylistPlanner.isPrepareRequest("Such mir traurige Musik"))
        assertFalse(PlaylistPlanner.isPrepareRequest("Such mir traurige Musik und spiel sie"))
        assertFalse(PlaylistPlanner.isPrepareRequest("Mach traurige Musik"))
        for (s in listOf("Spiel sie", "spiel sie an", "Mach", "ja mach", "los", "starte es")) assertTrue(s, PlaylistPlanner.isStartDraft(s))
        assertFalse(PlaylistPlanner.isStartDraft("Mach Musik"))
        assertEquals(UserIntent.Refine(RefineKind.LONGER), IntentEngine.understand("Mach sie länger"))
        assertEquals(UserIntent.Refine(RefineKind.SHORTER), IntentEngine.understand("etwas kürzer"))
    }

    private fun rapLibrary(n: Int = 12): FakeTools {
        val lib = (1..n).map { tr(it, genre = "Rap") }
        return FakeTools(lib)
    }

    @Test fun draftCanBeEditedBeforeStart() {
        val tools = rapLibrary()
        val brain = object : BrainEngine {
            override fun understand(text: String): UserIntent =
                if (text.startsWith("Such")) UserIntent.PlayMood(mood = null, genres = listOf("rap"), genreLabel = "Rap", prepare = true) else IntentEngine.understand(text)
        }
        val a = Assistant(tools, brain = brain, uiContext = EmptyCoroutineContext)
        val r = a.say("Such Rap")
        assertTrue(r.text.contains("Entwurf"))
        assertEquals(0, tools.replaceCalls)
        a.say("Der dritte ist scheiße")
        a.say("Mach den weg")
        assertEquals(0, tools.removed.size) // Entwurf aendern fasst die echte Queue nicht an
        a.say("Spiel sie")
        assertEquals(1, tools.replaceCalls)
        assertTrue(tools.queueItems.size in 1..11)
    }

    @Test fun refineLongerAndShorterOnTheRunningQueue() {
        val lib = (1..10).map { tr(it, genre = "Rap") }
        val tools = FakeTools(lib)
        tools.queueItems = lib.toMutableList(); tools.current = lib[0]
        val brain = object : BrainEngine {
            override fun understand(text: String): UserIntent =
                if (text == "init") UserIntent.PlayMood(mood = null, genres = listOf("rap"), genreLabel = "Rap") else IntentEngine.understand(text)
        }
        val a = Assistant(tools, brain = brain, uiContext = EmptyCoroutineContext)
        a.say("init")
        val before = tools.queueItems.size
        val r = a.say("kürzer")
        assertTrue(r.text.contains("gekürzt") || r.text.contains("Kürzer geht kaum"))
        assertTrue(tools.queueItems.size <= before)
        assertTrue(tools.queueItems.contains(tools.current)) // laufender Titel bleibt
    }

    @Test fun refineWithoutAPlaylistIsHonest() {
        val a = Assistant(FakeTools(emptyList()), uiContext = EmptyCoroutineContext)
        assertTrue(a.say("länger").text.contains("keine Playlist"))
    }

    // ---------------------------------------------------------------- Widerspruch (Spec 30, 80)

    @Test fun sadButNotTooSadIsSoftenedNotExcluded() {
        val lib = (1..6).map { tr(it) }
        val tools = FakeTools(lib)
        val brain = object : BrainEngine {
            override fun understand(text: String): UserIntent = UserIntent.PlayMood(mood = Mood.SAD, exclude = setOf(Mood.SAD))
        }
        val a = Assistant(tools, brain = brain, uiContext = EmptyCoroutineContext)
        val r = a.say("Mach traurige Musik, aber nicht zu traurig")
        assertFalse(r.text.isBlank())
        // wurde etwas gestartet, muss der Assistent offen sagen, dass er abgeschwaecht hat (Transparenz)
        if (tools.replaceCalls > 0) assertTrue(r.text.contains("Weicheres"))
    }

    // ---------------------------------------------------------------- Mehrphasige Wuensche (Spec 31)

    private fun stub(seg: String): UserIntent = when {
        seg.contains("trauri") -> UserIntent.PlayMood(mood = Mood.SAD, minutes = if (seg.contains("stunden")) 120 else null,
            eq = if (seg.contains("bass")) listOf(com.mp.player.ai.EqCommand(com.mp.player.ai.EqKind.BASS_UP, com.mp.player.ai.EqSize.SMALL)) else emptyList())
        seg.contains("ruhig") -> UserIntent.PlayMood(mood = Mood.CALM)
        seg.contains("pause") -> UserIntent.Pause
        else -> UserIntent.Unknown
    }

    @Test fun phasesAreSplitAndTotalTimeIsSharedHonestly() {
        val m = PhaseParser.parse("Mach für zwei Stunden was trauriges mit bisschen Bass und danach was ruhiges", ::stub)!!
        assertEquals(2, m.phases.size)
        assertEquals(Mood.SAD, m.phases[0].mood); assertEquals(Mood.CALM, m.phases[1].mood)
        assertEquals(listOf(60, 60), m.phases.map { it.minutes })
        assertTrue(m.splitNote!!.contains("120"))
        assertEquals(1, m.phases[0].eq.size)  // Bass gehoert zur ersten Phase
        assertTrue(m.phases[1].eq.isEmpty())
        assertFalse(m.prepare)
    }

    @Test fun introEmotionIsSeparatedFromThePlan() {
        val m = PhaseParser.parse("Mir geht's scheiße, mach für zwei Stunden was trauriges und danach was ruhiges", ::stub)!!
        assertEquals("mir geht's scheisse", m.intro)
        assertEquals(2, m.phases.size)
    }

    @Test fun explicitMinutesPerPhaseAreKept() {
        val (r, note) = PhaseParser.resolveMinutes(listOf(UserIntent.PlayMood(Mood.SAD, minutes = 30), UserIntent.PlayMood(Mood.CALM, minutes = 45)))
        assertEquals(listOf(30, 45), r.map { it.minutes }); assertNull(note)
    }

    @Test fun nonPhaseSentencesAreNotSplit() {
        assertNull(PhaseParser.parse("mach was trauriges und dann pause", ::stub))
        assertNull(PhaseParser.parse("mach was trauriges", ::stub))
        assertNull(PhaseParser.parse("dann", ::stub))
    }

    @Test fun phaseQueueIsBuiltInOrderAndStartedOnce() {
        val lib = (1..6).map { tr(it, genre = "Rap") } + (7..12).map { tr(it, genre = "Techno") }
        val tools = FakeTools(lib)
        val brain = object : BrainEngine {
            override fun understand(text: String): UserIntent = UserIntent.MultiPhase(
                listOf(UserIntent.PlayMood(mood = null, genres = listOf("rap"), genreLabel = "Rap"), UserIntent.PlayMood(mood = null, genres = listOf("techno"), genreLabel = "Techno")),
                intro = null, prepare = false, splitNote = null
            )
        }
        val a = Assistant(tools, brain = brain, uiContext = EmptyCoroutineContext)
        val r = a.say("egal")
        assertEquals(1, tools.replaceCalls)
        assertEquals(12, tools.queueItems.size)
        val genres = tools.queueItems.map { it.genre }
        assertEquals(List(6) { "Rap" } + List(6) { "Techno" }, genres) // Phase 1 komplett vor Phase 2
        assertTrue(r.text.contains("Phase 1") && r.text.contains("Phase 2"))
    }

    @Test fun preparedPhasePlanWaitsForStart() {
        val lib = (1..4).map { tr(it, genre = "Rap") } + (5..8).map { tr(it, genre = "Techno") }
        val tools = FakeTools(lib)
        val brain = object : BrainEngine {
            override fun understand(text: String): UserIntent =
                if (text.startsWith("Such")) UserIntent.MultiPhase(
                    listOf(UserIntent.PlayMood(null, genres = listOf("rap"), genreLabel = "Rap"), UserIntent.PlayMood(null, genres = listOf("techno"), genreLabel = "Techno")),
                    null, true, null
                ) else IntentEngine.understand(text)
        }
        val a = Assistant(tools, brain = brain, uiContext = EmptyCoroutineContext)
        val r = a.say("Such Rap und dann Techno")
        assertEquals(0, tools.replaceCalls)
        assertTrue(r.text.contains("Gestartet ist noch nichts"))
        a.say("Spiel sie")
        assertEquals(1, tools.replaceCalls); assertEquals(8, tools.queueItems.size)
    }

    @Test fun missingSecondPhaseIsReportedNotHidden() {
        val lib = (1..5).map { tr(it, genre = "Rap") }
        val tools = FakeTools(lib)
        val brain = object : BrainEngine {
            override fun understand(text: String): UserIntent = UserIntent.MultiPhase(
                listOf(UserIntent.PlayMood(null, genres = listOf("rap"), genreLabel = "Rap"), UserIntent.PlayMood(null, genres = listOf("klassik"), genreLabel = "Klassik")),
                null, false, null
            )
        }
        val r = Assistant(tools, brain = brain, uiContext = EmptyCoroutineContext).say("x")
        assertTrue(r.text.contains("Klassik") && r.text.contains("fehlt"))
        assertEquals(5, tools.queueItems.size)
    }
}
