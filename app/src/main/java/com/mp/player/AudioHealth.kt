package com.mp.player

/**
 * Technische Qualitätsindikatoren – keine subjektive „gute/schlechte Musik“.
 */
data class AudioHealthReport(
    val issues: List<String>,
    val notes: List<String>,
    val severity: Int // 0 ok, 1 mild, 2 notable
) {
    val isOk: Boolean get() = severity == 0
}

object AudioHealth {
    fun assess(a: AnalysisResult?, deep: DeepSnapshot? = null): AudioHealthReport {
        if (a == null) return AudioHealthReport(listOf("Keine Analyse"), emptyList(), 1)
        val issues = ArrayList<String>()
        val notes = ArrayList<String>()
        if (!a.lufs.isNaN()) {
            when {
                a.lufs < -28f -> issues += "Sehr leise (LUFS ${"%.1f".format(a.lufs)})"
                a.lufs > -8f -> issues += "Sehr laut / möglicherweise stark begrenzt (LUFS ${"%.1f".format(a.lufs)})"
                else -> notes += "Lautheit im üblichen Bereich"
            }
        }
        if (!a.peakDb.isNaN() && a.peakDb > -0.3f) {
            issues += "Peak nahe 0 dBFS (${"%.1f".format(a.peakDb)}) – mögliches Clipping"
        }
        val dr = a.dynamicRangeDb()
        if (!dr.isNaN()) {
            when {
                dr < 4f -> issues += "Sehr geringe Dynamik (Crest ${"%.1f".format(dr)} dB)"
                dr > 20f -> notes += "Hohe Dynamik (Crest ${"%.1f".format(dr)} dB)"
                else -> notes += "Dynamik Crest ${"%.1f".format(dr)} dB"
            }
        }
        if (deep != null && deep.frames > 0) {
            if (deep.silenceRatio > 0.35f) issues += "Auffällig viel Stille (${"%.0f".format(deep.silenceRatio * 100)} %)"
            if (deep.quality < 0.4f) notes += "Analyse-Qualität begrenzt"
            else notes += "Deep-Qualität ${"%.0f".format(deep.quality * 100)} %"
        }
        if (a.partial) notes += "Nur Teilanalyse (langer Titel)"
        if (a.bpm > 0f && a.bpmConfidence < 0.35f) notes += "BPM unsicher"
        val sev = when {
            issues.any { it.contains("Clipping") || it.contains("Sehr laut") } -> 2
            issues.isNotEmpty() -> 1
            else -> 0
        }
        return AudioHealthReport(issues, notes, sev)
    }

    fun shortLine(a: AnalysisResult?, deep: DeepSnapshot? = null): String {
        val r = assess(a, deep)
        return when {
            r.issues.isNotEmpty() -> "Audio-Check: " + r.issues.take(2).joinToString(" · ")
            r.notes.isNotEmpty() -> "Audio-Check: " + r.notes.take(2).joinToString(" · ")
            else -> "Audio-Check: ok"
        }
    }
}
