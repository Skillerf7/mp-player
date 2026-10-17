package com.mp.player.ai.llm

import com.mp.player.ai.ConversationState
import com.mp.player.ai.MemoryEngine
import com.mp.player.ai.MemoryHints
import com.mp.player.ai.Mood
import com.mp.player.ai.PreferenceExtractor
import com.mp.player.ai.PreferenceSignal
import com.mp.player.ai.TextUtil
import com.mp.player.ai.UserIntent

/**
 * Internes Player-KI-Modell (liegt im App-Code + Assets, kein Cloud-Upload).
 *
 * Kombiniert:
 * 1. TrainedIntentModel (assets/ml/intent_model.bin) — echte Matrix-Inferenz
 * 2. Gesprächskontext (ConversationState)
 * 3. Memory-Hints (Langzeit / situativ)
 * 4. PreferenceExtractor (Bass/Tempo/Stimmung aus Freitext)
 * 5. Leichte Heuristik nur als letzte Schärfung
 *
 * Das ist kein generatives 7B-LLM, sondern das **projektinterne** Sprach-/Intent-Modell
 * für Secret Player — bewusst ressourcenschonend für Galaxy S20 FE und älter.
 */
class PlayerBrain(
    private val trained: OptionalLlmInterpreter? = null,
    private val embedded: OptionalLlmInterpreter = EmbeddedIntentBrain
) : OptionalLlmInterpreter {

    @Volatile private var active = true

    override fun isAvailable(): Boolean = active

    override fun release() {
        // Gewichte des Trained-Modells bleiben resident (~72 KB); kein natives Handle
        trained?.release()
    }

    /**
     * Mehrstufige Interpretation:
     * Kontext + Memory fließen in die Slot-Wahl ein; Unsicherheit bleibt in rawConfidence.
     */
    override fun interpret(text: String, hintPlaylists: List<String>): StructuredMusicIntent? {
        return interpretWithContext(text, hintPlaylists, null, null)
    }

    fun interpretWithContext(
        text: String,
        hintPlaylists: List<String>,
        state: ConversationState?,
        memory: MemoryEngine?
    ): StructuredMusicIntent? {
        val t = TextUtil.norm(text)
        if (t.isBlank()) return null

        // 1) Trainiertes Modell
        val fromTrained = runCatching { trained?.interpret(text, hintPlaylists) }.getOrNull()
        // 2) Embedded Heuristik
        val fromEmbedded = runCatching { embedded.interpret(text, hintPlaylists) }.getOrNull()
        // 3) Preference-Signale (Bass/Energy/Mood)
        val prefs = PreferenceExtractor.extract(text)
        // 4) Memory + Conversation overlays
        val hints = runCatching { memory?.hints() }.getOrNull()
        val emotion = state?.emotion
        val goal = state?.goal

        val merged = merge(fromTrained, fromEmbedded, prefs, hints, emotion, goal, t)
        return merged
    }

    private fun merge(
        a: StructuredMusicIntent?,
        b: StructuredMusicIntent?,
        prefs: List<PreferenceSignal>,
        hints: MemoryHints?,
        emotion: Mood?,
        goal: Mood?,
        normText: String
    ): StructuredMusicIntent? {
        if (a == null && b == null && prefs.isEmpty() && emotion == null && goal == null) return null

        val genres = LinkedHashSet<String>()
        a?.genres?.let { genres.addAll(it) }
        b?.genres?.let { genres.addAll(it) }

        // Memory prefer/avoid als Genre-Hinweise
        hints?.prefer?.forEach { if (it.key.length in 3..24) genres.add(it.key) }
        val exclude = LinkedHashSet<String>()
        hints?.avoid?.forEach { if (it.key.length in 3..24) exclude.add(it.key) }

        var mood = a?.mood ?: b?.mood ?: goal ?: emotion
        var energy = a?.energyHint ?: b?.energyHint
        var conf = maxOf(a?.rawConfidence ?: 0f, b?.rawConfidence ?: 0f)

        for (p in prefs) {
            conf = maxOf(conf, p.confidence * 0.9f)
            when (p.dimension) {
                PreferenceSignal.Dimension.MOOD_SAD -> if (p.polarity > 0) mood = Mood.SAD
                PreferenceSignal.Dimension.MOOD_CALM -> if (p.polarity > 0) mood = Mood.CALM
                PreferenceSignal.Dimension.MOOD_PARTY -> if (p.polarity > 0) mood = Mood.PARTY
                PreferenceSignal.Dimension.MOOD_AGGRESSIVE -> if (p.polarity > 0) mood = Mood.ANGRY
                PreferenceSignal.Dimension.ENERGY -> {
                    energy = when {
                        p.polarity < -0.3f -> StructuredMusicIntent.EnergyHint.LOW
                        p.polarity > 0.3f -> StructuredMusicIntent.EnergyHint.HIGH
                        else -> energy
                    }
                }
                PreferenceSignal.Dimension.TEMPO -> {
                    if (p.polarity < -0.3f) energy = StructuredMusicIntent.EnergyHint.LOW
                }
                else -> Unit
            }
        }

        // „Druck aber nicht hektisch“
        if (normText.contains("druck") && (normText.contains("nicht hektisch") || normText.contains("nicht so hektisch"))) {
            energy = StructuredMusicIntent.EnergyHint.LOW
            conf = maxOf(conf, 0.72f)
        }

        val quieter = prefs.any { it.dimension == PreferenceSignal.Dimension.ENERGY && it.polarity < 0 } ||
            a?.refineQuieter == true || b?.refineQuieter == true
        val harder = prefs.any { it.dimension == PreferenceSignal.Dimension.ENERGY && it.polarity > 0.4f } ||
            a?.refineHarder == true || b?.refineHarder == true

        if (mood == null && genres.isEmpty() && energy == null && a?.referencePlaylist == null && b?.referencePlaylist == null) {
            return null
        }

        return StructuredMusicIntent(
            referencePlaylist = a?.referencePlaylist ?: b?.referencePlaylist,
            genres = genres.toList(),
            mood = mood,
            energyHint = energy,
            refineQuieter = quieter,
            refineHarder = harder && !quieter,
            prepareOnly = a?.prepareOnly == true || b?.prepareOnly == true,
            rawConfidence = conf.coerceIn(0f, 1f),
            notes = buildList {
                addAll(a?.notes.orEmpty())
                addAll(b?.notes.orEmpty())
                if (prefs.isNotEmpty()) add("prefs:${prefs.joinToString { it.key }}")
                if (hints != null) add("memory")
                if (emotion != null) add("emotion:$emotion")
            }
        )
    }
}
