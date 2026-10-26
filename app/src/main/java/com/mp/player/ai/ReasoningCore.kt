package com.mp.player.ai

/**
 * Lokaler Reasoning-Kern: Constraints prüfen, Widersprüche, fehlende Infos.
 * Keine Fake-Chain-of-Thought – strukturierte Prüfungen mit begrenztem Aufwand.
 */
data class ReasonResult(
    val constraints: MusicConstraints,
    val contradictions: List<String>,
    val missingQuestions: List<String>,
    val planSteps: List<String>,
    val canAct: Boolean,
    val explanation: String
)

object ReasoningCore {

    fun analyze(
        text: String,
        previous: MusicConstraints?,
        libraryEmpty: Boolean
    ): ReasonResult {
        val extracted = ConstraintExtractor.extract(text)
        val merged = if (previous != null && !extracted.isEmpty()) previous.mergeWith(extracted)
        else if (!extracted.isEmpty()) extracted
        else previous ?: MusicConstraints()

        val contradictions = ArrayList<String>()
        if (merged.preferLowEnergy == true && merged.preferHighEnergy == true) {
            contradictions += "Gleichzeitig ruhiger und härter gewünscht – Priorität unklar."
        }
        if (merged.notTooHard && merged.keepHardTracks) {
            contradictions += "„Nicht zu hart“ und „harte Tracks behalten“ – ich behalte moderate Härte."
        }
        if (merged.moods.contains(Mood.SLEEP) && merged.preferHighEnergy == true) {
            contradictions += "Schlaf-Mood und hohe Energie widersprechen sich."
        }

        val missing = ArrayList<String>()
        if (extracted.isEmpty() && previous == null && looksMusic(text) && !libraryEmpty) {
            // Nur nachfragen wenn wirklich unklar
            if (TextUtil.norm(text).split(Regex("\\s+")).size >= 4) {
                missing += "Meinst du eher Stimmung, Genre oder etwas Ähnliches zu einem Track?"
            }
        }
        if (libraryEmpty) {
            missing += "Deine Bibliothek ist leer – erst Ordner scannen."
        }

        val steps = ArrayList<String>()
        if (!merged.isEmpty()) {
            steps += "Constraints sammeln"
            if (merged.genres.isNotEmpty() || merged.moods.isNotEmpty()) steps += "Kandidaten in Library filtern"
            if (merged.notTooSlow || merged.preferHighEnergy == true) steps += "Zu langsame Titel abwerten"
            if (merged.notTooQuiet) steps += "Sehr leise Masterings abwerten"
            if (merged.keepHardTracks) steps += "Harte Tracks nicht ausschließen"
            steps += "Ranking + optional Entwurf"
        }

        val canAct = !libraryEmpty && contradictions.size < 3 && (
            !merged.isEmpty() || looksMusic(text)
            )

        val expl = buildString {
            if (merged.moods.isNotEmpty()) append("Stimmung: ${merged.moods.joinToString { m -> m.label }}. ")
            if (merged.genres.isNotEmpty()) append("Genre: ${merged.genres.joinToString()}. ")
            if (merged.excludeGenres.isNotEmpty()) append("Ohne: ${merged.excludeGenres.joinToString()}. ")
            if (merged.notTooSlow) append("Nicht zu langsam. ")
            if (merged.notTooQuiet) append("Nicht zu leise. ")
            if (merged.keepHardTracks) append("Harte Tracks behalten. ")
            if (contradictions.isNotEmpty()) append("Hinweis: ${contradictions.first()}")
        }.trim()

        return ReasonResult(
            constraints = merged,
            contradictions = contradictions,
            missingQuestions = missing,
            planSteps = steps,
            canAct = canAct && missing.none { it.contains("Bibliothek") },
            explanation = expl
        )
    }

    /**
     * Filtert TrackInfos nach Constraints (echte Daten, keine Erfindung).
     * Soft-Filter: score-Modifikation, kein hartes Leeren außer Genre-Hard wenn explizit.
     */
    fun applyToRequest(base: QueueRequest, c: MusicConstraints): QueueRequest {
        var mood = base.mood ?: c.moods.firstOrNull()
        if (c.keepHardTracks && mood == Mood.CALM) {
            // nicht aggressiv rausfiltern
        }
        val exclude = base.exclude + c.excludeMoods
        // „nicht zu hart“ → aggressive Moods soft vermeiden
        val excl2 = if (c.notTooHard) exclude + Mood.ANGRY + Mood.AGGRESSIVE else exclude
        return base.copy(
            mood = mood,
            exclude = excl2,
            genres = (if (c.genres.isNotEmpty()) c.genres else base.genres).filter { it !in c.excludeGenres },
            avoidTerms = (base.avoidTerms + ConstraintExtractor.excludeTerms(c.excludeGenres)).distinct(),
            preferUnheard = base.preferUnheard || c.preferUnheard,
            minutes = c.minutes ?: base.minutes
        )
    }

    /** Soft-Score-Anpassung nach Constraints (für Recommender-Nachbearbeitung). */
    fun scoreAdjust(info: TrackInfo, c: MusicConstraints): Float {
        var s = 0f
        val a = info.analysis
        if (c.notTooSlow && a != null && a.bpm > 0f && a.bpm < 90f) s -= 0.25f
        if (c.notTooSlow && a != null && a.bpm >= 110f) s += 0.08f
        if (c.notTooQuiet && a != null && !a.lufs.isNaN() && a.lufs < -18f) s -= 0.2f
        if (c.notTooQuiet && a != null && !a.lufs.isNaN() && a.lufs > -12f) s += 0.08f
        if (c.preferHighEnergy == true && a != null && a.bpm >= 130f) s += 0.1f
        if (c.preferLowEnergy == true && a != null && a.bpm in 70f..110f) s += 0.1f
        if (c.keepHardTracks) {
            val h = TextUtil.norm(info.track.title + " " + info.track.genre)
            if (listOf("hardtekk", "hardcore", "gabber", "terror").any { h.contains(it) }) s += 0.12f
        }
        if (c.notTooHard) {
            val h = TextUtil.norm(info.track.title + " " + info.track.genre)
            if (listOf("hardtekk", "hardcore", "gabber").any { h.contains(it) }) s -= 0.15f
        }
        return s
    }

    private fun looksMusic(raw: String): Boolean {
        val t = TextUtil.norm(raw)
        return listOf("spiel", "mach", "playlist", "song", "track", "rap", "tekk", "musik", "hör", "hoer")
            .any { t.contains(it) }
    }
}

/**
 * Qualitätskontrolle vor der Ausgabe – verhindert offensichtliche Widersprüche.
 */
object QualityGate {
    fun check(
        replyText: String,
        claimedTrackCount: Int?,
        actualStarted: Boolean,
        userAskedToStart: Boolean
    ): String {
        var t = replyText
        // Nicht behaupten, Wiedergabe laufe, wenn nicht gestartet
        if (!actualStarted && userAskedToStart.not()) {
            // ok for drafts
        }
        if (!actualStarted && (t.contains("läuft jetzt") || t.contains("spiele gerade"))) {
            t = t.replace("läuft jetzt", "ist vorbereitet")
                .replace("spiele gerade", "habe ausgewählt")
        }
        if (claimedTrackCount != null && claimedTrackCount == 0 && t.contains("Titel")) {
            // leave as is – persona should already say nothing found
        }
        return t
    }
}
