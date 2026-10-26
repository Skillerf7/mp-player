package com.mp.player.ai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* Zusatztexte des Musik-Buddys (Undo, Snapshots, Feedback, Muster). Kurz, locker, ehrlich. */

private val rnd = java.util.Random()
private fun choose(vararg o: String): String = o[rnd.nextInt(o.size)]

fun Persona.undone() = choose("Zurückgedreht - der Klang ist wieder wie vorher. ↩️", "Erledigt, alles wieder wie eben. ↩️", "Okay, hab's zurückgenommen. ↩️")

fun Persona.nothingToRestore() =
    "Da gibt's gerade nichts, was ich zurücknehmen könnte - an deinem Klang hab ich in dieser Sitzung noch nichts geändert."

private fun whenWord(w: RestoreWhen) = when (w) {
    RestoreWhen.YESTERDAY -> "gestern"
    RestoreWhen.EARLIER -> "letztens"
    RestoreWhen.PREVIOUS -> "vorher"
}

fun Persona.noSnapshot(w: RestoreWhen) = when (w) {
    RestoreWhen.YESTERDAY -> "Von gestern hab ich nichts gespeichert - ich merke mir Sessions erst, seit ich sie für dich starte. Sag mir einfach, was du hören willst."
    else -> "Eine frühere Session hab ich noch nicht gespeichert. Ab jetzt merke ich sie mir - sag mir diesmal, was du hören willst."
}

fun Persona.askSnapshot(w: RestoreWhen, labels: List<String>) =
    "${whenWord(w).replaceFirstChar { it.uppercase() }} gab's mehr als eins: ${labels.joinToString(" oder ")}. Welches meinst du?"

fun Persona.restoredFrom(w: RestoreWhen, s: SessionSnapshot): String {
    val len = s.minutes?.let { ", ca. $it Min." } ?: ""
    return "Okay, wie ${whenWord(w)}: ${s.label()}$len."
}

fun Persona.tellSnapshot(w: RestoreWhen, s: SessionSnapshot): String {
    val time = SimpleDateFormat("EEEE, HH:mm", Locale.GERMANY).format(Date(s.at))
    val eq = s.eq?.takeIf { !it.isNeutral() }?.let { " Mit angepasstem EQ." } ?: ""
    return "${whenWord(w).replaceFirstChar { it.uppercase() }} ($time) lief bei dir: ${s.label()}.$eq Sag \"mach wie ${whenWord(w)}\", dann stelle ich es wieder her."
}

fun Persona.eqSnapAsk(summary: String) = "Der damalige EQ-Stand war ziemlich kräftig ($summary). Soll ich ihn trotzdem setzen?"

fun Persona.feedbackNothingPlaying() = "Gerade läuft nichts, dazu kann ich mir auch nichts merken. 🔇"

fun Persona.likedTrack(name: String?) = if (name != null) choose("Feier ich auch. 🔥 Ich merk mir, dass dir $name gefällt.", "Läuft! 🙌 $name wandert bei mir auf die Mag-ich-Liste.")
else choose("Freut mich! 🔥", "Läuft! 🙌")

fun Persona.dislikedTrack(remembered: Boolean) = if (remembered)
    choose("Okay, weg damit. Hab's mir als \"eher nicht\" notiert - ein einzelner Skip macht aber noch keine feste Regel.", "Alles klar, nächster. Merk ich mir leicht, nicht für immer.")
else choose("Okay, weg damit. ⏭️", "Alles klar, nächster. ⏭️")

fun Persona.recentLearned(items: List<String>) =
    if (items.isEmpty()) "In diesem Gespräch hab ich mir noch nichts Neues gemerkt."
    else "Neu gemerkt in diesem Gespräch:\n" + items.joinToString("\n") { "• $it" } + "\n\nSag \"Vergiss …\", wenn etwas nicht stimmt."

fun emotionPhrase(emotionName: String) = when (emotionName.lowercase()) {
    "sad" -> "wenn du traurig bist"
    "angry" -> "wenn du sauer bist"
    "lonely" -> "wenn du dich einsam fühlst"
    else -> "nach stressigen Tagen"
}

fun Persona.patternOffer(emotionName: String, label: String) =
    "Mir ist aufgefallen: ${emotionPhrase(emotionName)} landest du oft bei $label. Soll ich das nehmen?"

fun Persona.mildAsk() = choose(
    "Kenn ich, so'n Tag. 😅 Was war los? Oder soll ich einfach was Entspanntes anmachen?",
    "So lala, hm. Willst du erzählen, oder lieber was Chilliges laufen lassen?",
    "Okay, nicht der Brüller. 🙂 Reden, oder soll ich was Ruhiges nehmen?"
)

fun Persona.followUp(topic: String?) = if (topic != null) choose(
    "Na, das mit $topic hängt dir noch nach, oder? 🙂 Erzähl, ich hör zu.",
    "Ja, echt. Ich merk mir, dass es um $topic ging. Magst du noch was dazu sagen?"
) else choose(
    "Gute Frage. 😄 Ich bin ein Musik-Hirn und nicht so der Philosoph, aber ich hör dir zu. Was meinst du genau?",
    "Hm, da war ich gerade zu schnell. 🙈 Erzähl mir mehr - oder ich mach einfach Musik an.",
    "Sag mal mehr dazu, bro. Ich bin ganz Ohr. 🎧"
)

fun Persona.glad() = choose(
    "Schön zu hören! 😄 Soll ich was Fröhliches dazu anmachen?",
    "Läuft bei dir! 🙌 Passende Musik dazu?"
)

fun Persona.listening() = choose(
    "Ich hör dir zu. 🙂 Willst du weiter erzählen, oder soll ich nebenbei was Passendes laufen lassen?",
    "Klingt, als hättest du einiges im Kopf. Erzähl ruhig - Musik kann ich jederzeit dazumachen."
)
