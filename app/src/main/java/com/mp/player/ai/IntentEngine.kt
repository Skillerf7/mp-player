package com.mp.player.ai

import com.mp.player.Eq
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Version 1 des "Gehirns": reine Textanalyse, komplett offline, ohne Netzwerk und ohne Android-Klassen.
 * Erkennt Deutsch inkl. Umgangssprache ("bro", "scheisse", "Abschalten") und liefert einen [UserIntent].
 * Alles Unverstandene wird zu [UserIntent.Unknown] - der Assistant fragt dann locker nach, statt zu raten.
 */
object IntentEngine : BrainEngine {

    private val YES = setOf("ja", "jo", "jap", "jep", "jau", "yes", "klar", "okay", "ok", "gerne", "gern", "sicher", "passt", "bitte", "mach", "los", "unbedingt")
    private val NO = setOf("nein", "nee", "nope", "no", "noe", "abbrechen")
    private val FILL = setOf("bitte", "lieber", "nicht", "danke", "jetzt", "mal", "doch", "ich", "will", "das", "so")
    private val HELLO = setOf("hi", "hey", "hallo", "moin", "yo", "servus", "huhu", "na", "hello", "sup")
    private val THANKS = setOf("danke", "thx", "thanks", "merci", "dankeschoen")
    private val NEXT_WORDS = setOf("naechster", "naechstes", "naechsten", "naechste", "skip", "ueberspringen", "ueberspring", "next")
    private val PAUSE_WORDS = setOf("pause", "pausiere", "stopp", "stop", "halt", "pausieren")
    private val RESUME_WORDS = setOf("play", "weiter", "fortsetzen", "resume", "abspielen", "weitermachen")

    private val CRISIS = listOf(
        "suizid", "selbstmord", "mich umbringen", "mich toeten", "nicht mehr leben", "will sterben",
        "moechte sterben", "lebensmuede", "ritzen", "mir was antun", "mir etwas antun", "ende machen"
    )

    private val NEGATIVE_FEEL = listOf(
        "scheiss", "beschissen", "mies", "kacke", "down", "deprimiert", "depressiv", "traurig", "fertig",
        "einsam", "allein", "gestresst", "stress", "ueberfordert", "leer", "kaputt", "schlecht drauf",
        "nicht gut", "nicht so gut", "schlimm", "niedergeschlagen", "antriebslos", "genervt"
    )
    // Aussagen, die auch ohne \"ich bin/fuehl\" ein Befinden ausdruecken
    private val STRONG_FEEL = listOf("schluss gemacht", "komplett durch", "total durch", "bin durch", "trennung", "verlassen worden", "fix und fertig", "erschoepft", "ausgelaugt")
    private val LONELY_FEEL = listOf("einsam", "allein", "niemanden", "niemand da")
    private val SAD_FEEL = listOf("traurig", "schluss gemacht", "trennung", "liebeskummer", "herzschmerz", "verlassen", "vermiss", "depri")
    // Gemischte/lasche Stimmung (\"so lala\", \"nicht so geil\"): kein Drama, aber ein Gespraech wert
    private val MILD_PHRASES = listOf("so lala", "lala", "geht so", "nicht so geil", "nicht so toll", "nicht so prall", "nichts besonderes", "nix besonderes", "mittelmaessig", "na ja")
    private val MILD_WORDS = setOf("naja", "meh")
    private val MORE_WORDS = setOf("noch", "mehr", "bisschen", "bissl", "bissel", "bisl", "etwas", "ein", "davon", "nochmal", "mal", "bitte", "wenig", "paar")
    private val LESS_WORDS = setOf("weniger", "bisschen", "bissl", "bissel", "bisl", "etwas", "davon", "mal", "bitte", "ein", "wenig", "paar")
    private val SURPRISE_PHRASES = listOf("such du", "such selbst", "such einfach", "du entscheid", "ueberrasch", "mach was", "mach einfach", "mach irgend", "irgendwas", "irgendwelche", "egal", "wie du willst", "dir ueberlassen", "freie wahl", "zufall")
    private val DUR_WORDS = setOf("min", "mins", "minute", "minuten", "m", "h", "std", "stunde", "stunden", "ne", "eine", "einer", "zwei", "drei", "halbe", "halbstunde", "viertelstunde", "bitte", "mal", "so", "ca", "circa", "ungefaehr", "noch", "fuer", "nur", "mach", "mir", "gib", "spiel", "spiele", "leg", "lege")
    private val FEEL_WORDS = setOf("bin", "fuehl", "fuehle", "fuehlt", "geht", "gehts", "hab", "habe", "ist", "war", "grad", "gerade", "heute")

    // Stichwoerter fuer Musikwuensche. Mehrwort-Eintraege: Teilstring; Einzelwort: Wortanfang ("aggress" -> "aggressives")
    private val MOOD_KW: List<Pair<Mood, List<String>>> = listOf(
        Mood.SLEEP to listOf("einschlaf", "schlafen", "schlaflied", "gute nacht", "sleep", "bettzeit"),
        Mood.FOCUS to listOf("konzentr", "lernen", "fokus", "focus", "arbeiten", "arbeit", "study", "programmier", "hausaufgaben"),
        Mood.AGGRESSIVE to listOf("aggress", "druck", "brutal", "ballern", "heavy", "abreagier", "austicken", "ausrasten", "dampf ablassen", "haerter", "harte", "hartes", "rage", "mosh"),
        // Wut (Gefuehl) getrennt von hartem Klang: "bin sauer" -> Wut-Songs, "mach Druck" -> harte Musik
        Mood.ANGRY to listOf("wuetend", "wutanfall", "wut", "sauer", "angepisst", "stinksauer", "zorn", "hass", "angry", "mir reichts", "ich raste aus"),
        Mood.PARTY to listOf("party", "feier", "tanz", "club", "vorgluehen", "rave", "disco"),
        Mood.MOTIVATED to listOf("motivier", "motivation", "motiviert", "ehrgeiz", "durchhalt", "kaempf", "antreib", "aufraffen"),
        Mood.ENERGETIC to listOf("wach werden", "aufwach", "munter", "wecken", "energie", "pushen", "puschen", "workout", "sport", "gym", "laufen", "joggen", "schnell", "power", "pump", "hype"),
        Mood.HAPPY to listOf("froehlich", "gute laune", "happy", "gluecklich", "sommer", "sonne", "positiv", "feelgood", "feel good"),
        Mood.SAD to listOf("melanchol", "traurig", "traurige musik", "traurige lieder", "traurige songs", "traurige sachen", "traurige titel", "sad song", "sad music", "depri musik", "depri lieder", "depri songs", "heulen", "tearjerker", "herzschmerz", "liebeskummer"),
        Mood.ROMANTIC to listOf("romant", "verliebt", "kuschel", "liebeslied", "liebessong", "love song", "lovesong", "schmetterling", "date night"),
        Mood.NOSTALGIC to listOf("nostalg", "erinnerung", "damals", "throwback", "oldie", "retro", "alte zeiten", "fruehere zeiten", "kindheit"),
        Mood.DARK to listOf("duester", "dunkle", "dark", "finster", "gothic", "mystisch", "unheimlich"),
        Mood.LONELY to listOf("einsame", "einsamkeit"),
        Mood.CALM to listOf("ruhig", "chill", "entspann", "abschalt", "runterkomm", "runterfahr", "relax", "sanft", "gemuetlich", "beruhig", "ausruhen", "kopf frei", "abkuehl", "cool down", "stress")
    )

    private val EXCL_MOOD: List<Pair<String, Mood>> = listOf(
        "traurig" to Mood.SAD, "sad" to Mood.SAD, "depri" to Mood.SAD, "melanch" to Mood.SAD,
        "aggress" to Mood.AGGRESSIVE, "hart" to Mood.AGGRESSIVE, "brutal" to Mood.AGGRESSIVE,
        "ruhig" to Mood.CALM, "langsam" to Mood.CALM, "chill" to Mood.CALM,
        "schnell" to Mood.ENERGETIC, "hektisch" to Mood.ENERGETIC,
        "party" to Mood.PARTY, "froehlich" to Mood.HAPPY, "happy" to Mood.HAPPY,
        "wuetend" to Mood.ANGRY, "romant" to Mood.ROMANTIC, "nostalg" to Mood.NOSTALGIC,
        "duester" to Mood.DARK, "einsam" to Mood.LONELY, "motiv" to Mood.MOTIVATED,
        "einschlaf" to Mood.SLEEP, "schlaf" to Mood.SLEEP
    )

    private class GenreGroup(val label: String, val triggers: List<String>, val libTerms: List<String>)

    private val GENRES = listOf(
        GenreGroup("Hardtekk", listOf("hardtekk", "hardtek", "hard tek", "hardtech"), listOf("hardtek", "hard tek", "tekk")),
        GenreGroup("Hardcore", listOf("hardcore", "gabber", "frenchcore", "uptempo", "speedcore"), listOf("hardcore", "gabber", "frenchcore", "uptempo", "speedcore")),
        GenreGroup("Hardstyle", listOf("hardstyle", "rawstyle"), listOf("hardstyle", "rawstyle")),
        GenreGroup("Techno", listOf("techno", "schranz"), listOf("techno", "schranz")),
        GenreGroup("Deep", listOf("deep"), listOf("deep")),
        GenreGroup("House", listOf("house"), listOf("house")),
        GenreGroup("Trance", listOf("trance"), listOf("trance")),
        GenreGroup("Drum & Bass", listOf("dnb", "drum and bass", "drum n bass", "jungle"), listOf("drum and bass", "drum & bass", "drum n bass", "dnb", "jungle")),
        GenreGroup("Metal", listOf("metal"), listOf("metal")),
        GenreGroup("Rock", listOf("rock"), listOf("rock")),
        GenreGroup("Punk", listOf("punk"), listOf("punk")),
        GenreGroup("Rap", listOf("rap", "hiphop", "hip hop", "deutschrap", "trap"), listOf("rap", "hip hop", "hip-hop", "hiphop", "trap")),
        GenreGroup("Pop", listOf("pop"), listOf("pop")),
        GenreGroup("Jazz", listOf("jazz"), listOf("jazz")),
        GenreGroup("Klassik", listOf("klassik", "classical", "klassische"), listOf("klassik", "classical")),
        GenreGroup("Lo-Fi", listOf("lofi", "lo-fi", "lo fi"), listOf("lofi", "lo-fi", "lo fi")),
        GenreGroup("Ambient", listOf("ambient"), listOf("ambient")),
        GenreGroup("Reggae", listOf("reggae"), listOf("reggae")),
        GenreGroup("Schlager", listOf("schlager"), listOf("schlager")),
        GenreGroup("Electro", listOf("electro", "edm"), listOf("electro", "edm")),
        GenreGroup("Blues", listOf("blues"), listOf("blues")),
        GenreGroup("Folk", listOf("folk"), listOf("folk"))
    )

    private val EXCL_RE = Regex("""\b(?:kein\w*|ohne|nicht|nix)\s+(?:so\s+|zu\s+|mehr\s+|irgendwelche\s+|diese\s+|komplett\s+|ganz\s+|total\s+|richtig\s+|extrem\s+|voll\s+)?([a-z]+)""")
    private val HOUR_RE = Regex("""(\d{1,2}(?:[.,]\d)?)\s*(?:h\b|std\b|stunde|stunden)""")
    private val MIN_RE = Regex("""(\d{1,3})\s*(?:min|m\b)""")
    private val PLAY_RE = Regex("""^(?:bitte\s+)?(?:spiel|spiele|play|starte|start|leg|lege|mach|mache|hoer|hoere|such|suche|finde)\s+(?:mir\s+)?(?:bitte\s+)?(?:mal\s+)?(.+?)(?:\s+(?:an|ab|auf))?$""")
    private val ARTIST_EXPLICIT = Regex("""^(?:bitte\s+)?(?:such|suche|finde|find|spiel|spiele|play|mach|mache|zeig|zeige|starte|start|leg|lege|hoer|hoere|gib)\s+(?:mir\s+)?(?:mal\s+)?(?:bitte\s+)?(?:den\s+|die\s+|alles\s+von\s+)?(?:interpret(?:en)?|kuenstler(?:in)?|artists?|saenger(?:in)?|band)\b\s*(?:namens\s+|von\s+|:\s*)?(.+?)(?:\s+(?:an|ab|auf))?$""")
    private val ARTIST_ALLES = Regex("""^(?:bitte\s+)?(?:(?:such|suche|finde|spiel|spiele|play|mach|mache|zeig|zeige|starte|start|leg|lege|hoer|hoere|gib)\s+)?(?:mir\s+)?(?:mal\s+)?(?:bitte\s+)?(?:alles|alle\s+(?:songs?|lieder|titel|tracks?)|(?:die\s+)?(?:songs?|lieder|titel|tracks?|musik))\s+(?:von|vom|by)\s+(.+?)(?:\s+(?:an|ab|auf))?$""")
    private val QUOTED = Regex("""["„“'»«]([^"„“”'»«]{1,40})["“”'»«]""")
    private val NAMED = Regex("""(?:namens|genannt|heisst|heißt|name)\s+(.{1,40})$""", RegexOption.IGNORE_CASE)
    private val GENERIC = setOf("musik", "was", "irgendwas", "etwas", "songs", "lieder", "zeug", "sachen", "was schoenes", "irgendwas schoenes", "mal was", "musik an", "titel", "mukke", "sound", "beats")

    private fun tokenize(t: String): List<String> = t.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    private fun kwHit(words: List<String>, t: String, kw: String): Boolean =
        if (kw.contains(' ')) t.contains(kw) else words.any { it.startsWith(kw) }

    internal fun parseMinutes(t: String): Int? {
        if (t.contains("halbe stunde") || t.contains("halbstunde")) return 30
        if (t.contains("viertelstunde")) return 15
        val hm = HOUR_RE.find(t)
        if (hm != null) {
            val v = hm.groupValues[1].replace(',', '.').toDoubleOrNull()
            if (v != null) return (v * 60).roundToInt().coerceIn(1, 600)
        }
        val mm = MIN_RE.find(t)
        if (mm != null) {
            val v = mm.groupValues[1].toIntOrNull()
            if (v != null) return v.coerceIn(1, 600)
        }
        for ((w, n) in listOf("eine" to 1, "ne" to 1, "einer" to 1, "zwei" to 2, "drei" to 3)) {
            if (Regex("""\b$w\s+stunde""").containsMatchIn(t)) return n * 60
        }
        return null
    }

    // ------------------------------------------------------------------ Tippfehler

    /** Kleiner Wortschatz der Schluesselwoerter - alles andere bleibt unangetastet (keine riesige Keyword-Liste). */
    private val TYPO_VOCAB = listOf(
        "bass", "bassiger", "hoehen", "scharf", "schrill", "waermer", "ruhig", "ruhiger", "traurig", "hardtekk",
        "techno", "party", "chillig", "aggressiv", "abschalten", "schlafen", "energie", "trance", "equalizer",
        "zuruecksetzen", "ueberrasch", "entspannt", "konzentration", "musik", "minuten", "stunde",
        "hoeher", "hoeren" // echte Woerter nicht in nahe EQ-Schluessel korrigieren
    )

    /** Damerau-Levenshtein (Vertauschungen wie \"tehcno\" zaehlen als 1 Fehler). */
    private fun editDistance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
            d[i][j] = v
        }
        return d[a.length][b.length]
    }

    private fun correctWord(w: String): String {
        if (w.length < 5 || w in TYPO_VOCAB) return w
        val maxDist = if (w.length >= 8) 2 else 1
        var best: String? = null
        var bestD = Int.MAX_VALUE
        for (v in TYPO_VOCAB) {
            if (v[0] != w[0] || abs(v.length - w.length) > maxDist) continue
            val d = editDistance(w, v)
            if (d <= maxDist && d < bestD) { bestD = d; best = v }
        }
        return best ?: w
    }

    internal fun fixTypos(t: String): String = Regex("[a-z]+").replace(t) { correctWord(it.value) }

    // ------------------------------------------------------------------ EQ-Presets / EQ speichern

    private fun squash(s: String) = s.filter { it in 'a'..'z' || it in '0'..'9' }

    private fun presetIntent(t: String, words: List<String>, text: String): UserIntent? {
        val eqContext = words.any { it == "eq" || it == "equalizer" || it == "klang" || it == "preset" }
        val wantsSave = words.any { it.startsWith("speicher") || it == "sichern" || it == "sichere" }
        if (wantsSave && eqContext && !t.contains("playlist")) {
            val name = QUOTED.find(text)?.groupValues?.get(1)?.trim()
                ?: Regex("""(?:namens|genannt|heisst)\s+([a-z0-9 ]{1,30})$""").find(t)?.groupValues?.get(1)?.trim()
            return UserIntent.EqSave(name?.takeIf { it.isNotBlank() })
        }
        val wantsPreset = "preset" in words ||
            (words.any { it == "eq" || it == "equalizer" } && words.any { it == "auf" || it == "modus" || it == "profil" })
        if (wantsPreset) {
            val sq = squash(t)
            val hit = Eq.BUILT_IN.keys.sortedByDescending { it.length }.firstOrNull { name ->
                val n = squash(TextUtil.norm(name))
                n.length >= 3 && sq.contains(n)
            }
            if (hit != null) return UserIntent.EqPreset(hit)
        }
        return null
    }

    // ------------------------------------------------------------------ Einstieg

    private val PLAY_VERBS = listOf("spiel", "mach mir", "mach musik", "gib mir", "starte", "play", "leg ", "queue", "playlist", "session", "minuten", "stunde")
    private val TOPICS = listOf(
        "arbeit" to "Arbeit", "job" to "Arbeit", "chef" to "Arbeit", "kollegen" to "Arbeit", "schicht" to "Arbeit",
        "schule" to "Schule", "uni" to "Uni", "pruefung" to "Prüfung", "klausur" to "Prüfung",
        "freundin" to "Beziehung", "beziehung" to "Beziehung", "ex" to "Beziehung",
        "familie" to "Familie", "mama" to "Familie", "papa" to "Familie", "streit" to "Streit"
    )
    private val VENT_EXTRA = listOf("scheiss", "mist", "muell", "nervig", "anstrengend", "zu viel", "reicht mir", "hat mich", "fertig gemacht", "zerlegt", "geschafft", "ausgebrannt", "keinen bock", "kein bock", "keine lust", "nervt alles",
        "irgendwie durch", "echt durch", "so durch", "voll durch", "irgendwie komisch", "komisch heute", "komisch drauf", "seltsam drauf",
        "was mit mir los ist", "durcheinander", "weiss nicht was los ist")
    private val SELF_WORDS = setOf("ich", "mir", "mich", "alles", "nichts", "gestern", "wird", "fuehle", "bock", "lust")
    private val POS_FEEL = listOf("bin happy", "bin gluecklich", "geht's super", "gehts super", "geht super", "geht mir gut", "geht's gut", "gehts gut", "geht es gut", "gute laune", "bin gut drauf", "bin froh", "bin zufrieden")
    private val FILLER_WHY = setOf("bro", "denn", "eigentlich", "das", "jetzt", "so", "digga", "alter", "nur")
    private val HM_WORDS = setOf("hm", "hmm", "hmmm", "mhm", "mmh", "aha", "ah", "oh", "ach", "joa", "tja", "puh", "uff")
    private val MUSIC_WANT = Regex("""\b(?:brauch|brauche|will|moechte|haette\s+gern(?:e)?|hab\s+(?:bock|lust)\s+auf|bock\s+auf|lust\s+auf)\s+(?:jetzt\s+|mal\s+|gerade\s+)?(?:bisschen\s+|etwas\s+)?(?:musik|mukke|sound|beats)\b""")
    private val SEARCH_ANY = Regex("""^(?:bitte\s+)?(?:such|suche)\s+(?:mir\s+)?(?:mal\s+)?(?:was|etwas|irgendwas)$""")
    private val MAKE_PLAYLIST = Regex("""^(?:bitte\s+)?(?:mach|mache|erstell|erstelle|stell|bau)\s+(?:mir\s+)?(?:mal\s+)?(?:eine?\s+)?(?:neue\s+)?(?:playlist|wiedergabeliste)(?:\s+(?:zusammen|fertig))?$""")
    private val AFFECTION = listOf("hab dich lieb", "hab dich gern", "ich mag dich", "du bist geil", "du bist der beste", "du bist cool", "love you", "bist der beste", "bester bro")
    private val HOW_ARE_YOU = listOf("wie gehts", "wie geht's", "wie geht es", "was geht", "alles klar bei dir", "wie laeufts", "was machst du")
    private val WHO_ARE_YOU = listOf("wer bist du", "was bist du", "wie heisst du")
    private val TALK = listOf("langweilig", "gelangweilt", "erzaehl", "lass quatschen", "lass reden", "will labern", "will quatschen", "was denkst du", "unterhalt")
    private val GOODBYE = listOf("gute nacht", "tschuess", "tschau", "ciao", "bis spaeter", "bis morgen", "bin weg", "bye")
    private val RECOMMEND = listOf("was wuerdest du", "was hoerst du", "was empfiehlst", "empfiehl mir", "was soll ich hoeren", "was passt jetzt", "schlag was vor", "hast du einen vorschlag", "was nehmen wir")

    /** Wort -> Stimmung, falls es eine ist ("traurigen" -> SAD). Fuer Ausschluesse und Gedaechtnis. */
    internal fun moodFor(word: String): Mood? = EXCL_MOOD.firstOrNull { word.startsWith(it.first) }?.second

    /** Gedaechtnis-Begriff -> Suchbegriffe in der Library ("hardtekk" -> hardtek, tekk, ...). */
    internal fun termsFor(key: String): List<String> {
        val g = GENRES.firstOrNull { grp -> grp.triggers.any { trig -> key.startsWith(trig) || (key.length >= 4 && trig.startsWith(key)) } }
        return g?.libTerms ?: listOf(key)
    }

    private val VOL_ABS = Regex("""(?:lautstaerke|volume|vol)\s*(?:auf|bei|=|:)?\s*(\d{1,3})\s*(?:%|prozent)?""")
    private val VOL_BIG = setOf("viel", "deutlich", "stark", "ganz", "richtig", "mega", "ordentlich")
    private val VOL_SMALL = setOf("bisschen", "etwas", "leicht", "bissl", "minimal", "wenig", "paar", "kaum")

    /** "lauter", "etwas leiser", "Lautstaerke auf 10". "Bass leiser" ist ein EQ-Befehl und kommt hier nie an. */
    internal fun volumeIntent(t: String, words: List<String>): UserIntent? {
        if (words.any { it == "warum" || it == "wieso" || it == "weshalb" }) return null
        VOL_ABS.find(t)?.let { m -> m.groupValues[1].toIntOrNull()?.let { return UserIntent.Volume(null, it.coerceIn(0, 100)) } }
        if (words.size > 6) return null
        val named = t.contains("lautstaerke") || "volume" in words
        val up = "lauter" in words || (named && words.any { it == "hoch" || it == "hoeher" || it == "rauf" || it == "erhoehen" || it == "mehr" }) ||
            ("laut" in words && words.any { it == "mach" || it == "machs" })
        val down = "leiser" in words || (named && words.any { it == "runter" || it == "niedriger" || it == "weniger" || it == "senken" || it == "reduzieren" }) ||
            ("leise" in words && words.any { it == "mach" || it == "machs" })
        if (up == down) return null
        val size = when {
            words.any { it in VOL_BIG } -> 20
            words.any { it in VOL_SMALL } -> 5
            else -> 10
        }
        return UserIntent.Volume(if (up) size else -size, null)
    }

    private fun memoryIntent(raw: String): UserIntent? {
        val t = raw.trim().trimEnd('.', '!', '?').trim()
        if (t.contains("gemerkt") && Regex("""\b(?:gerade|eben|vorhin|zuletzt|soeben)\b""").containsMatchIn(t)) return UserIntent.RecentMemory
        if (Regex("""\bwas\s+(?:weisst|weiss|kennst)\s+du\b""").containsMatchIn(t) ||
            (t.contains("gemerkt") && t.contains("was "))
        ) return UserIntent.WhatDoYouKnow
        val fg = Regex("""^(?:bitte\s+)?vergiss(?:e)?\b(.*)$""").find(t)
        if (fg != null) {
            val payload = fg.groupValues[1]
            val drop = setOf("dass", "ich", "mag", "meine", "meinen", "meiner", "musik", "musikgeschmack", "praeferenzen", "geschmack", "bitte", "das", "mit", "wieder", "doch", "mal", "die", "den", "der", "nicht", "alles")
            val rest = payload.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() && it !in drop }.joinToString(" ")
            val all = payload.contains("alles") || payload.contains("praeferenz") || payload.contains("geschmack") || rest.isBlank()
            return UserIntent.Forget(if (all) null else rest)
        }
        if (Regex("""^(?:bitte\s+)?merk(?:e)?(?:'?s)?\s+dir\b""").containsMatchIn(t)) return UserIntent.Remember(raw)
        // "Bock/Lust auf ..." ist im Musikdialog ein Wunsch, keine dauerhafte Vorliebe.
        // Explizite "Ich mag ..."-/"Merk dir ..."-Aussagen werden weiter gespeichert.
        if (PLAY_VERBS.none { t.contains(it) } && !t.contains("bock auf") && !t.contains("lust auf") &&
            MemoryExtractor.extract(raw).isNotEmpty()) return UserIntent.Remember(raw)
        return null
    }

    // ------------------------------------------------------------------ Feedback / Rueckgaengig / \"wie gestern\"

    private val FB_PRE = """(?:(?:boah|alter|bro|ey|oh|wow|junge|nee)\s+)?"""
    private val FB_SUBJ = """(?:das|der|dieser|dieses)\s+(?:song|lied|titel|track|beat|sound)?\s*(?:ist|is|klingt)\s+(?:so\s+|echt\s+|richtig\s+|voll\s+|total\s+|mega\s+)?"""
    private val POSITIVE_RE = listOf(
        Regex("^" + FB_PRE + FB_SUBJ + "(?:geil|mega|stark|nice|gut|super|top|hammer|krass|schoen|fett)$"),
        Regex("""^(?:geiler|mega|krasser|guter|starker|fetter)\s+(?:song|lied|titel|track|beat)$"""),
        Regex("""^(?:das\s+)?gefaellt\s+mir(?:\s+(?:gut|richtig\s+gut|mega))?$"""),
        Regex("""^(?:feier\s+(?:ich|das|den|es)|(?:das|den)\s+feier\s+ich|love\s+it)$""")
    )
    private val NEGATIVE_RE = listOf(
        Regex("^" + FB_PRE + FB_SUBJ + "(?:nicht\\s+mein\\s+ding|nicht\\s+meins|nix\\s+fuer\\s+mich|scheisse|scheiss|kacke|mist|langweilig|nervig|schlecht|furchtbar|cringe|aetzend)$"),
        Regex("""^(?:nee\s+)?(?:nicht\s+meins|nicht\s+mein\s+ding)$"""),
        Regex("""^(?:das\s+)?(?:nervt|gefaellt\s+mir\s+nicht)$"""),
        Regex("""^mag\s+(?:ich|den|das)\s+nicht$""")
    )

    /** Kurze Rueckmeldung zum gerade laufenden Titel. Nur ganze, kurze Saetze - \"bisschen Bass waere geil\" bleibt ein EQ-Wunsch. */
    internal fun feedbackIntent(raw: String): UserIntent? {
        val t = raw.trim().trimEnd('.', '!', '?', ' ').trim()
        if (t.length < 5 || t.length > 60) return null
        if (POSITIVE_RE.any { it.matches(t) }) return UserIntent.Feedback(true)
        if (NEGATIVE_RE.any { it.matches(t) }) return UserIntent.Feedback(false)
        return null
    }

    /** \"mach das rueckgaengig\", \"wie vorher\", \"wie gestern\", \"wie letztens\", \"wie war das gestern\". */
    internal fun restoreIntent(raw: String): UserIntent? {
        val t = raw.trim().trimEnd('.', '!', '?', ' ').trim()
        if (t.length > 70) return null
        val asks = t.startsWith("wie war") || t.contains("was lief") || t.contains("was lief")
        if (Regex("""\b(?:rueckgaengig|undo)\b""").containsMatchIn(t)) return UserIntent.Restore(RestoreWhen.PREVIOUS)
        if (Regex("""\bzurueck\s+(?:auf|zu|wie)\s+(?:vorher|eben|davor)\b""").containsMatchIn(t)) return UserIntent.Restore(RestoreWhen.PREVIOUS)
        if (t.contains("gestern") && (t.contains("wie gestern") || asks)) return UserIntent.Restore(RestoreWhen.YESTERDAY, tellOnly = asks && !t.contains("wie gestern"))
        if (Regex("""\b(?:letztens|neulich|damals|letzte\s+woche|letztes\s+mal)\b""").containsMatchIn(t) &&
            (Regex("""\bwie\s+(?:letztens|neulich|damals|letzte\s+woche|letztes\s+mal|das\s+letzte\s+mal)\b""").containsMatchIn(t) || asks)
        ) return UserIntent.Restore(RestoreWhen.EARLIER, tellOnly = asks && !t.contains("mach"))
        if (Regex("""\bwie\s+(?:vorher|eben|davor|zuvor|vorhin)\b""").containsMatchIn(t)) return UserIntent.Restore(RestoreWhen.PREVIOUS)
        return null
    }

    private fun artistIntent(raw: String): UserIntent? {
        val cleaned = raw.trim().trimEnd('.', '!', '?').trim()
        val m = ARTIST_EXPLICIT.find(cleaned) ?: ARTIST_ALLES.find(cleaned) ?: return null
        val name = m.groupValues[1].trim().trim('"', '\'', ',', ':').trim()
        if (name.length < 2 || name in GENERIC) return null
        return UserIntent.SearchArtist(name)
    }

    private fun playlistSimilarityIntent(raw: String): UserIntent? {
        val t = TextUtil.norm(raw).trim().trimEnd('.', '!', '?', ' ').trim()
        // Playlist/Wiedergabeliste als Referenz – NICHT als Titel-Referenz
        val listWord = """(?:playlist|wiedergabeliste|wiedergabe\s*liste|playliste|liste)"""
        val pre = """(?:mach|mache|stell|stelle|bau|baue|such|suche|erstell|erstelle)\s+(?:mir\s+)?(?:mal\s+)?(?:wieder\s+)?"""
        val patterns = listOf(
            // "bau/mach sowas wie meine rap wiedergabeliste"
            Regex(pre + """(?:so\s+etwas|sowas|etwas|so\s+was)\s+wie\s+(?:meine\s+|die\s+)?(.+?)\s*""" + listWord + """$"""),
            // "mach mir eine playlist wie meine schillah"
            Regex(pre + """(?:eine?n?\s+)?""" + listWord + """\s+wie\s+(?:meine\s+|die\s+)?(.+?)$"""),
            // "mehr wie meine schillah playlist"
            Regex("""(?:mehr|noch\s+mehr)\s+wie\s+(?:meine\s+|die\s+)?(.+?)\s*""" + listWord + """$"""),
            // "wie meine schillah-playlist" / "wie meine rap liste"
            Regex("""^(?:wieder\s+)?(?:so\s+etwas|sowas|etwas|so\s+was)\s+wie\s+(?:meine\s+|die\s+)?(.+?)\s*""" + listWord + """$"""),
            // "meine schillah playlist nochmal" / "nochmal meine rap wiedergabeliste"
            Regex("""(?:nochmal|noch\s+mal|wieder)\s+(?:meine\s+|die\s+)?(.+?)\s*""" + listWord + """$"""),
            Regex("""(?:meine\s+|die\s+)(.+?)\s*""" + listWord + """\s+(?:nochmal|noch\s+mal|wieder)$""")
        )
        val m = patterns.firstNotNullOfOrNull { it.find(t) } ?: return null
        var name = m.groupValues[1].trim().trim('"', '\'').trim()
        // "rap wiedergabeliste" wurde schon gesplittet – Reste wie "playlist" entfernen
        name = name.replace(Regex("""\b(?:playlist|wiedergabeliste|playliste|liste)\b"""), "").trim()
        name = name.trim('-', ' ')
        return if (name.length >= 2) UserIntent.SimilarToPlaylist(name) else null
    }

    private fun trackSimilarityIntent(raw: String): UserIntent? {
        val t = raw.trim().trimEnd('.', '!', '?', ' ').trim()
        if (t.isBlank()) return null
        val explicit = listOf(
            "mehr solcher tracks", "mehr solcher songs", "mehr solcher lieder", "mehr solcher titel",
            "noch mehr solcher tracks", "noch mehr solcher songs", "noch mehr davon",
            "mehr wie der song", "mehr wie das lied", "mehr wie der track", "mehr wie diesen",
            "weitere wie den", "weitere wie das", "sowas nochmal", "so etwas nochmal",
            "aehnliche tracks", "aehnliche songs", "aehnliche lieder", "aehnliche titel"
        )
        if (explicit.any { t.contains(it) }) return UserIntent.SimilarToCurrent
        val words = tokenize(t)
        val more = words.any { it in setOf("mehr", "noch", "weitere", "weitern") }
        val ref = words.any { it in setOf("solcher", "solche", "davon", "aehnliche", "sowas", "so") }
        val music = words.any { it in setOf("tracks", "track", "songs", "song", "lieder", "lied", "titel") }
        return if (more && ref && music) UserIntent.SimilarToCurrent else null
    }

    private fun analysisIntent(raw: String): UserIntent? {
        val t = raw.trim().trimEnd('.', '!', '?', ' ').trim()
        val w = tokenize(t)
        // Library-weite Analysefragen
        if ((t.contains("analysiere") || t.contains("analyse")) &&
            (t.contains("fehlend") || t.contains("fehlt") || t.contains("bibliothek") || t.contains("library") || t.contains("alle titel"))) {
            return UserIntent.AnalysisQuery(AnalysisQueryKind.REQUEST_ANALYSIS, raw = raw)
        }
        if (t.contains("abdeckung") || (t.contains("wie viele") && t.contains("analysiert"))) {
            return UserIntent.AnalysisQuery(AnalysisQueryKind.COVERAGE, raw = raw)
        }
        if (t.contains("was hast du uber") || t.contains("was weisst du uber") || t.contains("uber diesen track") ||
            t.contains("was hast du über") || t.contains("über diesen track")) {
            return UserIntent.AnalysisQuery(AnalysisQueryKind.CURRENT_TRACK, raw = raw)
        }
        val bpmM = Regex("""(?:ca\.?|circa|ungefaehr|ungefahr)?\s*(\d{2,3})\s*bpm""").find(t)
        if (bpmM != null && w.any { it in setOf("welche", "zeig", "finde", "songs", "titel", "tracks") }) {
            return UserIntent.AnalysisQuery(AnalysisQueryKind.BPM_RANGE, bpmAround = bpmM.groupValues[1].toFloatOrNull(), raw = raw)
        }
        if (w.any { it in setOf("aehnlich", "ahnlich", "similar") } || t.contains("mehr solche")) {
            return UserIntent.AnalysisQuery(AnalysisQueryKind.SIMILAR, raw = raw)
        }
        if (t.contains("warum") && (t.contains("playlist") || t.contains("auswahl"))) {
            return UserIntent.AnalysisQuery(AnalysisQueryKind.WHY_PLAYLIST, raw = raw)
        }
        if ((t.contains("was ist") || t.contains("was bedeutet") || t.contains("erklar")) &&
            w.any { it in setOf("bpm", "lufs", "centroid", "hires", "hi-res") }) {
            return UserIntent.AnalysisQuery(AnalysisQueryKind.FEATURE_HELP, raw = raw)
        }
        // Track-bezogene Analysewerte
        val wants =
            t.contains("lies") || t.contains("auslesen") || t.contains("werte") || t.contains("daten") ||
                t.contains("analyse") || t.contains("tempo") || t.contains("wie viel bpm") ||
                t.contains("wie viele bpm") || t.contains("wie schnell") || t.contains("wie laut") ||
                t.contains("spitzenpegel") || t.contains("rms") || w.any { it == "bpm" || it == "lufs" }
        if (!wants) return null
        val fields = LinkedHashSet<AnalysisField>()
        if (w.any { it == "bpm" } || t.contains("tempo") || t.contains("wie schnell")) fields += AnalysisField.BPM
        if (w.any { it == "lufs" } || t.contains("lautheit") || t.contains("wie laut")) fields += AnalysisField.LOUDNESS
        if (t.contains("spitzenpegel") || t.contains("peak")) fields += AnalysisField.PEAK
        if (w.any { it == "rms" }) fields += AnalysisField.RMS
        if (fields.isEmpty() || t.contains("komplette analyse") || t.contains("alle analyse") || t.contains("alle werte") || t.contains("analysedaten")) {
            fields.clear()
            fields += AnalysisField.ALL
        }
        return UserIntent.AskTrackAnalysis(fields)
    }

    override fun understand(text: String): UserIntent {
        val raw = TextUtil.norm(text).trim()
        if (raw.isEmpty()) return UserIntent.Unknown
        if (CRISIS.any { raw.contains(it) }) return UserIntent.Crisis
        // Interpret-Suche VOR Tippfehlerkorrektur/EQ/Genre: Namen (z.B. "Hetzer") duerfen nicht umgedeutet werden
        artistIntent(raw)?.let { return it }
        // Rueckgaengig / \"wie gestern\" und Feedback zum laufenden Titel VOR dem Gedaechtnis (\"das ist scheisse\" ist keine Vorliebe)
        // Bezug auf Titel ("den ersten", "mach den weg", "nee den nicht") vor Feedback/Gedaechtnis
        // "wie letztens/gestern" ist eine Zeitreferenz und darf nicht vorher als
        // "sowas wie ..."-Aehnlichkeitsreferenz landen.
        restoreIntent(raw)?.let { return it }
        playlistSimilarityIntent(raw)?.let { return it }
        ReferenceParser.parse(raw)?.let { return it }
        PlaylistPlanner.refineIntent(raw)?.let { return it }
        trackSimilarityIntent(raw)?.let { return it }
        PhaseParser.parse(raw) { seg -> understand(seg) }?.let { return it }
        TopicParser.parse(raw)?.let { return it }
        feedbackIntent(raw)?.let { return it }
        // Gedaechtnis-Aussagen VOR dem EQ-Parser ("ich mag viel Bass" ist kein EQ-Befehl)
        memoryIntent(raw)?.let { return it }
        analysisIntent(raw)?.let { return it }
        // Satzzeichen stoeren nur die anchliessenden Ganzsatz-Regeln (z. B. "Such was.").
        // Fuer die Intent-Erkennung selbst sind sie bedeutungslos.
        val t = fixTypos(raw).trim().trimEnd('.', '!', '?', ' ').trim()
        val words = tokenize(t)

        presetIntent(t, words, text)?.let { return it }

        // EQ-Woerter zuerst herausloesen; der Rest wird normal ausgewertet (-> kombinierte Befehle)
        val eq = EqParser.extract(t)
        if (eq != null) {
            val inner = if (eq.rest.isBlank()) UserIntent.Unknown else parseCore(eq.rest, eq.rest)
            return if (inner is UserIntent.PlayMood) inner.copy(eq = eq.cmds) else UserIntent.EqChange(eq.cmds)
        }
        return parseCore(t, text)
    }

    private fun parseCore(t: String, text: String): UserIntent {
        if (t.isEmpty()) return UserIntent.Unknown
        val words = tokenize(t)

        fun has(vararg s: String) = s.any { t.contains(it) }

        // Ernste Aeusserungen haben Vorrang vor allem anderen
        if (CRISIS.any { t.contains(it) }) return UserIntent.Crisis

        // Antworten auf Rueckfragen
        if (words.size <= 3) {
            if (words.any { it in YES } && words.all { it in YES || it in FILL }) return UserIntent.Confirm(true)
            if (words.any { it in NO } && words.all { it in NO || it in FILL }) return UserIntent.Confirm(false)
        }

        volumeIntent(t, words)?.let { return it }

        if (words.size == 1 && words[0] in setOf("bro", "digga", "alter", "bruder", "alda")) return UserIntent.Greeting
        if (words.isNotEmpty() && words.size <= 3 && words[0] in HELLO) return UserIntent.Greeting
        if (words.size <= 5 && words.any { it in THANKS }) return UserIntent.Thanks

        // Einwuerfe und Floskeln: nie "nicht verstanden" - die Bedeutung kommt aus dem Kontext (Assistant)
        if (words.size in 1..3 && words[0] in setOf("warum", "wieso", "weshalb") && words.drop(1).all { it in FILLER_WHY }) return UserIntent.Chat(ChatKind.WHY)
        if (words.isNotEmpty() && words.size <= 2 && words[0] in setOf("echt", "wirklich", "ehrlich", "krass", "safe")) return UserIntent.Chat(ChatKind.REALLY)
        if (words.isNotEmpty() && words.size <= 2 && words[0] == "und" && words.drop(1).all { it in setOf("jetzt", "dann", "bro", "so", "nun") }) return UserIntent.Chat(ChatKind.AND)
        if (words.isNotEmpty() && words.size <= 2 && words.all { it in HM_WORDS }) return UserIntent.Chat(ChatKind.HM)
        if (words.size <= 4 && (has("kein ding", "kein problem", "gern geschehen", "passt schon") || "np" in words)) return UserIntent.Chat(ChatKind.NO_PROBLEM)
        if (has("was kannst du", "hilfe", "wie funktionierst", "wie benutze ich dich") || "help" in words) return UserIntent.Help

        // Smalltalk: nicht jede Nachricht ist ein Musikbefehl
        if (AFFECTION.any { t.contains(it) }) return UserIntent.Chat(ChatKind.AFFECTION)
        if (HOW_ARE_YOU.any { t.contains(it) }) return UserIntent.Chat(ChatKind.HOW_ARE_YOU)
        if (WHO_ARE_YOU.any { t.contains(it) }) return UserIntent.Chat(ChatKind.WHO_ARE_YOU)
        if (GOODBYE.any { t.contains(it) } && words.size <= 5) return UserIntent.Chat(ChatKind.GOODBYE)
        if (words.size <= 2 && words.all { it.startsWith("haha") || it.startsWith("hehe") || it == "lol" || it == "xd" || it == "ahaha" || it == "lmao" }) return UserIntent.Chat(ChatKind.LAUGH)
        if (RECOMMEND.any { t.contains(it) }) return UserIntent.Recommend
        val noPlay = PLAY_VERBS.none { t.contains(it) } && !t.contains("musik")
        if (noPlay && (t.contains("langweil") || t.contains("gelangweilt") || "bored" in words)) return UserIntent.Chat(ChatKind.BORED)
        if (noPlay && t.contains("erzaehl")) return UserIntent.Chat(ChatKind.TELL)
        if (noPlay && t.contains("ablenk")) return UserIntent.Chat(ChatKind.DISTRACT)
        val feelish = NEGATIVE_FEEL.any { t.contains(it) } || STRONG_FEEL.any { t.contains(it) } || VENT_EXTRA.any { t.contains(it) }
        if (noPlay && !feelish && words.size <= 7 && (has("keine ahnung", "kein plan", "keinen plan", "weiss nicht", "weiss ich nicht", "weiss grad nicht") || words.any { it == "kp" || it == "ka" })) return UserIntent.Chat(ChatKind.UNDECIDED)
        if (noPlay && (words.any { it.startsWith("labern") || it.startsWith("quatsch") || it.startsWith("plauder") || it.startsWith("unterhalt") || it == "reden" || it == "schnacken" } ||
                TALK.any { t.contains(it) })) return UserIntent.Chat(ChatKind.TALK)

        // Kurzbefehle mit Kontext: "noch bisschen", "mehr", "bisschen weniger"
        if (words.size <= 4 && words.all { it in MORE_WORDS } && words.any { it == "mehr" || it == "noch" || it == "nochmal" || it == "davon" }) return UserIntent.More
        if (words.size <= 4 && words.all { it in LESS_WORDS } && words.any { it == "weniger" }) return UserIntent.Less

        // Sleep-Timer
        val minutes = parseMinutes(t)
        if (has("timer", "einschlafzeit")) return UserIntent.StartSleep(minutes ?: 30)
        if (minutes != null && has("musik aus", "stopp nach", "stop nach", "stopp in", "stop in", "pause nach", "pause in", "aus nach", "ausschalten in", "ausmachen in")) {
            return UserIntent.StartSleep(minutes)
        }

        // Wiedergabe-Steuerung (nur bei kurzen Befehlen, damit "Pause-Musik" etc. nicht faelschlich triggert)
        if (words.size <= 4 && (words.any { it in NEXT_WORDS } || has("naechstes lied", "naechsten song"))) return UserIntent.Next
        if (words.size <= 4 && words.any { it in PAUSE_WORDS }) return UserIntent.Pause
        // "play" allein = fortsetzen; "play Bonez MC" ist dagegen eine Suche (siehe unten)
        if (words.size <= 3 && words.any { it in RESUME_WORDS } && words.all { it in RESUME_WORDS || it in FILL || it == "mach" || it == "mache" }) return UserIntent.Resume
        if ((has("queue") || has("warteschlange")) && has("leer", "loesch", "clear", "zuruecksetz")) return UserIntent.ClearQueue

        // Fragen zum Klang
        val asksWhy = words.any { it == "warum" || it == "wieso" || it == "weshalb" || it == "woher" }
        if ((asksWhy && has("klingt", "klang", "bass", "dumpf", "verzerr", "schrill", "laut", "leise", "hall", "sound", "wumm", "droehn", "knack", "rausch")) ||
            has("basslast", "klingt komisch", "klingt schlecht", "klingt dumpf", "klingt anders")
        ) {
            val topic = when {
                has("bass", "wumm", "droehn", "dumpf") -> "bass"
                has("hoehen", "schrill", "zischt", "spitz") -> "hoehen"
                has("verzerr", "knack", "uebersteuer", "rausch") -> "verzerrung"
                has("hall", "echo") -> "hall"
                has("laut", "leise") -> "lautstaerke"
                else -> "allgemein"
            }
            return UserIntent.ExplainSound(topic)
        }

        // Infos
        if (has("was laeuft", "welcher song", "welcher titel", "welches lied", "wie heisst der song", "wie heisst das lied", "wie heisst der titel", "was ist das fuer", "was spielt")) return UserIntent.WhatsPlaying
        if (has("statistik") || (has("wie viele", "wieviele", "wie viel") && has("song", "titel", "lieder", "lied", "musik", "alben", "kuenstler", "interpret"))) return UserIntent.ShowStats
        if (has("zuletzt gehoert", "zuletzt gespielt", "letzten songs", "was hab ich gehoert", "was habe ich gehoert", "verlauf", "history", "kuerzlich gehoert")) return UserIntent.ShowHistory
        if (has("duplikat", "doppelte", "doppelt vorhanden", "mehrfach vorhanden")) return UserIntent.CheckDuplicates
        if (has("fehlende datei", "fehlende songs", "fehlende titel", "kaputte datei", "verwaiste", "missing", "nicht mehr vorhanden", "dateien fehlen", "datei fehlt")) return UserIntent.CheckMissing

        // Playlist speichern (dauerhaft -> Assistant fragt nach)
        if ((has("speicher") && has("playlist", "wiedergabeliste")) || has("als playlist", "als wiedergabeliste")) {
            val name = QUOTED.find(text)?.groupValues?.get(1)?.trim()
                ?: NAMED.find(text)?.groupValues?.get(1)?.trim()?.trim('"', '\'', '.', '!')
            return UserIntent.SavePlaylist(name?.takeIf { it.isNotBlank() })
        }

        val append = has("ergaenz", "fueg", "hinzu", "dazu", "zusaetzlich", "noch mehr", "anhaeng", "als naechstes")

        if (has("favorit", "lieblingssong", "lieblingslied", "lieblingstitel", "meine lieblings")) return UserIntent.PlayFavorites(minutes, append)
        if (has("lange nicht gehoert", "lange nicht gespielt", "lange nicht mehr gehoert", "ewig nicht gehoert", "nicht mehr gehoert", "selten gehoert", "noch nie gehoert", "vergessene")) {
            return UserIntent.PlayUnheard(minutes, append)
        }

        // Ausschluesse ("keine traurigen Songs", "ohne aggressive Sachen")
        val excluded = LinkedHashSet<Mood>()
        for (m in EXCL_RE.findAll(t)) {
            val w = m.groupValues[1]
            EXCL_MOOD.firstOrNull { w.startsWith(it.first) }?.let { excluded.add(it.second) }
        }
        val tPos = EXCL_RE.replace(t, " ")
        val posWords = tokenize(tPos)

        // Befinden MIT Kontext ("Arbeit hat mich fertig gemacht", "heute war richtig scheisse"): erst zuhoeren,
        // nicht sofort Musik starten. Ganz kurze Saetze ("bin down", "sad") bleiben direkte Musikwuensche.
        val mildHit = MILD_PHRASES.any { t.contains(it) } || words.any { it in MILD_WORDS }
        // Nur Aussagen ueber sich selbst ("bin", "fuehl", "heute war", "mir ...") - "Mach traurig" / "Noch trauriger" sind Musikwuensche
        val selfRef = words.size >= 4 || mildHit || words.any { it in FEEL_WORDS || it in SELF_WORDS } ||
            STRONG_FEEL.any { t.contains(it) } || VENT_EXTRA.any { t.contains(it) }
        if (excluded.isEmpty() && words.size >= 2 && selfRef && PLAY_VERBS.none { t.contains(it) } && !t.contains("musik")) {
            val neg = mildHit || NEGATIVE_FEEL.any { t.contains(it) } || STRONG_FEEL.any { t.contains(it) } || VENT_EXTRA.any { t.contains(it) }
            if (neg) {
                val feel = when {
                    SAD_FEEL.any { t.contains(it) } || "sad" in words -> Mood.SAD
                    LONELY_FEEL.any { t.contains(it) } -> Mood.LONELY
                    has("genervt", "nervt", "aergert", "sauer", "wuetend", "wut ") -> Mood.ANGRY
                    else -> Mood.CALM
                }
                val topic = TOPICS.firstOrNull { (k, _) -> if (k.length <= 4) k in words else t.contains(k) }?.second
                val strong = STRONG_FEEL.any { t.contains(it) } || has("komplett", "total", "richtig", "voll", "extrem", "zerlegt", "am boden")
                val mild = !strong && (mildHit || has("bisschen", "bissl", "etwas", "leicht", "ein wenig", "ein bisl"))
                val intensity = if (strong) 0.9f else if (mild) 0.35f else 0.6f
                return UserIntent.Emotion(feel, topic, strong, intensity)
            }
        }

        // Gute Laune ist auch ein Befinden: erst darauf eingehen, Musik nur als Angebot
        if (excluded.isEmpty() && words.size >= 2 && PLAY_VERBS.none { t.contains(it) } && !t.contains("musik") &&
            !(NEGATIVE_FEEL.any { t.contains(it) } || STRONG_FEEL.any { t.contains(it) } || VENT_EXTRA.any { t.contains(it) }) && POS_FEEL.any { t.contains(it) }
        ) return UserIntent.Emotion(Mood.HAPPY, null, false, 0.6f)

        // Stimmung / Genre positiv erkennen
        val mood: Mood? = MOOD_KW.firstOrNull { (_, kws) -> kws.any { kwHit(posWords, tPos, it) } }?.first
        val genreGroups = GENRES.filter { g ->
            g.triggers.any { trig ->
                if (trig.contains(' ') || trig.contains('-')) tPos.contains(trig)
                else if (trig.length <= 4) posWords.any { it == trig }
                else posWords.any { it.startsWith(trig) }
            }
        }

        val noRepeat = has("wiederholung", "ohne repeat", "no repeat", "nur einmal")
        val shuffle: Boolean? = if (has("shuffle", "zufaellig", "gemischt", "mischen")) true else null
        val session = has("session")

        if (mood != null || genreGroups.isNotEmpty()) {
            return UserIntent.PlayMood(
                mood = mood,
                exclude = excluded,
                genreLabel = genreGroups.takeIf { it.isNotEmpty() }?.joinToString(" + ") { it.label },
                genres = genreGroups.flatMap { it.libTerms },
                minutes = minutes,
                noRepeat = noRepeat,
                shuffle = shuffle,
                append = append,
                session = session
            )
        }

        // Befinden: "fuehl mich scheisse" -> ruhige Musik zum Runterkommen (ohne Witze, siehe Persona)
        // Auch kurz und ohne Satzbau: "sad", "bin down", "freundin hat schluss gemacht", "bin komplett durch"
        val feelHit = NEGATIVE_FEEL.any { t.contains(it) } || "sad" in words
        val strongFeel = STRONG_FEEL.any { t.contains(it) }
        val feelsBad = (feelHit && (words.any { it in FEEL_WORDS } || words.size <= 3)) || strongFeel
        if (feelsBad && !(excluded.isNotEmpty() && !has("fuehl"))) {
            val sad = "sad" in words || SAD_FEEL.any { t.contains(it) }
            val lonely = LONELY_FEEL.any { t.contains(it) }
            val feelMood = if (sad) Mood.SAD else if (lonely) Mood.LONELY else Mood.CALM
            return UserIntent.PlayMood(mood = feelMood, minutes = minutes, support = true)
        }

        // "such du aus", "egal", "ueberrasch mich" -> Auswahl dem Assistenten ueberlassen
        if (excluded.isEmpty() && words.size <= 5 && SURPRISE_PHRASES.any { t.contains(it) }) return UserIntent.Surprise

        if (MAKE_PLAYLIST.containsMatchIn(t)) return UserIntent.PlayMood(mood = null, prepare = true)
        if (SEARCH_ANY.containsMatchIn(t) || (excluded.isEmpty() && MUSIC_WANT.containsMatchIn(t))) return UserIntent.PlayMood(mood = null)

        // Nur eine Dauer ("30 min", "ne stunde") -> wird mit dem letzten Wunsch kombiniert
        if (minutes != null && excluded.isEmpty() && words.size <= 4 && words.all { w -> w.all { it.isDigit() } || w in DUR_WORDS }) {
            return UserIntent.Minutes(minutes)
        }

        if (excluded.isNotEmpty()) {
            val wantsPlay = has("spiel", "mach mir", "gib mir", "starte", "play", "musik", "was ")
            return if (wantsPlay) UserIntent.PlayMood(mood = null, exclude = excluded, minutes = minutes, noRepeat = noRepeat, shuffle = shuffle, append = append)
            else UserIntent.Exclude(excluded)
        }

        if (minutes != null && has("musik", "songs", "lieder", "mix", "playlist")) {
            return UserIntent.PlayMood(mood = null, minutes = minutes, noRepeat = noRepeat, shuffle = shuffle, append = append, session = session)
        }
        if (session) return UserIntent.PlayMood(mood = null, minutes = minutes ?: 60, noRepeat = noRepeat, shuffle = shuffle, session = true)

        // "spiel Bonez MC" -> Titel/Interpret suchen
        // WICHTIG: "mach an" / "spiel an" allein ist KEIN Suchbegriff "an" (Start-Befehl fuer Entwurf/Pending)
        if (PlaylistPlanner.isStartDraft(t) || t in setOf("mach an", "mache an", "spiel an", "spiele an", "anmachen")) {
            return UserIntent.Confirm(true)
        }
        val pm = PLAY_RE.find(t)
        if (pm != null) {
            var q = pm.groupValues[1].trim()
            q = q.replace(Regex("""^(?:(?:die\s+)?(?:musik|songs?|lieder?|titel|alben?|album|etwas|was|zeug|sachen)\s+)?(?:von|vom|by|mit)\s+"""), "")
            q = q.replace(Regex("""^(?:den|das|die|der)\s+(?:song|lied|titel|track)\s+"""), "").trim()
            // reine Start-Suffixe wie "an"/"ab" duerfen nie zur Library-Suche werden
            if (q.isEmpty() || q in GENERIC || q in setOf("an", "ab", "auf", "mal")) {
                return UserIntent.PlayMood(mood = null, noRepeat = noRepeat, shuffle = shuffle, append = append)
            }
            return UserIntent.SearchPlay(q)
        }

        return UserIntent.Unknown
    }
}
