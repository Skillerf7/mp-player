package com.mp.player.ai.llm

import android.content.Context
import com.mp.player.ai.UserIntent

/**
 * Koordinator:
 * 1. Trainiertes Modell (assets/ml) – wenn Context gesetzt und Dateien da
 * 2. EmbeddedIntentBrain – Fallback-Heuristik
 * 3. NativeLlmSlot – optional später
 *
 * Kein Internet. Modell ist Teil der APK.
 */
class LlmCoordinator(
    private val context: Context? = null,
    private val embedded: OptionalLlmInterpreter = EmbeddedIntentBrain,
    private val native: OptionalLlmInterpreter = NativeLlmSlot(),
    private val unloadAfterUse: Boolean = true
) {
    private val trained: OptionalLlmInterpreter? by lazy {
        runCatching { TrainedIntentModel.get(context) }.getOrNull()
    }

    fun tryInterpret(
        text: String,
        parsed: UserIntent,
        hintPlaylists: List<String> = emptyList()
    ): StructuredMusicIntent? {
        if (!LlmGate.needsHelp(parsed, text)) return null

        val chain = listOfNotNull(trained, embedded, native).filter { it.isAvailable() }
        for (slot in chain) {
            try {
                val result = slot.interpret(text, hintPlaylists)
                if (result != null && result.rawConfidence >= 0.4f) {
                    if (unloadAfterUse) runCatching { slot.release() }
                    return result
                }
            } catch (_: Throwable) {
                runCatching { slot.release() }
            }
        }
        return null
    }
}
