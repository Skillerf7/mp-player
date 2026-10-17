package com.mp.player.ai.llm

import android.app.ActivityManager
import android.content.Context
import kotlin.math.max

/**
 * Modellverwaltung für Secret Player (S20 FE und älter).
 * Internes Modell liegt in Assets + Kotlin-Code; externes GGUF optional.
 */
data class ModelInfo(
    val name: String,
    val kind: Kind,
    val sizeHint: String,
    val license: String,
    val loaded: Boolean,
    val fallback: Boolean,
    val detail: String
) {
    enum class Kind { INTERNAL_BRAIN, TRAINED_INTENT, HEURISTIC, EXTERNAL_GGUF }
}

object ModelManager {

    const val MIN_RAM_MB_INTERNAL = 256L
    const val MIN_RAM_MB_GGUF = 768L

    fun availableRamMb(ctx: Context?): Long {
        if (ctx == null) {
            val rt = Runtime.getRuntime()
            return (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / (1024 * 1024)
        }
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mem)
            mem.availMem / (1024 * 1024)
        } catch (_: Throwable) {
            val rt = Runtime.getRuntime()
            (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / (1024 * 1024)
        }
    }

    fun canRunInternal(ctx: Context?): Boolean = availableRamMb(ctx) >= MIN_RAM_MB_INTERNAL

    fun canRunGguf(ctx: Context?): Boolean = availableRamMb(ctx) >= MIN_RAM_MB_GGUF

    fun snapshot(ctx: Context?, coordinator: LlmCoordinator?): List<ModelInfo> {
        val ram = availableRamMb(ctx)
        val list = ArrayList<ModelInfo>()
        list += ModelInfo(
            name = "PlayerBrain",
            kind = ModelInfo.Kind.INTERNAL_BRAIN,
            sizeHint = "Code + ~72 KB Intent-Gewichte",
            license = "Projektintern",
            loaded = coordinator != null && canRunInternal(ctx),
            fallback = false,
            detail = "Internes Modell: Intent + Kontext + Memory + Prefs. Offline."
        )
        list += ModelInfo(
            name = "TrainedIntentModel",
            kind = ModelInfo.Kind.TRAINED_INTENT,
            sizeHint = "~72 KB (assets/ml/intent_model.bin)",
            license = "Projekttraining (ml/scripts)",
            loaded = true,
            fallback = false,
            detail = "Multi-Label LogReg, Hashing-Features, in der APK."
        )
        list += ModelInfo(
            name = "EmbeddedIntentBrain",
            kind = ModelInfo.Kind.HEURISTIC,
            sizeHint = "Kotlin-Heuristik",
            license = "Projektintern",
            loaded = true,
            fallback = true,
            detail = "Fallback wenn Intent-Modell unsicher ist."
        )
        val ggufOk = canRunGguf(ctx)
        list += ModelInfo(
            name = "Native GGUF (optional)",
            kind = ModelInfo.Kind.EXTERNAL_GGUF,
            sizeHint = "0,7–2 GB je nach Quantisierung",
            license = "Abhängig vom gewählten Modell (z. B. Apache-2.0 / Llama)",
            loaded = false,
            fallback = true,
            detail = if (ggufOk)
                "Slot bereit. Modelldatei separat importieren — nicht in der APK."
            else
                "Zu wenig freier RAM (~${ram} MB). Internes Modell wird genutzt."
        )
        return list
    }

    fun statusLine(ctx: Context?, coordinator: LlmCoordinator?): String {
        val ram = availableRamMb(ctx)
        val mode = when {
            !canRunInternal(ctx) -> "Notbetrieb (wenig RAM)"
            else -> "Internes Modell aktiv"
        }
        val coord = coordinator?.status() ?: "Koordinator:n/a"
        return "$mode · RAM frei ~${ram} MB · $coord"
    }

    /** Sichere Obergrenze Kontext für kleine Geräte. */
    fun maxContextTokens(ctx: Context?): Int = when {
        availableRamMb(ctx) < 512 -> 256
        availableRamMb(ctx) < 1024 -> 512
        else -> 1024
    }

    fun maxOutputTokens(ctx: Context?): Int = max(32, maxContextTokens(ctx) / 8)
}
