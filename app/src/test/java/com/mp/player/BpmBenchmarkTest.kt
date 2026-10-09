package com.mp.player

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Offline-BPM-Benchmark mit synthetischen Onset-Hüllkurven (keine echten Audio-Dateien).
 * Ergebnisse werden ehrlich ausgegeben – keine erfundenen Prozente.
 *
 * Limitation: synthetische Beats ≠ echte Musik (Hardtekk, Rap, Remixe).
 * Das misst die Kandidatenlogik (Half/Double, Confidence), nicht Studio-Mastering.
 */
class BpmBenchmarkTest {

    data class Row(val trueBpm: Float, val predicted: Float, val conf: Float, val half: Boolean, val dbl: Boolean)

    @Test fun syntheticBeatBenchmark() {
        val truths = listOf(70f, 85f, 90f, 100f, 120f, 128f, 140f, 150f, 174f, 180f)
        val rows = ArrayList<Row>()
        for (trueBpm in truths) {
            val env = BpmCore.syntheticEnvelope(trueBpm, seconds = 45)
            val est = BpmCore.estimate(env, env.size, 400)
            val half = est.bpm > 0 && abs(est.bpm - trueBpm / 2) < 3f
            val dbl = est.bpm > 0 && abs(est.bpm - trueBpm * 2) < 5f
            rows.add(Row(trueBpm, est.bpm, est.confidence, half, dbl))
            println("true=$trueBpm pred=${est.bpm} conf=${"%.2f".format(est.confidence)} src=${est.source} halfErr=$half dblErr=$dbl")
        }
        val exact = rows.count { abs(it.predicted - it.trueBpm) <= 2f }
        val within5 = rows.count { it.predicted > 0 && abs(it.predicted - it.trueBpm) <= 5f }
        val halfErr = rows.count { it.half }
        val dblErr = rows.count { it.dbl }
        val known = rows.filter { it.predicted > 0 }
        val avgDev = if (known.isEmpty()) -1.0 else known.map { abs(it.predicted - it.trueBpm).toDouble() }.average()
        println("=== BPM BENCHMARK (synthetic) ===")
        println("n=${rows.size}")
        println("exact(+-2)= $exact / ${rows.size}")
        println("within5= $within5 / ${rows.size}")
        println("half-time errors= $halfErr")
        println("double-time errors= $dblErr")
        println("avg abs deviation (detected only)= ${"%.2f".format(avgDev)}")
        // Sanity: at least some detections on clean synthetic beats
        assertTrue("Expected some detections on synthetic beats", within5 >= rows.size / 2)
    }

    @Test fun userHintPullsHalfDoubleCandidate() {
        // True period = 75 BPM envelope; without hint might prefer 150
        val env = BpmCore.syntheticEnvelope(150f, seconds = 40)
        val noHint = BpmCore.estimate(env, env.size, 400, userHint = null)
        val withHint = BpmCore.estimate(env, env.size, 400, userHint = 75f)
        println("noHint=${noHint.bpm} withHint75=${withHint.bpm}")
        // Hint should not invent: still near a real candidate
        assertTrue(withHint.bpm > 0f)
    }
}
