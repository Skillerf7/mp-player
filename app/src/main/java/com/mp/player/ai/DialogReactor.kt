package com.mp.player.ai

/**
 * Inhaltsbewusste Gesprächsreaktion **ohne** generatives LLM.
 * Klassifiziert Ton und zieht ein kurzes, sicheres Snippet aus dem Usertext,
 * damit Antworten auf den konkreten Inhalt eingehen können.
 */
enum class DialogTone {
    /** Belastung, Stress, traurig, fertig. */
    HEAVY,
    /** Leicht, positiv, entspannt. */
    LIGHT,
    /** Humor, Sarkasmus, Witze. */
    HUMOR,
    /** Explizite Frage an den Assistenten. */
    QUESTION,
    /** Längeres Erzählen / Share ohne starke Emotion-Keywords. */
    SHARE,
    /** Neutral / kurz. */
    NEUTRAL
}

data class DialogCue(
    val tone: DialogTone,
    /** Kurzes Wort/Phrase aus dem Usertext (max. ~40 Zeichen), nie der ganze Text. */
    val snippet: String?,
    val isQuestion: Boolean,
    val wantsMusicHint: Boolean
)

object DialogReactor {

    private val HEAVY = listOf(
        "schwer", "hart", "fertig", "erschopft", "erschöpft", "müde", "mude", "stress",
        "scheisse", "scheiße", "mist", "arsch", "allein", "einsam", "traurig", "heul",
        "weine", "angst", "panik", "überfordert", "uberfordert", "kaputt", "am boden",
        "depress", "schmerz", "weh", "verloren", "hoffnungslos", "kotzt", "nervt mich"
    )
    private val LIGHT = listOf(
        "gut", "nice", "geil", "super", "freu", "happy", "entspann", "chill", "lauft",
        "läuft", "passt", "top", "mega", "cool", "witzig", "lustig", "schön", "schoen"
    )
    private val HUMOR = listOf(
        "haha", "hehe", "lol", "xd", "lmao", "witz", "joke", "spaß", "spass", "sarkasm",
        "ironisch", "übertreib", "ubertreib"
    )
    private val MUSIC_HINT = listOf(
        "musik", "song", "playlist", "hör", "hoer", "bass", "track", "wiedergabe"
    )

    fun analyze(raw: String): DialogCue {
        val t = TextUtil.norm(raw)
        if (t.isBlank()) return DialogCue(DialogTone.NEUTRAL, null, false, false)
        val isQ = raw.contains('?') || t.startsWith("warum") || t.startsWith("wieso") ||
            t.startsWith("wie ") || t.startsWith("was ") || t.startsWith("ob ")
        val heavy = HEAVY.count { t.contains(it) }
        val light = LIGHT.count { t.contains(it) }
        val humor = HUMOR.count { t.contains(it) }
        val tone = when {
            humor >= 1 && heavy == 0 -> DialogTone.HUMOR
            heavy >= 1 && heavy >= light -> DialogTone.HEAVY
            isQ -> DialogTone.QUESTION
            light >= 1 && heavy == 0 -> DialogTone.LIGHT
            t.split(Regex("\\s+")).size >= 8 -> DialogTone.SHARE
            else -> DialogTone.NEUTRAL
        }
        return DialogCue(
            tone = tone,
            snippet = snippet(raw, t),
            isQuestion = isQ,
            wantsMusicHint = MUSIC_HINT.any { t.contains(it) }
        )
    }

    /** Sicheres Kurz-Snippet: 1–3 Inhaltswörter, keine Füllwörter. */
    private fun snippet(raw: String, norm: String): String? {
        val stop = setOf(
            "ich", "du", "der", "die", "das", "und", "oder", "aber", "denn", "weil",
            "ist", "war", "bin", "bist", "hat", "hab", "habe", "mal", "schon", "noch",
            "mir", "mich", "mein", "meine", "dein", "eine", "einer", "einem", "auch",
            "sehr", "echt", "richtig", "total", "voll", "einfach", "halt", "halt",
            "bro", "digga", "alter", "ey", "ja", "nein", "ok", "okay", "also", "dann"
        )
        val words = norm.split(Regex("[^a-z0-9äöü]+"))
            .filter { it.length >= 3 && it !in stop }
        if (words.isEmpty()) return null
        // bevorzugte „schwere“ oder konkrete Nomen-ähnliche Tokens
        val pick = words.filter { w -> HEAVY.any { w.contains(it) } || w.length >= 5 }
            .ifEmpty { words }
            .take(2)
        val s = pick.joinToString(" ")
        return s.take(40).ifBlank { null }
    }
}
