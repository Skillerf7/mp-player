package com.mp.player

import org.junit.Test
import java.io.File

/**
 * Infrastruktur für echte Audio-BPM-Benchmarks.
 *
 * Erwartetes Layout (lokal, optional):
 *   testdata/bpm/<referenz_bpm>__<name>.wav|mp3
 * z.B. testdata/bpm/140__hardtekk_loop.wav
 *
 * Wenn der Ordner fehlt oder leer ist: Test wird als SKIPPED dokumentiert,
 * keine erfundenen Accuracy-Zahlen.
 */
class BpmRealAudioBenchmarkPrep {
    @Test fun reportAvailability() {
        val dir = File("testdata/bpm")
        if (!dir.isDirectory || dir.list().isNullOrEmpty()) {
            println("=== REAL AUDIO BPM BENCHMARK ===")
            println("STATUS: keine verifizierten Audiodateien unter testdata/bpm/")
            println("Synthetischer Benchmark bleibt gültig (BpmBenchmarkTest).")
            println("Für echte Messung: Dateien ablegen als <bpm>__name.ext und Analyse-Pipeline anbinden.")
            return
        }
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        println("Found ${files.size} candidate files (analysis of decoded PCM not run in pure unit test without MediaCodec).")
        files.take(20).forEach { println(" - ${it.name}") }
    }
}
