package com.mp.player

import org.json.JSONObject
import kotlin.math.*

/**
 * Tiefgehende lokale Songanalyse (DSP, kein Cloud-ML).
 * Läuft parallel zur Basis-Analyse im Decoder-Loop.
 *
 * MEASURED = direkt aus Signal; ESTIMATED = algorithmische Schätzung.
 * Keine Instrumenten-/Genre-Garantie.
 */
data class DeepSnapshot(
    val version: Int = DeepAnalysis.VERSION,
    // MEASURED spectral bands (energy share 0..1)
    val bandSub: Float = 0f,      // <60
    val bandBass: Float = 0f,     // 60-120
    val bandLowMid: Float = 0f,   // 120-400
    val bandMid: Float = 0f,      // 400-2k
    val bandHighMid: Float = 0f,  // 2k-6k
    val bandPresence: Float = 0f, // 6k-10k
    val bandBrilliance: Float = 0f, // 10k-16k
    val bandAir: Float = 0f,      // >16k
    val spectralBandwidthHz: Float = 0f,
    val brightness: Float = 0f,   // high/(mid+eps) normalized
    // rhythm / dynamics MEASURED-ish
    val onsetDensity: Float = 0f, // onsets per second
    val punchIndex: Float = 0f,   // transient strength 0..1
    val silenceRatio: Float = 0f,
    val crestFactorDb: Float = 0f,
    val dynamicVariation: Float = 0f, // 0..1
    val energySlope: Float = 0f,  // late - intro, -1..1
    val dropJump: Float = 0f,     // max relative energy jump 0..1
    val grooveScore: Float = 0f,  // ESTIMATED from regularity + onset
    // ESTIMATED tonality
    val keyGuess: String = "",    // e.g. "Am?" or ""
    val keyConfidence: Float = 0f,
    val harmonicity: Float = 0f,  // 0..1 ESTIMATED
    val vocalPresence: Float = 0f, // ESTIMATED mid-presence bias, not singer ID
    val frames: Int = 0,
    val quality: Float = 0f       // overall analysis quality 0..1
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("v", version)
        put("bandSub", bandSub.toDouble()); put("bandBass", bandBass.toDouble())
        put("bandLowMid", bandLowMid.toDouble()); put("bandMid", bandMid.toDouble())
        put("bandHighMid", bandHighMid.toDouble()); put("bandPresence", bandPresence.toDouble())
        put("bandBrilliance", bandBrilliance.toDouble()); put("bandAir", bandAir.toDouble())
        put("bwHz", spectralBandwidthHz.toDouble()); put("brightness", brightness.toDouble())
        put("onsetDens", onsetDensity.toDouble()); put("punch", punchIndex.toDouble())
        put("silence", silenceRatio.toDouble()); put("crestDb", crestFactorDb.toDouble())
        put("dynVar", dynamicVariation.toDouble()); put("eSlope", energySlope.toDouble())
        put("dropJump", dropJump.toDouble()); put("groove", grooveScore.toDouble())
        put("key", keyGuess); put("keyConf", keyConfidence.toDouble())
        put("harm", harmonicity.toDouble()); put("vocal", vocalPresence.toDouble())
        put("frames", frames); put("quality", quality.toDouble())
    }

    companion object {
        fun fromJson(s: String?): DeepSnapshot? {
            if (s.isNullOrBlank()) return null
            return try {
                val o = JSONObject(s)
                DeepSnapshot(
                    version = o.optInt("v", 1),
                    bandSub = o.optDouble("bandSub").toFloat(),
                    bandBass = o.optDouble("bandBass").toFloat(),
                    bandLowMid = o.optDouble("bandLowMid").toFloat(),
                    bandMid = o.optDouble("bandMid").toFloat(),
                    bandHighMid = o.optDouble("bandHighMid").toFloat(),
                    bandPresence = o.optDouble("bandPresence").toFloat(),
                    bandBrilliance = o.optDouble("bandBrilliance").toFloat(),
                    bandAir = o.optDouble("bandAir").toFloat(),
                    spectralBandwidthHz = o.optDouble("bwHz").toFloat(),
                    brightness = o.optDouble("brightness").toFloat(),
                    onsetDensity = o.optDouble("onsetDens").toFloat(),
                    punchIndex = o.optDouble("punch").toFloat(),
                    silenceRatio = o.optDouble("silence").toFloat(),
                    crestFactorDb = o.optDouble("crestDb").toFloat(),
                    dynamicVariation = o.optDouble("dynVar").toFloat(),
                    energySlope = o.optDouble("eSlope").toFloat(),
                    dropJump = o.optDouble("dropJump").toFloat(),
                    grooveScore = o.optDouble("groove").toFloat(),
                    keyGuess = o.optString("key", ""),
                    keyConfidence = o.optDouble("keyConf").toFloat(),
                    harmonicity = o.optDouble("harm").toFloat(),
                    vocalPresence = o.optDouble("vocal").toFloat(),
                    frames = o.optInt("frames"),
                    quality = o.optDouble("quality").toFloat()
                )
            } catch (_: Exception) { null }
        }
    }
}

object DeepAnalysis {
    const val VERSION = 1
    const val MODULE = "DeepDSP-v1"
}

/**
 * Streaming deep meter – FFT frames like SpectralMeter, plus envelope stats.
 */
class DeepMeter(private val inputSampleRate: Int) {
    private val targetSr = 22050
    private val hop = 512
    private val nFft = 1024
    private val buf = FloatArray(nFft)
    private var bufN = 0
    private var resFrac = 0.0
    private var prev = 0.0
    private val ratio = inputSampleRate.toDouble() / targetSr
    private val win = FloatArray(nFft) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / nFft)).toFloat()
    }
    private val re = FloatArray(nFft)
    private val im = FloatArray(nFft)

    private val bandSum = DoubleArray(8)
    private var sumBw = 0.0
    private var sumBright = 0.0
    private var sumHarm = 0.0
    private var frames = 0

    private var silentFrames = 0
    private var totalEnvFrames = 0
    private var prevEnv = 0.0
    private var onsetCount = 0
    private var punchAcc = 0.0
    private val rmsWindows = ArrayList<Double>(512)
    private var windowSq = 0.0
    private var windowN = 0
    private val windowSize = targetSr / 2 // ~0.5s

    // chroma 12
    private val chroma = DoubleArray(12)
    private var samplesSeen = 0L

    fun push(mono: Double) {
        samplesSeen++
        // resample to targetSr
        resFrac += 1.0
        while (resFrac >= ratio) {
            resFrac -= ratio
            val x = mono
            // envelope / onset on resampled stream approx using mono every push is ok too
            val env = abs(x)
            totalEnvFrames++
            if (env < 0.008) silentFrames++
            val delta = env - prevEnv
            if (delta > 0.04 && env > 0.05) {
                onsetCount++
                punchAcc += delta
            }
            prevEnv = env
            windowSq += x * x
            windowN++
            if (windowN >= windowSize) {
                rmsWindows += sqrt(windowSq / windowN)
                windowSq = 0.0
                windowN = 0
            }

            buf[bufN++] = x.toFloat()
            if (bufN >= hop) {
                if (bufN >= nFft) {
                    processFrame()
                    // hop
                    System.arraycopy(buf, hop, buf, 0, nFft - hop)
                    bufN = nFft - hop
                } else {
                    // need full fft buffer - accumulate until nFft
                }
            }
            prev = x
        }
        // simpler: fill fft buffer without complex resampler for stability
    }

    /** Call with mono at original rate – internal decimation. */
    fun pushSample(mono: Double) {
        // Decimate by taking every `ratio` sample approximately
        resFrac += 1.0
        if (resFrac < ratio) return
        resFrac -= ratio
        val x = mono
        val env = abs(x)
        totalEnvFrames++
        if (env < 0.008) silentFrames++
        val delta = env - prevEnv
        if (delta > 0.045 && env > 0.04) {
            onsetCount++
            punchAcc += delta.coerceAtMost(1.0)
        }
        prevEnv = env * 0.85 + prevEnv * 0.15
        windowSq += x * x
        windowN++
        if (windowN >= windowSize) {
            if (rmsWindows.size < 400) rmsWindows += sqrt(windowSq / windowN.coerceAtLeast(1))
            windowSq = 0.0
            windowN = 0
        }
        buf[bufN++] = x.toFloat()
        if (bufN >= nFft) {
            processFrame()
            System.arraycopy(buf, hop, buf, 0, nFft - hop)
            bufN = nFft - hop
        }
    }

    private fun processFrame() {
        for (i in 0 until nFft) {
            re[i] = buf[i] * win[i]
            im[i] = 0f
        }
        fft(re, im)
        val sr = targetSr.toDouble()
        val nBins = nFft / 2
        var total = 1e-12
        var cent = 0.0
        var cent2 = 0.0
        val mag = DoubleArray(nBins)
        for (k in 1 until nBins) {
            val m = sqrt(re[k].toDouble() * re[k] + im[k].toDouble() * im[k])
            mag[k] = m
            total += m
            val f = k * sr / nFft
            cent += f * m
            cent2 += f * f * m
        }
        val centroid = cent / total
        val bw = sqrt(max(0.0, cent2 / total - centroid * centroid))
        sumBw += bw

        val b = DoubleArray(8)
        for (k in 1 until nBins) {
            val f = k * sr / nFft
            val m = mag[k]
            val bi = when {
                f < 60 -> 0
                f < 120 -> 1
                f < 400 -> 2
                f < 2000 -> 3
                f < 6000 -> 4
                f < 10000 -> 5
                f < 16000 -> 6
                else -> 7
            }
            b[bi] += m
        }
        for (i in 0 until 8) bandSum[i] += b[i] / total
        val mid = (b[3] + b[4]) / total
        val high = (b[5] + b[6] + b[7]) / total
        sumBright += high / (mid + 0.05)

        // peakiness / harmonicity proxy
        var peak = 0.0
        for (k in 1 until nBins) if (mag[k] > peak) peak = mag[k]
        val peakiness = (peak / (total / nBins + 1e-12)).coerceIn(0.0, 20.0) / 20.0
        sumHarm += peakiness

        // crude chroma: map bins to pitch class
        for (k in 2 until nBins) {
            val f = k * sr / nFft
            if (f < 80 || f > 5000) continue
            val midi = 69.0 + 12.0 * ln(f / 440.0) / ln(2.0)
            if (midi.isNaN()) continue
            val pc = ((midi.roundToInt() % 12) + 12) % 12
            chroma[pc] += mag[k]
        }
        frames++
    }

    fun result(peakDb: Float, rmsDb: Float, rhythmReg: Float, eIntro: Float, eLate: Float): DeepSnapshot {
        if (frames == 0 && totalEnvFrames == 0) return DeepSnapshot()
        val n = max(frames, 1).toDouble()
        fun band(i: Int) = (bandSum[i] / n).toFloat().coerceIn(0f, 1f)
        val silence = if (totalEnvFrames > 0) silentFrames.toFloat() / totalEnvFrames else 0f
        val durSec = totalEnvFrames.toFloat() / targetSr
        val onsetDens = if (durSec > 0.5f) onsetCount / durSec else 0f
        val punch = if (onsetCount > 0) (punchAcc / onsetCount).toFloat().coerceIn(0f, 1f) else 0f
        var dynVar = 0f
        if (rmsWindows.size >= 3) {
            val mean = rmsWindows.average()
            var v = 0.0
            for (x in rmsWindows) v += (x - mean) * (x - mean)
            val sd = sqrt(v / rmsWindows.size)
            dynVar = (sd / (mean + 1e-6)).toFloat().coerceIn(0f, 1f)
        }
        val slope = (eLate - eIntro).coerceIn(-1f, 1f)
        // drop jump from consecutive rms windows
        var drop = 0.0
        for (i in 1 until rmsWindows.size) {
            val a = rmsWindows[i - 1] + 1e-6
            val j = (rmsWindows[i] - rmsWindows[i - 1]) / a
            if (j > drop) drop = j
        }
        val crest = if (!peakDb.isNaN() && !rmsDb.isNaN()) (peakDb - rmsDb).coerceAtLeast(0f) else 0f
        val groove = (0.55f * rhythmReg + 0.45f * (onsetDens / 8f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val harm = (sumHarm / n).toFloat().coerceIn(0f, 1f)
        val bright = (sumBright / n).toFloat().coerceIn(0f, 3f) / 3f

        // key estimate
        var maxC = 0.0
        var maxI = 0
        var sumC = 1e-12
        for (i in 0 until 12) {
            sumC += chroma[i]
            if (chroma[i] > maxC) {
                maxC = chroma[i]
                maxI = i
            }
        }
        val keyNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val conf = (maxC / sumC).toFloat().coerceIn(0f, 1f)
        val key = if (conf >= 0.12f && frames >= 8) keyNames[maxI] + "?" else ""

        val vocal = ((band(3) + band(4) + band(5)) / 3f).coerceIn(0f, 1f) // ESTIMATED presence band

        val quality = when {
            frames >= 40 && durSec >= 20f -> 0.9f
            frames >= 15 && durSec >= 8f -> 0.7f
            frames >= 5 -> 0.45f
            else -> 0.25f
        }

        return DeepSnapshot(
            bandSub = band(0), bandBass = band(1), bandLowMid = band(2), bandMid = band(3),
            bandHighMid = band(4), bandPresence = band(5), bandBrilliance = band(6), bandAir = band(7),
            spectralBandwidthHz = (sumBw / n).toFloat(),
            brightness = bright,
            onsetDensity = onsetDens.coerceIn(0f, 20f),
            punchIndex = punch,
            silenceRatio = silence.coerceIn(0f, 1f),
            crestFactorDb = crest,
            dynamicVariation = dynVar,
            energySlope = slope,
            dropJump = drop.toFloat().coerceIn(0f, 3f) / 3f,
            grooveScore = groove,
            keyGuess = key,
            keyConfidence = conf,
            harmonicity = harm,
            vocalPresence = vocal,
            frames = frames,
            quality = quality
        )
    }

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
            val half = len shr 1
            val ang = -2.0 * PI / len
            val wlr = cos(ang).toFloat()
            val wli = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wr = 1f
                var wi = 0f
                for (k in 0 until half) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + half] * wr - im[i + k + half] * wi
                    val vi = re[i + k + half] * wi + im[i + k + half] * wr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + half] = ur - vr
                    im[i + k + half] = ui - vi
                    val nwr = wr * wlr - wi * wli
                    wi = wr * wli + wi * wlr
                    wr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
