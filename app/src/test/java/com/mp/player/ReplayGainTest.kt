package com.mp.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayGainTest {

    private fun analysis(lufs: Float, peakDb: Float) =
        AnalysisResult("u", 0L, 0f, lufs, peakDb, -20f, 44100, 2, false)

    @Test
    fun parsesId3AndVorbisForms() {
        val id3 = ReplayGain.parseTag("TXXX: description=REPLAYGAIN_TRACK_GAIN: values=[-7.20 dB]")
        assertNotNull(id3)
        assertEquals(0, id3!!.first)
        assertEquals(-7.2f, id3.second, 0.001f)

        val vorbis = ReplayGain.parseTag("VC: REPLAYGAIN_ALBUM_GAIN=+3,5 dB")
        assertNotNull(vorbis)
        assertEquals(1, vorbis!!.first)
        assertEquals(3.5f, vorbis.second, 0.001f)
    }

    @Test
    fun ignoresPeakAndOtherTags() {
        assertNull(ReplayGain.parseTag("TXXX: description=REPLAYGAIN_TRACK_PEAK: values=[0.98]"))
        assertNull(ReplayGain.parseTag("APIC: mimeType=image/jpeg"))
    }

    @Test
    fun modeOff_isAlwaysZero() {
        assertEquals(0f, ReplayGain.resolve(0, -5f, -4f, analysis(-10f, -1f), true), 0f)
    }

    @Test
    fun tagWinsOverAnalysis_andAlbumFallsBackToTrack() {
        assertEquals(-5f, ReplayGain.resolve(1, -5f, -4f, analysis(-10f, -1f), true), 0.001f)
        assertEquals(-4f, ReplayGain.resolve(2, -5f, -4f, null, false), 0.001f)
        assertEquals(-5f, ReplayGain.resolve(2, -5f, null, null, false), 0.001f)
    }

    @Test
    fun analysisFallback_targetsMinus18_andRespectsPeak() {
        // lauter Titel: -18 - (-9) = -9 dB
        assertEquals(-9f, ReplayGain.resolve(1, null, null, analysis(-9f, -0.5f), true), 0.001f)
        // leiser Titel mit hohem Peak: Anhebung auf -peak begrenzt (Peak -1 dBFS -> max +1 dB)
        assertEquals(1f, ReplayGain.resolve(1, null, null, analysis(-24f, -1f), true), 0.001f)
        // ohne Fallback und ohne Tag: unveraendert
        assertEquals(0f, ReplayGain.resolve(1, null, null, analysis(-24f, -1f), false), 0f)
    }

    @Test
    fun gainIsClamped() {
        assertEquals(-20f, ReplayGain.clamp(-40f), 0f)
        assertEquals(6f, ReplayGain.clamp(15f), 0f)
    }
}
