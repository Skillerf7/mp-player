package com.mp.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Regression: der Limiter muss das Signal NACH dem Hall begrenzen.
 * Vorher wurde der Spitzenpegel vor dem Hall gemessen, der Hall kam danach obendrauf (Ausgabe konnte weit ueber 1.0 gehen).
 */
class LimiterTest {

    private fun peakOut(cfg: Dsp, amp: Double): Double {
        val p = DspProcessor()
        p.floatOutput = true
        p.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT))
        p.flush()
        p.cfg = cfg
        var peak = 0.0
        var n = 0
        repeat(30) { // 30 x 0,1 s = 3 s, damit sich der Hall aufbaut
            val frames = 4800
            val inp = ByteBuffer.allocate(frames * 2 * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until frames) {
                val v = (amp * sin(2 * PI * 440.0 * n++ / 48000.0)).toFloat()
                inp.putFloat(v); inp.putFloat(v)
            }
            inp.flip()
            p.queueInput(inp)
            val out = p.getOutput().order(ByteOrder.nativeOrder())
            while (out.remaining() >= 4) peak = max(peak, abs(out.getFloat().toDouble()))
        }
        return peak
    }

    @Test
    fun reverbDoesNotPushOutputOverLimiterCeiling() {
        // Eingang 0,9 liegt unter der Limiter-Schwelle (0,98), der Hall (Mix 1, gross) addiert aber deutlich Pegel.
        val peak = peakOut(Dsp(on = true, limiter = true, reverbMix = 1f, reverbSize = 1f), 0.9)
        assertTrue("Ausgabe-Peak $peak ueber Limiter-Grenze", peak <= 0.99)
    }

    @Test
    fun limiterOff_doesNotLimit() {
        // Gegenprobe: ohne Limiter treibt der Hall das Signal bis an die harte Grenze (writeSample begrenzt auf +-1.0 = Clipping),
        // sonst misst der Test oben nichts.
        val peak = peakOut(Dsp(on = true, limiter = false, reverbMix = 1f, reverbSize = 1f), 0.9)
        assertTrue("erwartet Vollaussteuerung (Clipping) ohne Limiter, war $peak", peak >= 0.999)
    }
}
