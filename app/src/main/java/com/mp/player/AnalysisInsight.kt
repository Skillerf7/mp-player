package com.mp.player

enum class ValueKind { MEASURED, ESTIMATED, UNKNOWN }

data class FeatureNote(
    val name: String,
    val kind: ValueKind,
    val detail: String
)

object AnalysisInsight {

    fun notes(a: AnalysisResult?, deep: DeepSnapshot? = null): List<FeatureNote> {
        if (a == null) return listOf(FeatureNote("analysis", ValueKind.UNKNOWN, "noch nicht analysiert"))
        val out = ArrayList<FeatureNote>()
        if (!a.lufs.isNaN()) out += FeatureNote("LUFS", ValueKind.MEASURED, "BS.1770 integriert")
        else out += FeatureNote("LUFS", ValueKind.UNKNOWN, "nicht messbar")
        out += FeatureNote("peak_rms", ValueKind.MEASURED, "Sample-Peak und RMS")
        if (a.bpm > 0f) out += FeatureNote(
            "BPM",
            if (a.bpmConfidence >= 0.45f) ValueKind.ESTIMATED else ValueKind.UNKNOWN,
            "Konfidenz ${(a.bpmConfidence * 100).toInt()} % (Halb-/Doppeltempo geprüft)"
        )
        if (a.spectralCentroidHz > 0f) out += FeatureNote("spectral", ValueKind.MEASURED, "Centroid/Rolloff/Flux/Flatness")
        if (a.bassEnergy > 0f || a.subBassEnergy > 0f)
            out += FeatureNote("bass_bands", ValueKind.MEASURED, "Subbass/Bass/Mid/High (FFT)")
        if (a.rhythmRegularity > 0f)
            out += FeatureNote("rhythm", ValueKind.ESTIMATED, "Rhythmusstabilität aus Envelope")
        if (deep != null && deep.frames > 0) {
            out += FeatureNote("8_bands", ValueKind.MEASURED, "8 Frequenzbänder")
            out += FeatureNote("onset_punch", ValueKind.MEASURED, "Onset-Dichte & Punch")
            out += FeatureNote("dynamics", ValueKind.MEASURED, "Crest, Dynamikvariation, Drop-Jump")
            if (deep.keyGuess.isNotBlank())
                out += FeatureNote("key", ValueKind.ESTIMATED, "Tonart-Schätzung ${deep.keyGuess} (Konf. ${(deep.keyConfidence * 100).toInt()} %)")
            out += FeatureNote("harmonicity", ValueKind.ESTIMATED, "Harmonizitäts-Proxy")
            out += FeatureNote("vocal_presence", ValueKind.ESTIMATED, "Präsenzband – keine Sänger-Erkennung")
        }
        return out
    }

    fun summary(a: AnalysisResult?, deep: DeepSnapshot? = null): String {
        if (a == null) return "Keine Analyse."
        val parts = ArrayList<String>()
        if (a.bpm > 0f) parts += "BPM ${a.bpm.toInt()} (${(a.bpmConfidence * 100).toInt()} %)"
        if (!a.lufs.isNaN()) parts += "LUFS ${"%.1f".format(a.lufs)}"
        if (a.subBassEnergy > 0.05f) parts += "Sub ${"%.0f".format(a.subBassEnergy * 100)} %"
        if (deep != null && deep.frames > 0) {
            parts += "Onsets ${"%.1f".format(deep.onsetDensity)}/s"
            parts += "Punch ${"%.0f".format(deep.punchIndex * 100)} %"
            if (deep.keyGuess.isNotBlank()) parts += "Key ${deep.keyGuess}"
            parts += "Signalabdeckung ${"%.0f".format(deep.quality * 100)} % (keine Trefferquote)"
        }
        if (parts.isEmpty()) return "Analyse vorhanden, wenige Merkmale."
        return parts.joinToString(" · ")
    }

    fun deepReport(a: AnalysisResult?, deep: DeepSnapshot?): String {
        if (a == null) return "Keine Basisanalyse."
        val sb = StringBuilder()
        sb.appendLine("— BASIS (MESSUNG/SCHÄTZUNG) —")
        sb.appendLine(summary(a, deep))
        sb.appendLine("Peak ${"%.1f".format(a.peakDb)} dBFS · RMS ${"%.1f".format(a.rmsDb)} dBFS · DR ${"%.1f".format(a.dynamicRangeDb())} dB")
        sb.appendLine("Energie Intro/Mid/Late: ${"%.0f".format(a.energyIntro*100)}/${"%.0f".format(a.energyMid*100)}/${"%.0f".format(a.energyLate*100)} %")
        if (deep == null || deep.frames == 0) {
            sb.appendLine("\nKeine Deep-Daten – Analyse erneut starten (FEATURE_VERSION 5).")
            return sb.toString()
        }
        sb.appendLine("\n— DEEP DSP (${DeepAnalysis.MODULE}, Abdeckung ${"%.0f".format(deep.quality*100)} % – keine Trefferquote) —")
        sb.appendLine("8 Bänder Sub→Air: ${pct(deep.bandSub)} ${pct(deep.bandBass)} ${pct(deep.bandLowMid)} ${pct(deep.bandMid)} ${pct(deep.bandHighMid)} ${pct(deep.bandPresence)} ${pct(deep.bandBrilliance)} ${pct(deep.bandAir)}")
        sb.appendLine("Bandwidth ${"%.0f".format(deep.spectralBandwidthHz)} Hz · Brightness ${pct(deep.brightness)}")
        sb.appendLine("Onsets ${"%.2f".format(deep.onsetDensity)}/s · Punch ${pct(deep.punchIndex)} · Silence ${pct(deep.silenceRatio)}")
        sb.appendLine("Crest ${"%.1f".format(deep.crestFactorDb)} dB · DynVar ${pct(deep.dynamicVariation)} · DropJump ${pct(deep.dropJump)}")
        sb.appendLine("Energy-Slope ${"%.2f".format(deep.energySlope)} · Groove ${pct(deep.grooveScore)} (geschätzt)")
        if (deep.keyGuess.isNotBlank())
            sb.appendLine("Tonart ${deep.keyGuess} · Konfidenz ${pct(deep.keyConfidence)} · ESTIMATED")
        sb.appendLine("Harmonizität ${pct(deep.harmonicity)} · Vocal-Presence ${pct(deep.vocalPresence)} · ESTIMATED")
        sb.appendLine("\nHinweis: Instrumente/Genre werden nicht als Fakt behauptet.")
        return sb.toString()
    }

    private fun pct(v: Float) = "${"%.0f".format(v * 100)} %"
}
