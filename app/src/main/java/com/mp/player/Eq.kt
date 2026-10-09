package com.mp.player

import kotlin.math.*

/**
 * 32-Band-Equalizer: Band-Layout, Filterliste fuer die DSP, Frequenzgang fuer die Kurvenanzeige und
 * integrierte Presets. Die DSP (DspProcessor) und die UI (EqScreen) nutzen dieselbe Filterliste
 * ([specs]) - die angezeigte Kurve ist also genau das, was gerechnet wird.
 */
object Eq {
    const val BAND_COUNT = 32
    const val F_MIN = 20.0
    const val F_MAX = 20000.0
    const val DEFAULT_Q = 4.3f   // ~1/3 Oktave: bei 32 Baendern ueber 10 Oktaven liegen die Baender ~0,32 Oktaven auseinander
    const val MAX_GAIN = 12f

    fun centerFreq(i: Int): Float = (F_MIN * (F_MAX / F_MIN).pow(i / (BAND_COUNT - 1.0))).toFloat()

    fun flatBands(): List<Band> = List(BAND_COUNT) { Band(centerFreq(it), 0f, DEFAULT_Q) }

    // --- Bass / Hoehen: Regler wirken staerker als der reine dB-Wert, Boost-Schalter addieren ---
    fun bassGain(c: Dsp): Float = (if (c.bass >= 0f) c.bass * 1.5f else c.bass) + if (c.bassBoost) 10f else 0f
    fun trebleGain(c: Dsp): Float = c.treble + if (c.trebleBoost) 8f else 0f

    /** Wenn Bass/Hoehen stark angehoben werden, sinkt der Gesamtpegel automatisch (weniger Limiter-Pumpen, keine Mitten-Wand). */
    fun autoComp(c: Dsp): Double = -0.10 * (max(0f, bassGain(c)) + max(0f, trebleGain(c)))

    /** Alle Filter der Kette: [Art (0 Peak, 1 Low-Shelf, 2 High-Shelf), Frequenz, Gain dB, Q]. */
    fun specs(c: Dsp): List<DoubleArray> {
        val bg = bassGain(c)
        return c.bands.map { doubleArrayOf(0.0, it.f.toDouble(), it.g.toDouble(), it.q.toDouble().coerceAtLeast(0.1)) } + listOf(
            doubleArrayOf(1.0, 200.0, bg.toDouble(), 1.0),
            doubleArrayOf(0.0, 55.0, (max(0f, bg) * 0.4f).toDouble(), 0.9), // Sub-Punch
            doubleArrayOf(2.0, 7900.0, trebleGain(c).toDouble(), 1.0)
        )
    }

    /** Gesamter Frequenzgang (dB, ohne Preamp) an den gegebenen Frequenzen. */
    fun curve(c: Dsp, freqs: DoubleArray, fs: Double = 48000.0): DoubleArray {
        val out = DoubleArray(freqs.size)
        for (s in specs(c)) {
            if (abs(s[2]) < 0.05) continue
            val bq = Bq()
            bq.set(s[0].toInt(), s[1], s[2], s[3], fs)
            for (i in freqs.indices) out[i] += bq.magDb(freqs[i], fs)
        }
        return out
    }

    /** Alter Stand mit anderer Bandzahl -> 32 Baender mit moeglichst gleichem Klang. */
    fun resampleTo32(old: List<Band>): List<Band> {
        val freqs = DoubleArray(BAND_COUNT) { centerFreq(it).toDouble() }
        val db = curve(Dsp(bands = old, bass = 0f, treble = 0f), freqs)
        return List(BAND_COUNT) {
            Band(centerFreq(it), ((db[it].toFloat().coerceIn(-MAX_GAIN, MAX_GAIN)) * 10f).roundToInt() / 10f, DEFAULT_Q)
        }
    }

    /** Stuetzpunkte (Frequenz, Gain) auf die 32 Baender abbilden (linear in dB ueber log-Frequenz). */
    fun fromAnchors(anchorF: List<Float>, gains: List<Float>): List<Band> = List(BAND_COUNT) { i ->
        val f = centerFreq(i)
        val g = when {
            f <= anchorF.first() -> gains.first()
            f >= anchorF.last() -> gains.last()
            else -> {
                val k = anchorF.indexOfLast { it <= f }
                val t = (ln(f / anchorF[k]) / ln(anchorF[k + 1] / anchorF[k])).toFloat()
                gains[k] + (gains[k + 1] - gains[k]) * t
            }
        }
        Band(f, (g * 10f).roundToInt() / 10f, DEFAULT_Q)
    }

    private val ANCHORS_10 = listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    private val ANCHORS_5 = listOf(60f, 230f, 910f, 3600f, 14000f)

    private fun tenBand(g: List<Float>, mix: Float = 0f, size: Float = 0.5f): Dsp {
        val peak = max(0f, g.maxOrNull() ?: 0f)
        return Dsp(
            pre = -((peak * 0.5f) * 2f).roundToInt() / 2f,
            bands = fromAnchors(ANCHORS_10, g),
            reverbMix = mix, reverbSize = size
        )
    }

    private fun legacy(
        bass: Float = 0f, treble: Float = 0f, pre: Float = 0f, bassBoost: Boolean = false,
        g: List<Float> = listOf(0f, 0f, 0f, 0f, 0f), mix: Float = 0f, size: Float = 0.5f
    ) = Dsp(
        pre = pre, bands = fromAnchors(ANCHORS_5, g), bass = bass, treble = treble,
        bassBoost = bassBoost, reverbMix = mix, reverbSize = size
    )

    /** Integrierte Presets (jeder Wert wird beim Auswaehlen echt in die DSP uebernommen). */
    val BUILT_IN: Map<String, Dsp> = linkedMapOf(
        "Flat" to Dsp(),
        "Rock" to tenBand(listOf(4.5f, 3.5f, 2.5f, 1f, -0.5f, -1f, 1f, 2.5f, 3.5f, 4.5f)),
        "Pop" to tenBand(listOf(-1.5f, -1f, 0f, 2f, 3.5f, 3.5f, 2f, 0f, -1f, -1.5f)),
        "Classical" to tenBand(listOf(0f, 0f, 0f, 0f, 0f, 0f, -3f, -3f, -3f, -4f)),
        "Dance" to tenBand(listOf(5f, 4f, 2f, 0f, 0f, -2f, -3f, -3f, 0f, 1f)),
        "Electronic" to tenBand(listOf(4f, 3.5f, 1f, 0f, -2f, 2f, 1f, 1.5f, 3.5f, 4f)),
        "Hip-Hop" to tenBand(listOf(5f, 4.5f, 1.5f, 3f, -1f, -1f, 1.5f, -0.5f, 2f, 3f)),
        "Vocal" to tenBand(listOf(-2f, -3f, -3f, 1.5f, 3.5f, 3.5f, 3.5f, 2f, 0f, -1f)),
        "Bass Boost" to tenBand(listOf(6f, 5.5f, 4.5f, 2.5f, 1f, 0f, 0f, 0f, 0f, 0f)),
        "Bass" to legacy(bass = 6f, pre = -3f),
        "Bass extrem" to legacy(bass = 9f, bassBoost = true, pre = -6f),
        "Höhen" to legacy(treble = 6f, pre = -2f),
        "Bass & Höhen" to legacy(bass = 5f, treble = 5f, pre = -4f),
        "Klar" to legacy(treble = 2f, g = listOf(-1f, 0f, 1f, 3f, 2f)),
        "Kleiner Raum" to legacy(mix = 0.25f, size = 0.3f),
        "Halle" to legacy(mix = 0.4f, size = 0.8f)
    )
}
