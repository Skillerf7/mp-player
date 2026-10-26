package com.mp.player.ai.llm

/**
 * Optionaler Slot für ein externes quantisiertes GGUF-Modell (llama.cpp / vergleichbar).
 *
 * Standard: **nicht aktiv** — Secret Player nutzt das interne Modell
 * ([PlayerBrain] + [TrainedIntentModel] in Assets/Kotlin-Code).
 *
 * Wenn später eine GGUF-Datei unter dem App-Dateipfad liegt und natives Backend
 * angebunden ist, kann dieser Slot echte generative Inferenz liefern.
 * Bis dahin: immer null (sauberer Fallback, kein Fake-LLM).
 *
 * Speicher-Budget S20 FE: Modell nur laden wenn RAM ≥ [LlmGate.MIN_RAM_MB],
 * nach Nutzung entladen, nie auf dem UI-Thread.
 */
class NativeLlmSlot(
    private val modelPathProvider: () -> String? = { null },
    private val availableRamMb: () -> Long = {
        val rt = Runtime.getRuntime()
        (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / (1024 * 1024)
    }
) : OptionalLlmInterpreter {

    @Volatile private var loaded = false

    override fun isAvailable(): Boolean {
        val path = modelPathProvider() ?: return false
        if (path.isBlank()) return false
        if (availableRamMb() < LlmGate.MIN_RAM_MB) return false
        return true
    }

    override fun interpret(text: String, hintPlaylists: List<String>): StructuredMusicIntent? {
        // Kein natives Backend in diesem Build – bewusst null (Fallback auf PlayerBrain).
        return null
    }

    override fun release() {
        loaded = false
    }
}
