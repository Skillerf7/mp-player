package com.mp.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

data class Band(val f: Float, val g: Float, val q: Float) // Hz, dB, Q

data class Dsp(
    val on: Boolean = true,
    val pre: Float = 0f,
    val bands: List<Band> = Eq.flatBands(), // 32 Baender (aeltere gespeicherte Staende mit anderer Bandzahl laufen weiter)
    val bass: Float = 0f,
    val treble: Float = 0f,
    val bassBoost: Boolean = false,
    val trebleBoost: Boolean = false,
    val limiter: Boolean = true,
    val reverbMix: Float = 0f,   // 0 = aus/trocken, 1 = voller Hall-Anteil
    val reverbSize: Float = 0.5f // 0 = kleiner Raum, 1 = großer Raum (Decay-Länge)
)

object Spectrum { @Volatile var bands = FloatArray(32) }

object Engine {
    val proc = DspProcessor()

    /**
     * true = der laufende Klang wurde nur fuer diese Sitzung veraendert (z. B. durch den KI-Assistenten) und
     * ist NICHT gespeichert. Die Klang-Screens zeigen dann diesen Stand an, speichern ihn aber nicht von selbst.
     */
    @Volatile var sessionChanged = false

    /** Der Stand, den die Wiedergabe gerade wirklich benutzt - sonst der gespeicherte. */
    fun currentDsp(store: Store): Dsp = if (sessionChanged) proc.cfg else store.dsp()
}

// Biquad-Filter (RBJ Cookbook). kind: 0 = Peak, 1 = Low-Shelf, 2 = High-Shelf
internal class Bq {
    var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0
    var z1 = 0.0; var z2 = 0.0
    fun reset() { z1 = 0.0; z2 = 0.0 }
    /** Betrag des Frequenzgangs in dB bei Frequenz f (fuer die Kurvenanzeige). */
    fun magDb(f: Double, fs: Double): Double {
        val w = 2 * PI * f / fs
        val cw = cos(w); val sw = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * cw + b2 * c2; val ni = -(b1 * sw + b2 * s2)
        val dr = 1 + a1 * cw + a2 * c2; val di = -(a1 * sw + a2 * s2)
        return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di + 1e-30) + 1e-30)
    }
    fun run(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
    fun set(kind: Int, f: Double, g: Double, q: Double, fs: Double) {
        val a = 10.0.pow(g / 40)
        val w = 2 * PI * min(f, fs * 0.45) / fs
        val c = cos(w)
        val s = sin(w)
        val n: DoubleArray
        if (kind == 0) {
            val al = s / (2 * q)
            n = doubleArrayOf(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
        } else {
            val t = 2 * sqrt(a) * (s / 2 * sqrt(2.0))
            n = if (kind == 1) doubleArrayOf(
                a * ((a + 1) - (a - 1) * c + t), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - t),
                (a + 1) + (a - 1) * c + t, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - t
            ) else doubleArrayOf(
                a * ((a + 1) + (a - 1) * c + t), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - t),
                (a + 1) - (a - 1) * c + t, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - t
            )
        }
        b0 = n[0] / n[3]; b1 = n[1] / n[3]; b2 = n[2] / n[3]; a1 = n[4] / n[3]; a2 = n[5] / n[3]
    }
}

// Einfacher Schroeder-Hall: 4 parallele Kammfilter + 2 serielle Allpass-Filter.
// Stereo-Variante (separate Verzögerungslängen L/R für Breite) für ch==2,
// Mono-Fallback (auf mono-Summe, gleich auf alle Kanäle addiert) für alles andere.
private class Comb(size: Int) {
    private val buf = DoubleArray(size.coerceAtLeast(1))
    private var idx = 0
    var fb = 0.5
    fun process(x: Double): Double {
        val y = buf[idx]
        buf[idx] = x + y * fb
        idx = (idx + 1) % buf.size
        return y
    }
}

private class Allpass(size: Int) {
    private val buf = DoubleArray(size.coerceAtLeast(1))
    private var idx = 0
    private val g = 0.5
    fun process(x: Double): Double {
        val bo = buf[idx]
        val y = -g * x + bo
        buf[idx] = x + bo * g
        idx = (idx + 1) % buf.size
        return y
    }
}

private class ReverbChannel(fs: Double, msOffset: Double) {
    private val combs = listOf(29.7, 37.1, 41.1, 43.7).map {
        Comb(((it + msOffset) / 1000.0 * fs).toInt())
    }
    private val aps = listOf(5.0, 1.7).map { Allpass((it / 1000.0 * fs).toInt()) }
    fun setFeedback(fb: Double) { combs.forEach { it.fb = fb } }
    fun process(x: Double): Double {
        var w = combs.sumOf { it.process(x) } * 0.25
        for (ap in aps) w = ap.process(w)
        return w
    }
}

private class Reverb(fs: Double, stereo: Boolean) {
    private val left = ReverbChannel(fs, 0.0)
    private val right = if (stereo) ReverbChannel(fs, 0.8) else left
    fun setSize(size: Float) {
        val fb = 0.28 + size.toDouble().coerceIn(0.0, 1.0) * 0.68 // 0.28..0.96
        left.setFeedback(fb); if (right !== left) right.setFeedback(fb)
    }
    fun process(l: Double, r: Double): Pair<Double, Double> = left.process(l) to right.process(r)
}

class DspProcessor : BaseAudioProcessor() {
    @Volatile private var dirty = true
    @Volatile var cfg = Dsp()
        set(v) { field = v; dirty = true }

    // true = Ausgabe als 32-Bit-Float, false = 16 Bit (wirkt ab dem naechsten Konfigurieren des Audiopfads)
    @Volatile var floatOutput = true

    /** ReplayGain / Lautheits-Normalisierung in dB (wirkt immer, unabhaengig vom EQ-Schalter; 0 = aus). */
    @Volatile var replayGainDb = 0f
    private var outFloat = true
    // Nach einem unerwarteten Fehler: Effekte aus, Ton laeuft unveraendert weiter (statt Stille/Absturz)
    @Volatile private var failed = false

    private var fs = 44100.0
    private var ch = 2
    private var isFloat = false
    private var fl = Array(0) { Array(0) { Bq() } }
    private var y = DoubleArray(2)
    private var gain = 1.0
    private val N = 1024
    private val buf = DoubleArray(N)
    private var n = 0
    private val re = DoubleArray(N)
    private val im = DoubleArray(N)
    private val win = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / N) }
    private val sm = FloatArray(32)
    private var reverb: Reverb? = null
    private var reverbFs = -1.0
    private var reverbSizeApplied = -1f

    override fun onConfigure(inp: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inp.encoding != C.ENCODING_PCM_16BIT && inp.encoding != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        isFloat = inp.encoding == C.ENCODING_PCM_FLOAT
        fs = inp.sampleRate.toDouble()
        ch = inp.channelCount
        y = DoubleArray(ch)
        fl = Array(0) { Array(0) { Bq() } }
        dirty = true
        failed = false
        outFloat = floatOutput
        return AudioProcessor.AudioFormat(
            inp.sampleRate, inp.channelCount,
            if (outFloat) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT
        )
    }

    // Baender mit ~0 dB werden uebersprungen (spart CPU: ein flacher 32-Band-EQ kostet nichts)
    private var active = BooleanArray(0)

    private fun rebuild() {
        val sp = Eq.specs(cfg)
        if (fl.size != ch || fl.firstOrNull()?.size != sp.size) fl = Array(ch) { Array(sp.size) { Bq() } }
        if (active.size != sp.size) active = BooleanArray(sp.size)
        sp.forEachIndexed { i, s -> active[i] = abs(s[2]) > 0.05 }
        for (k in 0 until ch) sp.forEachIndexed { i, s ->
            fl[k][i].set(s[0].toInt(), s[1], s[2], s[3], fs)
            if (!active[i]) fl[k][i].reset()
        }
    }

    // Ein Fehler in der DSP-Kette darf nie die Wiedergabe beenden: Effekte abschalten, Ton unveraendert weiter.
    override fun queueInput(inp: ByteBuffer) {
        inp.order(ByteOrder.nativeOrder())
        val frames = inp.remaining() / (if (isFloat) 4 else 2) / ch
        if (frames <= 0) {
            inp.position(inp.limit())
            return
        }
        val out = replaceOutputBuffer(frames * ch * (if (outFloat) 4 else 2))
        val start = inp.position()
        if (failed) {
            passthrough(inp, out, frames)
        } else {
            try {
                process(inp, out, frames)
            } catch (t: Throwable) {
                failed = true
                inp.position(start)
                out.clear()
                passthrough(inp, out, frames)
            }
        }
        inp.position(inp.limit()) // Reste (unvollstaendiges Frame) verwerfen, damit der Audiopfad nie haengt
        out.flip()
    }

    private fun readSample(inp: ByteBuffer): Double =
        if (isFloat) inp.getFloat().toDouble() else inp.getShort() / 32768.0

    private fun writeSample(out: ByteBuffer, v: Double) {
        val c = if (v.isNaN()) 0.0 else v.coerceIn(-1.0, 1.0)
        if (outFloat) out.putFloat(c.toFloat()) else out.putShort((c * 32767.0).toInt().toShort())
    }

    private fun passthrough(inp: ByteBuffer, out: ByteBuffer, frames: Int) {
        for (i in 0 until frames * ch) writeSample(out, readSample(inp))
    }

    private fun process(inp: ByteBuffer, out: ByteBuffer, frames: Int) {
        if (dirty) { dirty = false; rebuild() }
        val c = cfg
        val pre = 10.0.pow((c.pre + Eq.autoComp(c)) / 20.0)
        val rg = 10.0.pow(replayGainDb / 20.0)
        val rel = 1 - exp(-1.0 / (0.1 * fs))
        var bad = false
        for (i in 0 until frames) {
            var mono = 0.0
            for (k in 0 until ch) {
                var x = readSample(inp) * rg
                if (c.on) {
                    x *= pre
                    val chain = fl[k]
                    for (bi in chain.indices) if (active[bi]) x = chain[bi].run(x)
                    if (!x.isFinite()) { x = 0.0; bad = true }
                }
                y[k] = x
                mono += x
            }
            if (c.on && c.reverbMix > 0f) {
                if (reverb == null || reverbFs != fs) {
                    reverb = Reverb(fs, ch == 2)
                    reverbFs = fs
                    reverbSizeApplied = -1f
                }
                if (reverbSizeApplied != c.reverbSize) { reverb!!.setSize(c.reverbSize); reverbSizeApplied = c.reverbSize }
                val mix = c.reverbMix.toDouble().coerceIn(0.0, 1.0)
                if (ch == 2) {
                    val (wl, wr) = reverb!!.process(y[0], y[1])
                    y[0] += wl * mix; y[1] += wr * mix
                } else {
                    val w = reverb!!.process(mono / ch, mono / ch).first
                    for (k in 0 until ch) y[k] += w * mix
                }
            }
            if (c.on && c.limiter) {
                // Spitzenpegel erst NACH dem Hall messen: der Limiter muss das komplette Ausgangssignal sehen,
                // sonst addiert der Hall seinen Anteil hinter dem Limiter und die Ausgabe kann clippen.
                var pk = 0.0
                for (k in 0 until ch) pk = max(pk, abs(y[k]))
                val t = if (pk > 0.98) 0.98 / pk else 1.0
                gain = if (t < gain) t else gain + (1 - gain) * rel
            } else gain = 1.0
            for (k in 0 until ch) writeSample(out, y[k] * gain)
            tap(mono / ch)
        }
        if (bad) {
            // Ein Filter ist numerisch aus dem Ruder gelaufen (NaN/Unendlich): zuruecksetzen
            fl.forEach { perChannel -> perChannel.forEach { it.reset() } }
            reverb = null
            gain = 1.0
        }
    }

    override fun onFlush() { fl = Array(0) { Array(0) { Bq() } }; n = 0; gain = 1.0; dirty = true; reverb = null }
    override fun onReset() { onFlush() }

    private fun tap(v: Double) {
        buf[n++] = v
        if (n < N) return
        n = 0
        for (i in 0 until N) { re[i] = buf[i] * win[i]; im[i] = 0.0 }
        fft()
        for (b in 0 until 32) {
            val lo = 40.0 * (400.0).pow(b / 32.0)
            val hi = 40.0 * (400.0).pow((b + 1) / 32.0)
            val i0 = (lo * N / fs).toInt().coerceIn(1, N / 2 - 1)
            val i1 = max(i0 + 1, (hi * N / fs).toInt()).coerceAtMost(N / 2)
            var m = 0.0
            for (i in i0 until i1) m = max(m, hypot(re[i], im[i]))
            val db = 20 * log10(m / (N / 4.0) + 1e-9)
            val nv = ((db + 70) / 70).coerceIn(0.0, 1.0).toFloat()
            sm[b] = max(nv, sm[b] * 0.85f)
        }
        Spectrum.bands = sm.copyOf()
    }

    private fun fft() {
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val a = re[i]; re[i] = re[j]; re[j] = a
                val b = im[i]; im[i] = im[j]; im[j] = b
            }
        }
        var len = 2
        while (len <= N) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            val h = len / 2
            var i = 0
            while (i < N) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until h) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + h] * cr - im[i + k + h] * ci
                    val vi = re[i + k + h] * ci + im[i + k + h] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + h] = ur - vr; im[i + k + h] = ui - vi
                    val t = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = t
                }
                i += len
            }
            len = len shl 1
        }
    }
}

fun Dsp.toJson(): JSONObject = JSONObject()
    .put("on", on).put("pre", pre.toDouble()).put("bass", bass.toDouble()).put("treble", treble.toDouble())
    .put("bb", bassBoost).put("tb", trebleBoost).put("lim", limiter)
    .put("rvMix", reverbMix.toDouble()).put("rvSize", reverbSize.toDouble())
    .put("bands", JSONArray(bands.map { JSONArray(listOf(it.f.toDouble(), it.g.toDouble(), it.q.toDouble())) }))

fun dspFrom(o: JSONObject): Dsp {
    val a = o.getJSONArray("bands")
    return Dsp(
        on = o.getBoolean("on"),
        pre = o.getDouble("pre").toFloat(),
        bands = (0 until a.length()).map { i ->
            val b = a.getJSONArray(i)
            Band(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat())
        },
        bass = o.getDouble("bass").toFloat(),
        treble = o.getDouble("treble").toFloat(),
        bassBoost = o.getBoolean("bb"),
        trebleBoost = o.getBoolean("tb"),
        limiter = o.getBoolean("lim"),
        // rückwärtskompatibel: alte gespeicherte Presets/DSP-Werte ohne Reverb-Felder laden weiterhin (Default 0/0.5)
        reverbMix = o.optDouble("rvMix", 0.0).toFloat(),
        reverbSize = o.optDouble("rvSize", 0.5).toFloat()
    )
}

// Speicher: Favoriten, Playlists, EQ-Presets, EQ-Einstellungen, Hi-Res
class Store(c: Context) {
    internal val appContext: Context = c.applicationContext
    private val p = c.getSharedPreferences("mp", 0)

    /** ReplayGain: 0 = aus, 1 = Titel, 2 = Album */
    var rgMode: Int
        get() = p.getInt("rgMode", 0)
        set(v) { p.edit().putInt("rgMode", v).apply() }
    /** Titel ohne ReplayGain-Tag: Lautheit aus der Audioanalyse nutzen */
    var rgFallback: Boolean
        get() = p.getBoolean("rgFallback", true)
        set(v) { p.edit().putBoolean("rgFallback", v).apply() }
    /** Sleep-Timer: Ausblenden in Sekunden (0 = aus) */
    var sleepFadeSec: Int
        get() = p.getInt("sleepFade", 10)
        set(v) { p.edit().putInt("sleepFade", v).apply() }
    fun favs(): Set<Long> = try { p.getStringSet("fav", emptySet())!!.map { it.toLong() }.toSet() } catch (e: Exception) { emptySet() }
    fun saveFavs(s: Set<Long>) { p.edit().putStringSet("fav", s.map { it.toString() }.toSet()).apply() }
    fun pls(): Map<String, List<Long>> = try {
        val o = JSONObject(p.getString("pl", "{}")!!)
        o.keys().asSequence().associateWith { k ->
            val a = o.getJSONArray(k)
            (0 until a.length()).map { a.getLong(it) }
        }
    } catch (e: Exception) { emptyMap() }
    fun savePls(m: Map<String, List<Long>>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, JSONArray(v)) }
        p.edit().putString("pl", o.toString()).apply()
    }
    fun presets(): Map<String, Dsp> = try {
        val o = JSONObject(p.getString("pr", "{}")!!)
        o.keys().asSequence().associateWith { dspFrom(o.getJSONObject(it)) }
    } catch (e: Exception) { emptyMap() }
    fun savePresets(m: Map<String, Dsp>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v.toJson()) }
        p.edit().putString("pr", o.toString()).apply()
    }
    fun dsp(): Dsp = try { p.getString("dsp", null)?.let { dspFrom(JSONObject(it)) } ?: Dsp() } catch (e: Exception) { Dsp() }
    fun saveDsp(d: Dsp) { p.edit().putString("dsp", d.toJson().toString()).apply() }
    var hires: Boolean
        get() = p.getBoolean("hires", true)
        set(v) { p.edit().putBoolean("hires", v).apply() }
    fun rawFolders(): String = p.getString("folders", "[]")!!
    fun saveRawFolders(s: String) { p.edit().putString("folders", s).apply() }
    fun rawLibrary(): String = p.getString("library", "[]")!!
    fun saveRawLibrary(s: String) { p.edit().putString("library", s).apply() }
}
