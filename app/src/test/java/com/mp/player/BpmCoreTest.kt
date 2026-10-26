package com.mp.player

import org.junit.Assert.assertTrue
import org.junit.Test

class BpmCoreTest {
    @Test
    fun syntheticBeatNearTempo() {
        val rate = 400
        val bpm = 128.0
        val n = rate * 40
        val env = FloatArray(n)
        val period = (rate * 60.0 / bpm)
        for (i in 0 until n) {
            val phase = (i % period) / period
            env[i] = if (phase < 0.08) 1.2f else 0.05f
        }
        val est = BpmCore.estimate(env, n, rate)
        assertTrue("got ${est.bpm}", est.bpm in 115f..145f)
        assertTrue(est.confidence > 0.15f)
    }
}
