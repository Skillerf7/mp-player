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
import com.mp.player.ai.LyricsMood
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
            var spectral: SpectralMeter? = null
            var rhythm: RhythmEnergyMeter? = null
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
                            if (meter == null) { meter = LoudnessMeter(sr, ch); bpmDet = BpmDetector(sr); spectral = SpectralMeter(sr); rhythm = RhythmEnergyMeter(sr) }
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
                                spectral!!.push(mono / ch)
                                rhythm!!.push(mono / ch)
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
                        meter = null; bpmDet = null; spectral = null; rhythm = null
                    }
                    else -> if (inEos && ++idle > 300) outEos = true // Decoder liefert nichts mehr -> Sicherung gegen Haengen
                }
            }
            val m = meter ?: return null
            val rmsDb = if (nSamples > 0 && sumSq > 0) 10 * log10(sumSq / nSamples) else -120.0
            val peakDb = if (peak > 0) 20 * log10(peak) else -120.0
            val bpmEst = bpmDet?.bpm() ?: 0f
            val conf = bpmDet?.lastConfidence ?: 0f
            val sp = spectral?.result() ?: SpectralSnapshot()
            val rx = rhythm?.result() ?: ExtendedSnapshot()
            return AnalysisResult(
                uri = t.uri, modified = t.dateModified,
                bpm = bpmEst,
                lufs = m.integrated().toFloat(),
                peakDb = peakDb.toFloat(), rmsDb = rmsDb.toFloat(),
                sampleRate = sr, channels = ch, partial = limited,
                bpmConfidence = conf,
                spectralCentroidHz = sp.centroidHz,
                spectralRolloffHz = sp.rolloffHz,
                spectralFlux = sp.flux,
                spectralFlatness = sp.flatness,
                bassEnergy = sp.bass,
                midEnergy = sp.mid,
                highEnergy = sp.high,
                featureVersion = AnalysisVersions.FEATURE_VERSION,
                analysisLevel = AnalysisLevel.LIGHT.name,
                rhythmRegularity = rx.rhythmRegularity,
                energyIntro = rx.energyIntro,
                energyMid = rx.energyMid,
                energyLate = rx.energyLate,
                analysisStatus = "ok"
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

private class BpmDetector(fs: Int) {
    private val rate = 400
    private val hop = max(1, fs / rate)
    private val env = FloatArray((Analyzer.MAX_SECONDS + 5) * rate)
    private var n = 0
    private var acc = 0.0
    private var cnt = 0
    var lastConfidence: Float = 0f
        private set
    var lastSource: String = ""
        private set

    fun push(mono: Double) {
        acc += mono * mono
        if (++cnt >= hop) {
            if (n < env.size) env[n++] = ln(1.0 + 100.0 * sqrt(acc / cnt)).toFloat()
            acc = 0.0; cnt = 0
        }
    }

    fun bpm(userHint: Float? = null): Float {
        val est = BpmCore.estimate(env, n, rate, userHint)
        lastConfidence = est.confidence
        lastSource = est.source
        return est.bpm
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
    /** Gelesene Songtexte (nur Profil, nicht der Text selbst): Titel -> Zeile. */
    var lyrics by mutableStateOf<Map<String, LibraryDb.LyricsRow>>(emptyMap())
    @Volatile private var cancelled = false

    private val main = Handler(Looper.getMainLooper())

    fun loadResults(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            val db = LibraryDb.get(app)
            val m = db.analysisAll()
            val l = db.lyricsAll()
            main.post { results = m; lyrics = l }
        }.start()
    }

    /**
     * ONNX-Genre fuer einen Titel. @return false, wenn das Modell gar nicht laeuft (dann nichts speichern, nicht weiter versuchen).
     * Laeuft das Modell, aber es kommt nichts heraus (zu kurz, nicht dekodierbar), wird ein leerer Marker gespeichert.
     */
    private fun runMlGenre(app: Context, db: LibraryDb, t: Track): Boolean {
        return try {
            val attrs = com.mp.player.ai.GenreOnnxClassifier.classify(app, t, topK = 3)
            if (!com.mp.player.ai.GenreOnnxClassifier.isReady()) return false
            if (attrs.isNotEmpty()) {
                val top = attrs.first()
                val json = attrs.joinToString(",") { "${it.genre}:${"%.3f".format(java.util.Locale.US, it.confidence)}" }
                db.saveMlGenre(t.uri, com.mp.player.ai.GenreOnnxClassifier.MODEL_VERSION, top.genre, top.confidence, json)
            } else {
                db.saveMlGenre(t.uri, com.mp.player.ai.GenreOnnxClassifier.MODEL_VERSION, "", 0f, "")
            }
            true
        } catch (_: Throwable) { true }
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
        // Audio UND Songtext: ein Titel kommt dran, wenn eines von beiden fehlt oder die Datei geaendert wurde
        // (Mengen vorab bilden: der Hintergrund-Thread soll keinen Compose-Zustand lesen)
        val audioTodo = LibraryState.tracks.filter { tr ->
            val prev = results[tr.uri]
            when {
                prev == null -> true
                prev.modified != tr.dateModified -> true
                prev.featureVersion < AnalysisVersions.FEATURE_VERSION -> true
                !isUsableAnalysis(prev) -> true
                else -> false
            }
        }.map { it.uri }.toHashSet()
        val lyricsTodo = LibraryState.tracks.filter { lyrics[it.uri]?.modified != it.dateModified }.map { it.uri }.toHashSet()
        // ML-Genre-Nachlauf: bereits analysierte Titel ohne Ergebnis (oder Marker) fuer das aktuelle Modell
        val mlDone = try { LibraryDb.get(app).mlGenreDoneUris(com.mp.player.ai.GenreOnnxClassifier.MODEL_VERSION) } catch (e: Throwable) { emptySet<String>() }
        val mlTodo = LibraryState.tracks.filter { it.uri !in mlDone && it.uri !in audioTodo && results[it.uri]?.let { r -> isUsableAnalysis(r) } == true }
            .map { it.uri }.toHashSet()
        val todo = LibraryState.tracks.filter { it.uri in audioTodo || it.uri in lyricsTodo || it.uri in mlTodo }
        if (todo.isEmpty()) { message = "Alle Titel sind bereits analysiert."; return }
        running = true; cancelled = false; done = 0; total = todo.size; message = ""
        Thread {
            Thread.currentThread().priority = Thread.MIN_PRIORITY
            val db = LibraryDb.get(app)
            var stoppedForBattery = false
            var mlAvailable = true // false, sobald das Modell nicht startet -> kein endloser Neuversuch pro Titel
            for ((i, t) in todo.withIndex()) {
                if (cancelled) break
                if (i % 10 == 0 && !batteryOk(app)) { stoppedForBattery = true; break }
                main.post { current = t.title }
                // 1) Eingebetteten Songtext lesen (schnell, nur Tags) - zuerst, damit er auch bei fruehem Abbruch da ist.
                //    "kein Text" wird ebenfalls gemerkt, sonst wuerde jeder Lauf dieselben Dateien neu oeffnen.
                if (t.uri in lyricsTodo) {
                    val text = try { LyricsReader.read(app, t.uri) } catch (e: Throwable) { null }
                    val profile = if (text != null) LyricsMood.analyze(text)?.encode() ?: "" else ""
                    db.saveLyrics(t.uri, t.dateModified, text, profile)
                    val row = LibraryDb.LyricsRow(t.uri, t.dateModified, text != null, profile)
                    main.post { lyrics = lyrics + (t.uri to row) }
                }
                if (t.uri in audioTodo) {
                    if (!db.canRetryAnalysis(t.uri) && results[t.uri] == null) {
                        // Zu viele Fehlversuche / Backoff – überspringen
                        main.post { done = i + 1 }
                        continue
                    }
                    val r = Analyzer.analyze(app, t) { cancelled }
                    if (cancelled) break
                    if (r != null && isUsableAnalysis(r)) {
                        db.saveAnalysis(r)
                        main.post { results = results + (r.uri to r) }
                        // Optional: ONNX-Genre (GTZAN) – Fehler brechen Analyse nicht
                        if (mlAvailable) mlAvailable = runMlGenre(app, db, t)
                    } else if (r != null) {
                        db.saveAnalysis(r.copy(analysisStatus = "partial"))
                        main.post { results = results + (r.uri to r.copy(analysisStatus = "partial")) }
                    } else {
                        db.recordAnalysisFailure(t.uri)
                    }
                }
                if (t.uri in mlTodo && mlAvailable && !cancelled) {
                    mlAvailable = runMlGenre(app, db, t)
                }
                main.post { done = i + 1 }
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

/** Erfolgreiche Analyse: mindestens ein verwertbares Signal. */
internal fun isUsableAnalysis(r: AnalysisResult): Boolean =
    r.sampleRate > 0 && (r.bpm > 0f || !r.lufs.isNaN() || r.rmsDb > -119f || r.spectralCentroidHz > 0f)
