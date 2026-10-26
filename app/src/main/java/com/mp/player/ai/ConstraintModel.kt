package com.mp.player.ai

/**
 * Strukturierte Anforderungen aus einer Nutzernachricht (und über Turns hinweg).
 * Deterministisch extrahiert – keine erfundenen Songs.
 */
data class MusicConstraints(
    val moods: Set<Mood> = emptySet(),
    val excludeMoods: Set<Mood> = emptySet(),
    val genres: List<String> = emptyList(),
    /** Ausdruecklich NICHT gewuenschte Genres ("kein Frenchcore", "ohne Rap"). Nie als positive Praeferenz speichern. */
    val excludeGenres: List<String> = emptyList(),
    val genreLabel: String? = null,
    val preferLowEnergy: Boolean? = null,
    val preferHighEnergy: Boolean? = null,
    val notTooSlow: Boolean = false,
    val notTooQuiet: Boolean = false,
    val notTooHard: Boolean = false,
    val wantDeepBass: Boolean = false,
    val targetBpm: Int? = null,
    /** Toleranz um [targetBpm] (z. B. "zwischen 170 und 190" -> 180 +-10); null = Standardtoleranz. */
    val bpmTolerance: Int? = null,
    val keepHardTracks: Boolean = false,
    val preferUnheard: Boolean = false,
    val fromOwnPlaylists: Boolean = false,
    val minutes: Int? = null,
    val prepareOnly: Boolean = false,
    val rawConfidence: Float = 0.5f,
    val notes: List<String> = emptyList()
) {
    fun isEmpty(): Boolean =
        moods.isEmpty() && excludeMoods.isEmpty() && genres.isEmpty() && excludeGenres.isEmpty() &&
            preferLowEnergy == null && preferHighEnergy == null &&
            !notTooSlow && !notTooQuiet && !notTooHard && !wantDeepBass &&
            targetBpm == null && !keepHardTracks &&
            !preferUnheard && !fromOwnPlaylists && minutes == null

    fun mergeWith(newer: MusicConstraints): MusicConstraints = MusicConstraints(
        // Neuere ausdrueckliche Wuensche gewinnen: ein neu gewuenschtes Genre hebt einen frueheren Ausschluss auf und umgekehrt
        moods = (if (newer.moods.isNotEmpty()) newer.moods else moods).filter { it !in newer.excludeMoods }.toSet(),
        excludeMoods = (excludeMoods + newer.excludeMoods).filter { it !in newer.moods }.toSet(),
        genres = (if (newer.genres.isNotEmpty()) newer.genres else genres).filter { it !in newer.excludeGenres },
        excludeGenres = (excludeGenres + newer.excludeGenres).distinct().filter { it !in newer.genres },
        genreLabel = newer.genreLabel ?: genreLabel,
        preferLowEnergy = newer.preferLowEnergy ?: preferLowEnergy,
        preferHighEnergy = newer.preferHighEnergy ?: preferHighEnergy,
        notTooSlow = notTooSlow || newer.notTooSlow,
        notTooQuiet = notTooQuiet || newer.notTooQuiet,
        notTooHard = notTooHard || newer.notTooHard,
        wantDeepBass = wantDeepBass || newer.wantDeepBass,
        targetBpm = newer.targetBpm ?: targetBpm,
        bpmTolerance = if (newer.targetBpm != null) newer.bpmTolerance else bpmTolerance,
        keepHardTracks = keepHardTracks || newer.keepHardTracks,
        preferUnheard = preferUnheard || newer.preferUnheard,
        fromOwnPlaylists = fromOwnPlaylists || newer.fromOwnPlaylists,
        minutes = newer.minutes ?: minutes,
        prepareOnly = newer.prepareOnly || prepareOnly,
        rawConfidence = maxOf(rawConfidence, newer.rawConfidence),
        notes = (notes + newer.notes).distinct()
    )

    fun toPlayMood(): UserIntent.PlayMood {
        val mood = moods.firstOrNull()
        return UserIntent.PlayMood(
            mood = mood,
            exclude = excludeMoods,
            genreLabel = genreLabel,
            genres = genres,
            minutes = minutes,
            prepare = prepareOnly,
            support = mood == Mood.SAD || mood == Mood.LONELY || mood == Mood.CALM
        )
    }
}

/**
 * Extrahiert **mehrere** Constraints aus einem Satz (Umgangssprache).
 * Text UND Schluesselwoerter werden mit [TextUtil.norm] normalisiert (ä->ae usw.),
 * damit "düster", "duester" und "kräftig"/"kraeftig" gleich treffen.
 */
object ConstraintExtractor {

    /** "um die 180", "um 175 bpm" - aber nicht "um 20 Uhr" / "um 15 Minuten". */
    private val BPM_AROUND = Regex("""\bum\s+(?:die\s+)?(\d{2,3})\b(?!\s*(?:uhr|min|sek|prozent|%))""")
    private val BPM_EXPLICIT = Regex("""\b(\d{2,3})\s*bpm\b""")
    /** "zwischen 170 und 190" / "170-190 bpm" -> Zielwert = Mitte, Toleranz = halbe Spanne. */
    private val BPM_RANGE_ZWISCHEN = Regex("""\bzwischen\s+(\d{2,3})\s+(?:und|bis)\s+(\d{2,3})\b(?!\s*(?:uhr|min|sek|prozent|%))""")
    private val BPM_RANGE_BPM = Regex("""\b(\d{2,3})\s*(?:-|bis)\s*(\d{2,3})\s*bpm\b""")

    private val NEGATORS = setOf("kein", "keine", "keinen", "keinem", "keiner", "keines", "ohne", "nicht", "nix", "weder")
    /** Diese Woerter beenden den Geltungsbereich einer Verneinung ("kein Rap, aber dunkel"). */
    private val CLAUSE_BREAKS = setOf("aber", "sondern", "dafuer", "trotzdem", "sonst")

    private val GENRE_MAP = listOf(
        "hardtekk" to listOf("hardtekk", "hardtek", "tekk"),
        "rap" to listOf("rap", "hiphop", "hip hop", "deutschrap"),
        "techno" to listOf("techno"),
        "house" to listOf("house"),
        "metal" to listOf("metal"),
        "rock" to listOf("rock"),
        "pop" to listOf("pop"),
        "classical" to listOf("klassik", "classical"),
        "frenchcore" to listOf("frenchcore", "french core"),
        "drum and bass" to listOf("dnb", "drum and bass", "drum&bass")
    )

    /** Suchbegriffe fuer ausgeschlossene Genre-Labels (fuer QueueRequest.avoidTerms). */
    fun excludeTerms(labels: List<String>): List<String> =
        labels.flatMap { l -> GENRE_MAP.firstOrNull { it.first == l }?.second ?: listOf(l) }.distinct()

    /** Steht vor der Fundstelle (idx) innerhalb derselben Teilaussage eine Verneinung? Fenster: 3 Woerter. */
    private fun negatedAt(t: String, idx: Int): Boolean {
        val clause = t.substring(0, idx).split(Regex("[,.;!?]")).last()
        val tail = clause.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.takeLast(3)
        val cut = tail.indexOfLast { it in CLAUSE_BREAKS }
        val scope = if (cut >= 0) tail.drop(cut + 1) else tail
        return scope.any { it in NEGATORS }
    }
    private fun plausibleBpm(v: Int?): Int? = v?.takeIf { it in 60..300 }

    fun extract(text: String): MusicConstraints {
        val t = TextUtil.norm(text)
        if (t.isBlank()) return MusicConstraints()
        val notes = ArrayList<String>()
        val moods = LinkedHashSet<Mood>()
        val exclude = LinkedHashSet<Mood>()
        val genres = ArrayList<String>()
        var label: String? = null

        val excludeGenres = ArrayList<String>()
        for ((lab, keys) in GENRE_MAP) {
            val idx = keys.map { t.indexOf(TextUtil.norm(it)) }.filter { it >= 0 }.minOrNull() ?: continue
            if (negatedAt(t, idx)) {
                excludeGenres += lab
                notes += "exclude:$lab"
            } else {
                genres += lab
                if (label == null) label = lab.replaceFirstChar { it.uppercase() }
            }
        }

        // Stimmung: eine verneinte Stimmung ("nicht traurig") wird ausgeschlossen, nie als Wunsch gespeichert
        fun mood(m: Mood, keys: List<String>) {
            val idx = keys.map { t.indexOf(TextUtil.norm(it)) }.filter { it >= 0 }.minOrNull() ?: return
            if (negatedAt(t, idx)) exclude += m else moods += m
        }
        mood(Mood.SAD, listOf("melanchol", "traurig", "sad", "wehmut"))
        mood(Mood.CALM, listOf("ruhig", "chill", "entspann", "calm", "lofi"))
        mood(Mood.ANGRY, listOf("aggressiv", "wuetend", "wütend", "hardcore"))
        mood(Mood.DARK, listOf("duester", "düster", "dark", "dunkel", "finster"))
        mood(Mood.PARTY, listOf("party", "feiern", "banger", "euphor"))
        mood(Mood.SAD, listOf("emotional", "gefuehl", "gefühl"))

        val notTooSlow = listOf("nicht zu langsam", "nicht langsam", "ohne schleich", "kein schleich").any { t.contains(TextUtil.norm(it)) }
        val notTooQuiet = listOf("nicht zu leise", "nicht leise", "nicht zu ruhig gemastert").any { t.contains(TextUtil.norm(it)) }
        val notTooHard = listOf(
            "nicht zu hart", "nicht zu aggressiv", "ohne hardcore", "weniger aggressiv", "nicht so hektisch"
        ).any { t.contains(TextUtil.norm(it)) }

        val wantDeepBass = listOf(
            "richtig tief", "tiefem bass", "tiefer bass", "subbass", "mehr bass", "druckvoll",
            "mehr sub", "viel sub", "tiefen sub", "low end", "low-end", "lowend", "fundament",
            "bass stark", "richtigen bass", "fetten bass", "wummern", "kräftigen bass", "kraftigen bass"
        ).any { t.contains(TextUtil.norm(it)) } ||
            (t.contains("bass") && (t.contains("tief") || t.contains("kraeftig") || t.contains("kraftig")))

        val range = (BPM_RANGE_ZWISCHEN.find(t) ?: BPM_RANGE_BPM.find(t))?.let { m ->
            val a = m.groupValues[1].toIntOrNull()
            val b = m.groupValues[2].toIntOrNull()
            if (a != null && b != null && a != b && a in 60..300 && b in 60..300) minOf(a, b) to maxOf(a, b) else null
        }
        val bpmPoint = plausibleBpm(BPM_AROUND.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull())
            ?: plausibleBpm(BPM_EXPLICIT.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull())
        val bpmAround = range?.let { (it.first + it.second) / 2 } ?: bpmPoint
        val bpmTol = range?.let { (it.second - it.first) / 2 }

        val keepHard = listOf(
            "lass die harten", "harte tracks drin", "harte drin", "aggressiv drin",
            "nicht die harten raus", "inklusive hart"
        ).any { t.contains(TextUtil.norm(it)) }

        val lowE = listOf("ruhiger", "leiser", "sanfter", "weniger energie", "chilliger").any { t.contains(TextUtil.norm(it)) } && !notTooSlow
        val highE = listOf("haerter", "härter", "mehr energie", "treibend", "schneller", "mehr druck").any { t.contains(TextUtil.norm(it)) }

        val fromPl = listOf("meine playlist", "aus meinen", "eigenen wiedergabe", "aus playlist").any { t.contains(TextUtil.norm(it)) }
        val unheard = listOf("lange nicht", "noch nie", "unbekannt", "frisch").any { t.contains(TextUtil.norm(it)) }
        val prepare = listOf("such mir", "entwurf", "zeig mir erst", "noch nicht starten").any { t.contains(TextUtil.norm(it)) }

        var conf = 0.4f
        if (moods.isNotEmpty()) conf += 0.15f
        if (genres.isNotEmpty() || excludeGenres.isNotEmpty()) conf += 0.15f
        if (notTooSlow || notTooQuiet || keepHard || wantDeepBass) conf += 0.1f
        if (bpmAround != null) conf += 0.1f

        return MusicConstraints(
            moods = moods,
            excludeMoods = exclude,
            genres = genres.distinct(),
            excludeGenres = excludeGenres.distinct(),
            genreLabel = label,
            preferLowEnergy = if (lowE) true else null,
            preferHighEnergy = if (highE) true else null,
            notTooSlow = notTooSlow,
            notTooQuiet = notTooQuiet,
            notTooHard = notTooHard,
            wantDeepBass = wantDeepBass,
            targetBpm = bpmAround,
            bpmTolerance = bpmTol,
            keepHardTracks = keepHard,
            preferUnheard = unheard,
            fromOwnPlaylists = fromPl,
            prepareOnly = prepare,
            rawConfidence = conf.coerceIn(0f, 1f),
            notes = notes
        )
    }
}
