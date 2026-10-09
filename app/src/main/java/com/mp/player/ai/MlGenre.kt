package com.mp.player.ai

/**
 * ML-Genre (GTZAN-Labels) als schwache Evidenz. Rang unter Benutzer-Korrektur, Dateitag und Playlist:
 * Evidenz = Konfidenz * 0.8, gedeckelt bei 0.72. Nur sehr sichere Treffer (roh >= 0.85) erreichen die
 * strenge Genre-Schwelle des Recommenders (0.68), alles darunter wirkt nur im Scoring.
 */
object MlGenre {
    const val MIN_RAW = 0.25f
    const val FACTOR = 0.8f
    const val CAP = 0.72f

    /** "rock:0.812,metal:0.101" -> [(rock, 0.812), (metal, 0.101)]; defekte Teile werden ignoriert. */
    fun parse(json: String): List<Pair<String, Float>> =
        json.split(',').mapNotNull { part ->
            val i = part.lastIndexOf(':')
            if (i <= 0) return@mapNotNull null
            val label = part.substring(0, i).trim()
            val conf = part.substring(i + 1).trim().replace(',', '.').toFloatOrNull() ?: return@mapNotNull null
            if (label.isBlank() || conf.isNaN()) null else label to conf.coerceIn(0f, 1f)
        }

    fun weight(raw: Float): Float = (raw * FACTOR).coerceAtMost(CAP)

    /** uri -> Evidenzen (Label über [GenreProfile.normalizeGenre] vereinheitlicht, z. B. hiphop -> rap). */
    fun toEvidence(byUri: Map<String, String>): Map<String, List<GenreEvidence>> {
        val out = HashMap<String, List<GenreEvidence>>()
        for ((uri, json) in byUri) {
            val list = parse(json).filter { it.second >= MIN_RAW }.mapNotNull { (label, raw) ->
                val g = GenreProfile.normalizeGenre(label)
                if (g.isBlank()) null
                else GenreEvidence(g, weight(raw), listOf("Audio-ML (GTZAN, ${(raw * 100).toInt()} %)"))
            }.groupBy { it.genre }.map { (_, v) -> v.maxByOrNull { it.confidence }!! }
            if (list.isNotEmpty()) out[uri] = list
        }
        return out
    }
}
