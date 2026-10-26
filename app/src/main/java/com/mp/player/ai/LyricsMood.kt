package com.mp.player.ai

import java.util.Locale
import kotlin.math.exp

/**
 * Stimmung eines Songtextes - rein lokal, ohne Modell: ein Woerterbuch (Deutsch + Englisch) zaehlt gefuehlsbesetzte
 * Woerter, gewichtet nach Staerke und normiert auf die Textlaenge. Das ist eine SCHAETZUNG aus Woertern:
 * Ironie, Zitate und Mehrdeutigkeit versteht es nicht. Verneinungen direkt davor ("nicht traurig") zaehlen nicht mit.
 *
 * Eintraege stehen in normalisierter Schreibweise (siehe TextUtil.norm: "ä" = "ae", "ß" = "ss").
 *  - "wort*"  = Wortanfang (traurig* trifft traurige, traurigen ...)
 *  - "a_b"    = Wortfolge (nie_aufgeben)
 *  - sonst    = ganzes Wort
 */
class LyricsProfile(val scores: Map<Mood, Float>, val words: Int) {

    /** Stimmungen, die dieser Text klar traegt, staerkste zuerst. */
    fun top(n: Int = 2, min: Float = 0.25f): List<Mood> =
        scores.entries.filter { it.value >= min }.sortedByDescending { it.value }.take(n).map { it.key }

    fun encode(): String = scores.entries
        .sortedByDescending { it.value }
        .joinToString(";") { "${it.key.name}=${String.format(Locale.US, "%.2f", it.value)}" }

    companion object {
        /** Gegenstueck zu [encode]; unbekannte oder kaputte Teile werden ueberlesen. */
        fun decode(s: String?): Map<Mood, Float> {
            if (s.isNullOrBlank()) return emptyMap()
            val out = LinkedHashMap<Mood, Float>()
            for (part in s.split(';')) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val mood = try { Mood.valueOf(part.substring(0, eq)) } catch (e: Exception) { continue }
                val v = part.substring(eq + 1).toFloatOrNull() ?: continue
                out[mood] = v.coerceIn(0f, 1f)
            }
            return out
        }
    }
}

object LyricsMood {

    private class Lex(val strong: String, val weak: String = "")

    private val LEXICON: Map<Mood, Lex> = mapOf(
        Mood.SAD to Lex(
            strong = "traurig* trauer* traene* weine weinen weinte weint schmerz* herzschmerz gebrochen* vermiss* abschied " +
                "verlust verloren leiden vergeblich kummer liebeskummer " +
                "tears cry crying cried pain broken goodbye grief sorrow heartbreak* heartbroken depressed sadness funeral regret lost_you miss_you",
            weak = "leid tod gestorben sterben verlieren zerbrochen hurt hurts miss died death sad lose"
        ),
        Mood.LONELY to Lex(
            strong = "einsam* einsamkeit verlassen* isoliert niemand lonely loneliness nobody",
            weak = "allein* leer leere stille alone empty"
        ),
        Mood.ANGRY to Lex(
            strong = "wut wuetend* hass hasse hassen hasst zorn sauer verdammt scheiss* fick* kotz* ausrast* rache fresse arschloch* " +
                "idiot* bastard* angry anger rage hate hated fury revenge fuck* bitch asshole",
            weak = "schrei* gewalt* kill killing scream* shit damn liar luegner luegen"
        ),
        Mood.ROMANTIC to Lex(
            strong = "liebe lieben liebling verliebt* kuss kuesse kuessen schatz zaertlich* umarm* sehnsucht schmetterlinge begehren " +
                "love loved lover darling kiss* sweetheart romance romantic desire forever_yours tonight_with_you",
            weak = "herz herzen zusammen naehe ewig* fuer_immer beautiful honey baby babe heart hearts together"
        ),
        Mood.NOSTALGIC to Lex(
            strong = "damals frueher erinner* kindheit jugend nostalg* vergangenheit alte_zeiten fruehere_zeiten heimat " +
                "remember memories memory yesterday childhood nostalgia old_days back_then years_ago used_to",
            weak = "vergangen* erinnerung"
        ),
        Mood.DARK to Lex(
            strong = "dunkel* finster* schatten teufel daemon* hoelle blut grab friedhof geist* albtraum alptraum abgrund krieg* " +
                "dark darkness shadow* devil hell demon* blood grave ghost nightmare evil abyss void",
            weak = "nacht angst tot schwarz sterben night fear black death sin soul"
        ),
        Mood.MOTIVATED to Lex(
            strong = "kaempf* durchhalten weitermach* siegen sieg gewinner champion* ehrgeiz erfolg* nie_aufgeben aufstehen unaufhaltsam kraft " +
                "strong stronger winner fight never_give_up rise unstoppable hustle grind believe dreams goals keep_going",
            weak = "traum traeume ziel ziele mut mutig stark glaub* gewinn* power win dream goal"
        ),
        Mood.HAPPY to Lex(
            strong = "froehlich gluecklich glueck lachen lachst laecheln sonne sommer tanzen freude spass " +
                "happy happiness smile smiling laugh* sunshine summer joy fun",
            weak = "schoen lebendig good alive sunny"
        ),
        Mood.PARTY to Lex(
            strong = "party feiern feier tanzen tanz club disco rave dancefloor wochenende vorgluehen " +
                "dance dancing dj weekend drinks shots",
            weak = "hands_up drink"
        ),
        Mood.CALM to Lex(
            strong = "ruhe ruhig friedlich sanft entspannt entspannen traumen schlafen gutenacht frieden " +
                "peace peaceful calm gentle quiet relax relaxed sleep lullaby breathe",
            weak = "stille leise langsam atmen meer wind regen mond sterne ocean rain moon stars slow soft"
        )
    )

    private val NEGATIONS = setOf(
        "nicht", "kein", "keine", "keinen", "keiner", "nie", "niemals", "nix", "ohne",
        "no", "not", "never", "dont", "isnt", "aint", "cant", "wont", "without"
    )

    private class Matcher {
        val exact = HashMap<String, MutableList<Pair<Mood, Float>>>()
        val prefix = ArrayList<Triple<String, Mood, Float>>()
        val phrases = ArrayList<Triple<String, Mood, Float>>()
    }

    private val matcher: Matcher = Matcher().also { m ->
        for ((mood, lex) in LEXICON) {
            fun add(list: String, weight: Float) {
                for (raw in list.split(' ')) {
                    val term = raw.trim()
                    if (term.isEmpty()) continue
                    when {
                        term.contains('_') -> m.phrases.add(Triple(term.replace('_', ' '), mood, weight))
                        term.endsWith("*") -> m.prefix.add(Triple(term.dropLast(1), mood, weight))
                        else -> m.exact.getOrPut(term) { ArrayList() }.add(mood to weight)
                    }
                }
            }
            add(lex.strong, 1.0f)
            add(lex.weak, 0.4f)
        }
    }

    /** Ab hier wird ein Treffer ueberhaupt gespeichert. */
    private const val MIN_SCORE = 0.08f
    /** Ein Text unter dieser Wortzahl ist zu kurz, um Stimmung zu behaupten. */
    private const val MIN_WORDS = 12

    /**
     * @return Profil oder null, wenn der Text zu kurz ist. Ein Profil ohne Eintraege heisst: Text vorhanden, aber ohne
     * erkennbare Gefuehlswoerter (neutral).
     */
    fun analyze(text: String): LyricsProfile? {
        val norm = TextUtil.norm(text)
        val tokens = norm.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (tokens.size < MIN_WORDS) return null

        val counts = HashMap<Mood, Float>()
        for (i in tokens.indices) {
            val tok = tokens[i]
            val negated = i > 0 && tokens[i - 1] in NEGATIONS
            if (negated) continue
            matcher.exact[tok]?.forEach { (mood, w) -> counts[mood] = (counts[mood] ?: 0f) + w }
            if (tok.length >= 4) {
                for ((stem, mood, w) in matcher.prefix) {
                    if (tok.startsWith(stem)) counts[mood] = (counts[mood] ?: 0f) + w
                }
            }
        }
        if (matcher.phrases.isNotEmpty()) {
            val flat = tokens.joinToString(" ")
            for ((phrase, mood, w) in matcher.phrases) {
                var from = 0
                while (true) {
                    val idx = flat.indexOf(phrase, from)
                    if (idx < 0) break
                    counts[mood] = (counts[mood] ?: 0f) + w
                    from = idx + phrase.length
                }
            }
        }

        // Dichte statt Rohzahl: lange Texte haben von Natur aus mehr Treffer. 1 - e^(-k*d) bleibt in 0..1.
        val denom = maxOf(60, tokens.size).toFloat()
        val scores = LinkedHashMap<Mood, Float>()
        for ((mood, c) in counts) {
            val s = 1f - exp(-30f * c / denom)
            if (s >= MIN_SCORE) scores[mood] = s.coerceIn(0f, 1f)
        }
        return LyricsProfile(scores, tokens.size)
    }
}
