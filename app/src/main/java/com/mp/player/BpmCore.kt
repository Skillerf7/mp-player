package com.mp.player

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Reine BPM-Schätzlogik (ohne Android). Test- und Benchmark-fähig.
 * Input: Onset-Hüllkurve (bereits heruntergerechnet, z.B. 400 Hz).
 */
object BpmCore {
    data class Estimate(
        val bpm: Float,
        val confidence: Float,
        val candidates: List<Pair<Float, Float>>, // bpm to relative score
        val source: String
    )

    /**
     * @param env log-energy envelope samples
     * @param rate envelope sample rate (e.g. 400)
     * @param userHint optional known BPM for this track (personal correction) – only used as soft prior
     */
    fun estimate(env: FloatArray, n: Int, rate: Int = 400, userHint: Float? = null): Estimate {
        if (n < rate * 15) return Estimate(0f, 0f, emptyList(), "too_short")
        val o = FloatArray(n)
        for (i in 1 until n) o[i] = max(0f, env[i] - env[i - 1])
        val start = if (n > rate * 40) rate * 8 else 0
        val usable = n - start
        if (usable < rate * 12) return Estimate(0f, 0f, emptyList(), "too_short")
        var mean = 0.0
        for (i in start until n) mean += o[i]
        mean /= usable
        for (i in start until n) o[i] = (o[i] - mean).toFloat()

        fun r(lag: Int): Double {
            var s = 0.0
            val m = usable - lag
            if (m <= 0) return 0.0
            for (i in 0 until m) s += o[start + i] * o[start + i + lag]
            return s / m
        }
        val r0 = r(0)
        if (r0 <= 0) return Estimate(0f, 0f, emptyList(), "no_energy")

        val minLag = rate * 60 / 200
        val maxLag = rate * 60 / 60
        val rs = DoubleArray(maxLag + 3)
        for (l in (minLag / 2).coerceAtLeast(1)..maxLag + 1) rs[l] = r(l)

        data class Peak(val lag: Int, val score: Double)
        val peaks = ArrayList<Peak>()
        for (l in minLag..maxLag) {
            if (rs[l] >= rs[l - 1] && rs[l] >= rs[l + 1] && rs[l] / r0 >= 0.02) peaks.add(Peak(l, rs[l]))
        }
        peaks.sortByDescending { it.score }
        if (peaks.isEmpty()) {
            var best = minLag
            for (l in minLag..maxLag) if (rs[l] > rs[best]) best = l
            if (rs[best] / r0 < 0.03) return Estimate(0f, 0f, emptyList(), "no_beat")
            peaks.add(Peak(best, rs[best]))
        }

        data class Cand(val bpm: Double, val score: Double)
        val cands = ArrayList<Cand>()
        for (p in peaks.take(5)) {
            val a = rs.getOrElse(p.lag - 1) { 0.0 }
            val b = p.score
            val c = rs.getOrElse(p.lag + 1) { 0.0 }
            val den = a - 2 * b + c
            val lag = if (den < -1e-12) p.lag + 0.5 * (a - c) / den else p.lag.toDouble()
            val bpm0 = rate * 60.0 / lag
            fun add(bpm: Double, factor: Double) {
                if (bpm in 55.0..210.0) cands.add(Cand(bpm, p.score * factor))
            }
            add(bpm0, 1.0)
            add(bpm0 * 2, 0.92)
            add(bpm0 / 2, 0.88)
        }
        if (cands.isEmpty()) return Estimate(0f, 0f, emptyList(), "no_candidates")

        fun prior(bpm: Double): Double {
            // Breite Musikalische Range – Hardtekk 160–180 nicht abstrafen.
            // 85–95 oft Half-Time von 170–190 → leichter Abzug ohne userHint.
            var p = when {
                bpm in 120.0..180.0 -> 1.08
                bpm in 90.0..155.0 -> 1.0
                bpm in 70.0..200.0 -> 0.95
                bpm in 55.0..210.0 -> 0.85
                else -> 0.7
            }
            if (userHint != null && userHint > 0f) {
                val d = kotlin.math.abs(bpm - userHint)
                if (d < 3) p *= 1.4
                else if (d < 8) p *= 1.18
            }
            return p
        }

        var ranked = cands
            .map { Cand(it.bpm, it.score * prior(it.bpm)) }
            .sortedByDescending { it.score }

        // Half/Double: wenn Top-2 etwa Faktor 2 und Scores nah, bevorzuge den Kandidaten
        // mit stärkerem ACF-Peak (höherer Roh-Score vor Prior) im 120–185-Bereich.
        if (ranked.size >= 2) {
            val a = ranked[0].bpm
            val b = ranked[1].bpm
            val hi = maxOf(a, b)
            val lo = minOf(a, b)
            if (lo > 55 && kotlin.math.abs(hi / lo - 2.0) < 0.08) {
                val scoreA = ranked[0].score
                val scoreB = ranked[1].score
                if (scoreB >= scoreA * 0.92 && hi in 130.0..195.0 && lo in 65.0..100.0) {
                    // Bevorzuge die schnellere Interpretation (typisch Hardtekk/Rave)
                    ranked = ranked.sortedByDescending { c ->
                        when {
                            kotlin.math.abs(c.bpm - hi) < 3 -> c.score * 1.12
                            else -> c.score
                        }
                    }
                }
            }
        }

        val best = ranked.first()
        val second = ranked.getOrNull(1)?.score ?: 0.0
        val ratio = if (best.score > 0) (best.score - second) / best.score else 0.0
        val strength = (best.score / r0).coerceIn(0.0, 1.0)
        var conf = (0.45 * strength + 0.55 * ratio.coerceIn(0.0, 1.0)).toFloat().coerceIn(0f, 1f)
        // Bei Half/Double-Ambiguität Konfidenz dämpfen
        if (ranked.size >= 2) {
            val a = ranked[0].bpm
            val b = ranked[1].bpm
            val hi = maxOf(a, b); val lo = minOf(a, b)
            if (lo > 55 && kotlin.math.abs(hi / lo - 2.0) < 0.1) conf = (conf * 0.75f).coerceIn(0f, 1f)
        }
        if (strength < 0.025) return Estimate(0f, 0f, emptyList(), "weak")

        val source = when {
            userHint != null && kotlin.math.abs(best.bpm - userHint) < 5 -> "analysis+user_hint"
            else -> "analysis_v2"
        }
        val top = ranked.take(5).map { (it.bpm.toFloat() * 10).toInt() / 10f to it.score.toFloat() }
        return Estimate(((best.bpm * 10).toInt() / 10f), conf, top, source)
    }

    /** Synthetische Hüllkurve mit klaren Beats (für Offline-Benchmark). */
    fun syntheticEnvelope(bpm: Float, seconds: Int = 40, rate: Int = 400): FloatArray {
        val n = seconds * rate
        val env = FloatArray(n)
        val period = rate * 60.0 / bpm
        for (i in 0 until n) {
            val phase = (i % period) / period
            // impulse-like onset each beat
            val onset = if (phase < 0.08) 1.0 else 0.05
            env[i] = ln(1.0 + 100.0 * onset).toFloat()
        }
        return env
    }
}
