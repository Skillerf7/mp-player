package com.mp.player.ai

import com.mp.player.AnalysisResult
import com.mp.player.Track
import java.util.Random

data class QueueRequest(
    val mood: Mood?,
    val exclude: Set<Mood> = emptySet(),
    val genres: List<String> = emptyList(),
    val minutes: Int? = null,
    val preferUnheard: Boolean = false,
    val favoritesOnly: Boolean = false,
    val avoidUris: Set<String> = emptySet(),
    /** Nicht verboten, aber unerwuenscht (z. B. schon in der Queue): bekommt Punktabzug, damit nicht dasselbe nochmal kommt. */
    val softAvoid: Set<String> = emptySet(),
    /** Harte Ausschluesse aus dem Gedaechtnis ("heute keinen Hardtekk"): Titel mit diesen Begriffen fliegen raus. */
    val avoidTerms: List<String> = emptyList(),
    /** Weiche Vorlieben aus dem Gedaechtnis: kleiner Punktebonus. */
    val preferTerms: List<String> = emptyList(),
    val maxTracks: Int = 25,
    val seed: Long = System.nanoTime(),
    /** Persönliches Genreprofil; BPM wird hier bewusst niemals zur Genreklassifikation verwendet. */
    val genreProfile: GenreProfile? = null,
    /** Sichere Personal-Learning-Korrekturen (kein Basis-Modell). */
    val personal: PersonalSignals? = null
)

data class QueueResult(
    val tracks: List<Track>,
    val totalMs: Long,
    /** Es wurde nach einem Genre gefragt, aber nichts in der Bibliothek passt. */
    val genreNotFound: Boolean,
    /** Anteil der Kandidaten mit Audioanalyse (BPM/Lautheit) - je hoeher, desto treffsicherer die Stimmung. */
    val analysisCoverage: Float,
    /** Anteil der Kandidaten mit eingebettetem Songtext - je hoeher, desto genauer die Gefuehls-Stimmung. */
    val lyricsCoverage: Float = 0f
)

/**
 * Waehlt Titel aus der EXISTIERENDEN lokalen Bibliothek. Keine Cloud, kein Modell:
 * Stimmung = Mischung aus Energie (BPM + Lautheit aus der Audioanalyse), Genre-/Titel-Stichwoertern
 * und Hoerverlauf. Das ist eine Schaetzung - ohne Analysedaten faellt sie auf Stichwoerter zurueck.
 */
object Recommender {

    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR
    private const val UNKNOWN_DURATION_MS = 210_000L
    private const val HARD_MAX_TRACKS = 300

    private val KEYWORDS: Map<Mood, List<String>> = mapOf(
        Mood.AGGRESSIVE to listOf("hardtek", "hardtekk", "hard tek", "tekk", "hardcore", "gabber", "frenchcore", "uptempo", "speedcore", "hardstyle", "rawstyle", "hard techno", "hardtechno", "metal", "punk", "thrash", "industrial", "terror", "drill", "rage", "krach", "brutal", "death", "kill"),
        Mood.CALM to listOf("ambient", "chill", "lofi", "lo-fi", "lo fi", "classical", "klassik", "piano", "acoustic", "akustik", "jazz", "downtempo", "instrumental", "new age", "meditation", "folk", "soft", "relax", "sleep", "calm"),
        Mood.SAD to listOf("sad", "traurig", "melanch", "tears", "traene", "cry", "lonely", "alone", "allein", "einsam", "goodbye", "abschied", "pain", "schmerz", "broken", "heartbreak", "herzschmerz", "miss you", "depress", "grief", "trauer"),
        Mood.PARTY to listOf("dance", "party", "club", "disco", "house", "edm", "techno", "trance", "rave", "festival", "feier"),
        Mood.HAPPY to listOf("happy", "summer", "sommer", "sunshine", "joy", "pop", "funk", "reggae", "dance", "good vibes"),
        Mood.ENERGETIC to listOf("techno", "trance", "hardstyle", "edm", "dnb", "drum and bass", "drum & bass", "rock", "punk", "metal", "gym", "workout", "power", "energy", "hype"),
        Mood.FOCUS to listOf("instrumental", "ambient", "classical", "lofi", "lo-fi", "study", "focus", "minimal", "piano"),
        Mood.SLEEP to listOf("sleep", "ambient", "lullaby", "drone", "nature", "piano", "meditation", "calm", "night"),
        Mood.ANGRY to listOf("wut", "wuetend", "angry", "anger", "rage", "hate", "hass", "revenge", "rache", "fury", "zorn", "fuck"),
        Mood.LONELY to listOf("lonely", "alone", "allein", "einsam", "solitude", "isolation", "empty", "leere"),
        Mood.ROMANTIC to listOf("love", "liebe", "lover", "romance", "romantic", "kiss", "kuss", "valentine", "amor", "darling", "schatz", "forever"),
        Mood.NOSTALGIC to listOf("nostalg", "memories", "memory", "remember", "erinner", "damals", "yesterday", "retro", "oldies", "throwback", "childhood", "kindheit"),
        Mood.DARK to listOf("dark", "dunkel", "shadow", "schatten", "goth", "doom", "devil", "teufel", "demon", "grave", "blood", "blut", "horror", "evil", "abyss", "void"),
        Mood.MOTIVATED to listOf("motivat", "motivier", "power", "strong", "winner", "champion", "gym", "workout", "rise", "fight", "kaempf", "grind", "hustle", "unstoppable", "believe")
    )

    /** Pro Stimmung EIN vorkompilierter Regex (kurze Stichwoerter nur als ganzes Wort, damit "rap" nicht "therapy" trifft). */
    private val KEYWORD_RE: Map<Mood, Regex> = KEYWORDS.mapValues { (_, kws) -> termsRegex(kws) }

    internal fun termsRegex(terms: List<String>): Regex =
        Regex(terms.map { TextUtil.norm(it) }.joinToString("|") { if (it.length <= 4) "\\b${Regex.escape(it)}\\b" else Regex.escape(it) })

    private fun haystack(info: TrackInfo): String {
        val t = info.track
        return TextUtil.norm("${t.title} ${t.artist} ${t.album} ${t.genre} ${t.folder}")
    }

    /** Energie 0..1 aus Lautheit und BPM; null, wenn der Titel nicht analysiert ist. */
    internal fun energy(a: AnalysisResult?): Float? {
        if (a == null) return null
        val loud: Float? = when {
            !a.lufs.isNaN() -> a.lufs
            a.rmsDb > -119f -> a.rmsDb + 2f // grob in LUFS-Naehe
            else -> null
        }
        val eLoud = loud?.let { ((it + 24f) / 16f).coerceIn(0f, 1f) }   // -24 LUFS = leise, -8 LUFS = sehr laut
        val eBpm = if (a.bpm > 0f) ((a.bpm - 60f) / 120f).coerceIn(0f, 1f) else null // 60 BPM = 0, 180 BPM = 1
        return when {
            eLoud != null && eBpm != null -> 0.5f * eLoud + 0.5f * eBpm
            eLoud != null -> eLoud
            else -> eBpm
        }
    }

    /**
     * Abzug fuer oft uebersprungene Titel: Anteil Skips an den Wiedergaben, ab dem 3. Skip voll gewichtet
     * (ein einzelner Skip ist noch kein Urteil). Ein Skip in den letzten 24 h wiegt zusaetzlich.
     */
    internal fun skipPenalty(info: TrackInfo, nowMs: Long): Float {
        val skips = info.skipCount
        if (skips <= 0) return 0f
        val rate = skips.toFloat() / maxOf(info.playCount, skips)
        var p = 0.4f * rate * minOf(1f, skips / 3f)
        val last = info.lastSkippedAt
        if (last != null && nowMs - last < DAY) p += 0.1f
        return p
    }

    /** Aufnahmen bis zu diesem Jahr zaehlen als "aelter" (15 Jahre zurueck). */
    private fun oldYear(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - 15

    fun moodScore(info: TrackInfo, mood: Mood): Float {
        val e = energy(info.analysis)
        val low = if (e != null) 1f - e else 0.5f
        val high = e ?: 0.5f
        val h = haystack(info)
        fun hit(m: Mood): Boolean = KEYWORD_RE.getValue(m).containsMatchIn(h)
        fun b(cond: Boolean, v: Float) = if (cond) v else 0f
        // Songtext-Stimmung (0 = kein Text oder nichts erkannt) - ergaenzt Klang und Stichwoerter, ersetzt sie nicht
        fun ly(m: Mood): Float = info.lyricMood[m] ?: 0f

        val raw = when (mood) {
            Mood.CALM -> 0.55f * low + b(hit(Mood.CALM), 0.35f) - b(hit(Mood.AGGRESSIVE), 0.45f) - b(hit(Mood.PARTY), 0.15f) +
                0.20f * ly(Mood.CALM) - 0.25f * ly(Mood.ANGRY)
            Mood.SLEEP -> 0.60f * low + b(hit(Mood.SLEEP) || hit(Mood.CALM), 0.35f) - b(hit(Mood.AGGRESSIVE), 0.5f) - b(hit(Mood.PARTY), 0.2f) +
                0.15f * ly(Mood.CALM) - 0.25f * ly(Mood.ANGRY)
            // Gesang lenkt beim Konzentrieren ab: Titel mit Text leicht zurueck
            Mood.FOCUS -> 0.40f * low + b(hit(Mood.FOCUS), 0.40f) + b(hit(Mood.CALM), 0.10f) - b(hit(Mood.AGGRESSIVE), 0.3f) - b(info.hasLyrics, 0.10f)
            Mood.SAD -> 0.30f * low + b(hit(Mood.SAD), 0.55f) + b(hit(Mood.CALM), 0.20f) -
                b(hit(Mood.PARTY) || hit(Mood.AGGRESSIVE) || hit(Mood.HAPPY), 0.4f) +
                0.45f * ly(Mood.SAD) + 0.20f * ly(Mood.LONELY) - 0.25f * ly(Mood.HAPPY)
            Mood.ENERGETIC -> 0.60f * high + b(hit(Mood.ENERGETIC), 0.35f) - b(hit(Mood.CALM), 0.3f) + 0.20f * ly(Mood.MOTIVATED)
            Mood.AGGRESSIVE -> 0.45f * high + b(hit(Mood.AGGRESSIVE), 0.5f) - b(hit(Mood.CALM), 0.5f) - b(hit(Mood.HAPPY), 0.2f) +
                0.30f * ly(Mood.ANGRY) + 0.10f * ly(Mood.DARK)
            Mood.PARTY -> 0.45f * high + b(hit(Mood.PARTY), 0.40f) - b(hit(Mood.SAD), 0.4f) - b(hit(Mood.CALM), 0.3f) +
                0.30f * ly(Mood.PARTY) - 0.25f * ly(Mood.SAD)
            Mood.HAPPY -> 0.40f * high + b(hit(Mood.HAPPY), 0.45f) - b(hit(Mood.SAD), 0.5f) - b(hit(Mood.AGGRESSIVE), 0.3f) +
                0.35f * ly(Mood.HAPPY) - 0.30f * ly(Mood.SAD)
            // Wut: Energie + Wut-Woerter im Text (nicht nur harter Klang) - "Ventil", kein Beruhigen
            Mood.ANGRY -> 0.35f * high + b(hit(Mood.ANGRY), 0.40f) + b(hit(Mood.AGGRESSIVE), 0.20f) + 0.55f * ly(Mood.ANGRY) +
                0.10f * ly(Mood.DARK) - b(hit(Mood.CALM), 0.4f) - b(hit(Mood.HAPPY), 0.2f)
            Mood.LONELY -> 0.30f * low + b(hit(Mood.LONELY), 0.40f) + b(hit(Mood.SAD), 0.20f) + 0.55f * ly(Mood.LONELY) +
                0.25f * ly(Mood.SAD) - b(hit(Mood.PARTY) || hit(Mood.AGGRESSIVE), 0.4f)
            Mood.ROMANTIC -> 0.25f * low + b(hit(Mood.ROMANTIC), 0.45f) + 0.55f * ly(Mood.ROMANTIC) -
                b(hit(Mood.AGGRESSIVE), 0.4f) - 0.20f * ly(Mood.ANGRY)
            // Nostalgie: Stichworte, Text ueber Erinnerungen und aeltere Aufnahmen (Jahr aus den Tags, falls vorhanden)
            Mood.NOSTALGIC -> 0.15f * low + b(hit(Mood.NOSTALGIC), 0.50f) + 0.55f * ly(Mood.NOSTALGIC) + 0.10f * ly(Mood.SAD) +
                b(info.track.year in 1..oldYear(), 0.20f)
            Mood.DARK -> 0.15f * low + b(hit(Mood.DARK), 0.45f) + 0.55f * ly(Mood.DARK) + 0.15f * ly(Mood.ANGRY) -
                b(hit(Mood.HAPPY) || hit(Mood.PARTY), 0.35f)
            Mood.MOTIVATED -> 0.50f * high + b(hit(Mood.MOTIVATED), 0.35f) + b(hit(Mood.ENERGETIC), 0.15f) + 0.50f * ly(Mood.MOTIVATED) -
                b(hit(Mood.SAD), 0.4f) - b(hit(Mood.CALM), 0.3f)
        }
        return raw.coerceIn(0f, 1f)
    }

    /**
     * "Klingt der Titel erkennbar nach dieser Stimmung?" - bewusst streng (>= 0.6): Energie allein reicht nicht,
     * es muss ein Stichwort dazukommen. So filtert "keine traurigen Songs" nicht einfach alle leisen Titel weg.
     */
    /** Streng: Track muss klar nach der Stimmung klingen. Energie allein reicht nicht. */
    fun looksLike(info: TrackInfo, mood: Mood): Boolean =
        moodScore(info, mood) >= 0.68f || (info.lyricMood[mood] ?: 0f) >= 0.65f

    /** Harte BPM-Gates: verhindert z.B. 170-BPM-Hardtekk in einer Trauer-Queue. */
    fun bpmFits(info: TrackInfo, mood: Mood, personal: PersonalSignals? = null): Boolean {
        val measured = info.analysis?.bpm ?: 0f
        val bpm = personal?.effectiveBpm(info.track.uri, measured) ?: measured
        if (bpm <= 0f) return true // ohne Analyse nicht blockieren
        // Bei niedriger BPM-Konfidenz Gates weicher
        val conf = info.analysis?.bpmConfidence ?: 0f
        val soft = conf > 0f && conf < 0.35f
        return when (mood) {
            Mood.SAD, Mood.LONELY, Mood.SLEEP, Mood.CALM, Mood.DARK, Mood.NOSTALGIC ->
                if (soft) bpm <= 145f else bpm <= 128f
            Mood.AGGRESSIVE, Mood.ANGRY, Mood.PARTY, Mood.ENERGETIC, Mood.MOTIVATED ->
                if (soft) bpm >= 90f else bpm >= 100f
            else -> true
        }
    }

    private fun matchesGenre(re: Regex, info: TrackInfo): Boolean = re.containsMatchIn(haystack(info))

    fun build(all: List<TrackInfo>, req: QueueRequest, nowMs: Long = System.currentTimeMillis()): QueueResult {
        val rnd = Random(req.seed)

        var pool = all.filter { it.track.uri !in req.avoidUris }
        if (req.favoritesOnly) pool = pool.filter { it.favorite }
        val candidateLyricsCoverage = if (pool.isEmpty()) 0f else pool.count { it.hasLyrics }.toFloat() / pool.size
        if (req.exclude.isNotEmpty()) pool = pool.filter { info -> req.exclude.none { looksLike(info, it) } }

        // STRICT MODE: Wenn eine Stimmung gefordert ist, nur Tracks behalten die wirklich passen.
        // Keine "irgendwie ruhigen" Tracks bei traurig, kein weicher Techno bei aggressiv.
        if (req.mood != null) {
            val mood = req.mood
            val strict = pool.filter { info ->
                moodScore(info, mood) >= 0.55f && bpmFits(info, mood, req.personal)
            }
            // Nur wenn genug Kandidaten uebrig bleiben, strikt filtern - sonst soft bleiben
            if (strict.size >= 5 || strict.size >= pool.size / 4) {
                pool = strict
            } else {
                // Fallback: zumindest die schlimmsten Fehltreffer raus (Score < 0.35)
                pool = pool.filter { moodScore(it, mood) >= 0.35f && bpmFits(it, mood, req.personal) }
            }
        }

        if (req.avoidTerms.isNotEmpty()) {
            val avoid = termsRegex(req.avoidTerms)
            pool = pool.filter { !matchesGenre(avoid, it) }
        }
        val preferRe = if (req.preferTerms.isNotEmpty()) termsRegex(req.preferTerms) else null

        if (req.genres.isNotEmpty()) {
            val profile = req.genreProfile
            val genrePool = if (profile != null) {
                // Starkes persönliches Genre-Evidence: Tag 1.0, persönliche Playlist bis 0.98,
                // Artist-Lernen schwächer. BPM spielt hier exakt 0 Rollen.
                pool.filter { profile.matches(it, req.genres) >= 0.68f }
            } else {
                val re = termsRegex(req.genres)
                pool.filter { matchesGenre(re, it) }
            }
            pool = genrePool
            if (pool.isEmpty()) return QueueResult(emptyList(), 0L, genreNotFound = true, analysisCoverage = 0f)
        }
        if (pool.isEmpty()) return QueueResult(emptyList(), 0L, genreNotFound = false, analysisCoverage = 0f)

        val coverage = pool.count { it.analysis != null }.toFloat() / pool.size
        val lyricsCoverage = candidateLyricsCoverage

        val scored = pool.map { info ->
            val moodPart = if (req.mood != null) moodScore(info, req.mood) else 0.5f
            val last = info.lastPlayedAt
            var s: Float
            if (req.preferUnheard) {
                val stale = if (last == null) 1f else (((nowMs - last).toFloat() / DAY) / 365f).coerceIn(0f, 0.95f)
                s = if (req.mood != null) 0.5f * moodPart + 0.5f * stale else stale
            } else {
                s = moodPart
                if (last != null && nowMs - last < 6 * HOUR) s -= 0.25f // gerade erst gehoert -> nicht gleich wieder
            }
            if (info.track.uri in req.softAvoid) s -= 0.3f
            s -= skipPenalty(info, nowMs) // oft uebersprungen -> weiter hinten (bei "lange nicht gehoert" ebenso)
            if (info.favorite) s += 0.08f
            if (preferRe != null && matchesGenre(preferRe, info)) s += 0.12f
            if (req.genres.isNotEmpty() && req.genreProfile != null) {
                // Genre bleibt das Leitkriterium; BPM darf diesen Ausschluss nicht unterlaufen.
                s += 0.55f * req.genreProfile.matches(info, req.genres)
            }
            // Personal Learning: Verhalten/Overrides – nie intent_model.bin
            req.personal?.let { s += it.scoreBonus(info) }
            // Audio-Charakter (Energie) soft – nur wenn Analyse da und Mood gesetzt
            val ac = AudioCharacter.from(info.analysis, req.personal?.bpmOverride?.get(info.track.uri))
            if (ac != null && req.mood != null) {
                val wantHigh = req.mood in setOf(Mood.AGGRESSIVE, Mood.ANGRY, Mood.PARTY, Mood.ENERGETIC, Mood.MOTIVATED)
                val wantLow = req.mood in setOf(Mood.SLEEP, Mood.CALM, Mood.SAD, Mood.LONELY, Mood.DARK)
                when {
                    wantHigh -> s += 0.12f * ac.energy * ac.confEnergy
                    wantLow -> s += 0.12f * (1f - ac.energy) * ac.confEnergy
                }
            }
            s += rnd.nextFloat() * 0.15f // Abwechslung
            info to s
        }

        val targetMs = req.minutes?.let { it * 60_000L }
        val remaining = ArrayList(scored.sortedByDescending { it.second }.map { it.first })
        val picked = ArrayList<TrackInfo>()
        val seenKeys = HashSet<String>()
        var total = 0L

        while (remaining.isNotEmpty() && picked.size < HARD_MAX_TRACKS) {
            if (targetMs != null) {
                if (total >= targetMs) break
            } else if (picked.size >= req.maxTracks) {
                break
            }
            // Nicht zweimal hintereinander derselbe Interpret, wenn sich unter den naechsten Kandidaten eine Alternative findet
            var idx = 0
            val lastArtist = picked.lastOrNull()?.track?.artist
            if (lastArtist != null) {
                val look = minOf(remaining.size, 8)
                for (i in 0 until look) {
                    if (remaining[i].track.artist != lastArtist) { idx = i; break }
                }
            }
            val cand = remaining.removeAt(idx)
            // Dieselbe Aufnahme (z. B. doppelte Dateien) nie zweimal in einer Queue
            val key = TextUtil.norm(cand.track.artist) + "|" + TextUtil.norm(cand.track.title)
            if (!seenKeys.add(key)) continue
            picked.add(cand)
            total += if (cand.track.durationMs > 0) cand.track.durationMs else UNKNOWN_DURATION_MS
        }

        // Einschlafen: Reihenfolge von laut nach leise (die Queue "faehrt runter")
        val ordered = if (req.mood == Mood.SLEEP) picked.sortedByDescending { energy(it.analysis) ?: 0.5f } else picked

        return QueueResult(
            tracks = ordered.map { it.track },
            totalMs = total,
            genreNotFound = false,
            analysisCoverage = coverage,
            lyricsCoverage = lyricsCoverage
        )
    }
}
