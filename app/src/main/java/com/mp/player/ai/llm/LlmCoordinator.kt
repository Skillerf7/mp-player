package com.mp.player.ai.llm

import android.content.Context
import com.mp.player.ai.ConversationState
import com.mp.player.ai.MemoryEngine
import com.mp.player.ai.UserIntent

/**
 * Koordinator der lokalen KI-Schicht (alles offline, im Player-Code/Assets).
 *
 * Reihenfolge:
 * 1. [PlayerBrain] — internes Modell (TrainedIntent + Kontext + Prefs + Memory)
 * 2. [TrainedIntentModel] — falls Brain nicht greift, direkt
 * 3. [EmbeddedIntentBrain] — Heuristik-Fallback
 * 4. [NativeLlmSlot] — optional späteres GGUF (Datei-Import), standardmäßig null
 *
 * Kein Internet. Kein Anspruch auf „Selbsttraining des LLM“ — nur Intent/Ranking-Lernen.
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

    private val brain: PlayerBrain by lazy {
        PlayerBrain(trained = trained, embedded = embedded)
    }

    /** Optional: Gesprächs- und Memory-Kontext für bessere Interpretation. */
    @Volatile var conversationState: ConversationState? = null
    @Volatile var memoryEngine: MemoryEngine? = null

    fun tryInterpret(
        text: String,
        parsed: UserIntent,
        hintPlaylists: List<String> = emptyList()
    ): StructuredMusicIntent? {
        if (!LlmGate.needsHelp(parsed, text)) return null

        // 1) Internes Player-Brain (mit Kontext)
        try {
            val r = brain.interpretWithContext(text, hintPlaylists, conversationState, memoryEngine)
            if (r != null && r.rawConfidence >= 0.4f) {
                if (unloadAfterUse) runCatching { brain.release() }
                return r
            }
        } catch (_: Throwable) {
            runCatching { brain.release() }
        }

        // 2) Rest der Kette
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

    fun status(): String {
        val parts = mutableListOf<String>()
        parts += if (brain.isAvailable()) "PlayerBrain:aktiv" else "PlayerBrain:aus"
        parts += if (trained?.isAvailable() == true) "IntentModel:aktiv" else "IntentModel:fehlt"
        parts += if (embedded.isAvailable()) "Heuristik:aktiv" else "Heuristik:aus"
        parts += if (native.isAvailable()) "NativeGGUF:bereit" else "NativeGGUF:kein Modell"
        return parts.joinToString(" · ")
    }
}
