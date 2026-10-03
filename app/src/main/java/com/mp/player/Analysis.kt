package com.mp.player

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Audioanalyse als Hintergrundjob (nur auf Wunsch gestartet, niedrige Prioritaet, jederzeit abbrechbar).
 * Gemessen wird am dekodierten Signal (MediaCodec) - nichts wird geraten:
 *  - Lautheit: integrierte Lautheit nach ITU-R BS.1770 (K-Gewichtung, 400-ms-Bloecke, Gating -70 LUFS / -10 LU)
 *  - Spitzenpegel (Sample-Peak, dBFS) und RMS (dBFS)
 *  - BPM: Onset-Huellkurve + Autokorrelation (70-200 BPM). Das ist eine SCHAETZUNG; bei Musik ohne klaren Beat
 *    oder mit Tempowechseln kann sie falsch liegen oder fehlen (dann steht dort "–").
 * Lange Titel: nur die ersten 5 Minuten (Markierung "partial").
 */
object Analyzer {
    const val MAX_SECONDS = 300

    fun analyze(ctx: Context, t: Track, cancelled: () -> Boolean): AnalysisResult? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, Uri.parse(t.uri), null)
            var idx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { idx = i; fmt = f; break }
            }
            if (idx < 0 || fmt == null) return null
            ex.selectTrack(idx)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val maxUs = MAX_SECONDS * 1_000_000L
            var limited = false
            var pcmFloat = false
            var meter: LoudnessMeter? = null
            var bpmDet: BpmDetector? = null
            var peak = 0.0
            var sumSq = 0.0
            var nSamples = 0L
            val info = MediaCodec.BufferInfo()
            var inEos = false
            var outEos = false
            var idle = 0

            while (!outEos) {
                if (cancelled()) return null
                if (!inEos) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)
                        val n = if (buf != null) ex.readSampleData(buf, 0) else -1
                        if (n < 0 || ex.sampleTime > maxUs) {
                            if (n >= 0) limited = true
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi >= 0 -> {
                        idle = 0
                        val ob = codec.getOutputBuffer(oi)
                        if (ob != null && info.size > 0) {
                            if (meter == null) { meter = LoudnessMeter(sr, ch); bpmDet = BpmDetector(sr) }
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            ob.order(ByteOrder.nativeOrder())
                            val bytes = if (pcmFloat) 4 else 2
                            val frames = ob.remaining() / (bytes * ch)
                            val fr = DoubleArray(ch)
                            for (i in 0 until frames) {
                                var mono = 0.0
                                for (c in 0 until ch) {
                                    val v = if (pcmFloat) ob.getFloat().toDouble() else ob.getShort() / 32768.0
                                    fr[c] = v
                                    mono += v
                                    val a = abs(v)
                                    if (a > peak) peak = a
                                    sumSq += v * v
                                }
                                nSamples += ch
                                meter!!.push(fr)
                                bpmDet!!.push(mono / ch)
                            }
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                    }
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        meter = null; bpmDet = null // Format steht fest, bevor Daten kommen
                    }
                    else -> if (inEos && ++idle > 300) outEos = true // Decoder liefert nichts mehr -> Sicherung gegen Haengen
                }
            }
            val m = meter ?: return null
            val rmsDb = if (nSamples > 0 && sumSq > 0) 10 * log10(sumSq / nSamples) else -120.0
            val peakDb = if (peak > 0) 20 * log10(peak) else -120.0
            return AnalysisResult(
                uri = t.uri, modified = t.dateModified,
                bpm = bpmDet?.bpm() ?: 0f,
                lufs = m.integrated().toFloat(),
                peakDb = peakDb.toFloat(), rmsDb = rmsDb.toFloat(),
                sampleRate = sr, channels = ch, partial = limited
            )
        } catch (e: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (ignored: Exception) { }
            try { codec?.release() } catch (ignored: Exception) { }
            try { ex.release() } catch (ignored: Exception) { }
        }
    }
}

/** ITU-R BS.1770-4: K-Gewichtung (Hochton-Shelf + Hochpass), 400-ms-Bloecke mit 75 % Ueberlappung, zweistufiges Gating. */
private class LoudnessMeter(private val fs: Int, private val ch: Int) {
    private class Bq(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        var z1 = 0.0; var z2 = 0.0
        fun run(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    private val shelf = Array(ch) { shelfBq() }
    private val hp = Array(ch) { hpBq() }
    private val weight = DoubleArray(ch) { c -> if (ch >= 6 && c == 3) 0.0 else if (c >= 4) 1.41 else 1.0 } // LFE aus, Surround +1,5 dB
    private val acc = DoubleArray(ch)
    private val blockN = max(1, fs / 10) // 100-ms-Teilbloecke
    private var cnt = 0
    private val subs = ArrayList<Double>()

    private fun shelfBq(): Bq {
        val f0 = 1681.974450955533; val g = 3.999843853973347; val q = 0.7071752369554196
        val k = tan(PI * f0 / fs); val vh = 10.0.pow(g / 20); val vb = vh.pow(0.4996667741545416)
        val a0 = 1.0 + k / q + k * k
        return Bq((vh + vb * k / q + k * k) / a0, 2.0 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
            2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
    }

    private fun hpBq(): Bq {
        val f0 = 38.13547087602444; val q = 0.5003270373238773
        val k = tan(PI * f0 / fs)
        val a0 = 1.0 + k / q + k * k
        return Bq(1.0, -2.0, 1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
    }

    fun push(frame: DoubleArray) {
        for (c in 0 until ch) {
            val v = hp[c].run(shelf[c].run(frame[c]))
            acc[c] += v * v
        }
        if (++cnt >= blockN) {
            var z = 0.0
            for (c in 0 until ch) { z += weight[c] * acc[c] / blockN; acc[c] = 0.0 }
            subs.add(z)
            cnt = 0
        }
    }

    private fun lk(z: Double) = -0.691 + 10 * log10(z)

    /** Integrierte Lautheit in LUFS; NaN bei Stille / zu kurzem Material (< 400 ms). */
    fun integrated(): Double {
        val n = subs.size
        if (n < 4) return Double.NaN
        val blocks = ArrayList<Double>(n - 3)
        for (i in 0..n - 4) blocks.add((subs[i] + subs[i + 1] + subs[i + 2] + subs[i + 3]) / 4.0)
        val abs = blocks.filter { it > 0 && lk(it) > -70.0 }
        if (abs.isEmpty()) return Double.NaN
        val rel = lk(abs.average()) - 10.0
        val gated = abs.filter { lk(it) > rel }
        return if (gated.isEmpty()) Double.NaN else lk(gated.average())
    }
}

/** Tempo-Schaetzung: Onset-Huellkurve (400 Hz) + Autokorrelation im Bereich 70-200 BPM. */
private class BpmDetector(fs: Int) {
    private val rate = 400
    private val hop = max(1, fs / rate)
    private val env = FloatArray((Analyzer.MAX_SECONDS + 5) * rate)
    private var n = 0
    private var acc = 0.0
    private var cnt = 0

    fun push(mono: Double) {
        acc += mono * mono
        if (++cnt >= hop) {
            if (n < env.size) env[n++] = ln(1.0 + 100.0 * sqrt(acc / cnt)).toFloat()
            acc = 0.0; cnt = 0
        }
    }

    /** @return BPM oder 0, wenn kein klarer Beat erkennbar. */
    fun bpm(): Float {
        if (n < rate * 20) return 0f // weniger als 20 s Material
        val o = FloatArray(n)
        for (i in 1 until n) o[i] = max(0f, env[i] - env[i - 1])
        var mean = 0.0
        for (v in o) mean += v
        mean /= n
        for (i in 0 until n) o[i] = (o[i] - mean).toFloat()

        fun r(lag: Int): Double {
            var s = 0.0
            val m = n - lag
            for (i in 0 until m) s += o[i] * o[i + lag]
            return s / m
        }
        val r0 = r(0)
        if (r0 <= 0) return 0f
        val minLag = rate * 60 / 200   // 200 BPM
        val maxLag = rate * 60 / 70    // 70 BPM
        val rs = DoubleArray(maxLag + 2)
        for (l in minLag / 2..maxLag + 1) rs[l] = r(l)
        var best = minLag
        for (l in minLag..maxLag) if (rs[l] > rs[best]) best = l
        if (rs[best] / r0 < 0.03) return 0f // kein klarer Beat
        val a = rs[best - 1]; val b = rs[best]; val c = rs[best + 1]
        val den = a - 2 * b + c
        val lag = if (den < -1e-12) best + 0.5 * (a - c) / den else best.toDouble()
        var bpm = rate * 60.0 / lag
        // Oktav-Fehler: bei langsamem Ergebnis pruefen, ob der doppelte Takt (halbe Lag) fast genauso stark ist
        if (bpm < 100) {
            val half = (best / 2).coerceAtLeast(minLag / 2)
            if (rs[half] >= 0.9 * rs[best] && bpm * 2 <= 200) bpm *= 2
        }
        return (bpm * 10).toInt() / 10f
    }
}

/** Zustand des Hintergrundjobs fuer die UI. */
object AnalysisState {
    var running by mutableStateOf(false)
    var done by mutableStateOf(0)
    var total by mutableStateOf(0)
    var current by mutableStateOf("")
    var message by mutableStateOf("")
    var results by mutableStateOf<Map<String, AnalysisResult>>(emptyMap())
    @Volatile private var cancelled = false

    private val main = Handler(Looper.getMainLooper())

    fun loadResults(ctx: Context) {
        val app = ctx.applicationContext
        Thread { val m = LibraryDb.get(app).analysisAll(); main.post { results = m } }.start()
    }

    fun cancel() { cancelled = true }

    private fun batteryOk(ctx: Context): Boolean = try {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        charging || level * 100 / max(1, scale) >= 15
    } catch (e: Exception) { true }

    fun start(ctx: Context) {
        if (running) return
        val app = ctx.applicationContext
        val todo = LibraryState.tracks.filter { results[it.uri]?.modified != it.dateModified }
        if (todo.isEmpty()) { message = "Alle Titel sind bereits analysiert."; return }
        running = true; cancelled = false; done = 0; total = todo.size; message = ""
        Thread {
            Thread.currentThread().priority = Thread.MIN_PRIORITY
            val db = LibraryDb.get(app)
            var stoppedForBattery = false
            for ((i, t) in todo.withIndex()) {
                if (cancelled) break
                if (i % 10 == 0 && !batteryOk(app)) { stoppedForBattery = true; break }
                main.post { current = t.title }
                val r = Analyzer.analyze(app, t) { cancelled }
                if (cancelled) break
                // Fehlschlag wird als "versucht" gemerkt, damit derselbe kaputte Titel nicht bei jedem Lauf blockiert
                val res = r ?: AnalysisResult(t.uri, t.dateModified, 0f, Float.NaN, -120f, -120f, 0, 0, false)
                db.saveAnalysis(res)
                main.post { results = results + (res.uri to res); done = i + 1 }
            }
            main.post {
                running = false
                current = ""
                message = when {
                    stoppedForBattery -> "Gestoppt: Akku unter 15 %. Zum Fortsetzen laden und erneut starten."
                    cancelled -> "Abgebrochen. Bisherige Ergebnisse sind gespeichert."
                    else -> "Fertig."
                }
            }
        }.start()
    }
}
