package com.mp.player.ai

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.mp.player.Track
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lokale GTZAN-Genre-Klassifikation via HuBERT-ONNX (quantisiert).
 * Nur Apache-2.0-Modell, Asset offline. Keine Cloud.
 *
 * Labels: blues, classical, country, disco, hiphop, jazz, metal, pop, reggae, rock
 * Keine erfundenen Subgenres.
 */
object GenreOnnxClassifier {
    private const val TAG = "GenreOnnx"
    const val MODEL_ASSET = "ml/model_quantized.onnx"
    const val META_ASSET = "ml/genre_labels.json"
    const val MODEL_VERSION = "hubert-gtzan-q8-v1"
    private const val TARGET_SR = 16000
    private const val MAX_SECONDS = 30

    @Volatile private var session: OrtSession? = null
    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var labels: List<String> = emptyList()
    @Volatile private var ready = false
    @Volatile private var lastError: String? = null

    fun lastError(): String? = lastError
    fun isReady(): Boolean = ready
    fun modelVersion(): String = MODEL_VERSION
    fun supportedLabels(): List<String> = labels

    @Synchronized
    fun init(context: Context): Boolean {
        if (ready && session != null) return true
        return try {
            val app = context.applicationContext
            val meta = app.assets.open(META_ASSET).bufferedReader().readText()
            val jo = JSONObject(meta)
            val arr = jo.getJSONArray("labels")
            labels = (0 until arr.length()).map { arr.getString(it) }
            // Modell in Cache-Datei (Ort braucht Seekable File/MappedByteBuffer)
            val modelFile = java.io.File(app.cacheDir, "genre_model_quantized.onnx")
            if (!modelFile.exists() || modelFile.length() < 1_000_000) {
                // Erst in Temp-Datei, dann umbenennen: ein abgebrochener Kopiervorgang hinterlaesst keine halbe Modelldatei
                val tmp = java.io.File(app.cacheDir, "genre_model_quantized.onnx.tmp")
                app.assets.open(MODEL_ASSET).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                if (!tmp.renameTo(modelFile)) {
                    tmp.copyTo(modelFile, overwrite = true)
                    tmp.delete()
                }
            }
            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = env!!.createSession(modelFile.absolutePath, opts)
            ready = true
            lastError = null
            LocalOnnxBridge.markGenreRuntimeReady(true)
            true
        } catch (e: Throwable) {
            ready = false
            lastError = e.message
            LocalOnnxBridge.markGenreRuntimeReady(false)
            Log.w(TAG, "Genre ONNX init failed", e)
            false
        }
    }

    /**
     * @return Top-K Attributionen oder leer bei Fehler
     */
    fun classify(context: Context, track: Track, topK: Int = 3): List<GenreAttribution> {
        if (!init(context)) return emptyList()
        val sess = session ?: return emptyList()
        val wave = try {
            decodeMono16k(context, track.uri, MAX_SECONDS)
        } catch (e: Throwable) {
            Log.w(TAG, "decode failed ${track.uri}", e)
            return emptyList()
        }
        if (wave.size < TARGET_SR) return emptyList() // <1s
        // Wav2Vec2/HuBERT: mean-variance normalize if do_normalize
        normalizeInPlace(wave)
        return try {
            val shape = longArrayOf(1, wave.size.toLong())
            val fb = FloatBuffer.wrap(wave)
            OnnxTensor.createTensor(env, fb, shape).use { input ->
                // Input-Name aus Session
                val inputName = sess.inputNames.first()
                val results = sess.run(mapOf(inputName to input))
                results.use {
                    val out = it[0].value
                    val logits = when (out) {
                        is Array<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            val row = (out as Array<FloatArray>)[0]
                            row
                        }
                        is FloatArray -> out
                        else -> {
                            Log.w(TAG, "unexpected output ${out?.javaClass}")
                            return emptyList()
                        }
                    }
                    val probs = softmax(logits)
                    probs.mapIndexed { i, p -> i to p }
                        .sortedByDescending { it.second }
                        .take(topK)
                        .filter { it.second >= 0.08f }
                        .map { (i, p) ->
                            val label = labels.getOrNull(i) ?: "unknown"
                            GenreAttribution(
                                genre = label,
                                confidence = p.coerceIn(0f, 1f),
                                source = GenreSource.AUDIO_ML,
                                reasons = listOf("ONNX $MODEL_VERSION", "GTZAN-label")
                            )
                        }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "infer failed", e)
            lastError = e.message
            emptyList()
        }
    }

    private fun softmax(logits: FloatArray): FloatArray {
        var max = logits[0]
        for (v in logits) if (v > max) max = v
        val ex = FloatArray(logits.size)
        var sum = 0.0
        for (i in logits.indices) {
            val e = kotlin.math.exp((logits[i] - max).toDouble())
            ex[i] = e.toFloat()
            sum += e
        }
        for (i in ex.indices) ex[i] = (ex[i] / sum).toFloat()
        return ex
    }

    private fun normalizeInPlace(x: FloatArray) {
        var mean = 0.0
        for (v in x) mean += v
        mean /= x.size
        var varSum = 0.0
        for (v in x) {
            val d = v - mean
            varSum += d * d
        }
        val std = sqrt(varSum / x.size).coerceAtLeast(1e-7)
        for (i in x.indices) x[i] = ((x[i] - mean) / std).toFloat()
    }

    /**
     * Startposition des Analysefensters: kurze Titel ab 0, lange Titel aus der Mitte
     * (das Intro sagt oft wenig ueber das Genre).
     */
    internal fun chooseStartUs(durationUs: Long, windowSec: Int): Long {
        val windowUs = windowSec * 1_000_000L
        if (durationUs <= windowUs + 10_000_000L) return 0L
        return (durationUs / 2 - windowUs / 2).coerceAtLeast(0L)
    }

    /** Dekodiert bis zu [maxSec] Sekunden Mono-PCM (Limit gilt in Quell-Samples) und resampled auf 16 kHz. */
    private fun decodeMono16k(context: Context, uri: String, maxSec: Int): FloatArray {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, Uri.parse(uri), null)
            var idx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    idx = i; fmt = f; break
                }
            }
            if (idx < 0 || fmt == null) return FloatArray(0)
            ex.selectTrack(idx)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return FloatArray(0)
            val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val startUs = chooseStartUs(durationUs, maxSec)
            if (startUs > 0L) ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            var sr = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var ch = (if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1).coerceAtLeast(1)
            var pcmFloat = false
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var buf = FloatArray(sr * maxSec)
            var n = 0
            var inDone = false
            val timeout = 10_000L
            val deadline = System.nanoTime() + 20_000_000_000L
            while (n < sr * maxSec && System.nanoTime() < deadline) {
                if (!inDone) {
                    val inIx = codec.dequeueInputBuffer(timeout)
                    if (inIx >= 0) {
                        val ib = codec.getInputBuffer(inIx)!!
                        val got = ex.readSampleData(ib, 0)
                        if (got < 0) {
                            codec.queueInputBuffer(inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(inIx, 0, got, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIx = codec.dequeueOutputBuffer(info, timeout)
                if (outIx >= 0) {
                    if (info.size > 0) {
                        val out = codec.getOutputBuffer(outIx)!!
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val bb = out.slice().order(ByteOrder.LITTLE_ENDIAN)
                        val fb = if (pcmFloat) bb.asFloatBuffer() else null
                        val sb = if (pcmFloat) null else bb.asShortBuffer()
                        val total = if (pcmFloat) fb!!.remaining() else sb!!.remaining()
                        var k = 0
                        while (k + ch <= total && n < sr * maxSec) {
                            var sum = 0f
                            for (c in 0 until ch) sum += if (pcmFloat) fb!!.get(k + c) else sb!!.get(k + c) / 32768f
                            if (n >= buf.size) buf = buf.copyOf(max(buf.size * 2, n + 1))
                            buf[n++] = sum / ch
                            k += ch
                        }
                    }
                    codec.releaseOutputBuffer(outIx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Rate/Kanaele/Kodierung koennen sich nach dem Start aendern (z. B. HE-AAC): neu lesen
                    val of = codec.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (inDone && outIx == MediaCodec.INFO_TRY_AGAIN_LATER) break
            }
            return resample(buf.copyOf(n), sr, TARGET_SR)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { ex.release() } catch (_: Exception) {}
        }
    }

    /** Beim Verkleinern der Rate wird ueber das Quellfenster gemittelt (grober Tiefpass gegen Aliasing). */
    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input
        val outLen = (input.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val ratio = from.toDouble() / to
        for (i in 0 until outLen) {
            val src = i * ratio
            if (ratio > 1.0) {
                val a = src.toInt().coerceIn(0, input.lastIndex)
                val b = (src + ratio).toInt().coerceIn(a + 1, input.size)
                var sum = 0f
                for (j in a until b) sum += input[j]
                out[i] = sum / (b - a)
            } else {
                val i0 = src.toInt().coerceIn(0, input.lastIndex)
                val i1 = (i0 + 1).coerceAtMost(input.lastIndex)
                val t = (src - i0).toFloat()
                out[i] = input[i0] * (1 - t) + input[i1] * t
            }
        }
        return out
    }
}
