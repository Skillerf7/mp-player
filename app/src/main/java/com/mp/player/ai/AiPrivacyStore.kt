package com.mp.player.ai

import android.content.Context

/**
 * Datenschutz- und Lern-Schalter für die lokale KI.
 * Alles standardmäßig lokal; kein Upload.
 *
 * Deaktiviertes Lernen speichert keine *neuen* Präferenzen.
 * Bereits gespeicherte Daten bleiben, bis der Nutzer sie ausdrücklich löscht.
 */
class AiPrivacyStore(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("ai_privacy", Context.MODE_PRIVATE)

    /** Personal Learning (Play/Skip/Accept + Dimensions-Affinitäten). */
    var learningEnabled: Boolean
        get() = p.getBoolean("learning", true)
        set(v) { p.edit().putBoolean("learning", v).apply() }

    /** MemoryEngine darf neue Einträge speichern. */
    var memoryEnabled: Boolean
        get() = p.getBoolean("memory", true)
        set(v) { p.edit().putBoolean("memory", v).apply() }

    var storeConversations: Boolean
        get() = p.getBoolean("store_chat", true)
        set(v) { p.edit().putBoolean("store_chat", v).apply() }

    var storeEphemeralPrefs: Boolean
        get() = p.getBoolean("store_ephemeral", true)
        set(v) { p.edit().putBoolean("store_ephemeral", v).apply() }

    /** Optionaler GGUF-Import (Pfad); leer = nur internes Modell. */
    var externalModelPath: String
        get() = p.getString("gguf_path", "") ?: ""
        set(v) { p.edit().putString("gguf_path", v).apply() }

    fun resetLearningFlags() {
        p.edit()
            .putBoolean("learning", true)
            .putBoolean("memory", true)
            .putBoolean("store_chat", true)
            .putBoolean("store_ephemeral", true)
            .apply()
    }
}
