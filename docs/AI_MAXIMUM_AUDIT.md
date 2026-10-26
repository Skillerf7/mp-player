# Phase 1 – Audit: Secret Player Local AI

## Was wirklich implementiert ist

| Komponente | Art | Semantik? |
|------------|-----|-----------|
| IntentEngine | Regel-/Keyword-Parser | Nein – Token/Substring |
| TrainedIntentModel | Multi-Label LogReg, Hashing-Features (~72 KB) | Begrenzt – keine echte Semantik |
| EmbeddedIntentBrain | Heuristik | Nein |
| PlayerBrain | Merge Intent + Prefs + Memory + DialogCue | Hybrid, kein LLM |
| DialogReactor | Ton-Klassifikation | Keyword |
| ResponsePlanner | Modus → Antworttyp | Minimal genutzt |
| ConversationState | Emotion, Topic, Mode, recent | Ja, strukturell |
| Memory | LIKE/DISLIKE/FACT/PATTERN + SQLite | Ja |
| PersonalLearning | Play/Skip/Accept Affinitäten | Ja |
| Recommender/HybridScore | Ranking mit Features | Ja |
| NativeLlmSlot | Stub (null) | — |
| Genre ONNX | Optional, GTZAN | Audio, nicht Dialog |

**Fazit:** Das „Intent-Modell“ klassifiziert über **Hashing-N-Gramm-Features + LogReg**, nicht über kontextuelle Embeddings. Behauptung „großes LLM“ wäre falsch.

## Doppelungen / Lücken

- ResponsePlanner vs. Persona-Templates: Planner kaum angebunden
- StructuredMusicIntent vs. PlayMood: parallel, Merge in Coordinator
- Multi-Constraints („melancholisch + nicht langsam + harte Tracks behalten“) nur teilweise
- Kein zentrales Constraint-Objekt über Turns
- Keine Qualitätsprüfung vor Reply (Halluzination von Tracks möglich in Templates – Tools prüfen Ausführung)

## Architektur-Entscheidung (dieser Stand)

Erweiterung zu **hybrider Pipeline**:

```
Text → ConstraintExtractor + IntentEngine
    → ReasoningCore (Constraints, Widersprüche, fehlende Infos)
    → Action/Recommender (nur echte Library-Daten)
    → QualityGate
    → Persona (Formulierung)
```

Kein ungefragtes externes LLM. App ohne Extra-Modell voll nutzbar.
EOF

cat > app/src/main/java/com/mp/player/ai/ConstraintModel.kt << 'EOF'
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
    /** true = Nutzer will langsamere/ruhigere Energie; false = härter/schneller; null = egal */
    val preferLowEnergy: Boolean? = null,
    val preferHighEnergy: Boolean? = null,
    /** „nicht zu langsam“ / „nicht zu leise“ */
    val notTooSlow: Boolean = false,
    val notTooQuiet: Boolean = false,
    val notTooHard: Boolean = false,
    /** Harte Tracks behalten / aggressive Tracks nicht rausfiltern */
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
            !notTooSlow && !notTooQuiet && !notTooHard && !keepHardTracks &&
            !preferUnheard && !fromOwnPlaylists && minutes == null

    /** Merge: neuere Nachricht überschreibt, Flags OR für „nicht zu…“ und keepHard. */
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
        val energyEq = when {
            preferHighEnergy == true -> emptyList()
            preferLowEnergy == true -> emptyList()
            else -> emptyList()
        }
        return UserIntent.PlayMood(
            mood = mood,
            exclude = excludeMoods,
            genreLabel = genreLabel,
            genres = genres,
            minutes = minutes,
            prepare = prepareOnly,
            support = mood == Mood.SAD || mood == Mood.LONELY || mood == Mood.CALM,
            eq = energyEq
        )
    }
}

/**
 * Extrahiert **mehrere** Constraints aus einem Satz (Umgangssprache).
 */
object ConstraintExtractor {

    fun extract(raw: String): MusicConstraints {
        val t = TextUtil.norm(raw)
        if (t.isBlank()) return MusicConstraints()
        val notes = ArrayList<String>()
        val moods = LinkedHashSet<Mood>()
        val exclude = LinkedHashSet<Mood>()
        val genres = ArrayList<String>()
        var label: String? = null

        // Genres
        val genreMap = listOf(
            "hardtekk" to listOf("hardtekk", "hardtek", "tekk"),
            "rap" to listOf("rap", "hiphop", "hip hop", "deutschrap"),
            "techno" to listOf("techno"),
            "house" to listOf("house"),
            "metal" to listOf("metal"),
            "rock" to listOf("rock"),
            "pop" to listOf("pop"),
            "classical" to listOf("klassik", "classical")
        )
        for ((lab, keys) in genreMap) {
            if (keys.any { t.contains(it) }) {
                genres += lab
                if (label == null) label = lab.replaceFirstChar { it.uppercase() }
            }
        }

        // Moods positiv
        if (listOf("melanchol", "traurig", "sad", "wehmut").any { t.contains(it) }) moods += Mood.SAD
        if (listOf("ruhig", "chill", "entspann", "calm", "lofi").any { t.contains(it) }) moods += Mood.CALM
        if (listOf("aggressiv", "wuetend", "wütend", "hardcore", "duster", "düster", "dark").any { t.contains(it) }) {
            if (t.contains("duster") || t.contains("düster") || t.contains("dark")) moods += Mood.DARK
            else moods += Mood.ANGRY
        }
        if (listOf("party", "feiern", "banger", "euphor").any { t.contains(it) }) moods += Mood.PARTY
        if (listOf("emotional", "gefuehl", "gefühl").any { t.contains(it) }) moods += Mood.SAD

        // Negationen / Ausnahmen
        val notTooSlow = listOf("nicht zu langsam", "nicht langsam", "ohne schleich", "kein schleich").any { t.contains(it) }
        val notTooQuiet = listOf("nicht zu leise", "nicht leise", "nicht zu ruhig gemastert", "mit druck").any { t.contains(it) } &&
            !t.contains("nicht mit druck")
        val notTooHard = listOf("nicht zu hart", "nicht zu aggressiv", "ohne hardcore").any { t.contains(it) }
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
        if (notTooSlow || notTooQuiet || keepHard) conf += 0.1f

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
            keepHardTracks = keepHard,
            preferUnheard = unheard,
            fromOwnPlaylists = fromPl,
            prepareOnly = prepare,
            rawConfidence = conf.coerceIn(0f, 1f),
            notes = notes
        )
    }
}
EOF
