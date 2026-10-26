package com.mp.player.ai

/**
 * Strukturierte Anforderungen aus einer Nutzernachricht (und über Turns hinweg).
 * Deterministisch extrahiert – keine erfundenen Songs.
 */
data class MusicConstraints(
    val moods: Set<Mood> = emptySet(),
    val excludeMoods: Set<Mood> = emptySet(),
    val genres: List<String> = emptyList(),
    val genreLabel: String? = null,
    val preferLowEnergy: Boolean? = null,
    val preferHighEnergy: Boolean? = null,
    val notTooSlow: Boolean = false,
    val notTooQuiet: Boolean = false,
    val notTooHard: Boolean = false,
    val wantDeepBass: Boolean = false,
    val targetBpm: Int? = null,
    val keepHardTracks: Boolean = false,
    val preferUnheard: Boolean = false,
    val fromOwnPlaylists: Boolean = false,
    val minutes: Int? = null,
    val prepareOnly: Boolean = false,
    val rawConfidence: Float = 0.5f,
    val notes: List<String> = emptyList()
) {
    fun isEmpty(): Boolean =
        moods.isEmpty() && excludeMoods.isEmpty() && genres.isEmpty() &&
            preferLowEnergy == null && preferHighEnergy == null &&
            !notTooSlow && !notTooQuiet && !notTooHard && !wantDeepBass &&
            targetBpm == null && !keepHardTracks &&
            !preferUnheard && !fromOwnPlaylists && minutes == null

    fun mergeWith(newer: MusicConstraints): MusicConstraints = MusicConstraints(
        moods = if (newer.moods.isNotEmpty()) newer.moods else moods,
        excludeMoods = excludeMoods + newer.excludeMoods,
        genres = if (newer.genres.isNotEmpty()) newer.genres else genres,
        genreLabel = newer.genreLabel ?: genreLabel,
        preferLowEnergy = newer.preferLowEnergy ?: preferLowEnergy,
        preferHighEnergy = newer.preferHighEnergy ?: preferHighEnergy,
        notTooSlow = notTooSlow || newer.notTooSlow,
        notTooQuiet = notTooQuiet || newer.notTooQuiet,
        notTooHard = notTooHard || newer.notTooHard,
        wantDeepBass = wantDeepBass || newer.wantDeepBass,
        targetBpm = newer.targetBpm ?: targetBpm,
        keepHardTracks = keepHardTracks || newer.keepHardTracks,
        preferUnheard = preferUnheard || newer.preferUnheard,
        fromOwnPlaylists = fromOwnPlaylists || newer.fromOwnPlaylists,
        minutes = newer.minutes ?: minutes,
        prepareOnly = newer.prepareOnly || prepareOnly,
        rawConfidence = maxOf(rawConfidence, newer.rawConfidence),
        notes = (notes + newer.notes).distinct()
    )
}

object ConstraintExtractor {
    fun extract(text: String): MusicConstraints {
        val t = text.lowercase()
        val notes = ArrayList<String>()
        val moods = LinkedHashSet<Mood>()
        val exclude = LinkedHashSet<Mood>()
        val genres = ArrayList<String>()
        var label: String? = null

        val genreMap = listOf(
            "hardtekk" to listOf("hardtekk", "hardtek", "tekk"),
            "rap" to listOf("rap", "hiphop", "hip hop", "deutschrap"),
            "techno" to listOf("techno"),
            "house" to listOf("house"),
            "metal" to listOf("metal"),
            "rock" to listOf("rock"),
            "frenchcore" to listOf("frenchcore", "french core"),
            "drum and bass" to listOf("dnb", "drum and bass", "drum&bass")
        )
        for ((lab, keys) in genreMap) {
            if (keys.any { t.contains(it) }) {
                genres += lab
                if (label == null) label = lab.replaceFirstChar { it.uppercase() }
            }
        }

        if (listOf("melanchol", "traurig", "sad", "wehmut").any { t.contains(it) }) moods += Mood.SAD
        if (listOf("ruhig", "chill", "entspann", "calm", "lofi").any { t.contains(it) }) moods += Mood.CALM
        if (listOf("aggressiv", "wuetend", "wütend", "hardcore").any { t.contains(it) }) moods += Mood.ANGRY
        if (listOf("duster", "düster", "dark").any { t.contains(it) }) moods += Mood.DARK
        if (listOf("party", "feiern", "banger", "euphor").any { t.contains(it) }) moods += Mood.PARTY
        if (listOf("emotional", "gefuehl", "gefühl").any { t.contains(it) }) moods += Mood.SAD

        val notTooSlow = listOf("nicht zu langsam", "nicht langsam", "ohne schleich", "kein schleich").any { t.contains(it) }
        val notTooQuiet = listOf("nicht zu leise", "nicht leise", "nicht zu ruhig gemastert").any { t.contains(it) }
        val notTooHard = listOf(
            "nicht zu hart", "nicht zu aggressiv", "ohne hardcore", "weniger aggressiv", "nicht so hektisch"
        ).any { t.contains(it) }

        val wantDeepBass = listOf(
            "richtig tief", "tiefem bass", "tiefer bass", "subbass", "mehr bass", "druckvoll",
            "bass stark", "richtigen bass", "fetten bass", "wummern", "kräftigen bass", "kraftigen bass"
        ).any { t.contains(it) } || (t.contains("bass") && (t.contains("tief") || t.contains("kräftig") || t.contains("kraftig")))

        val bpmAround = Regex("""um\s+(?:die\s+)?(\d{2,3})\s*(?:bpm)?""").find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(\d{2,3})\s*bpm""").find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val keepHard = listOf(
            "lass die harten", "harte tracks drin", "harte drin", "aggressiv drin",
            "nicht die harten raus", "inklusive hart"
        ).any { t.contains(it) }

        if (listOf("kein hardtekk", "keinen hardtekk", "ohne hardtekk").any { t.contains(it) }) {
            notes += "exclude:hardtekk"
        }

        val lowE = listOf("ruhiger", "leiser", "sanfter", "weniger energie", "chilliger").any { t.contains(it) } && !notTooSlow
        val highE = listOf("haerter", "härter", "mehr energie", "treibend", "schneller", "mehr druck").any { t.contains(it) }

        val fromPl = listOf("meine playlist", "aus meinen", "eigenen wiedergabe", "aus playlist").any { t.contains(it) }
        val unheard = listOf("lange nicht", "noch nie", "unbekannt", "frisch").any { t.contains(it) }
        val prepare = listOf("such mir", "entwurf", "zeig mir erst", "noch nicht starten").any { t.contains(it) }

        var conf = 0.4f
        if (moods.isNotEmpty()) conf += 0.15f
        if (genres.isNotEmpty()) conf += 0.15f
        if (notTooSlow || notTooQuiet || keepHard || wantDeepBass) conf += 0.1f
        if (bpmAround != null) conf += 0.1f

        return MusicConstraints(
            moods = moods,
            excludeMoods = exclude,
            genres = genres.distinct(),
            genreLabel = label,
            preferLowEnergy = if (lowE) true else null,
            preferHighEnergy = if (highE) true else null,
            notTooSlow = notTooSlow,
            notTooQuiet = notTooQuiet,
            notTooHard = notTooHard,
            wantDeepBass = wantDeepBass,
            targetBpm = bpmAround,
            keepHardTracks = keepHard,
            preferUnheard = unheard,
            fromOwnPlaylists = fromPl,
            prepareOnly = prepare,
            rawConfidence = conf.coerceIn(0f, 1f),
            notes = notes
        )
    }
}
