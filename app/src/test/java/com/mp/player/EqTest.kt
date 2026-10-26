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

    @Test
    fun bassKnob_boostsPositiveByFactor() {
        assertEquals(0f, Eq.bassGain(Dsp(bass = 0f)), 0.01f)
        // positive Regler wirken x1,5 (Planner/Assistant rechnen in effektiven dB), Absenkung 1:1
        assertEquals(4.5f, Eq.bassGain(Dsp(bass = 3f)), 0.01f)
        assertEquals(-4f, Eq.bassGain(Dsp(bass = -4f)), 0.01f)
        // Obergrenze des effektiven Bass-Gains
        assertEquals(Eq.BASS_MAX_EFFECTIVE_DB, Eq.bassGain(Dsp(bass = 15f)), 0.01f)
    }

    @Test
    fun powerampTone_independentFrom32Bands() {
        val flat = Dsp(bass = 6f, treble = 4f)
        val specs = Eq.specs(flat)
        // 32 peaks + 5 tone filters (Bass, Sub, Punch, Hoehen, Air) + 2 PARAM-Shelves
        assertTrue(specs.size >= 39)
        val lowShelf = specs.first { it[0] == 1.0 }
        val highShelf = specs.first { it[0] == 2.0 }
        assertEquals(Eq.BASS_SHELF_HZ, lowShelf[1], 0.1)
        assertEquals(Eq.TREBLE_SHELF_HZ, highShelf[1], 0.1)
        assertEquals(9.0, lowShelf[2], 0.1)   // Regler 6 -> effektiv 9 dB
        assertEquals(4.0, highShelf[2], 0.1)
        // 32 Band-Gains bleiben 0
        assertTrue(flat.bands.all { kotlin.math.abs(it.g) < 0.01f })
    }

    @Test
    fun trebleAndBass_independentInSpecs() {
        val onlyBass = Eq.specs(Dsp(bass = 6f, treble = 0f))
        val onlyTreble = Eq.specs(Dsp(bass = 0f, treble = 6f))
        val bassShelf = onlyBass.first { it[0] == 1.0 }
        val trebleShelf = onlyTreble.first { it[0] == 2.0 }
        assertTrue(bassShelf[2] > 5.0)
        assertTrue(abs(onlyBass.first { it[0] == 2.0 }[2]) < 0.1)
        assertTrue(trebleShelf[2] > 5.0)
        assertTrue(abs(onlyTreble.first { it[0] == 1.0 }[2]) < 0.1)
    }

    @Test
    fun autoComp_engagesWithPositiveBoost() {
        assertEquals(0.0, Eq.autoComp(Dsp()), 0.001)
        assertTrue(Eq.autoComp(Dsp(bass = 8f)) < -0.5)
    }

    @Test
    fun bass_reachesDeepAndHasPunch() {
        val c = Eq.curve(Dsp(bass = 6f), doubleArrayOf(30.0, 50.0, 80.0, 100.0, 1000.0))
        assertTrue("tiefer Bass (30 Hz) kraeftig", c[0] > 10.0)
        assertTrue("50 Hz kraeftig", c[1] > 10.0)
        assertTrue("80 Hz (Kick) deutlich >= 9 dB", c[2] > 9.0)
        assertTrue("100 Hz noch klar angehoben", c[3] > 6.5)
        assertTrue("Mitten bleiben unberuehrt", abs(c[4]) < 0.2)
    }

    @Test
    fun treble_isAirySoftAndNotShrill() {
        val c = Eq.curve(Dsp(treble = 6f), doubleArrayOf(500.0, 4000.0, 6000.0, 10000.0, 14000.0))
        assertTrue("500 Hz unberuehrt", abs(c[0]) < 0.2)
        assertTrue("4 kHz (Schaerfe/Zischen) bleibt ruhig", abs(c[1]) < 0.5)
        assertTrue("6 kHz nur sanft", c[2] in 0.0..2.0)
        assertTrue("10 kHz klar hoerbar", c[3] > 4.5)
        assertTrue("14 kHz Luft", c[4] > 6.0)
    }

    @Test
    fun subPunchAndAir_onlyOnPositiveGain() {
        val cut = Eq.specs(Dsp(bass = -6f, treble = -6f))
        val sub = cut.first { it[0] == 1.0 && it[1] == Eq.SUB_SHELF_HZ }
        val punch = cut.first { it[0] == 0.0 && it[1] == Eq.PUNCH_HZ }
        val air = cut.first { it[0] == 2.0 && it[1] == Eq.AIR_SHELF_HZ }
        assertEquals(0.0, sub[2], 0.001)
        assertEquals(0.0, punch[2], 0.001)
        assertEquals(0.0, air[2], 0.001)
    }

    @Test
    fun paramBlock_pluginLowShelfFromPowerampStack() {
        // Poweramp-Stack: Low-Shelf 100 Hz, +7 dB, Q 1,03
        val c = Eq.curve(Dsp(pLowDb = 7f), doubleArrayOf(40.0, 60.0, 1000.0))
        assertTrue(c[0] > 6.5 && c[1] > 6.5)
        assertTrue(abs(c[2]) < 0.3)
        val d = Dsp()
        assertEquals(100f, d.pLowHz, 0.001f)
        assertEquals(1.03f, d.pLowQ, 0.001f)
        assertEquals(8000f, d.pHighHz, 0.001f)
        assertEquals(0.83f, d.pHighQ, 0.001f)
        val sp = Eq.specs(Dsp(pLowDb = 7f, pHighDb = 6.2f))
        assertTrue(sp.any { it[0] == 1.0 && it[1] == 100.0 && abs(it[2] - 7.0) < 0.01 && abs(it[3] - 1.03) < 0.01 })
        assertTrue(sp.any { it[0] == 2.0 && it[1] == 8000.0 && abs(it[2] - 6.2) < 0.01 && abs(it[3] - 0.83) < 0.01 })
    }

    @Test
    fun shelfQ_isUsed() {
        val soft = Bq().also { it.set(1, 100.0, 9.0, 0.5, 48000.0) }
        val sharp = Bq().also { it.set(1, 100.0, 9.0, 1.5, 48000.0) }
        // hoeheres Q = steilerer Uebergang: bei 250 Hz (oberhalb der Ecke) liegt der Shelf naeher an 0 dB
        assertTrue(abs(sharp.magDb(250.0, 48000.0)) != abs(soft.magDb(250.0, 48000.0)))
        // Plateau unten bleibt der volle Gain
        assertEquals(9.0, soft.magDb(20.0, 48000.0), 0.6)
        assertEquals(9.0, sharp.magDb(20.0, 48000.0), 0.6)
    }

    @Test
    fun autoComp_followsMeasuredPeak_andRespectsManualPreamp() {
        assertEquals(0.0, Eq.autoComp(Dsp()), 0.001)
        val plain = Eq.autoComp(Dsp(bass = 6f))
        assertTrue("Bass 6 -> deutliche Absenkung", plain < -8.0 && plain >= -16.0)
        // bereits manuell abgesenkter Preamp zaehlt mit: keine doppelte Kompensation
        assertTrue(Eq.autoComp(Dsp(bass = 6f, pre = -8f)) > plain + 5.0)
        // nur Absenkungen -> kein Eingriff
        assertTrue(Eq.autoComp(Dsp(bass = -6f, treble = -6f)) > -0.5)
        // nie staerker als die Untergrenze
        assertEquals(-16.0, Eq.autoComp(Dsp(bass = 15f, bassBoost = true)), 0.001)
    }

    @Test
    fun powerampDruckPreset_matchesVideoStack() {
        val d = Eq.BUILT_IN.getValue("Poweramp Druck")
        assertEquals(7f, d.pLowDb, 0.001f)
        assertEquals(6.2f, d.pHighDb, 0.001f)
        // Poweramp fuhr im Video PreAmp -12,3 dB: gleiche Groessenordnung
        assertTrue(Eq.autoComp(d) in -13.5..-10.0)
    }
}
