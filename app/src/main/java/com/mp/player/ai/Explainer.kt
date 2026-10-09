package com.mp.player.ai

import com.mp.player.AnalysisResult
import com.mp.player.HiResCapabilities
import java.util.Locale

data class Explanation(val text: String, val offerBassReset: Boolean)

/**
 * Erklaert den aktuellen Klang aus dem, was die DSP-Kette WIRKLICH gerade tut
 * (Equalizer, Bass/Hoehen, Limiter, ReplayGain, Hall) plus den Messwerten des laufenden Titels.
 * Es wird nichts geraten: Was nicht festgestellt werden kann, wird auch so gesagt.
 */
object Explainer {

    private fun f1(v: Float) = String.format(Locale.GERMANY, "%.1f", v)

    fun explain(
        topic: String,
        s: AudioSettings,
        analysis: AnalysisResult?,
        trackName: String?,
        device: HiResCapabilities?
    ): Explanation {
        val d = s.dsp
        val reasons = ArrayList<String>()
        var bassCulprit = false

        if (!d.on) {
            reasons += "Dein Equalizer/DSP ist aus – der Sound kommt unbearbeitet raus."
        } else {
            if (s.bassGainDb >= 3f) {
                reasons += "Bass-Regler${if (d.bassBoost) " + Bass-Boost" else ""} heben die Tiefen um ca. ${f1(s.bassGainDb)} dB an."
                bassCulprit = true
            }
            val lowMax = d.bands.filter { it.f <= 250f }.maxOfOrNull { it.g } ?: 0f
            if (lowMax >= 3f) {
                reasons += "Im 32-Band-Equalizer sind die tiefen Bänder (bis 250 Hz) um bis zu ${f1(lowMax)} dB angehoben."
                bassCulprit = true
            }
            if (d.pre >= 3f) reasons += "Die Vorverstärkung steht auf +${f1(d.pre)} dB."
            if (d.reverbMix >= 0.25f) reasons += "Der Hall ist aktiv (${(d.reverbMix * 100).toInt()} %) - das macht den Klang wuchtiger und weniger klar."
            if (!d.limiter && (s.bassGainDb > 6f || d.pre > 3f)) reasons += "Der Limiter ist aus - bei viel Anhebung kann es verzerren."
            if (s.trebleGainDb >= 4f && (topic == "hoehen" || topic == "allgemein")) {
                reasons += "Die Höhen sind um ca. ${f1(s.trebleGainDb)} dB angehoben."
            }
        }

        if (s.replayGainMode != 0 && s.replayGainDb >= 3f) {
            reasons += "ReplayGain hebt diesen Titel um ${f1(s.replayGainDb)} dB an (er ist von Haus aus eher leise)."
        } else if (s.replayGainMode != 0 && s.replayGainDb <= -3f) {
            reasons += "ReplayGain senkt diesen Titel um ${f1(-s.replayGainDb)} dB ab, weil er sehr laut gemastert ist."
        }

        if (analysis != null && !analysis.lufs.isNaN() && analysis.lufs >= -9f) {
            reasons += "Der Titel selbst ist dicht und laut gemastert (ca. ${f1(analysis.lufs)} LUFS)."
        }
        if (device != null && device.isBluetooth) {
            reasons += "Du hörst über Bluetooth (${device.deviceName}) - je nach Codec und Hersteller-Abstimmung färbt das den Bass zusätzlich."
        }

        val head = when (topic) {
            "bass" -> "Warum es so basslastig klingt:"
            "hoehen" -> "Warum die Höhen so klingen:"
            "verzerrung" -> "Mögliche Gründe für Verzerrung:"
            "hall" -> "Zum Hall:"
            "lautstaerke" -> "Zur Lautstärke:"
            else -> "So ist dein Klang gerade eingestellt:"
        }
        val about = if (trackName != null) " (läuft: $trackName)" else ""

        val body = if (reasons.isEmpty()) {
            val noAnalysis = if (analysis == null && trackName != null) " Den Titel selbst kann ich nicht beurteilen, weil er noch nicht analysiert ist (Menü → Audioanalyse)." else ""
            "Ich finde in deinen Einstellungen nichts, was den Klang künstlich verändert - Equalizer ist flach, kein Boost, kein Hall.$noAnalysis " +
                "Dann liegt es am Titel selbst oder an deinem Kopfhörer/Lautsprecher."
        } else {
            reasons.joinToString("\n") { "• $it" }
        }

        val offer = bassCulprit && (topic == "bass" || topic == "allgemein")
        val tail = if (offer) "\n\nSoll ich Bass-Regler, Bass-Boost und die angehobenen Tiefen-Bänder auf 0 setzen? (Ja/Nein)" else ""
        return Explanation("$head$about\n$body$tail", offer)
    }
}
