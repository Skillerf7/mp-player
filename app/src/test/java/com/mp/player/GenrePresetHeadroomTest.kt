package com.mp.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bass-Regler, Sub-Shelf, PARAM-2-Shelf und 32 Baender stapeln sich. Diese Tests stellen sicher, dass
 * die Genre-Presets nach Preamp + Auto-Aussteuerung nicht deutlich ueber 0 dB landen (Uebersteuern).
 * Referenz: "Poweramp Druck" liegt bei ~ +4,4 dB Netto (bewusst druckvoll).
 */
class GenrePresetHeadroomTest {
    private val genrePresets = listOf("Deep Bass", "Subbass", "Hardtekk", "Tekk", "Techno", "Frenchcore", "Rap")

    private fun netDb(name: String): Double {
        val d = Eq.BUILT_IN.getValue(name)
        return Eq.peakBoostDb(d) + d.pre + Eq.autoComp(d)
    }

    @Test
    fun genrePresetsExistWith32Bands() {
        for (n in genrePresets) {
            assertTrue("Preset fehlt: $n", n in Eq.BUILT_IN)
            assertEquals(32, Eq.BUILT_IN.getValue(n).bands.size)
        }
    }

    @Test
    fun genrePresetsStayWithinHeadroom() {
        for (n in genrePresets) {
            val net = netDb(n)
            assertTrue("$n: Netto-Anhebung ${"%.1f".format(net)} dB zu hoch", net <= 3.0)
        }
    }

    @Test
    fun genrePresetsNotHotterThanPowerampReference() {
        val ref = netDb("Poweramp Druck")
        for (n in genrePresets) assertTrue("$n heisser als Referenz", netDb(n) <= ref)
    }
}
