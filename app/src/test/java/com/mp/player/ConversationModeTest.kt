
package com.mp.player

import com.mp.player.ai.ConversationMode
import com.mp.player.ai.ConversationState
import com.mp.player.ai.Mood
import com.mp.player.ai.ResponsePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationModeTest {
    @Test fun ventingSetsSupportMode() {
        val s = ConversationState()
        s.onEmotion(Mood.SAD, "Beziehung", true, 0.9f)
        assertEquals(ConversationMode.SUPPORT, s.mode)
        assertTrue(s.hasSharedContent)
        assertTrue(s.venting)
    }

    @Test fun alreadySaidDetected() {
        assertTrue(ResponsePlanner.isAlreadySaidPhrase("hab ich doch gesagt bro"))
        assertTrue(ResponsePlanner.isAlreadySaidPhrase("hab ich gesagt"))
        assertFalse(ResponsePlanner.isAlreadySaidPhrase("spiel rap"))
    }

    @Test fun talkAfterSharePlanIsPureChat() {
        val p = ResponsePlanner.plan(
            mode = ConversationMode.SUPPORT,
            venting = true,
            alreadyShared = true,
            userAskedForChat = true,
            userAlreadySaidThat = false
        )
        assertTrue(p.pureChat)
        assertEquals(ResponsePlanner.Kind.LISTEN, p.kind)
    }

    @Test fun alreadySaidPlanAcknowledges() {
        val p = ResponsePlanner.plan(
            ConversationMode.SUPPORT, true, true, true, true
        )
        assertEquals(ResponsePlanner.Kind.ACKNOWLEDGE, p.kind)
    }
}
