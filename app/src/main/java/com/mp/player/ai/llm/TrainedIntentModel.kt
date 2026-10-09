package com.mp.player.ai.llm

import android.content.Context
import com.mp.player.ai.Mood
import com.mp.player.ai.TextUtil
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Echt trainiertes Multi-Label-Modell (Hashing-Features + logistische Regression),
 * Gewichte in assets/ml/intent_model.bin – fest in der APK, kein Download.
 *
 * Training: ml/scripts (NumPy GD, one-vs-rest). Kein Regex-Intent-Ersatz als „KI“.
 * Inferenz ist Matrix-Multiplikation, kein generatives LLM.
 */
class TrainedIntentModel private constructor(
    private val dim: Int,
    private val labels: List<String>,
    private val threshold: Float,
    private val weights: FloatArray, // labels * dim
    private val bias: FloatArray
) : OptionalLlmInterpreter {

    @Volatile private var loaded = true

    override fun isAvailable(): Boolean = loaded && weights.isNotEmpty()

    override fun release() {
        // Gewichte bleiben (klein, ~72 KB); kein natives Handle
    }

    override fun interpret(text: String, hintPlaylists: List<String>): StructuredMusicIntent? {
        if (!isAvailable()) return null
        val scores = predict(text)
        if (scores.isEmpty()) return null

        fun on(label: String) = (scores[label] ?: 0f) >= threshold

        val hasPlaylist = on("intent_similar_playlist") || on("slot_has_playlist_ref")
        val similarTrack = on("intent_similar_track")
        val refine = on("intent_refine")
        val playMood = on("intent_play_mood")
        val start = on("intent_start")

        // Start-Befehle: kein StructuredMusicIntent nötig – Gate filtert meist vorher
        if (start && !hasPlaylist && !playMood && !refine) return null

        val genres = buildList {
            if (on("slot_genre_rap")) add("rap")
            if (on("slot_genre_tekk")) add("hardtekk")
            if (on("slot_genre_techno")) add("techno")
        }
        val mood = when {
            on("slot_mood_sad") -> Mood.SAD
            on("slot_mood_calm") -> Mood.CALM
            on("slot_mood_party") -> Mood.PARTY
            on("slot_mood_aggressive") -> Mood.ANGRY
            else -> null
        }
        val energy = when {
            on("slot_energy_low") -> StructuredMusicIntent.EnergyHint.LOW
            on("slot_energy_high") -> StructuredMusicIntent.EnergyHint.HIGH
            else -> null
        }
        val ref = if (hasPlaylist) extractPlaylistName(text, hintPlaylists) else null

        val conf = scores.filterKeys {
            it.startsWith("intent_") || it.startsWith("slot_")
        }.values.maxOrNull() ?: 0f

        if (ref == null && genres.isEmpty() && mood == null && energy == null && !similarTrack && !refine) {
            return null
        }

        return StructuredMusicIntent(
            referencePlaylist = ref,
            genres = genres,
            mood = mood,
            energyHint = energy,
            refineQuieter = energy == StructuredMusicIntent.EnergyHint.LOW || on("slot_bpm_down"),
            refineHarder = energy == StructuredMusicIntent.EnergyHint.HIGH || on("slot_bpm_up"),
            prepareOnly = true,
            rawConfidence = conf.coerceIn(0f, 1f),
            notes = buildList {
                scores.filter { it.value >= threshold }.keys.forEach { add("ml:$it=${"%.2f".format(it.let { scores[it] })}") }
                if (on("slot_bpm_up")) add("bpm_up")
                if (on("slot_bpm_down")) add("bpm_down")
                if (similarTrack) add("similar_track")
            }
        )
    }

    fun predict(text: String): Map<String, Float> {
        val x = hashFeat(text, dim)
        val out = HashMap<String, Float>(labels.size)
        for (k in labels.indices) {
            var z = bias[k]
            val base = k * dim
            for (i in 0 until dim) z += weights[base + i] * x[i]
            out[labels[k]] = sigmoid(z)
        }
        return out
    }

    private fun extractPlaylistName(text: String, hints: List<String>): String? {
        val t = TextUtil.norm(text)
        hints.map { it to TextUtil.norm(it) }
            .filter { (_, n) -> n.length >= 3 && t.contains(n) }
            .maxByOrNull { it.second.length }
            ?.let { return it.first }
        val m = Regex(
            """(?:meine|die)\s+([a-z0-9][a-z0-9\s\-]{1,40}?)\s*(?:playlist|wiedergabeliste|liste)"""
        ).find(t)
        val name = m?.groupValues?.get(1)?.trim()?.trim('-', ' ')
        return name?.takeIf { it.length >= 2 }
    }

    companion object {
        @Volatile private var instance: TrainedIntentModel? = null

        fun get(context: Context?): TrainedIntentModel? {
            instance?.let { return it }
            if (context == null) return null
            return try {
                load(context).also { instance = it }
            } catch (_: Throwable) {
                null
            }
        }

        /** Für Unit-Tests ohne Android-Context: aus ByteArrays laden. */
        fun fromBytes(metaJson: String, weightBytes: ByteArray): TrainedIntentModel {
            val meta = JSONObject(metaJson)
            val dim = meta.getInt("dim")
            val labels = meta.getJSONArray("labels").let { arr ->
                List(arr.length()) { arr.getString(it) }
            }
            val threshold = meta.optDouble("threshold", 0.45).toFloat()
            val bb = ByteBuffer.wrap(weightBytes).order(ByteOrder.LITTLE_ENDIAN)
            val weights = FloatArray(labels.size * dim)
            for (i in weights.indices) weights[i] = bb.float
            val bias = FloatArray(labels.size)
            for (i in bias.indices) bias[i] = bb.float
            return TrainedIntentModel(dim, labels, threshold, weights, bias)
        }

        private fun load(context: Context): TrainedIntentModel {
            val meta = context.assets.open("ml/intent_model_meta.json").bufferedReader().readText()
            val bytes = context.assets.open("ml/intent_model.bin").readBytes()
            return fromBytes(meta, bytes)
        }

        internal fun hashFeat(text: String, dim: Int): FloatArray {
            val v = FloatArray(dim)
            val t = TextUtil.norm(text).replace(Regex("[^a-z0-9\\s\\-]"), " ")
            val tokens = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val grams = ArrayList<String>()
            for (tok in tokens) {
                grams.add(tok)
                for (n in 2..3) {
                    if (tok.length >= n) {
                        for (i in 0..tok.length - n) grams.add(tok.substring(i, i + n))
                    }
                }
            }
            for (i in 0 until tokens.size - 1) grams.add(tokens[i] + "_" + tokens[i + 1])
            for (g in grams) {
                var h = g.hashCode()
                if (h < 0) h = -h
                v[h % dim] += 1f
            }
            var norm = 0.0
            for (x in v) norm += x * x
            norm = sqrt(norm)
            if (norm > 1e-8) {
                val inv = (1.0 / norm).toFloat()
                for (i in v.indices) v[i] *= inv
            }
            return v
        }

        private fun sigmoid(z: Float): Float {
            val x = z.coerceIn(-20f, 20f)
            return (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()
        }
    }
}
