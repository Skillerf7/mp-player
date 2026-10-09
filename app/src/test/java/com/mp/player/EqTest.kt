package com.mp.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EqTest {

    @Test
    fun flatBands_are32_andLogSpaced() {
        val b = Eq.flatBands()
        assertEquals(32, b.size)
        assertEquals(20f, b.first().f, 0.01f)
        assertEquals(20000f, b.last().f, 1f)
        for (i in 1 until b.size) assertTrue(b[i].f > b[i - 1].f)
    }

    @Test
    fun flatDsp_hasFlatResponse() {
        val freqs = doubleArrayOf(30.0, 100.0, 1000.0, 5000.0, 15000.0)
        val c = Eq.curve(Dsp(), freqs)
        c.forEach { assertEquals(0.0, it, 0.01) }
    }

    @Test
    fun singleBoostedBand_peaksAtItsCenter() {
        val bands = Eq.flatBands().toMutableList()
        bands[15] = bands[15].copy(g = 6f)
        val d = Dsp(bands = bands)
        val f = bands[15].f.toDouble()
        val c = Eq.curve(d, doubleArrayOf(f, f / 8, f * 8))
        assertEquals(6.0, c[0], 0.3)
        assertTrue("weit entfernt kaum Wirkung", abs(c[1]) < 0.7 && abs(c[2]) < 0.7)
    }

    @Test
    fun fromAnchors_hitsEndpointsAndInterpolates() {
        val bands = Eq.fromAnchors(listOf(100f, 10000f), listOf(0f, 10f))
        assertEquals(0f, bands.first().g, 0.11f)      // unter dem ersten Stuetzpunkt: Wert des ersten
        assertEquals(10f, bands.last().g, 0.11f)      // ueber dem letzten: Wert des letzten
        assertTrue(bands.zipWithNext().all { (a, b) -> b.g >= a.g - 0.11f }) // monoton steigend
    }

    @Test
    fun resampleTo32_keepsTheSound() {
        val old = listOf(Band(1000f, 6f, 1f))
        val out = Eq.resampleTo32(old)
        assertEquals(32, out.size)
        val nearest = out.minByOrNull { abs(it.f - 1000f) }!!
        assertTrue("Band nahe 1 kHz bleibt deutlich angehoben", nearest.g > 3f)
        assertTrue("Band bei 20 Hz bleibt ~0", abs(out.first().g) < 0.5f)
    }

    @Test
    fun builtInPresets_haveRequiredNames() {
        listOf("Flat", "Rock", "Pop", "Classical", "Dance", "Electronic", "Hip-Hop", "Vocal", "Bass Boost")
            .forEach { assertTrue("Preset fehlt: $it", it in Eq.BUILT_IN) }
        assertTrue(Eq.BUILT_IN.getValue("Rock").bands.size == 32)
    }

    @Test
    fun bassBoost_addsGainAndLowersLevel() {
        val plain = Dsp(bass = 6f)
        val boosted = Dsp(bass = 6f, bassBoost = true)
        assertTrue(Eq.bassGain(boosted) > Eq.bassGain(plain))
        assertTrue(Eq.autoComp(boosted) < Eq.autoComp(plain))
    }
}
