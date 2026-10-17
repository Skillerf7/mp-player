package com.mp.player.ai

/**
 * Lesbare Lernstatistik für UI (keine Roh-DB-Dumps).
 */
data class LearningStats(
    val longLikes: Int,
    val longDislikes: Int,
    val facts: Int,
    val patterns: Int,
    val ephemeral: Int,
    val recentLabels: List<String>,
    val topPrefer: List<String>,
    val topAvoid: List<String>,
    val dimensionSummary: List<String>
) {
    fun describe(): String = buildString {
        append("Vorlieben: $longLikes · Vermeiden: $longDislikes · Fakten: $facts · Muster: $patterns")
        if (ephemeral > 0) append(" · Situativ: $ephemeral")
    }
}

object LearningStatsBuilder {
    fun from(memory: MemoryEngine, recentLearned: List<String> = emptyList(), dims: Map<String, Float> = emptyMap()): LearningStats {
        val all = try {
            // active() filtert abgelaufene; wir zählen über hints + active
            val likes = memory.active(MemKind.LIKE, 0.15f)
            val dislikes = memory.active(MemKind.DISLIKE, 0.15f)
            val facts = memory.active(MemKind.FACT, 0.15f)
            val patterns = memory.active(MemKind.PATTERN, 0.15f)
            val ephemeral = (likes + dislikes + facts).count { it.scope == MemScope.EPHEMERAL }
            val topP = likes.sortedByDescending { memory.effective(it) }.take(5).map { it.label }
            val topA = dislikes.sortedByDescending { memory.effective(it) }.take(5).map { it.label }
            val dimLines = dims.entries
                .sortedByDescending { kotlin.math.abs(it.value) }
                .take(6)
                .map { (k, v) -> "$k: ${"%.0f".format(v * 100)}%" }
            LearningStats(
                longLikes = likes.count { it.scope == MemScope.LONG },
                longDislikes = dislikes.count { it.scope == MemScope.LONG },
                facts = facts.size,
                patterns = patterns.size,
                ephemeral = ephemeral,
                recentLabels = recentLearned.takeLast(8),
                topPrefer = topP,
                topAvoid = topA,
                dimensionSummary = dimLines
            )
        } catch (_: Throwable) {
            LearningStats(0, 0, 0, 0, 0, emptyList(), emptyList(), emptyList(), emptyList())
        }
        return all
    }
}
