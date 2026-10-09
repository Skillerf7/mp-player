package com.mp.player

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectralMeterTest {
    @Test fun pureToneHasLowFlatnessAndCentroidNearFrequency() {
        val sr = 44100
        val m = SpectralMeter(sr)
        val freq = 1000.0
        // ~2 seconds
        for (i in 0 until sr * 2) {
            val s = 0.5 * sin(2 * PI * freq * i / sr)
            m.push(s)
        }
        val r = m.result()
        assertTrue("frames=${r.frames}", r.frames > 10)
        // Centroid should be in ballpark of 1 kHz (windowing/leakage)
        assertTrue("centroid=${r.centroidHz}", r.centroidHz in 400f..2500f)
        assertTrue("flatness=${r.flatness}", r.flatness < 0.5f)
    }

    @Test fun silenceYieldsZeroFramesOrZeroEnergy() {
        val m = SpectralMeter(44100)
        for (i in 0 until 44100) m.push(0.0)
        val r = m.result()
        // either no frames accumulated as energy or near-zero bands
        assertTrue(r.frames == 0 || (r.bass + r.mid + r.high) < 0.01f || r.centroidHz == 0f)
    }

    @Test fun analysisResultDefaultsCompatible() {
        val a = AnalysisResult("u", 0L, 120f, -14f, -1f, -12f, 44100, 2, false)
        assertTrue(a.bpmConfidence == 0f)
        assertTrue(a.featureVersion >= 1)
        assertTrue(a.dynamicRangeDb() >= 0f)
    }
}
