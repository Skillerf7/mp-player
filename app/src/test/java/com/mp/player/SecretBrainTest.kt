package com.mp.player

import com.mp.player.ai.EqSnap
import com.mp.player.ai.InMemorySnapshotStore
import com.mp.player.ai.InMemoryStore
import com.mp.player.ai.IntentEngine
import com.mp.player.ai.MemKind
import com.mp.player.ai.MemoryEngine
import com.mp.player.ai.Mood
import com.mp.player.ai.RestoreWhen
import com.mp.player.ai.SessionSnapshot
import com.mp.player.ai.SnapshotChoice
import com.mp.player.ai.SnapshotCodec
import com.mp.player.ai.SnapshotResolver
import com.mp.player.ai.UserIntent
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests fuer Feedback, Rueckgaengig, \"wie gestern\", Snapshots, Muster-Lernen und Gefuehls-Intensitaet. */
class SecretBrainTest {
    private fun parse(s: String) = IntentEngine.understand(s)
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 86_400_000L
    private val base = 1_800_000_000_000L // beliebiger fester Zeitpunkt

    private fun snap(at: Long, mood: Mood? = null, label: String? = null, eq: EqSnap? = null) =
        SessionSnapshot(at, mood, label, emptyList(), 60, eq)

    // ---------------------------------------------------------------- Feedback zum laufenden Titel

    @Test fun positiveFeedback() {
        assertEquals(UserIntent.Feedback(true), parse("das ist geil"))
        assertEquals(UserIntent.Feedback(true), parse("Boah der Song ist richtig gut"))
        assertEquals(UserIntent.Feedback(true), parse("gefällt mir"))
    }

    @Test fun negativeFeedback() {
        assertEquals(UserIntent.Feedback(false), parse("nicht meins"))
        assertEquals(UserIntent.Feedback(false), parse("nee nicht mein ding"))
        assertEquals(UserIntent.Feedback(false), parse("das lied ist scheiße"))
    }

    @Test fun preferenceWithBassIsNotFeedback() {
        // \"bisschen Bass waere geil\" ist ein Wunsch, kein Urteil ueber den Titel
        assertFalse(parse("Und bisschen Wumms wäre schon geil") is UserIntent.Feedback)
        assertFalse(parse("mehr Bass") is UserIntent.Feedback)
    }

    // ---------------------------------------------------------------- Rueckgaengig / wie gestern

    @Test fun undoAndPrevious() {
        assertEquals(UserIntent.Restore(RestoreWhen.PREVIOUS), parse("mach das rückgängig"))
        assertEquals(UserIntent.Restore(RestoreWhen.PREVIOUS), parse("mach den Bass wieder wie vorher"))
        assertEquals(UserIntent.Restore(RestoreWhen.PREVIOUS), parse("zurück auf vorher"))
    }

    @Test fun yesterdayAndEarlier() {
        assertEquals(UserIntent.Restore(RestoreWhen.YESTERDAY, false), parse("Mach wieder wie gestern"))
        assertEquals(UserIntent.Restore(RestoreWhen.YESTERDAY, true), parse("Wie war das nochmal gestern?"))
        assertEquals(UserIntent.Restore(RestoreWhen.EARLIER, false), parse("mach wieder sowas wie letztens"))
    }

    @Test fun recentMemoryQuestion() {
        assertEquals(UserIntent.RecentMemory, parse("Was hast du dir gerade gemerkt?"))
        // die allgemeine Frage bleibt die allgemeine Frage
        assertEquals(UserIntent.WhatDoYouKnow, parse("Was hast du dir über mich gemerkt?"))
    }

    // ---------------------------------------------------------------- Snapshots

    @Test fun codecRoundTrip() {
        val s = SessionSnapshot(base, Mood.CALM, "Hardtekk", listOf("hardtek", "tekk"), 90, EqSnap(0f, 2.5f, -1f, listOf(0f, 1.5f, -2f)))
        val back = SnapshotCodec.decode(SnapshotCodec.encode(s))
        assertEquals(s, back)
        val bare = SessionSnapshot(base, null, null, emptyList(), null, null)
        assertEquals(bare, SnapshotCodec.decode(SnapshotCodec.encode(bare)))
    }

    @Test fun brokenLinesAreIgnored() {
        assertNull(SnapshotCodec.decode("quatsch"))
        assertNull(SnapshotCodec.decode("1\tNICHTDA\t-\t-\t-\t-"))
        assertNull(SnapshotCodec.decode(""))
    }

    @Test fun storeKeepsOnlyNewest() {
        val store = InMemorySnapshotStore()
        val now = System.currentTimeMillis()
        for (i in 0 until 60) store.add(snap(now - (60 - i) * 1000L))
        assertEquals(SnapshotCodec.MAX_ITEMS, store.all().size)
        assertEquals(now - 1000L, store.all().last().at)
    }

    @Test fun yesterdayResolvesSingleOrAsks() {
        val today = (base / day) * day + 12 * 3_600_000L
        val yest = today - day
        val one = SnapshotResolver.resolve(listOf(snap(yest, label = "Hardtekk"), snap(yest + 1000, label = "Hardtekk")), RestoreWhen.YESTERDAY, today, utc)
        assertTrue(one is SnapshotChoice.One)
        val many = SnapshotResolver.resolve(listOf(snap(yest, label = "Hardtekk"), snap(yest + 5000, mood = Mood.CALM)), RestoreWhen.YESTERDAY, today, utc)
        assertTrue(many is SnapshotChoice.Ask)
        assertEquals(2, (many as SnapshotChoice.Ask).options.size)
        // nichts von gestern -> ehrlich \"nichts\"
        assertTrue(SnapshotResolver.resolve(listOf(snap(today - 5 * day, label = "x")), RestoreWhen.YESTERDAY, today, utc) is SnapshotChoice.None)
    }

    @Test fun earlierSkipsCurrentSession() {
        val now = base
        val picked = SnapshotResolver.resolve(listOf(snap(now - 5 * day, label = "alt"), snap(now - 1000, label = "gerade")), RestoreWhen.EARLIER, now, utc)
        assertEquals("alt", (picked as SnapshotChoice.One).snap.label())
    }

    // ---------------------------------------------------------------- EQ-Snapshot

    @Test fun eqSnapNeutralAndMax() {
        assertTrue(EqSnap(0f, 0f, 0f, listOf(0f, 0f)).isNeutral())
        val e = EqSnap(0f, 3f, -7f, listOf(1f, 2f))
        assertFalse(e.isNeutral())
        assertEquals(7f, e.maxAbs(), 0.001f)
    }

    // ---------------------------------------------------------------- Muster-Lernen

    @Test fun patternGrowsSlowlyAndFades() {
        var now = 1_000_000_000_000L
        val engine = MemoryEngine(InMemoryStore()) { now }
        // einmal ist noch kein Muster
        engine.learnPattern(Mood.CALM, "Hardtekk")
        assertNull(engine.patternFor(Mood.CALM))
        // mehrfach beobachtet -> Muster
        repeat(3) { engine.learnPattern(Mood.CALM, "Hardtekk") }
        val p = engine.patternFor(Mood.CALM)
        assertNotNull(p)
        assertEquals(MemKind.PATTERN, p!!.kind)
        assertEquals("calm>hardtekk", p.key)
        // anderes Gefuehl -> kein Muster
        assertNull(engine.patternFor(Mood.SAD))
        // Muster zaehlt nicht als Vorliebe oder Ablehnung
        assertTrue(engine.hints().prefer.isEmpty())
        assertTrue(engine.hints().avoid.isEmpty())
        // ohne Wiederholung verblasst es
        now += 400 * day
        assertNull(engine.patternFor(Mood.CALM))
    }

    @Test fun patternShownInDescribeAndForgettable() {
        val engine = MemoryEngine(InMemoryStore())
        repeat(4) { engine.learnPattern(Mood.CALM, "Hardtekk") }
        assertTrue(engine.describe().contains("Hardtekk"))
        engine.forget("hardtekk")
        assertTrue(engine.isEmpty())
    }

    // ---------------------------------------------------------------- Gefuehls-Intensitaet

    @Test fun emotionIntensity() {
        val strong = parse("Arbeit hat mich komplett fertig gemacht") as UserIntent.Emotion
        assertTrue(strong.intensity >= 0.8f)
        val mild = parse("Die Arbeit war heute bisschen anstrengend") as UserIntent.Emotion
        assertTrue(mild.intensity < 0.5f)
    }

    @Test fun mildMoodIsAConversationNotAnError() {
        val e = parse("Ganz gut, mein Tag war so lala, nicht so geil") as UserIntent.Emotion
        assertTrue(e.intensity < 0.5f)
        assertTrue(parse("naja geht so") is UserIntent.Emotion)
    }

    @Test fun followUpsAreNotMusicCommands() {
        // Einwuerfe landen im Gespraechs-Fallback, nicht bei einem Musikbefehl.
        assertEquals(UserIntent.Chat(com.mp.player.ai.ChatKind.REALLY), parse("echt?"))
        assertEquals(UserIntent.Chat(com.mp.player.ai.ChatKind.WHY), parse("Warum bro"))
    }
}
