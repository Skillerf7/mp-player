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
    /** Poweramp Tone: max +15 dB Boost (ISO Bass/Treble). */
    const val TONE_MAX_DB = 15f
    /** Bass-Tone: breiter Low-Shelf fuer Koerper/Kick (80-150 Hz). */
    const val BASS_SHELF_HZ = 130.0
    /** Tiefer Bass: zusaetzlicher Sub-Shelf (nur bei positivem Bass). */
    const val SUB_SHELF_HZ = 50.0
    const val SUB_SHARE = 0.45f
    /** Druck: kleiner Peak im Kick-Bereich (nur bei positivem Bass). */
    const val PUNCH_HZ = 75.0
    const val PUNCH_Q = 1.0
    const val PUNCH_SHARE = 0.20f
    /** Hoehen-Tone: High-Shelf wie Poweramp-PARAM (8 kHz, Q 0,83) - nicht schrill, aber luftig. */
    const val TREBLE_SHELF_HZ = 8000.0
    const val TREBLE_SHELF_Q = 0.83
    /** Air: dezenter Zusatz-Shelf (nur bei positiven Hoehen). */
    const val AIR_SHELF_HZ = 14000.0
    const val AIR_SHARE = 0.20f
    /** Q fuer Sub-/Air-Shelf (breit, musikalisch). */
    const val TONE_SHELF_Q = 0.707
    /** Positive Bass-Reglerwerte wirken x1,5 (der EqPlanner/Assistant rechnet in effektiven dB). */
    const val BASS_KNOB_FACTOR = 1.5f
    /** Obergrenze des effektiven Bass-Shelf-Gains (Regler + Boost). */
    const val BASS_MAX_EFFECTIVE_DB = 20f

    // PARAM 2 - Standardwerte aus dem Poweramp-Stack (Low-Shelf 100 Hz / Q 1,03, High-Shelf 8 kHz / Q 0,83)
    const val PARAM_LOW_HZ = 100f
    const val PARAM_LOW_Q = 1.03f
    const val PARAM_HIGH_HZ = 8000f
    const val PARAM_HIGH_Q = 0.83f

    // Auto-Aussteuerung: Preamp wird um 95 % der gemessenen Spitzenanhebung gesenkt (Messung mit bass-lastiger Musik: Limiter 0 %, Lautheit +-0 dB; bei 85 % lag er bei 12 %)
    private const val AUTO_COMP_SHARE = 0.95
    private const val AUTO_COMP_MIN_DB = -16.0

    fun centerFreq(i: Int): Float = (F_MIN * (F_MAX / F_MIN).pow(i / (BAND_COUNT - 1.0))).toFloat()

    fun flatBands(): List<Band> = List(BAND_COUNT) { Band(centerFreq(it), 0f, DEFAULT_Q) }

    /**
     * Poweramp Bass/Treble (Knowledge Base + Forum):
     * - **eigene** Filter, unabhängig von den 32 EQ-Bändern
     * - klassisch **nur Boost** (0 = keine Änderung; Absenkung optional über negativen Regler bei uns)
     * - Bass: Low-Shelf 130 Hz + Sub 50 Hz + Punch 75 Hz · Treble: High-Shelf 8 kHz + Air 14 kHz
     * - bassBoost/trebleBoost = zusätzlicher Punch/Air wie starker Tone-Anschlag (+3 dB)
     *
     * Mapping: positive Bass-Regler x1,5 (effektiv max. [BASS_MAX_EFFECTIVE_DB]), Höhen und Absenkung 1:1.
     */
    fun bassGain(c: Dsp): Float {
        val v = c.bass.coerceIn(-TONE_MAX_DB, TONE_MAX_DB)
        // Positive Regler wirken x1,5, Absenkung 1:1. Boost-Schalter addiert +3 dB.
        val knob = if (v >= 0f) v * BASS_KNOB_FACTOR else v
        return (knob + if (c.bassBoost) 3f else 0f).coerceAtMost(BASS_MAX_EFFECTIVE_DB)
    }

    fun trebleGain(c: Dsp): Float {
        val v = c.treble.coerceIn(-TONE_MAX_DB, TONE_MAX_DB)
        return v + if (c.trebleBoost) 3f else 0f
    }

    /**
     * Preamp-Senkung bei Tone-Boost – verhindert Clipping zusammen mit dem Limiter
     * (Poweramp: Preamp manuell; wir auto-kompensieren leicht).
     */
    fun autoComp(c: Dsp): Double {
        val peak = peakBoostDb(c)
        if (peak < 0.5) return 0.0
        // Manuell gesetzter negativer Preamp zaehlt als schon vorhandene Absenkung (keine doppelte Kompensation)
        val manual = max(0.0, -c.pre.toDouble())
        return -max(0.0, AUTO_COMP_SHARE * peak - manual).coerceAtMost(-AUTO_COMP_MIN_DB)
    }

    /**
     * Groesste Anhebung der gesamten Filterkette im Musikbereich 40 Hz - 12 kHz (dB, >= 0).
     * Darunter/darueber liegt kaum Pegel; so wird nicht "untersteuert" und der Limiter hat wenig zu tun.
     */
    fun peakBoostDb(c: Dsp, fs: Double = 48000.0): Double {
        val sp = specs(c).filter { abs(it[2]) >= 0.05 }
        if (sp.isEmpty()) return 0.0
        val filters = sp.map { s -> Bq().also { it.set(s[0].toInt(), s[1], s[2], s[3], fs) } }
        var peak = 0.0
        for (i in 0 until 48) {
            val f = 40.0 * (12000.0 / 40.0).pow(i / 47.0)
            var sum = 0.0
            for (b in filters) sum += b.magDb(f, fs)
            if (sum > peak) peak = sum
        }
        return peak
    }

    /**
     * Filterkette (Poweramp-Stack: PreAmp > EQ > PARAM 2 > TON > LMT):
     * 1) 32 Peaks (EQ)
     * 2) TON Bass: Low-Shelf 130 Hz + Sub-Shelf 50 Hz + Punch-Peak 75 Hz (die beiden letzten nur bei Anhebung)
     * 3) TON Höhen: High-Shelf 8 kHz (Q 0,83) + Air-Shelf 14 kHz (nur bei Anhebung)
     * 4) PARAM 2: Low-Shelf (Standard 100 Hz, Q 1,03) und High-Shelf (Standard 8 kHz, Q 0,83)
     *
     * [Art: 0=Peak, 1=Low-Shelf, 2=High-Shelf, Hz, dB, Q]
     */
    fun specs(c: Dsp): List<DoubleArray> {
        val bg = bassGain(c)
        val tg = trebleGain(c)
        val bands = c.bands.map {
            doubleArrayOf(0.0, it.f.toDouble(), it.g.toDouble(), it.q.toDouble().coerceAtLeast(0.1))
        }
        val tone = listOf(
            // Bass: breiter Low-Shelf (Koerper) - bleibt der erste Low-Shelf der Liste
            doubleArrayOf(1.0, BASS_SHELF_HZ, bg.toDouble(), TONE_SHELF_Q),
            // Tiefer Bass + Druck: nur bei positivem Bass (Absenkung bleibt sauber)
            doubleArrayOf(1.0, SUB_SHELF_HZ, (max(0f, bg) * SUB_SHARE).toDouble(), TONE_SHELF_Q),
            doubleArrayOf(0.0, PUNCH_HZ, (max(0f, bg) * PUNCH_SHARE).toDouble(), PUNCH_Q),
            // Hoehen: High-Shelf 8 kHz / Q 0,83 + dezente Air
            doubleArrayOf(2.0, TREBLE_SHELF_HZ, tg.toDouble(), TREBLE_SHELF_Q),
            doubleArrayOf(2.0, AIR_SHELF_HZ, (max(0f, tg) * AIR_SHARE).toDouble(), TONE_SHELF_Q)
        )
        // PARAM 2 (Poweramp-Stack): frei einstellbare Shelf-Baender, nach dem Tone-Block
        val param = listOf(
            doubleArrayOf(1.0, c.pLowHz.toDouble().coerceIn(20.0, 1000.0), c.pLowDb.toDouble(), c.pLowQ.toDouble().coerceIn(0.2, 4.0)),
            doubleArrayOf(2.0, c.pHighHz.toDouble().coerceIn(1000.0, 20000.0), c.pHighDb.toDouble(), c.pHighQ.toDouble().coerceIn(0.2, 4.0))
        )
        return bands + tone + param
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
        // Poweramp-Stack aus realem A/B (Secret vs Poweramp): Low-Shelf +7 dB @100 Hz Q1.03,
        // High-Shelf +6.2 dB @8 kHz Q0.83, Tone Bass/Höhen, extra Mitten-Cut damit Bass punchy bleibt
        "Poweramp Druck" to Dsp(
            bass = 3f,
            treble = 2f,
            bassBoost = true,
            pLowDb = 7f,
            pHighDb = 6.2f,
            bands = fromAnchors(
                listOf(40f, 70f, 110f, 200f, 350f, 600f, 1200f, 3000f, 6000f, 12000f),
                listOf(1.5f, 2.5f, 3f, 0f, -4f, -6f, -5f, -1f, 1f, 2f)
            )
        ),
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
        // Genre-Bass-Presets: kontrollierter Headroom (Preamp negativ), kein Clipping-Wahn
        // Werte so gewaehlt, dass Spitzenanhebung <= ~20 dB bleibt und der Auto-Preamp (max -16 dB)
        // sie voll ausgleicht (Netto ~ +1 dB) - Bass-Regler, Sub-Shelf, PARAM-2 und Baender stapeln sich!
        "Deep Bass" to Dsp(
            bass = 3f, bassBoost = true, pre = -4f, limiter = true,
            pLowDb = 3.5f,
            bands = fromAnchors(
                listOf(35f, 55f, 80f, 120f, 250f, 500f, 1200f, 3000f, 6000f, 12000f),
                listOf(3f, 3.5f, 2.5f, 1f, -1f, -2f, -1f, 0f, 0.5f, 1f)
            )
        ),
        "Subbass" to Dsp(
            bass = 3f, bassBoost = true, pre = -5f, limiter = true,
            pLowDb = 3.5f,
            bands = fromAnchors(
                listOf(30f, 45f, 65f, 100f, 200f, 400f, 1000f, 2500f, 6000f, 12000f),
                listOf(4f, 3.5f, 2f, 0.5f, -2f, -3f, -1.5f, 0f, 0f, 0.5f)
            )
        ),
        "Hardtekk" to Dsp(
            bass = 3.5f, treble = 3f, bassBoost = true, pre = -5f, limiter = true,
            pLowDb = 4.5f, pHighDb = 3f,
            bands = fromAnchors(
                listOf(40f, 60f, 90f, 150f, 300f, 600f, 1500f, 3500f, 7000f, 12000f),
                listOf(3f, 4f, 2.5f, 0f, -3f, -4f, -2f, 1f, 3f, 3.5f)
            )
        ),
        "Tekk" to Dsp(
            bass = 3.5f, treble = 2.5f, bassBoost = true, pre = -4f, limiter = true,
            pLowDb = 4.5f, pHighDb = 2.5f,
            bands = fromAnchors(
                listOf(40f, 70f, 110f, 200f, 400f, 800f, 1600f, 3500f, 7000f, 12000f),
                listOf(3f, 4f, 2f, -0.5f, -2.5f, -2f, -1f, 1.5f, 2.5f, 3f)
            )
        ),
        "Techno" to Dsp(
            bass = 3f, treble = 1.5f, pre = -3.5f, limiter = true,
            pLowDb = 4f,
            bands = fromAnchors(
                listOf(40f, 70f, 100f, 180f, 350f, 700f, 1400f, 3000f, 6000f, 11000f),
                listOf(2.5f, 3.5f, 2f, 0.5f, -1.5f, -1f, 0f, 1f, 1.5f, 2f)
            )
        ),
        "Frenchcore" to Dsp(
            bass = 4.5f, treble = 4f, bassBoost = true, pre = -6f, limiter = true,
            pLowDb = 5f, pHighDb = 4f,
            bands = fromAnchors(
                listOf(40f, 65f, 95f, 160f, 320f, 700f, 1800f, 4000f, 8000f, 13000f),
                listOf(3f, 4f, 2f, -1f, -3.5f, -3f, 0f, 2.5f, 4f, 4.5f)
            )
        ),
        "Rap" to Dsp(
            bass = 3f, treble = 1f, bassBoost = true, pre = -4.5f, limiter = true,
            pLowDb = 4f,
            bands = fromAnchors(
                listOf(40f, 60f, 90f, 150f, 300f, 600f, 1500f, 3000f, 6000f, 10000f),
                listOf(3f, 3.5f, 2.5f, 1.5f, -1f, -2f, 1.5f, 2f, 1f, 0.5f)
            )
        ),
        "Neutral" to Dsp(pre = 0f, limiter = true),
        "Kleiner Raum" to legacy(mix = 0.25f, size = 0.3f),
        "Halle" to legacy(mix = 0.4f, size = 0.8f)
    )
}
