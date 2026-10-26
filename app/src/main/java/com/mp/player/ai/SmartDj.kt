package com.mp.player.ai

import kotlin.math.abs

/**
 * Leichte Queue-Ordnung: Energie/Tempo-Übergänge glätten, keine Beatmatching-Claims.
 */
object SmartDj {
    data class Ordered(val tracks: List<TrackInfo>, val note: String)

    fun smoothEnergyOrder(items: List<TrackInfo>): Ordered {
        if (items.size <= 2) return Ordered(items, "zu kurz für Verlauf")
        val scored = items.map { info ->
            val e = MusicalProfile.from(info).energy ?: 0.5f
            val bpm = info.analysis?.bpm?.takeIf { it > 0f } ?: 120f
            info to (e to bpm)
        }
        // Greedy: start mid energy, then nearest neighbor in energy+tempo space
        val remaining = scored.toMutableList()
        remaining.sortBy { it.second.first }
        val start = remaining.removeAt(remaining.size / 2)
        val out = ArrayList<TrackInfo>()
        out += start.first
        var lastE = start.second.first
        var lastB = start.second.second
        while (remaining.isNotEmpty()) {
            var bestI = 0
            var bestD = Float.MAX_VALUE
            for (i in remaining.indices) {
                val (e, b) = remaining[i].second
                val d = abs(e - lastE) * 2.2f + abs(b - lastB) / 80f
                if (d < bestD) {
                    bestD = d
                    bestI = i
                }
            }
            val next = remaining.removeAt(bestI)
            out += next.first
            lastE = next.second.first
            lastB = next.second.second
        }
        return Ordered(out, "Energie/Tempo-Übergänge geglättet (kein Beatmatching)")
    }
}
