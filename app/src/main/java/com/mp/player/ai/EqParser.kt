package com.mp.player.ai

import com.mp.player.Dsp
import com.mp.player.Eq
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

/*
 * EQ per Text: "mehr bass", "Höhen raus", "mach's wärmer", "ruhiger Rap mit bisschen Bass".
 * Reine Textlogik ohne Android-Klassen (testbar). Die Ausfuehrung macht der Assistant ueber PlayerTools.
 */

enum class EqKind { BASS_UP, BASS_DOWN, MID_UP, MID_DOWN, TREBLE_UP, TREBLE_DOWN, AIR_UP, AIR_DOWN, WARM, CLEAR, RESET }

/** TINY ~ "minimal" (0,75 dB), SMALL ~ "bisschen" (1,5 dB), NORMAL ~ ohne Zusatz (3 dB), BIG ~ "richtig viel" (5 dB). Alles in effektiven dB. */
enum class EqSize { TINY, SMALL, NORMAL, BIG }

data class EqCommand(val kind: EqKind, val size: EqSize = EqSize.NORMAL)

/**
 * Findet EQ-Woerter in einem bereits normalisierten Text (Umlaute ausgeschrieben, Kleinbuchstaben) und
 * liefert die Befehle plus den Resttext. Der Rest wird vom IntentEngine weiter ausgewertet, damit
 * kombinierte Saetze ("30 Minuten chilliger Rap mit bisschen mehr Bass") in einem Rutsch funktionieren.
 */
internal object EqParser {

    class Parsed(val cmds: List<EqCommand>, val rest: String)

    private val WHY = setOf("warum", "wieso", "weshalb", "woher")

    private val BASS = setOf("bass", "bassiger", "bassig", "wumms", "wumm", "untenrum")
    private val MIDS = setOf("mitte", "mitten", "mids", "midrange", "mittig")
    private val TREBLE = setOf("hoehen", "treble")
    private val AIR = setOf("luft", "air", "glanz")
    private val SHARP = setOf("scharf", "schrill", "schriller", "spitz", "stechend", "zischt", "zischelt", "hell")
    private val WARM = setOf("waermer", "waerme", "duenn")
    private val CLEAR = setOf("klarer", "klaerer", "praesenter", "praesenz", "brillanter", "transparenter", "klarheit", "dumpf")
    private val MATSCH = setOf("matsch", "matschig")
    /** Nur mit Richtungswort ein EQ-Befehl ("in der Mitte" / "oben" allein sind keine). */
    private val WEAK = setOf("oben", "unten", "mitte", "mitten", "untenrum", "mids", "midrange", "mittig", "luft", "air", "glanz", "hell", "dumpf", "duenn", "matsch", "matschig")

    private val RESET_WORDS = setOf("zurueck", "zuruecksetzen", "reset", "resetten", "ruecksetzen", "flat", "neutral", "original", "normal", "standard")
    private val EQ_WORDS = setOf("eq", "equalizer", "klang", "sound")

    private val DOWN = setOf(
        "runter", "raus", "weniger", "leiser", "weg", "reduzieren", "reduziere", "senken", "senke",
        "absenken", "schwaecher", "rausnehmen", "zuviel", "nicht", "kein", "keinen", "ohne"
    )
    private val UP = setOf("mehr", "hoch", "rein", "lauter", "staerker", "anheben", "boost", "boosten", "dazu", "drauf", "aufdrehen", "hoeher", "dran")
    /** "tiefer": bei Bass = mehr Tiefgang, bei Mitten/Hoehen/Luft = absenken. */
    private val TIEFER = setOf("tiefer")
    private val TINY = setOf("minimal", "kaum", "hauch", "winzig")
    private val SMALL = setOf("bisschen", "bissl", "bissel", "bisl", "etwas", "leicht", "wenig", "paar")
    private val BIG = setOf("viel", "stark", "richtig", "mega", "extrem", "total", "krass", "maximal", "fett")
    private val MEDIUM = setOf("deutlich", "ordentlich", "merklich")
    private val FILL = setOf(
        "noch", "mal", "ma", "mit", "und", "bitte", "auch", "aber", "so", "zu", "eine", "jetzt", "mach", "mache",
        "machs", "machen", "s", "es", "den", "die", "das", "doch", "einfach", "grad", "gerade", "wieder", "mir",
        "nur", "der", "in", "ist", "ein", "oben"
    )
    /** Woerter, die sich auf das FOLGENDE EQ-Wort beziehen ("nicht so scharf", "zu viel Bass"). */
    private val PREFIX = setOf("nicht", "kein", "keinen", "ohne", "zu", "so")

    private enum class Key { BASS, MID, TREBLE, AIR, SHARP, WARM, CLEAR, MATSCH }

    private fun keyOf(w: String): Key? = when (w) {
        in BASS -> Key.BASS
        in MIDS -> Key.MID
        in TREBLE -> Key.TREBLE
        in AIR -> Key.AIR
        in SHARP -> Key.SHARP
        in WARM -> Key.WARM
        in CLEAR -> Key.CLEAR
        in MATSCH -> Key.MATSCH
        else -> null
    }

    private val BETWEEN = Regex("""zwischen\s+m?bass\s+und\s+(?:den\s+)?hoehen|zwischen\s+(?:den\s+)?hoehen\s+und\s+m?bass""")

    /** "..., aber nicht uebertrieben" begrenzt die Staerke - es dreht die Richtung NICHT um. */
    private val MODERATE = Regex("""(?:,\s*)?(?:aber\s+)?nicht\s+(?:zu\s+|so\s+)?(?:uebertrieben|uebertreib\w*|extrem|krass|uebermaessig|zu\s+viel|zu\s+stark|zu\s+heftig)""")

    fun extract(t00: String): Parsed? {
        val moderate = MODERATE.containsMatchIn(t00)
        val t0 = MODERATE.replace(t00, " ")
        // "zwischen Bass und Hoehen" bedeutet Mitten
        val t = BETWEEN.replace(t0, " mitten ")
        val words0 = t.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (words0.isEmpty() || words0.any { it in WHY }) return null
        // "klingt dumpf" ist eine Beobachtung/Frage fuer den Erklaerer, kein Befehl
        if (words0.any { it == "klingt" || it == "klingen" }) return null

        // "EQ zuruecksetzen", "Klang wieder normal", "Equalizer reset"
        if (words0.any { it in RESET_WORDS } && words0.any { it in EQ_WORDS }) {
            val rest = words0.filter { it !in RESET_WORDS && it !in EQ_WORDS && it !in FILL }
            return Parsed(listOf(EqCommand(EqKind.RESET)), rest.joinToString(" "))
        }

        val hasDirection = words0.any { it in DOWN || it in UP || it in TIEFER || it in SMALL || it in BIG || it in TINY || it in MEDIUM || it == "zu" }
        val hasAir = words0.any { it in AIR }
        // "oben" / "unten" sind nur mit Richtungswort EQ-Woerter; "oben ... Luft" meint die Luft
        val words = words0.map { w ->
            when {
                w == "unten" && hasDirection -> "untenrum"
                w == "oben" && hasDirection && !hasAir -> "hoehen"
                else -> w
            }
        }
        val keys = words.indices.filter { i ->
            val k = keyOf(words[i])
            k != null && (words[i] !in WEAK || hasDirection)
        }
        if (keys.isEmpty()) return null

        val assigned = HashMap<Int, MutableList<String>>()
        val consumed = HashSet<Int>(keys)
        for (j in words.indices) {
            if (j in keys) continue
            val w = words[j]
            if (w !in DOWN && w !in UP && w !in TIEFER && w !in TINY && w !in SMALL && w !in BIG && w !in MEDIUM && w !in FILL) continue
            var best = -1
            var bestDist = Int.MAX_VALUE
            val nextKey = keys.firstOrNull { it > j }
            if (w in PREFIX && nextKey != null && nextKey - j <= 3) {
                best = nextKey
                bestDist = nextKey - j
            } else {
                for (k in keys) {
                    val d = abs(j - k)
                    if (d < bestDist) { bestDist = d; best = k } // bei Gleichstand gewinnt der frühere ("Bass runter")
                }
            }
            if (best >= 0 && bestDist <= 3) {
                assigned.getOrPut(best) { mutableListOf() }.add(w)
                consumed.add(j)
            }
        }

        val cmds = ArrayList<EqCommand>()
        for (k in keys) {
            val ws = assigned[k].orEmpty()
            val key = keyOf(words[k]) ?: continue
            val down = ws.any { it in DOWN } || "zu" in ws || words[k] == "bassig" ||
                (ws.any { it in TIEFER } && key != Key.BASS)
            val size = when {
                ws.any { it in TINY } -> EqSize.TINY
                ws.any { it in SMALL } -> EqSize.SMALL
                "weg" in ws -> EqSize.BIG // "Mitte weg" = deutlich raus
                ws.any { it in BIG } && "zu" !in ws -> EqSize.BIG
                else -> EqSize.NORMAL
            }
            val kind = when (key) {
                Key.BASS -> if (down) EqKind.BASS_DOWN else EqKind.BASS_UP
                Key.MID -> if (down) EqKind.MID_DOWN else EqKind.MID_UP
                Key.TREBLE -> if (down) EqKind.TREBLE_DOWN else EqKind.TREBLE_UP
                Key.AIR -> if (down) EqKind.AIR_DOWN else EqKind.AIR_UP
                Key.SHARP -> EqKind.TREBLE_DOWN
                Key.WARM -> EqKind.WARM
                Key.CLEAR -> EqKind.CLEAR
                Key.MATSCH -> EqKind.MID_DOWN
            }
            cmds += EqCommand(kind, if (moderate && (size == EqSize.NORMAL || size == EqSize.BIG)) EqSize.SMALL else size)
        }
        val rest = words.indices.filter { it !in consumed }.joinToString(" ") { words[it] }
        return Parsed(cmds, rest)
    }
}

/** Was am Equalizer geaendert werden soll - berechnet aus dem aktuellen Zustand. Reine Rechnung, keine Seiteneffekte. */
data class EqPlan(
    val bass: Float?,                       // neuer Bass-Reglerwert (dB) oder null = unveraendert
    val treble: Float?,
    val bands: List<Pair<Float, Float>>,    // (Frequenz Hz, neuer Gain dB) fuer das jeweils naechste Band
    val reset: Boolean,
    val extreme: Boolean,                   // so stark, dass erst nachgefragt wird
    val summary: String
)

object EqPlanner {
    /** Schritte in EFFEKTIVEN dB (so wie man es hoert), nicht in Reglerstellung. */
    const val STEP_TINY = 0.75f
    const val STEP_SMALL = 1.5f
    const val STEP_NORMAL = 3f     // Spezifikation: "mittel" = 2 bis 4 dB
    const val STEP_BIG = 5f        // "stark" = 4 bis 6 dB
    private const val PRESENCE_HZ = 3000f
    private const val BASS_KNOB_FACTOR = 1.5f // wie in Eq.bassGain: positive Regler wirken x1,5
    /** Ab hier fragt der Assistant vorher nach (effektiver Wert inkl. Regler-Verstaerkung). */
    const val EXTREME_BASS_DB = 9f
    const val EXTREME_TREBLE_DB = 6f
    const val EXTREME_BAND_DB = 6f

    // Mitten: 200 Hz - 2,5 kHz (Low-Mids bis Praesenz), Luft: ab 10 kHz
    private const val MID_LO = 200f
    private const val MID_HI = 2500f
    private const val AIR_LO = 10_000f
    private const val BAND_SHARE = 0.6f // mehrere ueberlappende Baender addieren sich -> pro Band weniger

    private fun step(s: EqSize) = when (s) { EqSize.TINY -> STEP_TINY; EqSize.SMALL -> STEP_SMALL; EqSize.NORMAL -> STEP_NORMAL; EqSize.BIG -> STEP_BIG }
    private fun scale(s: EqSize) = when (s) { EqSize.TINY -> 0.3f; EqSize.SMALL -> 0.6f; EqSize.NORMAL -> 1f; EqSize.BIG -> 1.6f }
    private fun r(v: Float) = Math.round(v.coerceIn(-Eq.MAX_GAIN, Eq.MAX_GAIN) * 2f) / 2f

    /** Neuer Bass-Reglerwert, damit sich der EFFEKTIVE Bass um [deltaDb] aendert (beruecksichtigt Faktor 1,5 und Boost). */
    internal fun bassKnobFor(state: Dsp, current: Float, deltaDb: Float): Float {
        val knobEffective = (if (current >= 0f) current * BASS_KNOB_FACTOR else current) + deltaDb
        val knob = if (knobEffective >= 0f) knobEffective / BASS_KNOB_FACTOR else knobEffective
        return r(knob)
    }

    fun plan(state: Dsp, cmds: List<EqCommand>): EqPlan {
        if (cmds.any { it.kind == EqKind.RESET }) {
            return EqPlan(null, null, emptyList(), reset = true, extreme = false, summary = "EQ zurückgesetzt")
        }
        var bass = state.bass
        var treble = state.treble
        val bandTargets = LinkedHashMap<Float, Float>()
        val groups = LinkedHashMap<String, MutableList<Float>>() // Anzeige-Name -> betroffene Band-Frequenzen
        fun bandGain(f: Float): Float = bandTargets[f] ?: state.bands.firstOrNull { it.f == f }?.g ?: 0f
        fun bandsIn(lo: Float, hi: Float): List<Float> = state.bands.map { it.f }.filter { it >= lo && it <= hi }
        fun shiftBands(name: String, freqs: List<Float>, delta: Float) {
            if (freqs.isEmpty()) return
            val per = delta * BAND_SHARE
            for (f in freqs) bandTargets[f] = r(bandGain(f) + (if (per >= 0) maxOf(per, 0.5f) else minOf(per, -0.5f)))
            groups.getOrPut(name) { mutableListOf() }.let { l -> freqs.forEach { if (it !in l) l += it } }
        }
        for (c in cmds) {
            when (c.kind) {
                EqKind.BASS_UP -> bass = bassKnobFor(state, bass, step(c.size))
                EqKind.BASS_DOWN -> bass = bassKnobFor(state, bass, -step(c.size))
                EqKind.TREBLE_UP -> treble = r(treble + step(c.size))
                EqKind.TREBLE_DOWN -> treble = r(treble - step(c.size))
                EqKind.MID_UP -> shiftBands("Mitten", bandsIn(MID_LO, MID_HI), step(c.size))
                EqKind.MID_DOWN -> shiftBands("Mitten", bandsIn(MID_LO, MID_HI), -step(c.size))
                EqKind.AIR_UP -> shiftBands("Luft", bandsIn(AIR_LO, Eq.F_MAX.toFloat()), step(c.size))
                EqKind.AIR_DOWN -> shiftBands("Luft", bandsIn(AIR_LO, Eq.F_MAX.toFloat()), -step(c.size))
                EqKind.WARM -> { treble = r(treble - 1.5f * scale(c.size)); bass = r(bass + 1.5f * scale(c.size)) }
                EqKind.CLEAR -> {
                    treble = r(treble + 1f * scale(c.size))
                    val idx = state.bands.indices.minByOrNull { abs(ln(state.bands[it].f / PRESENCE_HZ)) }
                    if (idx != null) {
                        val f = state.bands[idx].f
                        bandTargets[f] = r(bandGain(f) + 1.5f * scale(c.size))
                        groups.getOrPut("Präsenz") { mutableListOf() }.let { if (f !in it) it += f }
                    }
                }
                EqKind.RESET -> Unit
            }
        }
        val before = state
        val after = state.copy(bass = bass, treble = treble)
        val bassOut = if (bass != state.bass) bass else null
        val trebleOut = if (treble != state.treble) treble else null
        val bandsOut = bandTargets.entries.filter { (f, g) -> state.bands.firstOrNull { it.f == f }?.g != g }.map { it.key to it.value }

        val extreme = (bassOut != null && Eq.bassGain(after) > EXTREME_BASS_DB && Eq.bassGain(after) > Eq.bassGain(before)) ||
            (trebleOut != null && Eq.trebleGain(after) > EXTREME_TREBLE_DB && Eq.trebleGain(after) > Eq.trebleGain(before)) ||
            bandsOut.any { it.second > EXTREME_BAND_DB && it.second > (state.bands.firstOrNull { b -> b.f == it.first }?.g ?: 0f) }

        val parts = ArrayList<String>()
        if (bassOut != null) parts += "Bass ${db(Eq.bassGain(after))}"
        if (trebleOut != null) parts += "Höhen ${db(Eq.trebleGain(after))}"
        for ((name, freqs) in groups) {
            val now = freqs.map { bandGain(it) }.average().toFloat()
            val was = freqs.map { f -> state.bands.firstOrNull { it.f == f }?.g ?: 0f }.average().toFloat()
            if (now != was) parts += "$name ${db(now)}"
        }
        val summary = if (parts.isEmpty()) "Da war schon am Anschlag." else parts.joinToString(", ")
        return EqPlan(bassOut, trebleOut, bandsOut, reset = false, extreme = extreme, summary = summary)
    }

    private fun db(v: Float): String = String.format(Locale.GERMANY, "%+.1f dB", v)

    /** Gegenteil eines Befehls - fuer "bisschen weniger" nach einer EQ-Aenderung. */
    fun reverse(c: EqCommand): EqCommand? = when (c.kind) {
        EqKind.BASS_UP -> EqCommand(EqKind.BASS_DOWN, EqSize.SMALL)
        EqKind.BASS_DOWN -> EqCommand(EqKind.BASS_UP, EqSize.SMALL)
        EqKind.MID_UP -> EqCommand(EqKind.MID_DOWN, EqSize.SMALL)
        EqKind.MID_DOWN -> EqCommand(EqKind.MID_UP, EqSize.SMALL)
        EqKind.TREBLE_UP -> EqCommand(EqKind.TREBLE_DOWN, EqSize.SMALL)
        EqKind.TREBLE_DOWN -> EqCommand(EqKind.TREBLE_UP, EqSize.SMALL)
        EqKind.AIR_UP -> EqCommand(EqKind.AIR_DOWN, EqSize.SMALL)
        EqKind.AIR_DOWN -> EqCommand(EqKind.AIR_UP, EqSize.SMALL)
        EqKind.WARM -> EqCommand(EqKind.CLEAR, EqSize.SMALL)
        EqKind.CLEAR -> EqCommand(EqKind.WARM, EqSize.SMALL)
        EqKind.RESET -> null
    }
}
