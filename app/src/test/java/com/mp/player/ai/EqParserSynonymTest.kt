package com.mp.player.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bass-Synonyme und "zu schwach" (Master-Prompt Teil 5.1). Eingaben sind bereits normalisiert (klein, ASCII). */
class EqParserSynonymTest {

    private fun kinds(text: String): List<EqKind> = EqParser.extract(text)?.cmds?.map { it.kind } ?: emptyList()

    @Test fun moreSub() = assertEquals(listOf(EqKind.BASS_UP), kinds("mehr sub"))

    @Test fun moreLowEnd() {
        assertEquals(listOf(EqKind.BASS_UP), kinds("mehr low-end"))
        assertEquals(listOf(EqKind.BASS_UP), kinds("mehr low end"))
    }

    @Test fun beatMoreFoundation() = assertEquals(listOf(EqKind.BASS_UP), kinds("gib dem beat mehr fundament"))

    @Test fun lowsShouldPushHarder() = assertEquals(listOf(EqKind.BASS_UP), kinds("die tiefen sollen staerker druecken"))

    @Test fun bassTooWeakMeansMore() = assertEquals(listOf(EqKind.BASS_UP), kinds("der bass ist zu schwach"))

    @Test fun tooLittleBassMeansMore() = assertEquals(listOf(EqKind.BASS_UP), kinds("zu wenig bass"))

    @Test fun tooMuchBassStillMeansLess() = assertEquals(listOf(EqKind.BASS_DOWN), kinds("zu viel bass"))

    @Test fun lessBassStillMeansLess() = assertEquals(listOf(EqKind.BASS_DOWN), kinds("weniger bass"))

    @Test fun deepConversationsAreNotAnEqCommand() {
        assertNull(EqParser.extract("tiefen entspannung"))
        assertNull(EqParser.extract("ich mag tiefe gespraeche"))
    }

    @Test fun volumeComplaintAloneIsNotAnEqCommand() {
        assertTrue(kinds("die musik ist zu leise").isEmpty())
    }
}
