package com.mp.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Leichte spektrale Deskriptoren aus Mono-PCM (kein Essentia, kein ML).
 * Wird während des bestehenden MediaCodec-Decode-Passes gefüttert.
 *
 * Features (alle 0..1 oder Hz wo angegeben):
 * - spectralCentroidHz, spectralRolloffHz
 * - spectralFlux (mittlere Onset-ähnliche spektrale Änderung)
 * - spectralFlatness (0 = tonal, 1 = rauschähnlich)
 * - bassEnergy, midEnergy, highEnergy (Anteil an Gesamtenergie)
 *
 * FFT-Größe 512 @ downsampled rate ~ 11025–22050 → ausreichend für LIGHT.
 */
class SpectralMeter(private val inputSampleRate: Int) {
    // Effektive Rate nach Downsampling – nicht hart 11025, sonst falsche Hz bei 48 kHz.
    private val hopFactor = max(1, inputSampleRate / 11025)
    private val effectiveRate = inputSampleRate.toDouble() / hopFactor
    private var hopCount = 0
    private var acc = 0.0

    private val nFft = 512
    private val window = FloatArray(nFft) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (nFft - 1))).toFloat()
    }
    private val frame = FloatArray(nFft)
    private var framePos = 0

    private var prevMag: FloatArray? = null
    private var frames = 0

    private var sumCentroid = 0.0
    private var sumRolloff = 0.0
    private var sumFlux = 0.0
    private var sumFlatness = 0.0
    private var sumBass = 0.0
    private var sumMid = 0.0
    private var sumHigh = 0.0

    /** Mono-Sample −1..1 */
    fun push(mono: Double) {
        acc += mono
        if (++hopCount < hopFactor) return
        val s = (acc / hopCount).toFloat()
        acc = 0.0
        hopCount = 0
        frame[framePos++] = s
        if (framePos >= nFft) {
            processFrame()
            // 50 % overlap
            val half = nFft / 2
            System.arraycopy(frame, half, frame, 0, half)
            framePos = half
        }
    }

    private fun processFrame() {
        val re = FloatArray(nFft)
        val im = FloatArray(nFft)
        for (i in 0 until nFft) {
            re[i] = frame[i] * window[i]
            im[i] = 0f
        }
        fft(re, im)
        val nBins = nFft / 2
        val mag = FloatArray(nBins)
        var total = 0.0
        for (k in 0 until nBins) {
            val m = sqrt(re[k] * re[k] + im[k] * im[k].toDouble()).toFloat()
            mag[k] = m
            total += m
        }
        if (total < 1e-8) {
            prevMag = mag
            return
        }

        val sr = effectiveRate
        // Centroid
        var num = 0.0
        for (k in 1 until nBins) {
            val f = k * sr / nFft
            num += f * mag[k]
        }
        val centroid = (num / total).toFloat()

        // Rolloff 85 %
        val thr = total * 0.85
        var cum = 0.0
        var rolloff = (nBins - 1) * sr / nFft
        for (k in 0 until nBins) {
            cum += mag[k]
            if (cum >= thr) {
                rolloff = k * sr / nFft
                break
            }
        }

        // Flatness (geometric / arithmetic mean of magnitude)
        var logSum = 0.0
        var arith = 0.0
        val eps = 1e-12
        for (k in 1 until nBins) {
            val m = mag[k] + eps
            logSum += ln(m)
            arith += m
        }
        val n = (nBins - 1).toDouble()
        val geo = Math.exp(logSum / n)
        val flat = (geo / (arith / n + eps)).toFloat().coerceIn(0f, 1f)

        // Flux
        var flux = 0.0
        val pm = prevMag
        if (pm != null) {
            for (k in 1 until nBins) {
                val d = mag[k] - pm[k]
                if (d > 0) flux += d
            }
            flux /= total
        }
        prevMag = mag

        // Band energies: bass < 250, mid 250–4000, high > 4000
        var bass = 0.0
        var mid = 0.0
        var high = 0.0
        for (k in 1 until nBins) {
            val f = k * sr / nFft
            when {
                f < 250 -> bass += mag[k]
                f < 4000 -> mid += mag[k]
                else -> high += mag[k]
            }
        }
        val inv = 1.0 / total
        sumCentroid += centroid
        sumRolloff += rolloff
        sumFlux += flux
        sumFlatness += flat
        sumBass += bass * inv
        sumMid += mid * inv
        sumHigh += high * inv
        frames++
    }

    fun result(): SpectralSnapshot {
        if (frames == 0) return SpectralSnapshot()
        val n = frames.toDouble()
        return SpectralSnapshot(
            centroidHz = (sumCentroid / n).toFloat(),
            rolloffHz = (sumRolloff / n).toFloat(),
            flux = (sumFlux / n).toFloat(),
            flatness = (sumFlatness / n).toFloat(),
            bass = (sumBass / n).toFloat().coerceIn(0f, 1f),
            mid = (sumMid / n).toFloat().coerceIn(0f, 1f),
            high = (sumHigh / n).toFloat().coerceIn(0f, 1f),
            frames = frames
        )
    }

    /** In-place radix-2 FFT (n must be power of 2). */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wlenRe = cos(ang).toFloat()
            val wlenIm = sin(ang).toFloat()
            var i0 = 0
            while (i0 < n) {
                var wr = 1f
                var wi = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i0 + k]
                    val uIm = im[i0 + k]
                    val vRe = re[i0 + k + len / 2] * wr - im[i0 + k + len / 2] * wi
                    val vIm = re[i0 + k + len / 2] * wi + im[i0 + k + len / 2] * wr
                    re[i0 + k] = uRe + vRe
                    im[i0 + k] = uIm + vIm
                    re[i0 + k + len / 2] = uRe - vRe
                    im[i0 + k + len / 2] = uIm - vIm
                    val nwr = wr * wlenRe - wi * wlenIm
                    wi = wr * wlenIm + wi * wlenRe
                    wr = nwr
                }
                i0 += len
            }
            len = len shl 1
        }
    }
}

data class SpectralSnapshot(
    val centroidHz: Float = 0f,
    val rolloffHz: Float = 0f,
    val flux: Float = 0f,
    val flatness: Float = 0f,
    val bass: Float = 0f,
    val mid: Float = 0f,
    val high: Float = 0f,
    val frames: Int = 0
)

enum class AnalysisLevel {
    /** Metadaten + LUFS/Peak/RMS + BPM + leichte Spektralfeatures. */
    LIGHT,
    /** + erweiterte Rhythmus-/Tonal-Features (später). */
    NORMAL,
    /** + optionales ML (MAEST etc.) – nicht in APK gebündelt ohne explizite Integration. */
    DEEP
}

object AnalysisVersions {
    /** Erhöhen, wenn Feature-Schema sich ändert → Cache-Invalidierung. */
    const val FEATURE_VERSION = 3
    const val BPM_VERSION = 2
    const val SPECTRAL_VERSION = 1
}
