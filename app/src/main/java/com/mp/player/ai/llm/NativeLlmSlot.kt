package com.mp.player.ai.llm

/**
 * Erweiterungspunkt für ein echtes On-Device-Modell (GGUF / MediaPipe / llama.cpp).
 *
 * Realistische Optionen auf Android (ohne Cloud):
 * - MediaPipe LLM Inference (Google AI Edge) – kleine Gemma/Phi-Varianten
 * - llama.cpp via JNI – GGUF z.B. TinyLlama, Qwen2 0.5B/1.5B Q4
 * - MLC-LLM
 *
 * Diese Klasse lädt KEIN Modell mit. Sie prüft nur, ob der Nutzer optional
 * eine Modell-Datei hinterlegt hat und ob genug RAM da ist.
 * Solange kein natives Backend angebunden ist, liefert [interpret] immer null
 * → Fallback auf Heuristik / IntentEngine.
 *
 * Erwarteter Dateipfad (App-intern, offline):
 *   filesDir/llm/intent-helper.gguf
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
        // Datei-Existenz ohne java.io in unit tests: nur non-blank path = "könnte existieren"
        // Echte File-Prüfung gehört in Android-Impl (PlayerToolsImpl / Context).
        return true
    }

    override fun interpret(text: String, hintPlaylists: List<String>): StructuredMusicIntent? {
        // Kein natives Backend in diesem Build – bewusst null (Fallback).
        // Hier später: Modell laden → Prompt mit erlaubten Feldern → JSON parsen → StructuredMusicIntent
        return null
    }

    override fun release() {
        loaded = false
        // nativ: free model weights
    }
}
