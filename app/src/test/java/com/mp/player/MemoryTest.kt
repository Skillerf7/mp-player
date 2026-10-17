package com.mp.player

import com.mp.player.ai.ConversationState
import com.mp.player.ai.InMemoryStore
import com.mp.player.ai.MemKind
import com.mp.player.ai.MemScope
import com.mp.player.ai.MemoryEngine
import com.mp.player.ai.MemoryExtractor
import com.mp.player.ai.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTest {
    private var now = 1_000_000_000_000L
    private val engine = MemoryEngine(InMemoryStore()) { now }
    private fun say(s: String) = MemoryExtractor.extract(s).forEach { engine.remember(it) }

    @Test fun longTermLikeVsTodayOnly() {
        val like = MemoryExtractor.extract("Ich liebe Techno, das höre ich eigentlich immer").first()
        assertEquals(MemScope.LONG, like.scope); assertTrue(like.confidence >= 0.85f)
        val today = MemoryExtractor.extract("Heute hätte ich gerne Techno").first()
        assertEquals(MemScope.EPHEMERAL, today.scope); assertTrue(today.confidence < 0.6f)
    }

    @Test fun singleMentionIsNotCertain() {
        val c = MemoryExtractor.extract("Ich mag Hardtekk").first()
        assertEquals(MemKind.LIKE, c.kind); assertEquals("hardtekk", c.key)
        assertTrue(c.confidence < 0.7f)
    }

    @Test fun bassKeyIgnoresFillers() {
        val c = MemoryExtractor.extract("Ich mag eigentlich viel Bass").first()
        assertEquals("bass", c.key); assertEquals("viel Bass", c.label)
    }

    @Test fun todayDislikeExpires() {
        say("Ich mag Hardtekk"); say("Heute aber keinen Hardtekk")
        assertEquals(1, engine.active(MemKind.DISLIKE, 0.5f).size)
        now += 20 * 3_600_000L
        assertTrue(engine.active(MemKind.DISLIKE, 0.5f).isEmpty())
        assertEquals(1, engine.active(MemKind.LIKE, 0.3f).size) // die Vorliebe bleibt
    }

    @Test fun changingYourMindReplacesOldStatement() {
        say("Ich mag Schlager"); say("Ich mag keine Schlager")
        assertTrue(engine.active(MemKind.LIKE, 0.1f).isEmpty())
        assertEquals(1, engine.active(MemKind.DISLIKE, 0.1f).size)
    }

    @Test fun repetitionStrengthens() {
        say("Ich mag Hardtekk"); val first = engine.active(MemKind.LIKE).first().confidence
        say("Ich mag Hardtekk"); assertTrue(engine.active(MemKind.LIKE).first().confidence > first)
    }

    @Test fun weakStatementsFadeExplicitOnesStay() {
        say("Ich mag Hardtekk"); say("Merk dir, dass ich Klavier mag")
        now += 400L * 86_400_000L
        val keys = engine.active(MemKind.LIKE, 0.3f).map { it.key }
        assertFalse("hardtekk" in keys); assertTrue("klavier" in keys)
    }

    @Test fun forgetAndDescribe() {
        say("Ich mag Hardtekk"); say("Ich mag keine Schlager")
        val text = engine.describe()
        assertTrue(text.contains("Hardtekk")); assertTrue(text.contains("Schlager"))
        assertEquals(1, engine.forget("hardtekk"))
        assertFalse(engine.describe().contains("Hardtekk"))
        assertEquals(1, engine.forgetAll())
        assertTrue(engine.isEmpty())
    }

    @Test fun moodStatementsAreNotStored() {
        assertTrue(MemoryExtractor.extract("Heute keine traurigen Songs").isEmpty())
    }

    @Test fun contextSurvivesLongConversation() {
        val s = ConversationState { now }
        s.onEmotion(Mood.CALM, "Arbeit", true); s.onGoal(Mood.CALM)
        repeat(60) { s.note("nachricht $it") }
        assertTrue(s.recentCount() <= 30)
        assertEquals("Arbeit", s.topic); assertTrue(s.summary.contains("Arbeit"))
        assertEquals(Mood.CALM, s.musicMood())
    }
}
