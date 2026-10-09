package com.mp.player

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * ReplayGain / Lautstaerke-Normalisierung.
 *  1. ReplayGain-Tag in der Datei (ID3 TXXX, Vorbis/FLAC-Kommentar, MP4-Tags) -> wird vom Player beim Abspielen gelesen.
 *  2. Kein Tag: optional die selbst gemessene Lautheit (Audioanalyse, BS.1770) -> Ziel -18 LUFS (ReplayGain 2.0).
 * Der Gain wird in der DSP (DspProcessor.replayGainDb) angewendet, in [-20, +6] dB begrenzt; bei Analysewerten
 * wird zusaetzlich so begrenzt, dass der gemessene Spitzenpegel nicht ueber 0 dBFS steigt.
 */
object ReplayGain {
    const val TARGET_LUFS = -18f
    private val RE = Regex("REPLAYGAIN_(TRACK|ALBUM)_GAIN\\D*?(-?\\d+(?:[.,]\\d+)?)", RegexOption.IGNORE_CASE)

    /** Parst die Textform eines Metadaten-Eintrags. @return (0 = Titel / 1 = Album) zu dB, oder null. */
    fun parseTag(entryText: String): Pair<Int, Float>? {
        val m = RE.find(entryText) ?: return null
        val kind = if (m.groupValues[1].equals("TRACK", true)) 0 else 1
        val v = m.groupValues[2].replace(',', '.').toFloatOrNull() ?: return null
        return kind to v
    }

    fun clamp(db: Float): Float = max(-20f, min(6f, db))

    /** Gain aus der Analyse (Ziel -18 LUFS), begrenzt durch den Spitzenpegel. null, wenn nicht messbar. */
    fun fromAnalysis(a: AnalysisResult?): Float? {
        if (a == null || a.lufs.isNaN()) return null
        var g = TARGET_LUFS - a.lufs
        if (g > 0f) g = min(g, -a.peakDb) // nie ueber 0 dBFS anheben
        return clamp(g)
    }

    /** @param mode 0 aus, 1 Titel, 2 Album */
    fun resolve(mode: Int, trackTag: Float?, albumTag: Float?, analysis: AnalysisResult?, fallback: Boolean): Float {
        if (mode == 0) return 0f
        val tag = if (mode == 2) (albumTag ?: trackTag) else (trackTag ?: albumTag)
        if (tag != null) return clamp(tag)
        if (fallback) fromAnalysis(analysis)?.let { return it }
        return 0f
    }

    @Suppress("unused")
    private fun db(x: Double) = 20 * log10(x)
}
