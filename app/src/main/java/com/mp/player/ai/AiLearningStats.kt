package com.mp.player.ai

import com.mp.player.ai.llm.LlmCoordinator

/**
 * Echte Lern- und Modellstatus-Werte aus laufenden Komponenten.
 * Keine erfundenen Zahlen — nur was Memory / PersonalSignals / Coordinator hergeben.
 */
data class AiModelStatus(
    val playerBrainActive: Boolean,
    val intentModelLoaded: Boolean,
    val offline: Boolean,
    val memoryEnabled: Boolean,
    val learningEnabled: Boolean,
    val coordinatorLabel: String
)

data class AiLearningStats(
    val preferenceCount: Int,
    val feedbackEvents: Int,
    val memoryItemCount: Int,
    val bassAffinity: Float?,
    val tempoAffinity: Float?,
    val energyAffinity: Float?,
    val moodAffinity: Float?
)

object AiStatsCollector {

    fun modelStatus(
        coordinator: LlmCoordinator?,
        privacy: AiPrivacyStore?
    ): AiModelStatus {
        val label = runCatching { coordinator?.status() }.getOrNull() ?: "PlayerBrain:unbekannt"
        val brain = label.contains("PlayerBrain:aktiv", ignoreCase = true)
        val intent = label.contains("IntentModel:aktiv", ignoreCase = true)
        return AiModelStatus(
            playerBrainActive = brain,
            intentModelLoaded = intent,
            offline = true,
            memoryEnabled = privacy?.memoryEnabled ?: true,
            learningEnabled = privacy?.learningEnabled ?: true,
            coordinatorLabel = label
        )
    }

    fun learningStats(
        memory: MemoryEngine?,
        signals: PersonalSignals?
    ): AiLearningStats {
        val likes = runCatching { memory?.active(MemKind.LIKE, 0.05f).orEmpty() }.getOrNull().orEmpty()
        val dislikes = runCatching { memory?.active(MemKind.DISLIKE, 0.05f).orEmpty() }.getOrNull().orEmpty()
        val memCount = runCatching { memory?.countAll() ?: 0 }.getOrNull() ?: 0
        val prefCount = likes.size + dislikes.size

        val dim = signals?.dimensionAffinity.orEmpty()
        fun dimOrNull(key: String): Float? = dim[key]?.takeIf { kotlin.math.abs(it) > 1e-4f }

        val accepted = signals?.acceptedRecs?.values?.sum() ?: 0
        val rejected = signals?.rejectedRecs?.values?.sum() ?: 0
        val trackEvents = signals?.trackAffinity?.size ?: 0

        return AiLearningStats(
            preferenceCount = prefCount,
            feedbackEvents = accepted + rejected + trackEvents,
            memoryItemCount = memCount,
            bassAffinity = dimOrNull("bass"),
            tempoAffinity = dimOrNull("tempo"),
            energyAffinity = dimOrNull("energy"),
            moodAffinity = listOf("mood_sad", "mood_calm", "mood_party", "mood_aggressive")
                .mapNotNull { dimOrNull(it) }
                .maxByOrNull { kotlin.math.abs(it) }
        )
    }
}
