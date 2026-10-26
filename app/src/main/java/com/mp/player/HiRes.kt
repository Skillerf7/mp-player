package com.mp.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager

/**
 * Hi-Res analog zu Poweramp (soweit mit Media3/ExoPlayer möglich):
 *
 * Poweramp: Hi-Res Output / AudioTrack / OpenSL / AAudio, Sample-Rate 44.1–384 kHz,
 * Bit-Tiefe 16/24/32/Float32, Geräte-Caps, Fallback.
 *
 * Secret Player: OUTPUT_HIRES → DSP 32-Bit-Float; STANDARD → 16-Bit;
 * Sample-Rate- und Bit-Präferenz gespeichert und an Caps geklemmt.
 * Kein proprietärer OpenSL-Hi-Res-Treiber (Poweramp-Grenze).
 */

data class HiResCapabilities(
    val deviceName: String,
    val isWired: Boolean,
    val isBluetooth: Boolean,
    val sampleRates: List<Int>,
    val bitDepths: List<Int>,
    val supportsHiRes: Boolean,
    val deviceType: Int = 0
)

data class HiResResolved(
    val mode: String,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val useFloatOutput: Boolean,
    val deviceName: String,
    val note: String
)

object HiRes {

    const val MODE_STANDARD = "OUTPUT_STANDARD"
    const val MODE_HIRES = "OUTPUT_HIRES"
    const val MODE_AUTO = "OUTPUT_AUTO"

    val RATE_CHOICES = listOf(0, 44100, 48000, 96000, 192000)
    val BIT_CHOICES = listOf(16, 24, 32)

    private val encodingToBits = mapOf(
        AudioFormat.ENCODING_PCM_16BIT to 16,
        AudioFormat.ENCODING_PCM_24BIT_PACKED to 24,
        AudioFormat.ENCODING_PCM_32BIT to 32,
        AudioFormat.ENCODING_PCM_FLOAT to 32
    )

    fun availableOutputs(context: Context): List<HiResCapabilities> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { toCapabilities(it) }
    }

    fun activeOutput(context: Context): HiResCapabilities? {
        val priority = listOf(
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        )
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val best = devices.minByOrNull { d ->
            priority.indexOf(d.type).let { if (it < 0) 99 else it }
        }
        return best?.let { toCapabilities(it) }
    }

    private fun toCapabilities(d: AudioDeviceInfo): HiResCapabilities {
        val rates = d.sampleRates?.toList()?.sorted() ?: emptyList()
        val bits = d.encodings.toList().mapNotNull { encodingToBits[it] }.distinct().sorted()
        val wired = d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            d.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            d.type == AudioDeviceInfo.TYPE_USB_HEADSET
        val bt = d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            d.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        return HiResCapabilities(
            deviceName = d.productName?.toString() ?: "Unbekanntes Gerät",
            isWired = wired,
            isBluetooth = bt,
            sampleRates = rates,
            bitDepths = bits,
            supportsHiRes = wired && (rates.any { it > 48000 } || bits.any { it > 16 }),
            deviceType = d.type
        )
    }

    fun isSupported(caps: HiResCapabilities, sampleRate: Int, bitDepth: Int): Boolean {
        val rateOk = sampleRate == 0 || caps.sampleRates.isEmpty() || caps.sampleRates.contains(sampleRate)
        val bitOk = caps.bitDepths.isEmpty() || caps.bitDepths.contains(bitDepth) ||
            (bitDepth == 32 && caps.bitDepths.any { it >= 24 })
        return rateOk && bitOk
    }

    fun resolve(context: Context, store: Store): HiResResolved {
        val caps = activeOutput(context)
        val mode = store.hiresOutputMode
        var rate = store.hiresSampleRate
        var bits = store.hiresBitDepth.coerceIn(16, 32)

        if (caps != null) {
            if (rate != 0 && caps.sampleRates.isNotEmpty() && rate !in caps.sampleRates) {
                rate = caps.sampleRates.filter { it <= rate }.maxOrNull()
                    ?: caps.sampleRates.minOrNull()
                    ?: 0
            }
            if (caps.bitDepths.isNotEmpty()) {
                val allowed = caps.bitDepths.toMutableSet()
                if (allowed.any { it >= 24 }) allowed.add(32)
                if (bits !in allowed) {
                    bits = allowed.filter { it <= bits }.maxOrNull() ?: allowed.minOrNull() ?: 16
                }
            }
        }

        val wantHiRes = when (mode) {
            MODE_STANDARD -> false
            MODE_HIRES -> true
            MODE_AUTO -> caps?.supportsHiRes == true || store.hires
            else -> store.hires
        }
        val useFloat = wantHiRes && bits >= 24

        val note = buildString {
            append(caps?.deviceName ?: "unbekannt")
            append(" · ")
            append(
                when (mode) {
                    MODE_STANDARD -> "Standard"
                    MODE_HIRES -> "Hi-Res"
                    MODE_AUTO -> "Auto"
                    else -> mode
                }
            )
            append(" · ")
            if (rate == 0) append("Rate Auto") else append("${rate / 1000f} kHz")
            append(" · ")
            append(if (bits >= 32) "Float32" else "$bits Bit")
            if (caps?.isBluetooth == true) append(" · BT (komprimiert)")
        }

        return HiResResolved(
            mode = mode,
            sampleRateHz = rate,
            bitDepth = bits,
            useFloatOutput = useFloat,
            deviceName = caps?.deviceName ?: "?",
            note = note
        )
    }

    fun applyToEngine(context: Context, store: Store) {
        val r = resolve(context, store)
        val hi = store.hiresOutputMode == MODE_HIRES ||
            (store.hiresOutputMode == MODE_AUTO && r.useFloatOutput) ||
            store.hiresOutputMode != MODE_STANDARD && store.hires
        store.hires = hi
        Engine.proc.preferFloatSink = false
        Engine.proc.floatOutput = false
        // Sample-Rate erzwingen (auch wenn Caps nur 48 kHz melden) – DSP resamplet
        val sr = store.hiresSampleRate
        Engine.proc.targetSampleRate = when {
            store.hiresOutputMode == MODE_STANDARD -> 0
            sr >= 44100 -> sr
            else -> 0
        }
    }

    fun audioInfoLine(context: Context, store: Store): String {
        val r = resolve(context, store)
        val modeLabel = when (store.hiresOutputMode) {
            MODE_STANDARD -> "Standard"
            MODE_HIRES -> "Hi-Res DSP (intern)"
            MODE_AUTO -> "Auto"
            else -> store.hiresOutputMode
        }
        val forced = Engine.proc.targetSampleRate
        val rateTxt = when {
            forced >= 44100 -> "DSP-Rate ${forced / 1000f} kHz"
            r.sampleRateHz == 0 -> "Rate Auto"
            else -> "${r.sampleRateHz / 1000f} kHz"
        }
        // Kein nachweisbarer Float-AudioTrack-Pfad in dieser Architektur
        val floatActive = Engine.proc.preferFloatSink && Engine.proc.floatOutput
        val session = when {
            floatActive -> "Ausgabe: Float (experimentell)"
            else -> "Ausgabe: 16-Bit PCM (stabil) – kein nachweisbarer Hi-Res-DAC-Pfad"
        }
        return "${r.deviceName} · $modeLabel · $rateTxt · $session"
    }
}

enum class HiResStatus {
    REQUESTED_FLOAT,
    SESSION_FLOAT_ACTIVE,
    SESSION_FALLBACK_16BIT,
    DEVICE_NO_HIRES,
    UNKNOWN_CAPABILITY
}

fun HiRes.resolveStatus(context: Context, requested: Boolean, sessionFloat: Boolean): HiResStatus {
    val caps = activeOutput(context)
    return when {
        caps == null -> HiResStatus.UNKNOWN_CAPABILITY
        caps.sampleRates.isEmpty() && caps.bitDepths.isEmpty() -> HiResStatus.UNKNOWN_CAPABILITY
        !caps.supportsHiRes && requested -> HiResStatus.DEVICE_NO_HIRES
        requested && sessionFloat -> HiResStatus.SESSION_FLOAT_ACTIVE
        requested && !sessionFloat -> HiResStatus.SESSION_FALLBACK_16BIT
        else -> HiResStatus.REQUESTED_FLOAT
    }
}
