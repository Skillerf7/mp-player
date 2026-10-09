package com.mp.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager

// Liefert die TATSÄCHLICH vom aktuell aktiven Audioausgang unterstützten
// Sample Rates und Bit-Tiefen (aus AudioDeviceInfo, nicht geraten/gemockt).
// Für Punkt 11: die UI soll damit nicht unterstützte Optionen ausblenden/
// ausgrauen statt sie einfach anzubieten.

data class HiResCapabilities(
    val deviceName: String,
    val isWired: Boolean,
    val isBluetooth: Boolean,
    val sampleRates: List<Int>,   // z.B. [44100, 48000, 96000]
    val bitDepths: List<Int>,     // aus AudioFormat.ENCODING_PCM_* abgeleitet, z.B. [16, 24, 32]
    val supportsHiRes: Boolean    // true, wenn >44.1/48kHz ODER >16Bit unterstützt wird
)

object HiRes {

    private val encodingToBits = mapOf(
        AudioFormat.ENCODING_PCM_16BIT to 16,
        AudioFormat.ENCODING_PCM_24BIT_PACKED to 24,
        AudioFormat.ENCODING_PCM_32BIT to 32,
        AudioFormat.ENCODING_PCM_FLOAT to 32
    )

    /** Alle aktuell verfügbaren Wiedergabe-Ausgänge (Lautsprecher, Kopfhörer, Bluetooth, USB-DAC ...). */
    fun availableOutputs(context: Context): List<HiResCapabilities> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.map { toCapabilities(it) }
    }

    /** Ausgang, über den gerade tatsächlich wiedergegeben wird (best effort: bevorzugt USB/wired/BT vor Lautsprecher). */
    fun activeOutput(context: Context): HiResCapabilities? {
        val outs = availableOutputs(context)
        val priority = listOf(
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        )
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val best = devices.minByOrNull { d -> priority.indexOf(d.type).let { if (it < 0) 99 else it } }
        return best?.let { toCapabilities(it) }
    }

    private fun toCapabilities(d: AudioDeviceInfo): HiResCapabilities {
        val rates = d.sampleRates?.toList()?.sorted() ?: emptyList()
        val bits = d.encodings.toList().mapNotNull { encodingToBits[it] }.distinct().sorted()
        val wired = d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET
        val bt = d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || d.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        return HiResCapabilities(
            deviceName = d.productName?.toString() ?: "Unbekanntes Gerät",
            isWired = wired,
            isBluetooth = bt,
            sampleRates = rates,
            bitDepths = bits,
            // Bluetooth (A2DP/BLE) läuft praktisch immer komprimiert (SBC/AAC/aptX) - echtes
            // Hi-Res ist da Marketing, nicht Technik. Nur bei kabelgebundenem/USB-Ausgang als
            // "echtes" Hi-Res werten, wenn Rate/Tiefe das hergeben.
            supportsHiRes = wired && (rates.any { it > 48000 } || bits.any { it > 16 })
        )
    }

    /** Ob eine konkrete Kombination auf dem aktuellen Ausgang wirklich unterstützt wird. */
    fun isSupported(caps: HiResCapabilities, sampleRate: Int, bitDepth: Int): Boolean =
        (caps.sampleRates.isEmpty() || caps.sampleRates.contains(sampleRate)) &&
            (caps.bitDepths.isEmpty() || caps.bitDepths.contains(bitDepth))
}


/** Angeforderter vs. bestätigbarer Zustand – nie „aktiv“ ohne Nachweis. */
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
