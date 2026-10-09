package com.mp.player

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Zusätzliches lokales Analysemodul (kein Essentia/AGPL, kein Cloud).
 * Läuft im selben Decode-Pass wie LIGHT: Rhythmus-Regelmäßigkeit + Energieverlauf.
 * Ergebnisse fließen in AnalysisResult-Erweiterungsfelder / TrackSimilarity ein.
 */
object ExtendedAnalysis {
    const val MODULE_VERSION = 1
    const val MODULE_ID = "extended_local_v1"
}

/**
 * Misst rhythmische Regelmäßigkeit (0..1) und relative Energie in Intro/Mitte/Ende.
 * Fütterung: Mono-Samples und Onset-Hüllkurve parallel zum bestehenden Detector.
 */
class RhythmEnergyMeter(private val sampleRate: Int) {
    private val envRate = 50 // ~50 Hz energy envelope
    private val hop = max(1, sampleRate / envRate)
    private var hopI = 0
    private var acc = 0.0
    private var accSq = 0.0
    private val env = ArrayList<Float>(envRate * 320)
    private var nSamples = 0L

    fun push(mono: Double) {
        acc += abs(mono)
        accSq += mono * mono
        nSamples++
        if (++hopI >= hop) {
            env.add((acc / hop).toFloat())
            hopI = 0
            acc = 0.0
        }
    }

    fun result(): ExtendedSnapshot {
        if (env.size < envRate * 8) return ExtendedSnapshot()
        // regularity: variance of inter-onset intervals from simple peak picks
        val peaks = ArrayList<Int>()
        val thr = env.average() * 1.35
        for (i in 2 until env.size - 2) {
            if (env[i] > thr && env[i] >= env[i - 1] && env[i] >= env[i + 1]) peaks.add(i)
        }
        var regularity = 0f
        if (peaks.size >= 4) {
            val intervals = DoubleArray(peaks.size - 1) { (peaks[it + 1] - peaks[it]).toDouble() }
            val mean = intervals.average()
            if (mean > 1) {
                var varSum = 0.0
                for (v in intervals) varSum += (v - mean) * (v - mean)
                val cv = sqrt(varSum / intervals.size) / mean
                regularity = (1.0 - cv.coerceIn(0.0, 1.0)).toFloat()
            }
        }
        // energy thirds
        val n = env.size
        fun meanRange(a: Int, b: Int): Float {
            if (b <= a) return 0f
            var s = 0.0
            for (i in a until b) s += env[i]
            return (s / (b - a)).toFloat()
        }
        val e0 = meanRange(0, n / 3)
        val e1 = meanRange(n / 3, 2 * n / 3)
        val e2 = meanRange(2 * n / 3, n)
        val maxE = maxOf(e0, e1, e2, 1e-6f)
        val rms = if (nSamples > 0) sqrt(accSq / nSamples).toFloat() else 0f
        return ExtendedSnapshot(
            rhythmRegularity = regularity.coerceIn(0f, 1f),
            energyIntro = (e0 / maxE).coerceIn(0f, 1f),
            energyMid = (e1 / maxE).coerceIn(0f, 1f),
            energyLate = (e2 / maxE).coerceIn(0f, 1f),
            moduleVersion = ExtendedAnalysis.MODULE_VERSION
        )
    }
}

data class ExtendedSnapshot(
    val rhythmRegularity: Float = 0f,
    val energyIntro: Float = 0f,
    val energyMid: Float = 0f,
    val energyLate: Float = 0f,
    val moduleVersion: Int = 0
)
