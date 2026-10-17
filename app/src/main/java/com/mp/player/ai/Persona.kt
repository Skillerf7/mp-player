package com.mp.player.ai

import kotlin.random.Random

/**
 * Tonfall des Assistenten: locker, kurz, leicht frech ("bro", Emojis) - aber nicht nervig.
 * Bei ernsten Themen (Befinden, Krise) gibt es bewusst keine Witze und keine Slang-Spielereien.
 */
class Persona(private val rnd: Random = Random.Default) {

    private fun pick(vararg o: String): String = o[rnd.nextInt(o.size)]

    fun greeting() = pick(
        "Yo! 👋 Sag mir, wie's dir geht oder was du hören willst - ich bau dir was aus deiner Library.",
        "Na? 🎧 Erzähl mir kurz, worauf du Bock hast, ich such dir was raus.",
        "Hey! Stimmung, Genre oder Minuten - sag einfach, ich mach die Queue."
    )

    // ---- Smalltalk ----
    fun ack() = pick("Jo. 👍", "Alles klar.", "Passt.", "Okay. 🙂")
    fun affection() = pick(
        "Hab dich auch gern, bro. 😄 Ich bin zwar nur 'ne Musik-App, aber ich hör dir zu. Was brauchst du?",
        "Haha, danke. 🙌 Sag Bescheid, wenn ich was anmachen soll."
    )
    fun howAreYou() = pick("Läuft bei mir. 😎 Und bei dir?", "Ich bin startklar, dauernd bereit für Bass. 🔊 Wie siehts bei dir aus?")
    fun whoAreYou() = "Ich bin dein Musik-Buddy in Secret Player. 🎧 Alles läuft offline hier auf deinem Gerät: Ich kann reden, mir deinen Geschmack merken und Musik für dich zusammenstellen."
    fun talk() = pick("Klar, lass quatschen. 🙂 Was geht dir durch den Kopf?", "Bin dabei. Erzähl mal. Musik machen wir, wenn du Bock hast.")
    /** Nach Venting: nicht nochmal „was geht dir durch den Kopf?“ – Nutzer hat schon was gesagt. */
    fun talkAfterShare(topic: String?) = pick(
        if (topic != null) "Ok, wir reden. Ich bin da – zu $topic oder was auch immer noch anliegt."
        else "Ok, wir reden. Ich hör zu – du musst nichts nochmal von vorn erzählen.",
        "Alles klar, nur quatschen. Sag einfach weiter, wenn du magst.",
        "Bin da. Kein Musik-Zwang – erzähl, was noch kommt."
    )
    fun alreadySaid() = pick(
        "Stimmt, hast du. Ich hab’s mitbekommen – sorry, falls’s so wirkte als hätt ich’s vergessen.",
        "Ja, war schon klar. Ich bin weiter hier und hör zu.",
        "Hab ich auf dem Schirm. Willst du noch was dazu sagen, oder lieber was anderes?"
    )
    fun chatContinue(topic: String?) = pick(
        "Mmm. Klingt schwer." + (if (topic != null) " Besonders wegen $topic." else ""),
        "Verstehe. Nimm dir die Zeit.",
        "Hart. Ich bin noch da, wenn du weiterreden willst.",
        "Ok. Kein Druck – sag so viel oder so wenig du willst."
    )
    fun goodbye() = pick("Bis dann, bro. ✌️", "Mach's gut! Die Musik läuft, wie du sie gelassen hast.")
    fun laugh() = pick("😄", "Haha. 😄")

    // ---- Zuhoeren / Gefuehle (bewusst ruhig, keine Witze) ----
    fun emotionAsk() = pick(
        "Oh man. Willst du erzählen oder soll ich dich erstmal mit Musik ablenken?",
        "Uff. Magst du erzählen, was los ist, oder lieber erstmal Musik?",
        "Klingt nicht gut. Willst du reden oder erstmal abschalten?"
    )
    fun emotionOffer(topic: String?, strong: Boolean): String {
        val lead = when (topic) {
            "Arbeit" -> pick("Uff, Arbeit kann einen echt zerlegen.", "Das klingt nach einem harten Arbeitstag.")
            "Schule", "Uni", "Prüfung" -> pick("Uff, das klingt nach viel Druck.", "Puh, anstrengend.")
            "Beziehung" -> pick("Das tut weh, ich weiß.", "Oh man, das ist echt scheiße.")
            "Familie", "Streit" -> pick("Streit zieht einen richtig runter.", "Uff, das nimmt einen mit.")
            else -> if (strong) pick("Uff, das klingt richtig hart.", "Oh man, das klingt echt fertig.") else pick("Verstehe.", "Klingt nicht gut.")
        }
        return "$lead Magst du erstmal nur quatschen, oder soll ich dir was Ruhiges anmachen?"
    }
    private fun thing(m: Mood?): String = when (m) {
        null -> "einen bunten Mix"
        Mood.CALM -> "was Ruhiges"
        Mood.SAD -> "was Melancholisches"
        Mood.LONELY -> "was Warmes"
        Mood.ANGRY -> "was, das Dampf ablässt"
        Mood.AGGRESSIVE -> "was Hartes"
        Mood.ENERGETIC -> "was Energiegeladenes"
        Mood.HAPPY -> "was Fröhliches"
        Mood.PARTY -> "Party-Mucke"
        Mood.FOCUS -> "was zum Fokussieren"
        Mood.SLEEP -> "was zum Einschlafen"
        Mood.ROMANTIC -> "was Romantisches"
        Mood.NOSTALGIC -> "was Nostalgisches"
        Mood.DARK -> "was Düsteres"
        Mood.MOTIVATED -> "was Motivierendes"
    }
    private fun notThing(m: Mood): String = when (m) {
        Mood.SAD -> "Trauriges"; Mood.AGGRESSIVE -> "Aggressives"; Mood.ENERGETIC -> "Hektisches"; Mood.CALM -> "Ruhiges"
        Mood.PARTY -> "Partymäßiges"; Mood.HAPPY -> "Fröhliches"; Mood.ANGRY -> "Wütendes"; Mood.ROMANTIC -> "Romantisches"
        Mood.NOSTALGIC -> "Nostalgisches"; Mood.DARK -> "Düsteres"; Mood.LONELY -> "Einsames"; Mood.MOTIVATED -> "Motivierendes"
        else -> m.label
    }
    private fun nothing(excl: Set<Mood>) = excl.joinToString(" oder ") { notThing(it) }

    fun goalOffer(mood: Mood?, excluded: Set<Mood>): String {
        val no = if (excluded.isNotEmpty()) ", aber nichts ${nothing(excluded)}" else ""
        return pick("Dann würde ich gerade ${thing(mood)} nehmen$no. Soll ich?", "Ich würd ${thing(mood)} machen$no. Passt das?")
    }
    fun offerStillOpen(excl: Set<Mood>) =
        "Okay, " + (if (excl.isNotEmpty()) "nichts ${nothing(excl)}" else "notiert") + ". Soll ich dann starten?"
    fun eqNoted() = pick("Merk ich mir, kommt mit dem Start dazu. 🔊 Soll ich loslegen?", "Alles klar, bisschen Bass kommt dazu. Soll ich starten?")
    fun recommend(mood: Mood?, likes: List<String>, night: Boolean): String {
        val base = when {
            mood != null -> "Ich würde jetzt ${thing(mood)} nehmen"
            likes.isNotEmpty() -> "Nach deinem Geschmack würde ich ${likes.first()} nehmen"
            night -> "Es ist spät, ich würde ${thing(Mood.CALM)} nehmen"
            else -> "Ich hab noch keinen Anhaltspunkt, würde dir ${thing(null)} machen"
        }
        return "$base. Soll ich?"
    }

    // ---- Gedaechtnis ----
    internal fun remembered(items: List<MemoryItem>): String = items.joinToString("\n") { i ->
        when {
            i.kind == MemKind.FACT -> "Notiert: ${i.label}. 📝"
            i.scope == MemScope.EPHEMERAL && i.kind == MemKind.DISLIKE -> "Okay, heute kein ${i.label}. Das gilt nur für heute."
            i.scope == MemScope.EPHEMERAL -> "Okay, heute: ${i.label}. Das gilt nur für heute."
            i.kind == MemKind.DISLIKE -> "Alles klar, ${i.label} ist nicht dein Ding. Hab ich mir gemerkt. 👎"
            i.confidence < 0.7f -> "Okay, ${i.label} - hab ich notiert. Wenn das öfter kommt, merk ich es mir fest. 👍"
            else -> "Merk ich mir: du magst ${i.label}. 👍"
        }
    }
    fun nothingToRemember() = "Das konnte ich nicht als Vorliebe einordnen. 🤔 Sag z. B. \"Ich mag Hardtekk\" oder \"Heute keine Schlager\"."
    fun memoryFailed() = "Das konnte ich gerade nicht speichern - die Musik läuft aber normal weiter."
    fun forgot(n: Int, topic: String?) = when {
        n == 0 -> "Dazu hab ich nichts gespeichert."
        topic == null -> "Erledigt, ich hab alles vergessen ($n Einträge). 🧹"
        else -> "Okay, \"$topic\" ist vergessen ($n ${if (n == 1) "Eintrag" else "Einträge"}). 🧹"
    }
    fun avoided(labels: List<String>) = "(Ohne ${labels.joinToString(", ")}, wie du's wolltest.)"

    fun thanks() = pick("Immer. 🤝", "Passt, bro. 😎", "Gern! Sag Bescheid, wenn's was anderes sein soll.")

    fun help() = "Das kann ich - alles offline, nur mit deiner Library:\n" +
        "• \"Mach mir 30 Minuten zum Abschalten\"\n" +
        "• \"Gib mir was Aggressives\" / \"Hardtekk-Session ohne Wiederholungen\"\n" +
        "• \"Heute keine traurigen Songs\"\n" +
        "• \"Spiel was, was ich lange nicht gehört habe\"\n" +
        "• \"Mehr Bass\", \"Höhen raus\", \"mach's wärmer\", \"EQ zurücksetzen\" (gilt nur für die Sitzung)\n" +
        "• Kurz geht auch: \"rap\", \"sad\", \"30 min\", \"noch bisschen\", \"such du aus\"\n" +
        "• \"Warum klingt das so basslastig?\"\n" +
        "• \"Sleep-Timer 20 Minuten\", \"Was läuft gerade?\", \"Wie viele Songs hab ich?\"\n" +
        "• \"Speicher die Queue als Playlist\", \"Gibt's Duplikate?\", \"Fehlen Dateien?\"\n" +
        "Ich lösche nie Dateien und ändere nichts Dauerhaftes, ohne vorher zu fragen."

    fun askAgain(k: AskKind) = when (k) {
        AskKind.MUSIC_OR_TALK -> pick("Kurz: Musik oder quatschen?", "Sag mir kurz: Musik anmachen oder erstmal reden?")
        AskKind.ENERGY -> pick("Eher ruhig oder mit bisschen Druck?", "Ruhig oder mit Druck?")
        AskKind.SAD_OR_UP -> pick("Eher reinfühlen oder langsam wieder hochkommen?", "Reinfühlen oder hochkommen?")
        AskKind.BORED -> pick("Was Neues entdecken oder kurz quatschen?", "Entdecken oder quatschen?")
    }
    fun askQuestion(k: AskKind) = when (k) {
        AskKind.MUSIC_OR_TALK -> "Musik oder quatschen?"
        AskKind.ENERGY -> pick("Okay. Eher ruhig oder mit bisschen Druck?", "Alles klar, ruhig oder mit etwas Druck?")
        AskKind.SAD_OR_UP -> pick("Okay. Eher traurig zum Reinfühlen oder lieber langsam wieder hochkommen?", "Alles klar. Reinfühlen oder lieber wieder hochkommen?")
        AskKind.BORED -> "Was Neues entdecken oder kurz quatschen?"
    }
    /** Transparenz bei mittlerer Sicherheit: nie so tun, als waere es eindeutig gewesen. */
    fun understoodAs(label: String) = "Ich war mir nicht ganz sicher, hab's als \"$label\" verstanden."

    fun empathyLine() = pick("Klingt nach 'nem miesen Tag. Dann machen wir's weich.", "Verstehe. Ich bau dir was dazu.", "Okay, ich hör dich. Hier ist der Plan:")
    fun phaseHead(count: Int, minutes: Long) = "Hab dir $count Titel für ca. $minutes Min. in Phasen zusammengestellt:"
    fun phaseMissing(labels: List<String>): String {
        val names = labels.joinToString(", ")
        return "Zu $names hab ich in deiner Library nichts Passendes gefunden, deshalb fehlt die Phase."
    }

    fun really() = pick("Jo, echt. 😄", "Ja, ehrlich.", "Jup.")
    fun noProblem() = pick("Immer. 🤝", "Gern!", "Passt.")
    fun hm() = pick("Alles gut? Sag Bescheid, wenn ich was machen soll.", "Hm. Ich bin da, falls du was brauchst.", "Jo. 🙂")
    fun hmListening() = pick("Nimm dir Zeit. Magst du erzählen?", "Ich bin da. Willst du erzählen?")
    fun andPrompt() = pick("Ich bin da. 🙂 Soll ich Musik anmachen, oder quatschen wir kurz?", "Und? Musik oder quatschen?")
    fun undecided() = pick("Kein Plan gerade? Wir können auch einfach bisschen Musik machen - oder kurz quatschen.", "Kenn ich. Soll ich was anmachen, oder quatschen wir erstmal?")
    fun distract() = pick("Klar. Soll ich dich mit Musik ablenken, oder quatschen wir einfach kurz?", "Mach ich. Musik zum Ablenken oder erstmal ein bisschen reden?")
    fun bored() = pick("Langweilig, hm. Soll ich dir was Unbekanntes aus deiner Library raussuchen, oder quatschen wir kurz?", "Dann wird's Zeit für was Neues. Entdecken, oder lieber kurz quatschen?")
    fun happyOffer() = pick("Das freut mich! 😄 Soll ich was Passendes dazu anmachen?", "Läuft bei dir. Magst du dazu was Fröhliches hören?")
    fun whyEq() = "Du wolltest den Klang anders haben, deshalb hab ich den EQ in kleinen Schritten verändert. Mit \"zurück\" geht's wieder weg."
    fun whyAsked() = "Weil's dir nicht gut ging und ich dir nicht einfach Musik draufwerfen wollte, ohne zu wissen, ob du das willst."
    fun whyNothing() = pick("Worauf bezieht sich dein Warum? Ich hab gerade nichts, was ich begründen könnte.", "Warum was genau? Sag mir, worum es geht, dann erklär ich's.")
    private val FACTS = listOf(
        "Die meisten Popsongs liegen irgendwo zwischen 90 und 130 BPM.",
        "Das menschliche Gehör reicht grob von 20 Hz bis 20 kHz - genau diesen Bereich deckt dein EQ ab.",
        "LUFS misst die wahrgenommene Lautheit eines Songs. Viele Streamingdienste normalisieren auf etwa -14 LUFS.",
        "Eine CD speichert 44.100 Messungen pro Sekunde. Das reicht, um alles bis 20 kHz sauber festzuhalten.",
        "Tiefe Bässe um 40 Hz spürst du fast mehr, als du sie hörst.",
        "Moll-Tonarten klingen für viele Menschen trauriger als Dur - das hängt an der kleinen Terz im Akkord.",
        "Ein Limiter drückt nur die lautesten Spitzen runter, damit der Rest lauter wirken kann."
    )
    fun funFact() = "Fun Fact: " + FACTS[rnd.nextInt(FACTS.size)] + " Noch einen, oder soll ich Musik machen?"

    fun resumeNothing(topic: Topic) = when (topic) {
        Topic.PLAYLIST -> "Von einer Playlist oder einem Entwurf von eben hab ich gerade nichts offen. Sag mir, was du hören willst."
        Topic.MUSIC -> "Gerade läuft nichts, was ich dir zeigen könnte. Sag mir, was du hören willst."
        Topic.EQ -> "Am EQ ist gerade alles auf Standard."
        else -> "Dazu hab ich gerade nichts offen."
    }
    fun resumeRunning(count: Int, minutes: Long, nowPlaying: String?) =
        "Aktuell läuft eine Queue mit $count Titeln (ca. $minutes Min.)" + (if (nowPlaying != null) ", gerade: $nowPlaying" else "") + "."
    fun resumeEq(bass: String, treble: String) = "Der EQ steht gerade auf Bass $bass und Höhen $treble."
    fun resumeConversation(summary: String) = if (summary.isBlank()) "Wir haben noch nicht viel besprochen." else "Bisher: $summary"
    fun patternRecommend(p: Pattern) = "Du hörst ${p.daypart.label} oft ${p.label} (${p.count}x). Soll ich das machen?"

    fun draftReady(count: Int, totalMs: Long, preview: List<String>, extended: Boolean): String {
        val min = totalMs / 60_000L
        val head = if (extended) "Entwurf erweitert: jetzt $count Titel, ca. $min Min." else pick("Hab dir einen Entwurf zusammengestellt: $count Titel, ca. $min Min.", "Entwurf steht: $count Titel, ca. $min Min.")
        return head + "\n\n" + preview.joinToString("\n") { "• $it" } + (if (count > preview.size) "\n… und ${count - preview.size} weitere" else "") +
            "\n\nGestartet ist noch nichts. Sag \"Spiel sie\", wenn's passt, oder ändere ihn (\"der dritte ist scheiße\", \"länger\")."
    }
    fun arcNote(arc: Arc) = when (arc) {
        Arc.FALL -> "Die Energie geht nach und nach runter."
        Arc.SUPPORT -> "Sanfter Einstieg, in der Mitte intensiver, am Ende wieder ruhiger."
        Arc.PEAK -> "Die Energie baut sich auf, der Höhepunkt liegt gegen Ende, danach klingt's aus."
        Arc.FLAT -> ""
    }
    fun softened(from: Mood) = "Du wolltest ${from.label}, aber nicht zu ${from.label} - ich nehm was Weicheres, Nostalgisches statt maximal ${from.label}."
    fun nothingToStart() = "Da wartet gerade kein Entwurf auf den Start."
    fun nothingToRefine() = "Ich hab gerade keine Playlist, die ich ändern könnte. Sag mir erst, was du hören willst."
    fun cannotShorten(n: Int) = "Kürzer geht kaum, das sind schon nur $n Titel."
    fun shortenFailed() = "Das Kürzen hat nicht geklappt - du kannst Titel in der Queue-Ansicht mit dem X entfernen."
    fun shortened(before: Int, after: Int, minBefore: Int, minAfter: Int, draft: Boolean) =
        (if (draft) "Entwurf gekürzt" else "Queue gekürzt") + ": von $before auf $after Titel (ca. $minBefore → $minAfter Min.)."

    fun unknown() = pick(
        "Ich bin mir gerade nicht sicher, was du meinst. 🤔 Meinst du Musik, oder willst du einfach kurz quatschen?",
        "Hm, da bin ich grad nicht sicher. Musik oder quatschen?",
        "Das hab ich nicht ganz eingeordnet. 🙂 Soll ich Musik anmachen, oder reden wir kurz?"
    )

    // ---- EQ ----
    fun eqDone(summary: String) = pick("Mach ich.", "Alles klar.", "Okay bro.", "Kommt.") + " $summary 🔊"
    fun eqReset() = pick("EQ ist wieder flat.", "Alles zurück auf neutral. 👌", "Mach ich - EQ ist zurückgesetzt.")
    fun eqExtreme(summary: String) = "Das wäre schon ziemlich heftig ($summary). Trotzdem?"
    fun eqHint() = "Gilt nur für diese Sitzung - sag \"EQ speichern\", wenn es bleiben soll."
    fun eqPreset(name: String) = pick("Okay, $name an.", "Alles klar, Preset $name. 🎚️", "Kommt: $name.")
    fun eqPresetMissing() = "Das Preset kenn ich nicht. 🤷 Schau im Presets-Bildschirm, wie es heißt."
    fun eqNothingToUndo() = "Weniger wovon? Sag z. B. \"Bass runter\"."
    fun eqSaved(name: String?) = if (name != null) "Gespeichert: Preset \"$name\". ✅" else "Gespeichert - der EQ-Stand bleibt jetzt dauerhaft. ✅"
    fun eqSaveFailed() = "Das Speichern hat nicht geklappt - der EQ läuft aber weiter."
    fun eqAsk(name: String?) = if (name != null) "Soll ich den aktuellen EQ als Preset \"$name\" speichern?" else "Soll ich den aktuellen EQ dauerhaft als Standard speichern?"
    fun eqNotChanged() = "Da ist schon am Anschlag - weiter geht's nicht. 🙂"

    /** Bewusst ohne Witze und ohne Emoji-Overload. */
    fun support(mood: Mood? = null) = when (mood) {
        Mood.SAD -> "Okay bro. Kein Gelaber. 🫂\nIch mach dir was Weiches, das zu dir passt - ohne dich weiter runterzuziehen."
        Mood.LONELY -> "Ich bin da. 🫂\nIch mach dir was Warmes an, das sich nicht nach allein anfühlt."
        else -> "Okay bro. Kein Gelaber. 🫂\nIch mach dir erstmal was Ruhiges zum Abschalten."
    }

    /** Ernste Aeusserung: keine Musik, keine Witze, kurz und menschlich. */
    fun crisis() = "Das klingt gerade echt schwer, und ich bin froh, dass du es sagst. 💙\n" +
        "Ich bin nur eine Musik-App und kann dir dabei nicht wirklich helfen - bitte sprich mit einem Menschen, dem du vertraust. " +
        "Wenn du dich in Gefahr fühlst, ruf den Notruf 112 an. In Deutschland ist die Telefonseelsorge kostenlos und rund um die Uhr erreichbar: 0800 111 0 111.\n" +
        "Wenn du magst, mach ich dir danach was Beruhigendes an - sag einfach Bescheid."

    fun moodIntro(mood: Mood?, genreLabel: String?, isSupport: Boolean): String {
        if (isSupport) return support(mood)
        if (genreLabel != null) {
            return pick("$genreLabel, kommt. 🔊", "Okay, $genreLabel-Modus an. ⚡", "$genreLabel - los geht's.")
        }
        return when (mood) {
            Mood.AGGRESSIVE -> pick("AHAHA okay, verstanden. 💀\nDann machen wir jetzt Druck.", "Alles klar, Ventil auf. 🔥", "Okay, Gas geben. 💀")
            Mood.CALM -> pick("Alles klar, ich fahr mal runter. 🌙", "Okay, ruhiger Modus. 😌", "Kommt: was zum Runterkommen. 🌙")
            Mood.SLEEP -> pick("Gute-Nacht-Modus. 😴 Es wird von Titel zu Titel leiser.", "Schlafenszeit. 🌙 Ich fahr die Queue langsam runter.")
            Mood.FOCUS -> pick("Fokus-Modus an. 🎧 Nichts, was dich rauszieht.", "Okay, Konzentration. 🎯")
            Mood.ENERGETIC -> pick("Let's go! ⚡", "Okay, Energie rein. 🚀")
            Mood.PARTY -> pick("Party-Modus! 🔊", "Okay, es wird laut. 🎉")
            Mood.HAPPY -> pick("Gute Laune incoming. ☀️", "Okay, was Fröhliches. 😄")
            Mood.SAD -> pick("Okay, dann lassen wir's fließen. 🌧️", "Alles klar, melancholisch. 🌧️")
            Mood.ANGRY -> pick("Okay, Wut raus. 🔥 Ich such dir was, das Dampf ablässt.", "Verstanden, Ventil auf. 💢", "Alles klar, lass es raus. 🔥")
            Mood.LONELY -> pick("Ich bin da. 🫂 Was Warmes kommt.", "Okay, was für die einsamen Stunden. 🌙")
            Mood.ROMANTIC -> pick("Okay, was fürs Herz. 💗", "Alles klar, romantisch. 🌹")
            Mood.NOSTALGIC -> pick("Zeitreise startet. 📼", "Okay, alte Erinnerungen. 🌅")
            Mood.DARK -> pick("Okay, düster. 🖤", "Alles klar, dunkle Seite. 🌑")
            Mood.MOTIVATED -> pick("Los, du schaffst das. 💪", "Okay, Motivation rein. 🚀")
            null -> pick("Läuft. 🎶", "Okay, Mix kommt. 🎶", "Hier, Mischung aus deiner Library. 🎶")
        }
    }

    fun unheardIntro() = pick("Ich grab mal was aus, das lange keiner gehört hat. ⛏️", "Vergessene Schätze, kommen. 💎")
    fun favoritesIntro() = pick("Deine Favoriten, kommen. ❤️", "Das Beste vom Besten. ⭐")

    fun summary(count: Int, totalMs: Long, appended: Boolean): String {
        val min = (totalMs / 60_000L).toInt().coerceAtLeast(1)
        return "🎵 $count Titel · ca. $min Min.${if (appended) " (hinten angehängt)" else ""}"
    }

    fun analysisHint(pct: Int) =
        "Tipp: Nur $pct % deiner Titel sind analysiert (BPM/Lautheit). Lass unter Menü → Audioanalyse alles durchlaufen, dann treffe ich die Stimmung deutlich besser."

    fun lyricsHint() =
        "Tipp: Bei Titeln mit eingebettetem Songtext erkenne ich die Stimmung genauer. Menü → Audioanalyse liest ihn beim Durchlauf mit ein (nur lokal, aus den Datei-Tags)."

    fun genreMissing(label: String?) =
        "Hm bro, zu ${label ?: "dem Genre"} finde ich in deiner Library nichts. 😅 Ich suche in Titel, Interpret, Album, Genre-Tag und Ordnernamen - vielleicht sind die Tags leer?"

    fun nothingFound() = "Dazu finde ich gerade nichts Passendes. 🤷 Vielleicht ist die Auswahl zu streng - probier es allgemeiner."
    fun emptyLibrary() = "Deine Bibliothek ist noch leer. Wähle erst unter Menü → Bibliothek einen Musikordner aus."
    fun cancelled() = pick("Alles klar, lass ich. 👍", "Okay, nichts passiert.")
    fun nothingToConfirm() = "Gerade ist nichts offen, was ich bestätigen müsste. 🙂"
    fun error() = "Hm, da ist bei mir was schiefgelaufen. 😬 Deine Musik läuft normal weiter - versuch's gleich nochmal."

    fun excluded(moods: Set<Mood>, removed: Int): String {
        val names = moods.joinToString(" und ") { it.label }
        val tail = if (removed > 0) " Ich hab $removed passende Titel aus deiner Queue genommen." else " In der Queue war nichts davon."
        return "Okay, heute kein \"$names\" mehr.$tail Gilt für alles, was ich dir in dieser Sitzung zusammenstelle."
    }
}
