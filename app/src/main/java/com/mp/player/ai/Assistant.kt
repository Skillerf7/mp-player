package com.mp.player.ai

import com.mp.player.ai.llm.LlmCoordinator
import com.mp.player.ai.llm.StructuredMusicIntent

import com.mp.player.Dsp
import com.mp.player.Eq
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import com.mp.player.Track

data class Reply(val text: String, val quickReplies: List<String> = emptyList())

/** Aktionen mit bleibender Wirkung: erst nach einem ausdruecklichen "Ja" ausfuehren. */
private sealed class Pending {
    data class SavePlaylist(val name: String, val trackIds: List<Long>) : Pending()
    object ResetBass : Pending()
    data class SetEq(val plan: EqPlan, val cmds: List<EqCommand>) : Pending()
    data class SaveEq(val name: String?) : Pending()
    object StartDuplicateScan : Pending()
    /** Offene Rueckfrage des Assistenten ("Musik oder quatschen?"): die naechste Antwort wird im Licht der Frage gelesen. */
    data class Ask(val kind: AskKind, val tries: Int = 0) : Pending()
    /** "Soll der raus?" nach einem Urteil ueber einen Titel (optional mit Ersatz). */
    data class RemoveTrack(val track: Track, val similar: Boolean) : Pending()
    /** Ein vorbereiteter Playlist-Entwurf wartet auf "Spiel sie" / "Ja" / "Mach". */
    data class StartDraft(val play: UserIntent.PlayMood) : Pending()
    /** Gespeicherter EQ-Stand einer frueheren Session war kraeftig (> 6 dB) -> erst nach "Ja" setzen. */
    data class ApplyEqSnap(val eq: EqSnap) : Pending()
    /** Vorschlag, den der Assistent selbst gemacht hat ("Soll ich was Ruhiges anmachen?") - "Ja" startet ihn. */
    data class Offer(val play: UserIntent.PlayMood) : Pending()
}

/**
 * Der Musik-Buddy. Ablauf: Text -> [BrainEngine] (Absicht) -> [Recommender]/[PlayerTools] (Aktion) -> [Persona] (Antwort).
 *
 * Sicherheitsregeln (im Code erzwungen, nicht nur im Prompt):
 *  - Es gibt kein Tool zum Loeschen von Dateien.
 *  - Dauerhafte Aenderungen (Playlist anlegen, Klang aendern, Scan starten) laufen ueber [Pending] + "Ja/Nein".
 *  - Jede Anfrage ist in try/catch: ein Fehler hier erzeugt nur eine Entschuldigung, die Wiedergabe laeuft weiter.
 *  - Kein Netzwerkzugriff in diesem Paket.
 */
class Assistant(
    private val tools: PlayerTools,
    private val brain: BrainEngine = IntentEngine,
    private val persona: Persona = Persona(),
    memoryStore: MemoryStore = InMemoryStore(),
    private val snapshots: SnapshotStore = InMemorySnapshotStore(),
    /** Kontext fuer Player-Aufrufe (Main-Thread in der App; Tests geben EmptyCoroutineContext). */
    private val uiContext: CoroutineContext = Dispatchers.Main,
    episodeStore: EpisodeStore = InMemoryEpisodeStore(),
    /** Optional: Android-Context zum Laden des trainierten Modells aus assets/ml. */
    appContext: android.content.Context? = null
) {
    private val memory = MemoryEngine(memoryStore)
    private val episodes = EpisodeEngine(episodeStore)
    private val state = ConversationState()
    private val shownAvoid = HashSet<String>()
    private var pending: Pending? = null
    private val llmCoordinator = LlmCoordinator(context = appContext?.applicationContext)
    private val sessionExclusions = LinkedHashSet<Mood>()
    private val sessionAvoid = HashSet<String>()
    private var hintShown = false
    private var lyricsHintShown = false

    // Rueckgaengig, Feedback, Lernen
    private val eqUndo = ArrayDeque<Dsp>()
    private val recentLearned = ArrayList<String>()
    private val sessionDisliked = HashSet<String>()

    // Gespraechskontext: worauf sich "30 min", "noch bisschen", "such du aus" beziehen
    private var lastMusic: UserIntent.PlayMood? = null
    private var lastEq: List<EqCommand>? = null
    private var lastWasEq = false
    private var eqHintShown = false

    // Arbeitsgedaechtnis fuer Referenzen ("den ersten", "der davor", "die ersten drei", "mach den weg")
    private var lastResults: List<Track> = emptyList()
    private var lastSuggested: Track? = null
    /** Vorbereiteter, noch nicht gestarteter Playlist-Entwurf (aenderbar: "der dritte ist scheisse", "laenger"). */
    private var draftActive: List<Track>? = null
    private var draftPlay: UserIntent.PlayMood? = null
    private val DRUCK_RE = Regex("""^(?:noch\s+)?(?:(?:bisschen|etwas|bissl)\s+)?(?:mehr\s+)?druck$""")

    private val suggestions = listOf("Fühl mich grad scheiße", "30 Minuten zum Abschalten", "Gib mir was Aggressives", "Mehr Bass", "Spiel was, das ich lange nicht gehört habe")
    private val yesNo = listOf("Ja", "Nein")

    suspend fun handle(text: String): Reply = withContext(uiContext) {
        try {
            route(text)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            pending = null
            Reply(persona.error())
        }
    }

    fun welcome(): Reply = Reply(persona.greeting(), suggestions)

    private suspend fun route(text: String): Reply {
        val parsed0 = try { brain.understand(text) } catch (e: Exception) { UserIntent.Unknown }
        // Optionales lokales LLM nur bei komplexen/unklaren Anfragen – nie bei "mach an" / klaren Befehlen
        val hints = try { tools.loadPlaylists().keys.toList() } catch (_: Throwable) { emptyList() }
        val parsedLlm = try {
            llmCoordinator.tryInterpret(text, parsed0, hints)?.toUserIntent()
        } catch (_: Throwable) { null }
        val merged = when {
            parsedLlm is UserIntent.SimilarToPlaylist -> parsedLlm
            parsedLlm is UserIntent.PlayMood && parsed0 is UserIntent.Unknown -> parsedLlm
            parsedLlm is UserIntent.PlayMood && parsed0 is UserIntent.PlayMood -> parsed0.copy(
                mood = parsed0.mood ?: parsedLlm.mood,
                genres = (parsed0.genres + parsedLlm.genres).distinct(),
                prepare = parsed0.prepare || parsedLlm.prepare,
                support = parsed0.support || parsedLlm.support
            )
            else -> parsed0
        }
        val parsed = if (merged is UserIntent.PlayMood && !merged.append && PlaylistPlanner.isPrepareRequest(text)) merged.copy(prepare = true) else merged
        // "Spiel sie" / "Mach an" / "ok" mit wartendem Entwurf startet ihn – NIE als Suche
        if (draftActive != null && pending !is Pending.Ask && !(pending is Pending.Offer) &&
            (PlaylistPlanner.isStartDraft(text) || parsed0 is UserIntent.Confirm && (parsed0 as UserIntent.Confirm).yes)
        ) {
            pending = null
            state.note(text)
            return startDraft()
        }
        if (draftActive != null && pending !is Pending.Ask && !(pending is Pending.Offer)) {
            handleDraftSpecialRefinement(text)?.let { state.note(text); return it }
        }
        val askOpen = pending as? Pending.Ask
        if (askOpen != null) {
            pending = null
            if (parsed !is UserIntent.Crisis) answerAsk(askOpen, text, parsed)?.let { state.note(text); return it }
        }
        val open = pending
        // "das ist geil" als Antwort auf eine offene Frage heisst Ja
        // \"nee, das nicht\" als Antwort auf eine offene Frage heisst Nein - es ist keine Titel-Referenz
        val intent = when {
            // "Mehr Druck" waehrend Musik laeuft = untenrum mehr (EQ), nicht eine neue aggressive Queue
            lastMusic != null && DRUCK_RE.matches(TextUtil.norm(text).trim().trimEnd('.', '!', '?', ' ')) &&
                (parsed is UserIntent.Unknown || (parsed is UserIntent.PlayMood && parsed.mood == Mood.AGGRESSIVE && parsed.genres.isEmpty())) ->
                UserIntent.EqChange(listOf(EqCommand(EqKind.BASS_UP, if (TextUtil.norm(text).contains("bisschen") || TextUtil.norm(text).contains("etwas")) EqSize.SMALL else EqSize.NORMAL)))
            open != null && parsed is UserIntent.Feedback && parsed.positive -> UserIntent.Confirm(true)
            open != null && parsed is UserIntent.TrackRef && parsed.ordinal == null && parsed.rel == null &&
                parsed.countFirst == null && parsed.actions == setOf(RefAction.DISLIKE) -> UserIntent.Confirm(false)
            else -> parsed
        }
        state.note(text)

        if (intent is UserIntent.Confirm) {
            pending = null
            if (open == null) {
                // "ok"/"mach an" nach Entwurf ohne explizites Pending.StartDraft: Entwurf starten
                if (intent.yes && draftActive != null) {
                    return startDraft()
                }
                // "nee doch nicht" direkt nach einer Klangaenderung nimmt sie zurueck
                if (!intent.yes && isUndoPhrase(text) && eqUndo.isNotEmpty()) return restore(UserIntent.Restore(RestoreWhen.PREVIOUS))
                // "ja, mach" nach einem Gespraech ohne konkreten Vorschlag: Lage aus dem Gespraechszustand nehmen
                if (intent.yes && state.venting && state.isFresh() && state.musicMood() != null) {
                    return playMood(UserIntent.PlayMood(mood = null))
                }
                // "mach an" ohne Entwurf und ohne Pending: nachfragen, NICHT suchen
                if (intent.yes && PlaylistPlanner.isStartDraft(text)) {
                    return Reply("Was soll ich starten? Sag mir eine Playlist, Mood oder einen Titel.")
                }
                return Reply(persona.ack()) // "ok", "cool", "nee" ohne offene Frage: einfach quittieren
            }
            return if (intent.yes) execute(open) else Reply(persona.cancelled())
        }
        // Ein eigener Vorschlag bleibt offen, solange nur geredet, ausgeschlossen oder "bisschen Bass" gewuenscht wird
        topicOf(intent)?.let { state.pushTopic(it) }
        val keepDraft = open is Pending.StartDraft && (parsed is UserIntent.TrackRef || parsed is UserIntent.Refine || parsed is UserIntent.EqChange || parsed is UserIntent.Chat)
        val keepOffer = keepDraft || open is Pending.Offer && (
            intent is UserIntent.Exclude || intent is UserIntent.EqChange || intent is UserIntent.Remember ||
                intent is UserIntent.Chat || intent is UserIntent.WhatDoYouKnow
            )
        pending = if (keepOffer) open else null // jeder andere Befehl verwirft eine offene Rueckfrage

        return when (intent) {
            UserIntent.Crisis -> Reply(persona.crisis())
            UserIntent.Greeting -> Reply(persona.greeting(), suggestions)
            UserIntent.Thanks -> Reply(persona.thanks())
            UserIntent.Help -> Reply(persona.help(), suggestions)
            UserIntent.Pause -> { tools.pause(); Reply("Pause. ⏸️") }
            UserIntent.Resume -> { tools.play(); Reply("Weiter geht's. ▶️") }
            UserIntent.Next -> { tools.skipNext(); Reply("Nächster. ⏭️") }
            UserIntent.ClearQueue -> { tools.clearQueue(); Reply("Queue ist leer. 🧹") }
            UserIntent.WhatsPlaying -> whatsPlaying()
            UserIntent.ShowStats -> showStats()
            UserIntent.ShowHistory -> showHistory()
            UserIntent.CheckDuplicates -> checkDuplicates()
            UserIntent.CheckMissing -> checkMissing()
            is UserIntent.StartSleep -> {
                tools.startSleepTimer(intent.minutes)
                Reply("Sleep-Timer läuft: ${intent.minutes} Minuten, die Musik blendet am Ende aus. ⏲️😴")
            }
            is UserIntent.ExplainSound -> explainSound(intent.topic)
            is UserIntent.AnalysisQuery -> answerAnalysisQuery(intent)
            is UserIntent.AskTrackAnalysis -> askTrackAnalysis(intent.fields)
            UserIntent.SimilarToCurrent -> if (draftActive != null) expandDraftSimilar() else similarToCurrent()
            is UserIntent.SimilarToPlaylist -> similarToPlaylist(intent.name)
            is UserIntent.SavePlaylist -> askSavePlaylist(intent.name)
            is UserIntent.PlayFavorites -> playFavorites(intent)
            is UserIntent.PlayUnheard -> playUnheard(intent)
            is UserIntent.SearchPlay -> searchPlay(intent.query)
            is UserIntent.SearchArtist -> searchArtist(intent.name)
            is UserIntent.Exclude -> applyExclusion(intent.moods).let { r ->
                if (pending is Pending.Offer) Reply(persona.offerStillOpen(sessionExclusions.toSet()), yesNo) else r
            }
            is UserIntent.PlayMood -> if (draftActive != null && isDraftRefinement(text)) refineDraft(intent) else if (shouldHold(intent, text)) offerGoal(intent) else playMood(intent)
            is UserIntent.EqChange -> if (pending is Pending.Offer) {
                state.wishEq += intent.cmds
                Reply(persona.eqNoted(), yesNo)
            } else changeEq(intent.cmds)
            is UserIntent.Volume -> volume(intent)
            is UserIntent.TrackRef -> trackRef(intent)
            is UserIntent.Refine -> refine(intent.kind)
            is UserIntent.MultiPhase -> multiPhase(intent)
            is UserIntent.Emotion -> emotion(intent)
            is UserIntent.Chat -> chat(intent.kind)
            is UserIntent.Remember -> remember(intent.text)
            is UserIntent.Forget -> forget(intent.topic)
            UserIntent.WhatDoYouKnow -> Reply(memoryAndPatterns())
            is UserIntent.ResumeTopic -> resumeTopic(intent.topic)
            is UserIntent.AskPattern -> Reply(episodes.describe(intent.daypart))
            UserIntent.Recommend -> recommend()
            is UserIntent.EqPreset -> applyPreset(intent.name)
            is UserIntent.EqSave -> askSaveEq(intent.name)
            is UserIntent.Minutes -> playMood(
                lastMusic?.copy(minutes = intent.minutes, append = false, support = false, session = false, eq = emptyList())
                    ?: UserIntent.PlayMood(mood = null, minutes = intent.minutes)
            )
            UserIntent.More -> more()
            UserIntent.Less -> less()
            UserIntent.Surprise -> surprise()
            is UserIntent.Feedback -> feedback(intent.positive)
            is UserIntent.Restore -> restore(intent)
            UserIntent.RecentMemory -> Reply(persona.recentLearned(recentLearned.toList()))
            UserIntent.Unknown ->
                if (isUndoPhrase(text) && eqUndo.isNotEmpty()) restore(UserIntent.Restore(RestoreWhen.PREVIOUS)) else fallback(text)
            else -> Reply(persona.unknown(), suggestions)
        }
    }

    // ------------------------------------------------------------------ Bestaetigte Aktionen

    private suspend fun execute(p: Pending): Reply = when (p) {
        is Pending.SavePlaylist -> {
            val r = tools.createPlaylist(p.name, p.trackIds)
            if (r.name != null) Reply("Fertig: Playlist \"${r.name}\" mit ${r.added} Titeln angelegt. ✅")
            else Reply("Das hat leider nicht geklappt - die Playlist wurde nicht angelegt.")
        }
        Pending.ResetBass ->
            if (tools.resetBass()) Reply("Erledigt: Bass-Regler, Bass-Boost und angehobene Tiefen-Bänder stehen auf 0. 👌")
            else Reply("Das konnte ich nicht ändern - du kannst es unter Menü → Equalizer selbst zurücksetzen.")
        is Pending.SetEq -> {
            applyPlan(p.plan)
            lastEq = p.cmds; lastWasEq = true
            Reply(persona.eqDone(p.plan.summary) + eqHint())
        }
        is Pending.SaveEq ->
            if (tools.saveEq(p.name)) Reply(persona.eqSaved(p.name)) else Reply(persona.eqSaveFailed())
        is Pending.Offer -> playMood(p.play.copy(eq = p.play.eq + state.takeWishEq()))
        is Pending.ApplyEqSnap -> {
            rememberEq()
            tools.setEqState(p.eq.applyTo(tools.getEqState()))
            lastWasEq = false
            Reply("Okay, der frühere EQ-Stand ist wieder drin. 🎚️ " + persona.eqHint())
        }
        is Pending.Ask -> Reply(persona.unknown())
        is Pending.RemoveTrack -> removeTrack(p.track, p.similar)
        is Pending.StartDraft -> startDraft()
        Pending.StartDuplicateScan -> {
            tools.startDuplicateScan()
            Reply("Duplikat-Scan läuft im Hintergrund. Er liest die Dateien nur, es wird nichts gelöscht. Frag mich später nochmal nach Duplikaten. 🔍")
        }
    }

    // ------------------------------------------------------------------ Musik zusammenstellen

    private suspend fun handleDraftSpecialRefinement(text: String): Reply? {
        val t = TextUtil.norm(text).trim()
        val delta = when {
            t.contains("mehr bpm") || t.contains("schneller") -> 1
            t.contains("weniger bpm") || t.contains("langsamer") -> -1
            else -> 0
        }
        if (delta != 0) return refineDraftTempo(delta)
        val artist = Regex("(?:mehr|mehr von)\\s+(.+?)(?:\\s+tracks?|\\s+songs?)?$").find(t)?.groupValues?.getOrNull(1)?.trim()
        if (t.startsWith("mehr von ") && !artist.isNullOrBlank()) return refineDraftArtist(artist)
        return null
    }

    private suspend fun refineDraftTempo(direction: Int): Reply {
        val draft = draftActive.orEmpty()
        val infos = tools.loadTrackInfos()
        val byUri = infos.associateBy { it.track.uri }
        val base = draft.mapNotNull { byUri[it.uri] }
        val known = base.mapNotNull { it.analysis?.bpm?.takeIf { b -> b > 0f } }
        if (known.isEmpty()) return Reply("Für diesen Entwurf kenne ich noch keine brauchbaren BPM-Werte – ich lasse ihn unverändert.")
        val target = (known.average().toFloat() + direction * 18f).coerceIn(40f, 220f)
        val candidates = infos.filter { it.track.uri !in draft.map(Track::uri).toSet() }
            .filter { it.analysis?.bpm?.let { b -> b > 0f } == true }
            .sortedBy { kotlin.math.abs((it.analysis?.bpm ?: target) - target) }
        val keep = draft.size * 2 / 5
        val preserved = base.sortedBy { kotlin.math.abs((it.analysis?.bpm ?: target) - target) }.take(keep).map { it.track }
        val result = (preserved + candidates.take(draft.size - preserved.size).map { it.track }).distinctBy { it.uri }.take(draft.size)
        if (result.isEmpty()) return Reply("Ich konnte den BPM-Wunsch nicht sinnvoll anwenden – der Entwurf bleibt unverändert.")
        draftActive = result; lastResults = result
        return Reply(if (direction > 0) "Ich habe den Entwurf spürbar schneller gemacht. Er bleibt ein Entwurf. 🎚️" else "Ich habe den Entwurf etwas langsamer gemacht. Er bleibt ein Entwurf. 🎚️", listOf("Mach an", "Noch mehr BPM"))
    }

    private suspend fun refineDraftArtist(artist: String): Reply {
        val draft = draftActive.orEmpty(); val infos = tools.loadTrackInfos(); val wanted = TextUtil.norm(artist)
        val candidates = infos.filter { it.track.uri !in draft.map(Track::uri).toSet() && TextUtil.norm(it.track.artist).contains(wanted) }
        if (candidates.isEmpty()) return Reply("Ich finde in deiner Library gerade keine weiteren Tracks von \"$artist\". Der Entwurf bleibt unverändert.")
        val result = (draft.dropLast(minOf(candidates.size, maxOf(1, draft.size / 3))) + candidates.map { it.track }).distinctBy { it.uri }.take(draft.size)
        draftActive = result; lastResults = result
        return Reply("Ich habe mehr von $artist in den bestehenden Entwurf eingebaut. Er startet noch nicht. 🎶", listOf("Mach an", "Noch mehr"))
    }

    private fun isDraftRefinement(text: String): Boolean {
        val t = TextUtil.norm(text).trim()
        return t.contains("mehr rap") || t.contains("mehr hiphop") || t.contains("mehr bpm") ||
            t.contains("schneller") || t.contains("langsamer") || t.contains("trauriger") ||
            t.contains("chilliger") || t.contains("ruhiger") || t.contains("aggressiver") ||
            t.contains("weniger aggressiv") || t.contains("weniger traurig") || t.contains("mehr von ")
    }

    private suspend fun refineDraft(i: UserIntent.PlayMood): Reply {
        val current = draftActive.orEmpty()
        if (current.isEmpty()) return Reply(persona.nothingToRefine())
        val infos = tools.loadTrackInfos()
        val profile = GenreProfile.build(infos, tools.loadPlaylists(), null, tools.loadMlGenres())
        val all = infos.associateBy { it.track.uri }
        val base = current.mapNotNull { all[it.uri] }
        val requestedGenres = i.genres
        val mood = i.mood
        val candidates = infos.asSequence().filter { it.track.uri !in current.map(Track::uri).toSet() }
            .map { info ->
                val genre = if (requestedGenres.isNotEmpty()) profile.matches(info, requestedGenres) else 0f
                val moodScore = mood?.let { Recommender.moodScore(info, it) } ?: 0.5f
                info to (if (requestedGenres.isNotEmpty()) 0.68f * genre + 0.32f * moodScore else moodScore)
            }
            .filter { (info, score) ->
                requestedGenres.isEmpty() || profile.matches(info, requestedGenres) >= 0.68f
            }
            .sortedByDescending { it.second }.map { it.first }.toList()
        val keepCount = maxOf(1, current.size * 2 / 5)
        val preserved = base.sortedByDescending { info ->
            val g = if (requestedGenres.isEmpty()) 0.5f else profile.matches(info, requestedGenres)
            val m = mood?.let { Recommender.moodScore(info, it) } ?: 0.5f
            g * 0.7f + m * 0.3f
        }.take(keepCount).map { it.track }
        val replacement = candidates.take((current.size - preserved.size).coerceAtLeast(0)).map { it.track }
        val result = (preserved + replacement).distinctBy { it.uri }.take(current.size)
        if (result.isEmpty()) return Reply("Ich konnte den Entwurf nicht sinnvoll verfeinern – ich lasse ihn unverändert.")
        draftActive = result
        lastResults = result
        val label = i.genreLabel ?: i.mood?.label ?: if (requestedGenres.isNotEmpty()) requestedGenres.joinToString("/") else "deinen Wunsch"
        return Reply("Okay – ich habe den bestehenden Entwurf auf $label nachgeschärft. Er bleibt ein Entwurf und startet noch nicht.\n\n" + result.take(5).joinToString("\n") { "• ${it.title} – ${it.artist}" }, listOf("Mach an", "Noch mehr"))
    }

    
    private suspend fun loadPersonalSignals(infos: List<TrackInfo>): PersonalSignals {
        return try {
            val playlists = tools.loadPlaylists()
            val plays = infos.associate { it.track.uri to it.playCount }.filterValues { it > 0 }
            val skips = infos.associate { it.track.uri to it.skipCount }.filterValues { it > 0 }
            val byUri = infos.associate { it.track.uri to it.track }
            val (acc, rej) = try { tools.loadRecFeedback() } catch (_: Throwable) { emptyMap<String, Int>() to emptyMap() }
            PersonalLearning.build(
                playCounts = plays,
                skipCounts = skips,
                genreOverrides = try { tools.loadGenreOverrides() } catch (_: Throwable) { emptyMap() },
                bpmOverrides = try { tools.loadBpmOverrides() } catch (_: Throwable) { emptyMap() },
                acceptedRecs = acc,
                rejectedRecs = rej,
                playlistMemberships = playlists,
                trackByUri = byUri
            )
        } catch (_: Throwable) {
            PersonalSignals()
        }
    }

    private suspend fun playMood(i0: UserIntent.PlayMood): Reply {
        val i1 = enrich(i0)
        // Widerspruch "traurig, aber nicht zu traurig": beides gleichzeitig modellieren (abgeschwaecht), nicht einfach ausschliessen
        val clash = i1.mood?.takeIf { it in i1.exclude }
        val soft = clash?.let { PlaylistPlanner.soften(it) }
        val i = if (clash != null && soft != null) i1.copy(mood = soft, exclude = i1.exclude - clash, support = true) else i1
        val softenedFrom = if (clash != null && soft != null) clash else null
        val infos = tools.loadTrackInfos()
        if (infos.isEmpty()) return Reply(persona.emptyLibrary())
        val personal = loadPersonalSignals(infos)
        val genreProfile = GenreProfile.build(infos, tools.loadPlaylists(), personal, tools.loadMlGenres())

        // Kombinierte Befehle ("... mit bisschen mehr Bass"): EQ unabhaengig von der Queue anwenden
        val eqText = if (i.eq.isNotEmpty()) eqPart(i.eq) else null
        fun withEq(r: Reply) = if (eqText == null) r else r.copy(
            text = r.text + "\n\n" + eqText,
            quickReplies = if (pending is Pending.SetEq) yesNo else r.quickReplies
        )

        if (i.exclude.isNotEmpty()) sessionExclusions += i.exclude
        val minutes = i.minutes ?: if (i.support) 30 else if (i.session) 60 else null
        // Aktuelle Queue beruecksichtigen: beim Anhaengen nichts doppelt, beim Ersetzen nicht dieselben Titel nochmal bevorzugen
        val queueUris = tools.getQueue().map { it.uri }.toSet()
        var req = QueueRequest(
            mood = i.mood,
            exclude = sessionExclusions.toSet(),
            genres = i.genres,
            minutes = minutes,
            avoidUris = (if (i.noRepeat) sessionAvoid.toSet() else emptySet()) + (if (i.append) queueUris else emptySet()) +
                (if (i.append && i.prepare) draftActive.orEmpty().map { it.uri }.toSet() else emptySet()) + sessionDisliked,
            softAvoid = if (i.append) emptySet() else queueUris,
            genreProfile = genreProfile,
            personal = personal
        )
        // Gedaechtnis als HINWEIS: die aktuelle Anfrage gewinnt immer (ausdrueckliches Genre ueberstimmt "kein X")
        val hints = try { memory.prune(); memory.hints() } catch (e: Exception) { MemoryHints(emptyList(), emptyList()) }
        val askedTerms = i.genres.map { it.lowercase() }
        val avoidItems = hints.avoid.filter { d ->
            val terms = IntentEngine.termsFor(d.key).map { it.lowercase() }
            terms.none { t -> askedTerms.any { a -> a.contains(t) || t.contains(a) } }
        }
        val moodAvoid = avoidItems.mapNotNull { IntentEngine.moodFor(it.key) }.filter { it != i.mood }.toSet()
        val avoidTerms = avoidItems.filter { IntentEngine.moodFor(it.key) == null }.flatMap { IntentEngine.termsFor(it.key) }
        val preferTerms = if (i.genres.isEmpty() && !i.support) hints.prefer.filter { it.key.length >= 3 }.flatMap { IntentEngine.termsFor(it.key) } else emptyList()
        req = req.copy(avoidTerms = avoidTerms, preferTerms = preferTerms, exclude = req.exclude + moodAvoid)

        var result = withContext(Dispatchers.Default) { Recommender.build(infos, req) }
        if (result.tracks.isEmpty() && !result.genreNotFound && i.noRepeat && sessionAvoid.isNotEmpty()) {
            // Alles schon gespielt -> neue Runde
            sessionAvoid.clear()
            req = req.copy(avoidUris = if (i.append) queueUris else emptySet())
            result = withContext(Dispatchers.Default) { Recommender.build(infos, req) }
        }
        if (result.genreNotFound) return withEq(Reply(persona.genreMissing(i.genreLabel)))
        if (result.tracks.isEmpty()) return withEq(Reply(persona.nothingFound()))

        // Dramaturgie: Energiekurve nur mit echten Analysedaten, sonst bleibt die Reihenfolge des Recommenders
        val arc = PlaylistPlanner.arcFor(i.mood, i.support)
        var tracks = result.tracks
        var arcApplied = false
        if (!i.append || i.phased) {
            val plan = PlaylistPlanner.order(tracks, infos.associateBy { it.track.uri }, arc)
            tracks = plan.tracks; arcApplied = plan.applied
        }
        val notes = buildString {
            if (softenedFrom != null) append("\n").append(persona.softened(softenedFrom))
            if (arcApplied) append("\n").append(persona.arcNote(arc))
        }
        if (i.prepare) {
            // Entwurf: zeigen, NICHT starten (Spiel sie / Ja / Mach startet)
            val draft = (if (i.append) draftActive.orEmpty() else emptyList()) + tracks
            draftActive = draft
            draftPlay = i.copy(prepare = false, append = false, eq = emptyList())
            lastResults = draft; lastSuggested = null
            pending = Pending.StartDraft(draftPlay!!)
            lastMusic = draftPlay
            return withEq(Reply(persona.draftReady(draft.size, draft.sumOf { it.durationMs }, draft.take(5).map { "${it.title} – ${it.artist}" }, i.append) + notes, listOf("Spiel sie", "Länger", "Kürzer")))
        }
        val ids = tracks.map { it.id }
        if (i.append) tools.addToQueue(ids) else tools.replaceQueueAndPlay(ids, i.shuffle)
        draftActive = null; draftPlay = null
        if (i.noRepeat) sessionAvoid += tracks.map { it.uri }
        lastResults = tracks; lastSuggested = null

        // Kontext merken: darauf beziehen sich "30 min", "mehr", "such du aus"
        lastMusic = i.copy(eq = emptyList())
        if (i.eq.isEmpty()) lastWasEq = false
        // Verhalten beobachten: nach einem Ausheulen bewusst gewaehltes Genre -> langsam wachsende Verknuepfung (kein sofortiges Urteil)
        val learnFrom = if (state.venting && state.isFresh()) state.emotion else null
        if (learnFrom != null && i.genreLabel != null) memory.learnPattern(learnFrom, i.genreLabel)
        if (!i.append) recordEpisode(i, minutes)
        state.onMusicStarted()
        val newAvoid = avoidItems.filter { it.label !in shownAvoid && IntentEngine.moodFor(it.key) == null }
        shownAvoid += newAvoid.map { it.label }

        val out = withEq(Reply(buildString {
            append(persona.moodIntro(i.mood, i.genreLabel, i.support))
            append("\n\n")
            append(persona.summary(result.tracks.size, result.totalMs, i.append))
            coverageHint(result.analysisCoverage, i.mood)?.let { append("\n\n").append(it) }
            lyricsHint(result.lyricsCoverage, i.mood)?.let { append("\n\n").append(it) }
            if (newAvoid.isNotEmpty()) append("\n").append(persona.avoided(newAvoid.map { it.label }))
            append(notes)
        }))
        if (!i.append) recordSnapshot(i, minutes)
        return out
    }

    // ------------------------------------------------------------------ Gespraech & Gedaechtnis

    private fun hasPlayVerb(text: String): Boolean {
        val t = TextUtil.norm(text)
        return listOf("spiel", "mach", "gib mir", "starte", "play", "leg ", "musik", "queue", "playlist", "session", "minuten", "stunde").any { t.contains(it) }
    }

    /** Mitten im Ausheulen ("Ich will einfach abschalten") ist das ein Ziel, noch kein Startsignal. */
    private fun shouldHold(i: UserIntent.PlayMood, text: String): Boolean =
        state.venting && state.isFresh() && !hasPlayVerb(text) && i.eq.isEmpty() && i.genres.isEmpty() && i.mood != null

    private fun offerGoal(i: UserIntent.PlayMood): Reply {
        state.onGoal(i.mood)
        pending = Pending.Offer(i.copy(support = true))
        return Reply(persona.goalOffer(i.mood, sessionExclusions.toSet()), yesNo)
    }

    /** Kurzes "mach Musik" nach einem Gespraech: Gefuehl/Ziel von eben benutzen, auch wenn das Wort Musik nie fiel. */
    private fun enrich(i: UserIntent.PlayMood): UserIntent.PlayMood {
        if (i.mood != null || i.genres.isNotEmpty() || !state.isFresh()) return i
        if (!state.venting && state.goal == null) return i
        val m = state.musicMood() ?: return i
        return i.copy(mood = m, support = i.support || (state.venting && m != Mood.ANGRY))
    }

    private fun emotion(e: UserIntent.Emotion): Reply {
        if (e.feel == Mood.HAPPY) {
            // Gute Laune: freuen und anbieten, nichts starten; kein Ausheul-Zustand
            state.onGoal(Mood.HAPPY)
            pending = Pending.Offer(UserIntent.PlayMood(mood = Mood.HAPPY))
            return Reply(persona.happyOffer(), yesNo)
        }
        state.onEmotion(e.feel, e.topic, e.strong, e.intensity)
        state.markShared()
        val mood = when (e.feel) { Mood.SAD, Mood.LONELY, Mood.ANGRY -> e.feel; else -> Mood.CALM }
        // Nur lasch/\"so lala\": locker nachhaken und etwas Entspanntes anbieten, kein Drama
        if (e.intensity < 0.5f && state.ventCount <= 1 && (e.topic ?: state.topic) == null) {
            pending = Pending.Offer(UserIntent.PlayMood(mood = Mood.CALM, support = true))
            return Reply(persona.mildAsk(), listOf("Mach was Entspanntes", "Erzähl ich dir gleich"))
        }
        // Erste Aeusserung ohne Thema: erst nachfragen. Sonst Angebot machen - aber nichts starten.
        if (state.ventCount <= 1 && state.topic == null) {
            pending = Pending.Ask(AskKind.MUSIC_OR_TALK)
            return Reply(persona.emotionAsk(), askOptions(AskKind.MUSIC_OR_TALK))
        }
        pending = Pending.Offer(UserIntent.PlayMood(mood = mood, support = mood != Mood.ANGRY))
        return Reply(persona.emotionOffer(e.topic ?: state.topic, e.strong || e.intensity >= 0.8f), listOf("Mach was Ruhiges", "Lass erstmal quatschen"))
    }

    /** Lautstaerke: relative und absolute Werte; gemeldet wird immer der Wert, den das Geraet danach wirklich hat. */
    private fun volume(v: UserIntent.Volume): Reply {
        val cur = tools.getVolume() ?: return Reply("Die Lautstärke kann ich gerade nicht lesen - nimm bitte die Lautstärketasten. 🔈")
        val delta = v.delta
        val target = (v.absolute ?: (cur + (delta ?: 0))).coerceIn(0, 100)
        if (target == cur) {
            return Reply(if (delta != null && delta > 0) "Lauter geht nicht, du bist schon am Anschlag. 🔊" else if (delta != null && delta < 0) "Leiser geht nicht, es ist schon ganz leise. 🔈" else "Die Lautstärke steht schon auf $cur %.")
        }
        if (!tools.setVolume(target)) return Reply("Das hat nicht geklappt - nimm bitte die Lautstärketasten. 🔈")
        var now = tools.getVolume() ?: target
        // Geraetestufen sind grob: bei "etwas lauter" ggf. eine Stufe weiter, damit sich ueberhaupt etwas aendert
        if (now == cur && delta != null && tools.setVolume((target + if (delta > 0) 8 else -8).coerceIn(0, 100))) now = tools.getVolume() ?: now
        return Reply(if (now == cur) "Die Lautstärke hat sich nicht geändert - nimm bitte die Lautstärketasten. 🔈" else "Lautstärke jetzt bei $now %. ${if (now > cur) "🔊" else "🔉"}")
    }

    private suspend fun chat(kind: ChatKind): Reply = when (kind) {
        ChatKind.AFFECTION -> Reply(persona.affection())
        ChatKind.HOW_ARE_YOU -> Reply(persona.howAreYou())
        ChatKind.WHO_ARE_YOU -> Reply(persona.whoAreYou())
        ChatKind.TALK -> {
            state.setMode(if (state.venting) ConversationMode.SUPPORT else ConversationMode.CHAT)
            if (state.hasSharedContent || (state.venting && state.isFresh())) {
                Reply(persona.talkAfterShare(state.topic), listOf("Mach was Entspanntes", "Erzähl ich dir gleich"))
            } else {
                Reply(persona.talk(), listOf("Mach was Entspanntes", "Erzähl ich dir gleich"))
            }
        }
        ChatKind.CONTINUE -> {
            state.setMode(if (state.venting) ConversationMode.SUPPORT else ConversationMode.CHAT)
            state.markShared()
            Reply(persona.chatContinue(state.topic), listOf("Mach was Entspanntes", "Erzähl ich dir gleich"))
        }
        ChatKind.GOODBYE -> Reply(persona.goodbye())
        ChatKind.LAUGH -> Reply(persona.laugh())
        ChatKind.REALLY -> Reply(persona.really())
        ChatKind.NO_PROBLEM -> Reply(persona.noProblem())
        ChatKind.HM -> Reply(if (state.venting && state.isFresh()) persona.hmListening() else persona.hm())
        ChatKind.AND -> {
            if (draftActive != null) resumeTopic(Topic.PLAYLIST)
            else {
                pending = Pending.Ask(AskKind.MUSIC_OR_TALK)
                Reply(persona.andPrompt(), askOptions(AskKind.MUSIC_OR_TALK))
            }
        }
        ChatKind.WHY -> whyLast()
        ChatKind.UNDECIDED -> { pending = Pending.Ask(AskKind.MUSIC_OR_TALK); Reply(persona.undecided(), askOptions(AskKind.MUSIC_OR_TALK)) }
        ChatKind.DISTRACT -> { pending = Pending.Ask(AskKind.MUSIC_OR_TALK); Reply(persona.distract(), askOptions(AskKind.MUSIC_OR_TALK)) }
        ChatKind.BORED -> { pending = Pending.Ask(AskKind.BORED); Reply(persona.bored(), askOptions(AskKind.BORED)) }
        ChatKind.TELL -> Reply(persona.funFact())
    }

    /** "Warum?" - nur mit dem, was wirklich passiert ist. */
    private suspend fun whyLast(): Reply {
        val cur = tools.getCurrentTrack()
        if (lastWasEq) return Reply(persona.whyEq())
        if (lastMusic != null && cur != null && tools.getQueue().isNotEmpty()) return explainTrack(cur)
        if (state.venting && state.isFresh()) return Reply(persona.whyAsked())
        return Reply(persona.whyNothing())
    }

    private fun remember(text: String): Reply {
        val cands = MemoryExtractor.extract(text)
        if (cands.isEmpty()) return Reply(persona.nothingToRemember())
        val saved = cands.mapNotNull { memory.remember(it) }
        if (saved.isEmpty()) return Reply(persona.memoryFailed()) // ehrlich: nicht behaupten, es sei gespeichert
        for (s in saved) recentLearned += (if (s.kind == MemKind.DISLIKE) "kein " else "") + s.label
        while (recentLearned.size > 10) recentLearned.removeAt(0)
        return Reply(persona.remembered(saved))
    }

    private fun forget(topic: String?): Reply {
        val n = if (topic == null) memory.forgetAll() else memory.forget(topic)
        return Reply(persona.forgot(n, topic))
    }

    private fun recommend(): Reply {
        val mood = if (state.isFresh()) state.musicMood() else null
        // Gelerntes Muster ("nach Stress hoerst du oft Hardtekk") nur als Angebot, nie als Zwang
        val emo = if (state.isFresh()) state.emotion else null
        val pat = if (emo != null) memory.patternFor(emo) else null
        if (emo != null && pat != null) {
            pending = Pending.Offer(UserIntent.PlayMood(mood = null, genreLabel = pat.label, genres = IntentEngine.termsFor(pat.key.substringAfter('>'))))
            return Reply(persona.patternOffer(emo.name, pat.label), yesNo)
        }
        // Tageszeit-Muster nur aus echten Daten (>= 3 gleiche Starts im selben Tagesabschnitt), nur als Angebot
        if (mood == null) {
            val tp = episodes.topFor(Daypart.of(System.currentTimeMillis()))
            if (tp != null) {
                val play = if (tp.genre != null) UserIntent.PlayMood(mood = null, genreLabel = tp.genre, genres = IntentEngine.termsFor(TextUtil.norm(tp.genre)))
                else UserIntent.PlayMood(mood = tp.mood)
                pending = Pending.Offer(play)
                return Reply(persona.patternRecommend(tp), yesNo)
            }
        }
        val likes = memory.active(MemKind.LIKE, 0.6f)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val night = hour >= 23 || hour < 6
        val m = mood ?: if (night && likes.isEmpty()) Mood.CALM else null
        val top = likes.firstOrNull()
        val play = if (m == null && top != null) {
            UserIntent.PlayMood(mood = null, genreLabel = top.label, genres = IntentEngine.termsFor(top.key))
        } else UserIntent.PlayMood(mood = m, support = state.venting)
        pending = Pending.Offer(play)
        return Reply(persona.recommend(m, likes.map { it.label }, night), yesNo)
    }

    // ------------------------------------------------------------------ Rueckgaengig, Snapshots, Feedback

    private fun pushUndo(d: Dsp) {
        eqUndo.addLast(d)
        while (eqUndo.size > 10) eqUndo.removeFirst()
    }

    private fun rememberEq() {
        try { pushUndo(tools.getEqState()) } catch (e: Exception) { }
    }

    private fun isUndoPhrase(text: String): Boolean {
        val t = TextUtil.norm(text).trim().trimEnd('.', '!', '?', ' ')
        return t in setOf("doch nicht", "nee doch nicht", "nein doch nicht", "ne doch nicht", "lieber doch nicht", "doch lieber nicht", "nee lieber nicht", "hm doch nicht")
    }

    /** Merkt sich Stimmung, Genre, Dauer und EQ der gerade gestartete Session (fuer \"wie gestern\"). Fehler bleiben ohne Folgen. */
    private fun recordSnapshot(i: UserIntent.PlayMood, minutes: Int?) {
        try {
            val eq = EqSnap.of(tools.getEqState()).takeIf { !it.isNeutral() }
            snapshots.add(SessionSnapshot(System.currentTimeMillis(), i.mood, i.genreLabel, i.genres, minutes, eq))
        } catch (e: Exception) { }
    }

    private suspend fun restore(i: UserIntent.Restore): Reply {
        if (i.which == RestoreWhen.PREVIOUS) {
            val prev = eqUndo.removeLastOrNull() ?: return Reply(persona.nothingToRestore())
            tools.setEqState(prev)
            lastWasEq = false
            return Reply(persona.undone())
        }
        val all = try { snapshots.all() } catch (e: Exception) { emptyList() }
        return when (val c = SnapshotResolver.resolve(all, i.which, System.currentTimeMillis())) {
            SnapshotChoice.None -> Reply(persona.noSnapshot(i.which))
            is SnapshotChoice.Ask -> Reply(persona.askSnapshot(i.which, c.options.map { it.label() }), c.options.map { it.label() })
            is SnapshotChoice.One ->
                if (i.tellOnly) Reply(persona.tellSnapshot(i.which, c.snap)) else applySnapshot(i.which, c.snap)
        }
    }

    private suspend fun applySnapshot(w: RestoreWhen, s: SessionSnapshot): Reply {
        val music = playMood(UserIntent.PlayMood(mood = s.mood, genreLabel = s.genreLabel, genres = s.genres, minutes = s.minutes))
        val head = persona.restoredFrom(w, s) + "\n\n" + music.text
        val eq = s.eq
        if (eq == null || eq.isNeutral()) return music.copy(text = head)
        if (eq.maxAbs() > 6f) {
            pending = Pending.ApplyEqSnap(eq)
            return Reply(head + "\n\n" + persona.eqSnapAsk("bis ${eq.maxAbs().toInt()} dB"), yesNo)
        }
        rememberEq()
        tools.setEqState(eq.applyTo(tools.getEqState()))
        lastWasEq = false
        return music.copy(text = head + "\n\nDer EQ-Stand von damals ist auch wieder drin. 🎚️")
    }

    /** \"Das ist geil\" / \"nicht meins\" zum laufenden Titel: leise merken, nicht gleich ein festes Urteil faellen. */
    private fun feedback(positive: Boolean): Reply {
        val t = tools.getCurrentTrack() ?: return Reply(persona.feedbackNothingPlaying())
        val artist = t.artist.trim().takeIf { it.isNotBlank() && !it.startsWith("<") && !it.equals("unknown", ignoreCase = true) }
        if (positive) {
            val name = artist ?: t.title
            val key = TextUtil.norm(name).trim()
            val saved = if (key.length >= 3 && IntentEngine.moodFor(key) == null)
                memory.remember(MemCandidate(MemKind.LIKE, name, key, MemScope.LONG, 0.55f, 0.4f, false, null)) else null
            if (saved != null) recentLearned += "mag ich: ${saved.label}"
            return Reply(persona.likedTrack(if (saved != null) name else null))
        }
        val before = recentLearned.size
        learnDislike(t)
        val saved = recentLearned.size > before
        tools.skipNext()
        return Reply(persona.dislikedTrack(saved))
    }

    // ------------------------------------------------------------------ Titel-Referenzen

    private fun learnDislike(t: Track) {
        sessionDisliked += t.uri
        val key = TextUtil.norm(t.title).trim()
        val saved = if (key.length >= 3 && IntentEngine.moodFor(key) == null)
            memory.remember(MemCandidate(MemKind.DISLIKE, t.title, key, MemScope.LONG, 0.6f, 0.5f, false, null)) else null
        if (saved != null) recentLearned += "eher nicht: ${saved.label}"
    }

    private suspend fun trackRef(ref: UserIntent.TrackRef): Reply {
        val queue = tools.getQueue()
        val draft = draftActive
        val list = draft ?: if (queue.isNotEmpty()) queue else lastResults
        val current = tools.getCurrentTrack()
        val r = if (draft == null && ref.countFirst != null && lastResults.isNotEmpty()) {
            // "die ersten drei" bezieht sich auf die letzte Trefferliste (Suche/Vorschlag), nicht auf die Queue
            ReferenceResolver.resolve(ref, lastResults, current, lastSuggested)
        } else ReferenceResolver.resolve(ref, list, current, lastSuggested)
        return when (r) {
            is RefResult.None -> Reply(r.reason)
            is RefResult.Many -> {
                tools.replaceQueueAndPlay(r.tracks.map { it.id })
                lastResults = r.tracks; lastSuggested = null; draftActive = null; draftPlay = null
                Reply("Okay, die ersten ${r.tracks.size} laufen. 🎶")
            }
            is RefResult.One -> {
                val t = r.track
                lastSuggested = t
                val a = ref.actions
                when {
                    RefAction.WHY in a -> explainTrack(t)
                    RefAction.REMOVE in a -> removeTrack(t, RefAction.SIMILAR in a)
                    RefAction.DISLIKE in a -> {
                        learnDislike(t)
                        // Urteil allein ist keine Anweisung: erst fragen, ob er raus soll
                        pending = Pending.RemoveTrack(t, RefAction.SIMILAR in a)
                        Reply("Okay, \"${t.title}\" kommt bei dir nicht gut an. Soll er aus der Queue raus${if (RefAction.SIMILAR in a) " und ich such einen ähnlichen" else ""}?", yesNo)
                    }
                    RefAction.SIMILAR in a -> similarTo(t, removed = false)
                    else -> { tools.playTrack(t.id); Reply("Läuft: ${t.title} – ${t.artist} 🎶") }
                }
            }
        }
    }

    /** Entfernt einen Titel aus der Queue (nie die Datei). Laeuft er gerade, wird erst weitergeschaltet. */
    private suspend fun removeTrack(t: Track, similar: Boolean): Reply {
        sessionDisliked += t.uri // nur diese Sitzung: ein reines "mach weg" ist noch kein Dauerurteil
        val draft = draftActive
        if (draft != null && draft.any { it.uri == t.uri }) {
            draftActive = draft.filter { it.uri != t.uri }
            lastResults = draftActive.orEmpty()
            return if (similar) similarTo(t, removed = true)
            else Reply("Jo, \"${t.title}\" fliegt aus dem Entwurf. 🗑️ Er ist noch nicht gestartet - sag \"Spiel sie\", wenn er passt.")
        }
        val wasCurrent = tools.getCurrentTrack()?.uri == t.uri
        if (wasCurrent) tools.skipNext()
        val removed = tools.removeFromQueue(setOf(t.id))
        if (removed == 0) {
            return Reply(if (wasCurrent) "Ich hab weitergeschaltet, konnte \"${t.title}\" aber nicht aus der Queue nehmen. Du kannst ihn in der Queue-Ansicht mit dem X entfernen."
            else "\"${t.title}\" ist gar nicht in der Queue, da gibt's nichts zu entfernen.")
        }
        return if (similar) similarTo(t, removed = true)
        else Reply("Jo, \"${t.title}\" fliegt raus. 🗑️ Die Datei bleibt natürlich.")
    }

    private suspend fun similarTo(seedTrack: Track, removed: Boolean): Reply {
        val infos = tools.loadTrackInfos()
        val seed = infos.firstOrNull { it.track.uri == seedTrack.uri }
        val lead = if (removed) "Jo, \"${seedTrack.title}\" fliegt raus. " else ""
        if (seed == null) return Reply(lead + "Zu dem Titel hab ich keine Daten, um etwas Ähnliches zu finden.")
        val inQueue = tools.getQueue().map { it.uri }.toSet() + draftActive.orEmpty().map { it.uri }
        val pick = SimilarFinder.find(seed, infos, sessionDisliked + inQueue + sessionAvoid, lastMusic?.mood)
        if (pick == null) return Reply(lead + "Ich finde gerade nichts, was wirklich ähnlich ist - lieber nichts als irgendwas. Sag mir eine Stimmung oder ein Genre, dann such ich.")
        val why = if (pick.reasons.isEmpty()) "" else " (" + pick.reasons.joinToString(", ") + ")"
        lastSuggested = pick.info.track
        if (draftActive != null) {
            draftActive = draftActive.orEmpty() + pick.info.track
            lastResults = draftActive.orEmpty()
            return Reply(lead + "Als Ersatz steht jetzt \"${pick.info.track.title}\" – ${pick.info.track.artist} im Entwurf$why.")
        }
        tools.addToQueue(listOf(pick.info.track.id))
        return Reply(lead + "Als Ersatz hab ich \"${pick.info.track.title}\" – ${pick.info.track.artist} hinten angehängt$why. 🎶")
    }

    private suspend fun explainTrack(t: Track): Reply {
        val info = tools.loadTrackInfos().firstOrNull { it.track.uri == t.uri }
        return Reply(ChoiceExplainer.explain(info, t, lastMusic, state.musicMood()))
    }

    // ------------------------------------------------------------------ Themen-Stapel, Episoden

    private fun topicOf(i: UserIntent): Topic? = when (i) {
        is UserIntent.Emotion -> Topic.EMOTION
        is UserIntent.PlayMood, is UserIntent.SearchArtist, is UserIntent.SearchPlay, is UserIntent.MultiPhase,
        UserIntent.Recommend, UserIntent.SimilarToCurrent, is UserIntent.SimilarToPlaylist, is UserIntent.AskTrackAnalysis ->
            if (draftActive != null) Topic.PLAYLIST else Topic.MUSIC
        is UserIntent.Refine, is UserIntent.TrackRef -> if (draftActive != null) Topic.PLAYLIST else Topic.MUSIC
        is UserIntent.EqChange -> Topic.EQ
        is UserIntent.Volume -> Topic.VOLUME
        is UserIntent.Chat, UserIntent.Greeting -> Topic.CHAT
        else -> null
    }

    /** "Und die Playlist von eben?" - ein frueheres Thema zurueckholen; nur aus echtem Zustand, nichts erfinden. */
    private fun resumeTopic(t: Topic): Reply {
        state.pushTopic(t)
        return when (t) {
            Topic.PLAYLIST -> {
                val d = draftActive
                if (d != null && d.isNotEmpty()) {
                    draftPlay?.let { pending = Pending.StartDraft(it) }
                    Reply(persona.draftReady(d.size, d.sumOf { it.durationMs }, d.take(5).map { "${it.title} – ${it.artist}" }, false), listOf("Spiel sie", "Länger", "Kürzer"))
                } else resumeMusic()
            }
            Topic.MUSIC -> resumeMusic()
            Topic.EQ -> {
                val s = tools.getEqState()
                Reply(persona.resumeEq(String.format(java.util.Locale.GERMANY, "%+.1f dB", Eq.bassGain(s)), String.format(java.util.Locale.GERMANY, "%+.1f dB", Eq.trebleGain(s))))
            }
            Topic.CONVERSATION -> Reply(persona.resumeConversation(state.buildSummary()))
            else -> Reply(persona.resumeNothing(t))
        }
    }

    private fun resumeMusic(): Reply {
        val q = tools.getQueue()
        if (q.isEmpty()) return Reply(persona.resumeNothing(Topic.PLAYLIST))
        val cur = tools.getCurrentTrack()
        return Reply(persona.resumeRunning(q.size, q.sumOf { it.durationMs } / 60_000L, cur?.let { "${it.title} – ${it.artist}" }))
    }

    private fun memoryAndPatterns(): String {
        val base = memory.describe()
        val pat = if (episodes.hasPatterns()) episodes.describe() else ""
        return when {
            pat.isBlank() -> base
            memory.isEmpty() -> "Das hab ich beobachtet (nur lokal auf deinem Gerät):\n$pat"
            else -> base + "\n\nBeobachtete Muster:\n" + pat
        }
    }

    /** Ein echter Musikstart wird als strukturiertes Ereignis gemerkt (Tagesabschnitt, Stimmung/Genre, Gefuehl, Thema) - nie als Chattext. */
    private fun recordEpisode(i: UserIntent.PlayMood, minutes: Int?) {
        val fresh = state.isFresh()
        episodes.record(i.mood, i.genreLabel, if (fresh) state.emotion else null, if (fresh) state.topic else null, minutes)
    }

    // ------------------------------------------------------------------ Mehrphasige Wuensche

    private fun phaseLabel(p: UserIntent.PlayMood): String = p.genreLabel ?: p.mood?.label ?: "gemischt"

    private suspend fun multiPhase(m: UserIntent.MultiPhase): Reply {
        var lead = ""
        if (m.intro != null) {
            val e = try { brain.understand(m.intro) } catch (ex: Exception) { UserIntent.Unknown }
            if (e is UserIntent.Emotion) { state.onEmotion(e.feel, e.topic, e.strong); lead = persona.empathyLine() + "\n\n" }
        }
        draftActive = null; draftPlay = null
        class Done(val label: String, val tracks: Int, val minutes: Long)
        val done = ArrayList<Done>()
        val failed = ArrayList<String>()
        var prev = 0
        val eq = m.phases.first().eq
        for ((idx, p) in m.phases.withIndex()) {
            val r = playMood(p.copy(prepare = true, append = idx > 0, phased = true, shuffle = null, eq = emptyList()))
            val now = draftActive.orEmpty()
            if (now.size == prev) {
                if (idx == 0) { pending = null; return r.copy(text = lead + r.text) } // erste Phase scheitert: ehrlich so melden
                failed += phaseLabel(p)
                continue
            }
            done += Done(phaseLabel(p), now.size - prev, now.drop(prev).sumOf { it.durationMs } / 60_000L)
            prev = now.size
        }
        val draft = draftActive.orEmpty()
        val started = !m.prepare
        if (started) startDraft()
        val eqReply = if (eq.isNotEmpty()) changeEq(eq) else null
        return Reply(buildString {
            append(lead)
            append(persona.phaseHead(draft.size, draft.sumOf { it.durationMs } / 60_000L))
            done.forEachIndexed { k, d -> append("\n• Phase ${k + 1}: ${d.label}, ${d.tracks} Titel, ca. ${d.minutes} Min.") }
            if (m.splitNote != null) append("\n").append(m.splitNote)
            if (failed.isNotEmpty()) append("\n").append(persona.phaseMissing(failed))
            append("\n\n").append(if (started) "Läuft." else "Gestartet ist noch nichts. Sag \"Spiel sie\", wenn's passt.")
            if (eqReply != null) append("\n\n").append(eqReply.text)
        }, if (started) emptyList() else listOf("Spiel sie", "Länger", "Kürzer"))
    }

    // ------------------------------------------------------------------ Playlist-Entwurf starten / laenger / kuerzer

    private fun startDraft(): Reply {
        val list = draftActive.orEmpty()
        val play = draftPlay
        if (list.isEmpty()) { draftActive = null; draftPlay = null; return Reply(persona.nothingToStart()) }
        tools.replaceQueueAndPlay(list.map { it.id }, play?.shuffle)
        lastResults = list; lastSuggested = null
        lastMusic = play
        if (play?.noRepeat == true) sessionAvoid += list.map { it.uri }
        draftActive = null; draftPlay = null
        if (play != null) recordEpisode(play, play.minutes)
        state.onMusicStarted()
        if (play != null) recordSnapshot(play, play.minutes)
        return Reply("Läuft: ${list.size} Titel, ca. ${list.sumOf { it.durationMs } / 60_000L} Min. 🎶")
    }

    private suspend fun refine(kind: RefineKind): Reply {
        val draft = draftActive
        val base: List<Track> = draft ?: tools.getQueue()
        val play = (if (draft != null) draftPlay else lastMusic)
        if (base.isEmpty() || play == null) return Reply(persona.nothingToRefine())
        val totalMin = (base.sumOf { it.durationMs } / 60_000L).toInt()
        if (kind == RefineKind.LONGER) {
            val add = maxOf(15, totalMin * 2 / 5)
            return playMood(play.copy(append = true, prepare = draft != null, minutes = add, eq = emptyList()))
        }
        // kuerzer: bis ca. 60 % der Spielzeit behalten, laufenden Titel nie anfassen
        val target = maxOf(10, totalMin * 6 / 10)
        var keep = 0
        var acc = 0L
        while (keep < base.size && acc < target * 60_000L) { acc += base[keep].durationMs; keep++ }
        if (draft == null) {
            val curIdx = base.indexOfFirst { it.uri == tools.getCurrentTrack()?.uri }
            keep = maxOf(keep, curIdx + 1, 1)
        }
        if (keep >= base.size) return Reply(persona.cannotShorten(base.size))
        val kept = base.take(keep)
        if (draft != null) {
            draftActive = kept; lastResults = kept
            return Reply(persona.shortened(base.size, kept.size, totalMin, (kept.sumOf { it.durationMs } / 60_000L).toInt(), true))
        }
        val removed = tools.removeFromQueue(base.drop(keep).map { it.id }.toSet())
        val newMin = ((kept.sumOf { it.durationMs }) / 60_000L).toInt()
        return if (removed == 0) Reply(persona.shortenFailed()) else Reply(persona.shortened(base.size, base.size - removed, totalMin, newMin, false))
    }

    // ------------------------------------------------------------------ Rueckfragen im Kontext

    /** Liest eine Antwort im Licht der offenen Frage. null = war keine Antwort, normal weiterverarbeiten. */
    private suspend fun answerAsk(ask: Pending.Ask, text: String, parsed: UserIntent): Reply? {
        // Klare andere Befehle (EQ, Pause, Suche ...) gehen vor: die Frage ist dann erledigt
        if (parsed is UserIntent.EqChange || parsed is UserIntent.Pause || parsed is UserIntent.Next || parsed is UserIntent.Volume ||
            parsed is UserIntent.SearchArtist || parsed is UserIntent.TrackRef || parsed is UserIntent.Forget || parsed is UserIntent.WhatDoYouKnow
        ) return null
        val ans = AnswerParser.parse(ask.kind, text)
        if (ans.choice == null) {
            if (ans.surprise) return decideByContext(ask.kind)
            // Unklar: einmal kurz nachfragen, danach normal weiter
            if (ans.confidence in 0.1f..0.4f && ask.tries < 1) {
                pending = Pending.Ask(ask.kind, ask.tries + 1)
                return Reply(persona.askAgain(ask.kind), askOptions(ask.kind))
            }
            return null
        }
        val sure = ans.confidence >= 0.75f
        val prefix = if (sure) "" else persona.understoodAs(askChoiceLabel(ask.kind, ans.choice)) + " "
        return when (ask.kind) {
            AskKind.MUSIC_OR_TALK -> when (ans.choice) {
                "talk" -> {
                    state.setMode(if (state.venting) ConversationMode.SUPPORT else ConversationMode.CHAT)
                    val body = if (state.hasSharedContent || state.venting)
                        persona.talkAfterShare(state.topic)
                    else persona.talk()
                    Reply(prefix + body, listOf("Mach was Entspanntes", "Erzähl ich dir gleich"))
                }
                else -> {
                    state.setMode(ConversationMode.MUSIC)
                    val k = if (state.venting && state.emotion != null && state.emotion != Mood.ANGRY) AskKind.SAD_OR_UP else AskKind.ENERGY
                    pending = Pending.Ask(k)
                    Reply(prefix + persona.askQuestion(k), askOptions(k))
                }
            }
            AskKind.ENERGY -> {
                val r = if (ans.choice == "push") playMood(UserIntent.PlayMood(mood = Mood.ENERGETIC, eq = listOf(EqCommand(EqKind.BASS_UP, EqSize.SMALL))))
                else playMood(UserIntent.PlayMood(mood = Mood.CALM))
                r.copy(text = prefix + r.text)
            }
            AskKind.BORED -> if (ans.choice == "talk") {
                state.setMode(ConversationMode.CHAT)
                Reply(prefix + persona.talk(), listOf("Erzähl ich dir gleich"))
            } else {
                val r = playUnheard(UserIntent.PlayUnheard(null, false))
                r.copy(text = prefix + r.text)
            }
            AskKind.SAD_OR_UP -> {
                state.onGoal(if (ans.choice == "sad") Mood.SAD else Mood.CALM)
                val r = if (ans.choice == "sad") playMood(UserIntent.PlayMood(mood = Mood.SAD, support = true))
                else playMood(UserIntent.PlayMood(mood = Mood.CALM, support = true))
                r.copy(text = prefix + r.text)
            }
        }
    }

    private fun askOptions(k: AskKind) = when (k) {
        AskKind.MUSIC_OR_TALK -> listOf("Musik", "Quatschen")
        AskKind.ENERGY -> listOf("Ruhig", "Mit Druck")
        AskKind.SAD_OR_UP -> listOf("Traurig reinfühlen", "Hochkommen")
        AskKind.BORED -> listOf("Was Neues entdecken", "Quatschen")
    }

    private fun askChoiceLabel(k: AskKind, choice: String) = when (choice) {
        "music" -> "Musik"; "discover" -> "was Neues entdecken"; "talk" -> "quatschen"; "calm" -> "ruhig"; "push" -> "mit Druck"; "sad" -> "reinfühlen"; else -> "hochkommen"
    }

    /** "Such du" / "egal": Kontext entscheidet (Gefuehl, Ziel), sonst ein bunter Mix. */
    private suspend fun decideByContext(k: AskKind): Reply = when (k) {
        AskKind.BORED -> playUnheard(UserIntent.PlayUnheard(null, false))
        AskKind.MUSIC_OR_TALK -> playMood(UserIntent.PlayMood(mood = null))
        else -> playMood(UserIntent.PlayMood(mood = state.musicMood(), support = state.venting))
    }

    // ------------------------------------------------------------------ EQ / DSP (nur Sitzung)

    private fun applyPlan(plan: EqPlan) {
        rememberEq()
        if (plan.reset) { tools.resetEq(); return }
        plan.bass?.let { tools.setBass(it) }
        plan.treble?.let { tools.setTreble(it) }
        for ((hz, gain) in plan.bands) tools.adjustBand(hz, gain)
    }

    private fun eqHint(): String {
        if (eqHintShown) return ""
        eqHintShown = true
        return "\n" + persona.eqHint()
    }

    /** Plant die EQ-Aenderung aus dem aktuellen Zustand; extreme Werte werden erst nach einem \"Ja\" gesetzt. */
    private fun eqPart(cmds: List<EqCommand>): String {
        val plan = EqPlanner.plan(tools.getEqState(), cmds) // Zustand VOR der Aenderung auslesen
        if (!plan.reset && plan.bass == null && plan.treble == null && plan.bands.isEmpty()) return persona.eqNotChanged()
        if (plan.extreme) {
            pending = Pending.SetEq(plan, cmds)
            return persona.eqExtreme(plan.summary)
        }
        applyPlan(plan)
        lastEq = cmds; lastWasEq = true
        return (if (plan.reset) persona.eqReset() else persona.eqDone(plan.summary)) + eqHint()
    }

    private fun changeEq(cmds: List<EqCommand>): Reply {
        val text = eqPart(cmds)
        return Reply(text, if (pending is Pending.SetEq) yesNo else emptyList())
    }

    private fun applyPreset(name: String): Reply {
        val before = try { tools.getEqState() } catch (e: Exception) { null }
        if (!tools.applyEqPreset(name)) return Reply(persona.eqPresetMissing())
        if (before != null) pushUndo(before)
        lastWasEq = false
        return Reply(persona.eqPreset(name) + eqHint())
    }

    private fun askSaveEq(name: String?): Reply {
        pending = Pending.SaveEq(name)
        return Reply(persona.eqAsk(name), yesNo)
    }

    // ------------------------------------------------------------------ Kurzbefehle mit Kontext

    /** "noch bisschen" / "mehr": nach EQ nochmal leicht in dieselbe Richtung, sonst weitere Titel zum letzten Wunsch. */
    private suspend fun more(): Reply {
        val eq = lastEq?.filter { it.kind != EqKind.RESET }?.map { it.copy(size = EqSize.SMALL) }
        if (lastWasEq && !eq.isNullOrEmpty()) return changeEq(eq)
        val m = lastMusic ?: return surprise()
        return playMood(m.copy(append = true, minutes = 15, support = false, session = false, eq = emptyList()))
    }

    /** "bisschen weniger": Gegenteil der letzten EQ-Aenderung. */
    private fun less(): Reply {
        val eq = lastEq
        if (lastWasEq && eq != null) {
            val back = eq.mapNotNull { EqPlanner.reverse(it) }
            if (back.isNotEmpty()) return changeEq(back)
        }
        return Reply(persona.eqNothingToUndo())
    }

    /** "such du aus" / "egal": passend zum letzten Wunsch (z. B. traurig), sonst ein bunter Mix. */
    private suspend fun surprise(): Reply {
        val base = lastMusic
        val i = base?.copy(append = false, support = false, session = false, noRepeat = false, eq = emptyList())
            ?: UserIntent.PlayMood(mood = null)
        return playMood(i)
    }

    /** Unverstandenes: lieber etwas Sinnvolles tun (Titel/Interpret suchen) als \"verstehe ich nicht\" sagen. */
    private val followUpWords = setOf("echt", "wirklich", "warum", "wieso", "weshalb", "ernsthaft", "und", "hm", "hmm", "aha", "ach", "so", "oh", "hae", "he", "wie", "was", "ja", "doch", "dann", "wow", "krass")
    private val fillerNames = setOf("bro", "alter", "digga", "man", "ey", "junge", "buddy")
    private val gladWords = listOf("gut", "geil", "super", "happy", "froh", "freu", "nice", "mega", "toll", "entspannt", "schoen", "top", "gluecklich")

    /**
     * Unverstandenes: erst als Gespraech behandeln (Nachfragen wie \"echt?\" / \"warum bro\", gute Laune, Erzaehlen),
     * dann als Suche in der Library - und nur zuletzt \"verstehe ich nicht\".
     */
    private fun fallback(text: String): Reply {
        val q = text.trim()
        val tn = TextUtil.norm(q).trim().trimEnd('.', '!', '?', ' ')
        val words = tn.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        val core = words.filter { it !in fillerNames }

        // „Hab ich doch gesagt“ – nicht nochmal von vorn fragen
        if (ResponsePlanner.isAlreadySaidPhrase(tn)) {
            state.setMode(if (state.venting) ConversationMode.SUPPORT else ConversationMode.CHAT)
            return Reply(persona.alreadySaid(), listOf("Mach was Entspanntes", "Erzähl ich dir gleich"))
        }

        // Im CHAT/SUPPORT-Modus: freie Texte = zuhören, keine Musik-Schleife
        if (state.mode == ConversationMode.CHAT || state.mode == ConversationMode.SUPPORT ||
            (state.venting && state.isFresh())
        ) {
            state.markShared()
            state.setMode(if (state.venting) ConversationMode.SUPPORT else ConversationMode.CHAT)
            return Reply(persona.chatContinue(state.topic), listOf("Mach was Entspanntes", "Erzähl ich dir gleich"))
        }

        if (core.isNotEmpty() && core.size <= 2 && core.all { it in followUpWords }) {
            return Reply(persona.followUp(state.topic), listOf("Mach was Entspanntes", "Erzähl ich dir gleich"))
        }
        val negated = tn.contains("nicht") || tn.contains("kein") || tn.contains("nie ")
        if (words.size >= 3 && !negated && gladWords.any { tn.contains(it) }) {
            pending = Pending.Offer(UserIntent.PlayMood(mood = Mood.HAPPY))
            return Reply(persona.glad(), yesNo)
        }
        if (words.size >= 5) {
            state.markShared()
            state.setMode(ConversationMode.CHAT)
            return Reply(persona.chatContinue(state.topic), listOf("Mach was Entspanntes", "Erzähl ich dir gleich"))
        }
        if (q.length in 3..40 && tools.searchLibrary(q, 1).isNotEmpty()) return searchPlay(q)
        pending = Pending.Ask(AskKind.MUSIC_OR_TALK)
        return Reply(persona.unknown(), listOf("Mach Musik", "Lass quatschen"))
    }

    private suspend fun playUnheard(i: UserIntent.PlayUnheard): Reply {
        val infos = tools.loadTrackInfos()
        if (infos.isEmpty()) return Reply(persona.emptyLibrary())
        val req = QueueRequest(mood = null, exclude = sessionExclusions.toSet(), minutes = i.minutes, preferUnheard = true)
        val result = withContext(Dispatchers.Default) { Recommender.build(infos, req) }
        if (result.tracks.isEmpty()) return Reply(persona.nothingFound())
        val ids = result.tracks.map { it.id }
        if (i.append) tools.addToQueue(ids) else tools.replaceQueueAndPlay(ids)
        val historyKnown = infos.any { it.lastPlayedAt != null }
        val note = if (!historyKnown) "\n\nHinweis: Ich merke mir erst seit diesem Update, was du hörst - deshalb gelten für mich alle Titel als \"lange nicht gehört\". Das wird mit der Zeit besser." else ""
        return Reply("${persona.unheardIntro()}\n\n${persona.summary(result.tracks.size, result.totalMs, i.append)}$note")
    }

    private suspend fun playFavorites(i: UserIntent.PlayFavorites): Reply {
        val infos = tools.loadTrackInfos()
        val req = QueueRequest(mood = null, exclude = sessionExclusions.toSet(), minutes = i.minutes, favoritesOnly = true)
        val result = withContext(Dispatchers.Default) { Recommender.build(infos, req) }
        if (result.tracks.isEmpty()) return Reply("Du hast noch keine Favoriten. Tippe im Player auf das Herz, dann kann ich sie für dich spielen. ❤️")
        val ids = result.tracks.map { it.id }
        if (i.append) tools.addToQueue(ids) else tools.replaceQueueAndPlay(ids)
        return Reply("${persona.favoritesIntro()}\n\n${persona.summary(result.tracks.size, result.totalMs, i.append)}")
    }

    private fun searchArtist(name: String): Reply {
        val hits = tools.searchArtist(name, 200)
        if (hits.isEmpty()) return Reply("Zum Interpreten \"$name\" finde ich nichts in deiner Library. 🤷")
        tools.replaceQueueAndPlay(hits.map { it.id })
        lastResults = hits; lastSuggested = hits.singleOrNull()
        if (hits.size == 1) return Reply("Läuft: ${hits[0].title} – ${hits[0].artist} 🎶")
        val preview = hits.take(5).joinToString("\n") { "• ${it.title}" } + if (hits.size > 5) "\n… und ${hits.size - 5} weitere" else ""
        return Reply("${hits.size} Titel zu \"$name\" - Queue steht, es läuft los. 🎶\n\n$preview")
    }

    private fun searchPlay(query: String): Reply {
        val hits = tools.searchLibrary(query, 30)
        if (hits.isEmpty()) return Reply("Zu \"$query\" finde ich nichts in deiner Library. 🤷 (Ich suche in Titel, Interpret, Album, Genre und Dateiname.)")
        tools.replaceQueueAndPlay(hits.map { it.id })
        lastResults = hits; lastSuggested = hits.singleOrNull()
        val first = hits.first()
        return Reply(
            if (hits.size == 1) "Läuft: ${first.title} – ${first.artist} 🎶"
            else "${hits.size} Treffer zu \"$query\" - ich starte mit ${first.title} – ${first.artist}. 🎶"
        )
    }

    private suspend fun applyExclusion(moods: Set<Mood>): Reply {
        sessionExclusions += moods
        val queue = tools.getQueue()
        if (queue.isEmpty()) return Reply(persona.excluded(moods, 0))
        val infos = tools.loadTrackInfos().associateBy { it.track.uri }
        val currentUri = tools.getCurrentTrack()?.uri
        val toRemove = queue.filter { t ->
            t.uri != currentUri && moods.any { m -> infos[t.uri]?.let { Recommender.looksLike(it, m) } == true }
        }.map { it.id }.toSet()
        return Reply(persona.excluded(moods, tools.removeFromQueue(toRemove)))
    }

    /** Gefuehls-Stimmungen profitieren am meisten vom Songtext - einmal pro Sitzung darauf hinweisen, wenn kaum Texte gelesen sind. */
    private fun lyricsHint(coverage: Float, mood: Mood?): String? {
        if (lyricsHintShown || coverage >= 0.05f) return null
        val emotional = mood == Mood.SAD || mood == Mood.ANGRY || mood == Mood.LONELY || mood == Mood.ROMANTIC ||
            mood == Mood.NOSTALGIC || mood == Mood.DARK || mood == Mood.MOTIVATED
        if (!emotional) return null
        lyricsHintShown = true
        return persona.lyricsHint()
    }

    private fun coverageHint(coverage: Float, mood: Mood?): String? {
        if (hintShown || mood == null || coverage >= 0.3f) return null
        hintShown = true
        return persona.analysisHint((coverage * 100).toInt())
    }

    // ------------------------------------------------------------------ Auskunft

    private suspend fun askTrackAnalysis(fields: Set<AnalysisField>): Reply {
        val t = tools.getCurrentTrack() ?: return Reply("Gerade läuft nichts, dessen Audioanalyse ich auslesen kann. 🔇")
        val a = tools.getAnalysis(t.uri)
            ?: return Reply("Für \"${t.title}\" gibt es noch keine gespeicherte Audioanalyse. Lass unter Menü → Audioanalyse die Analyse durchlaufen, dann kann ich BPM und die übrigen Messwerte direkt auslesen.")

        fun f1(v: Float): String = String.format(Locale.GERMANY, "%.1f", v)
        val all = AnalysisField.ALL in fields
        val out = ArrayList<String>()
        if (all || AnalysisField.BPM in fields) {
            out += if (a.bpm > 0f) "${f1(a.bpm)} BPM" else "BPM: nicht zuverlässig erkannt"
        }
        if (all || AnalysisField.LOUDNESS in fields) {
            if (!a.lufs.isNaN()) out += "LUFS: ${f1(a.lufs)}"
        }
        if (all || AnalysisField.PEAK in fields) out += "Peak: ${f1(a.peakDb)} dBFS"
        if (all || AnalysisField.RMS in fields) out += "RMS: ${f1(a.rmsDb)} dBFS"

        val partial = if (a.partial) " (Analyse auf die ersten 5 Minuten begrenzt)" else ""
        return Reply("${t.title} – ${t.artist}:\n${out.joinToString(" · ")}$partial")
    }

    private suspend fun expandDraftSimilar(): Reply {
        val draft = draftActive.orEmpty()
        if (draft.isEmpty()) return Reply(persona.nothingToRefine())
        val infos = tools.loadTrackInfos()
        val personal = loadPersonalSignals(infos)
        val profile = GenreProfile.build(infos, tools.loadPlaylists(), personal, tools.loadMlGenres())
        val byUri = infos.associateBy { it.track.uri }
        val seeds = draft.mapNotNull { byUri[it.uri] }
        val excluded = draft.map { it.uri }.toSet()
        val additions = withContext(Dispatchers.Default) {
            infos.asSequence().filter { it.track.uri !in excluded }
                .map { c -> c to (seeds.map { TrackSimilarity.score(it, c, profile, personal) }.sortedDescending().take(5).average().toFloat()) }
                .filter { it.second >= 0.45f }.sortedByDescending { it.second }.take(10).map { it.first.track }.toList()
        }
        if (additions.isEmpty()) return Reply("Ich finde für den bestehenden Entwurf gerade keine weiteren wirklich passenden Tracks – ich lasse ihn unverändert.")
        draftActive = (draft + additions).distinctBy { it.uri }
        lastResults = draftActive.orEmpty()
        return Reply("Hab den bestehenden Entwurf um ${additions.size} ähnliche Tracks erweitert. Er startet noch nicht. 🎶", listOf("Mach an", "Noch mehr"))
    }

    private suspend fun similarToPlaylist(requestedName: String): Reply {
        val infos = tools.loadTrackInfos()
        if (infos.isEmpty()) return Reply(persona.emptyLibrary())
        val playlists = tools.loadPlaylists()
        val want = TextUtil.norm(requestedName.trim())
        val actualName = playlists.keys.firstOrNull { TextUtil.norm(it) == want }
            ?: playlists.keys.firstOrNull { TextUtil.norm(it).contains(want) || want.contains(TextUtil.norm(it)) }
            // "rap" soll z.B. "Chill-Rap" / "Deutschrap" treffen – laengere Treffer zuerst
            ?: playlists.keys
                .map { it to TextUtil.norm(it) }
                .filter { (_, n) -> want.split(" ").any { w -> w.length >= 3 && n.contains(w) } || n.split(" ", "-").any { it.length >= 3 && want.contains(it) } }
                .maxByOrNull { (_, n) ->
                    want.split(" ").count { w -> w.length >= 3 && n.contains(w) } * 10 + n.length
                }?.first
            ?: return Reply("Ich finde keine Playlist namens \"$requestedName\" in deiner Library. Ich erfinde keine Referenz. 🤷")
        val genreProfile = GenreProfile.build(infos, playlists, null, tools.loadMlGenres())
        val seeds = playlists[actualName].orEmpty().mapNotNull { t -> infos.firstOrNull { it.track.uri == t.uri } }
        if (seeds.isEmpty()) return Reply("Die Playlist \"$actualName\" ist leer oder ihre Titel sind nicht mehr in der Library.")
        val profile = PlaylistProfile.build(actualName, seeds, genreProfile)
        val seedUris = seeds.map { it.track.uri }.toSet()
        val ranked = withContext(Dispatchers.Default) {
            infos.asSequence().filter { it.track.uri !in seedUris }
                .map { candidate ->
                    val best = seeds.asSequence().map { TrackSimilarity.score(it, candidate, genreProfile) }.sortedDescending().take(5).toList()
                    val similarity = if (best.isEmpty()) 0f else best.average().toFloat()
                    val genreBoost = if (profile.dominantGenres.isEmpty()) 0f else profile.dominantGenres.take(2).maxOf { (g, c) -> genreProfile.evidence(candidate, g).confidence * c }
                    candidate to (0.62f * similarity + 0.38f * genreBoost)
                }.filter { it.second >= 0.42f }
                .sortedByDescending { it.second }.take(25).map { it.first }.toList()
        }
        if (ranked.isEmpty()) return Reply("Ich habe aus \"$actualName\" noch keinen wirklich ähnlichen neuen Entwurf gefunden – lieber nichts als Genre-Ausreißer.")
        val draft = PlaylistPlanner.order(ranked.map { it.track }, infos.associateBy { it.track.uri }, PlaylistPlanner.arcFor(profile.moods.maxByOrNull { it.value }?.key, false)).tracks
        draftActive = draft
        draftPlay = UserIntent.PlayMood(mood = profile.moods.maxByOrNull { it.value }?.key, genres = profile.dominantGenres.take(2).map { it.first }, prepare = false)
        lastResults = draft
        lastSuggested = null
        pending = Pending.StartDraft(draftPlay!!)
        val genreText = profile.dominantGenres.take(3).joinToString(", ") { it.first }
        val bpmText = profile.averageBpm?.let { "Ø ${it.toInt()} BPM" }
        val basis = listOfNotNull(genreText.takeIf { it.isNotBlank() }, bpmText).joinToString(" · ")
        val preview = draft.take(5).joinToString("\n") { "• ${it.title} – ${it.artist}" }
        return Reply("Ich hab dir einen ähnlichen Entwurf zu \"$actualName\" erstellt. $basis\n\n$preview\n\nSoll ich ihn starten?", listOf("Mach an", "Noch trauriger", "Mehr Rap"))
    }

    private suspend fun similarToCurrent(): Reply {
        val current = tools.getCurrentTrack() ?: return Reply("Gerade läuft nichts, an dem ich ähnliche Tracks ausrichten kann. 🔇")
        val infos = tools.loadTrackInfos()
        val source = infos.firstOrNull { it.track.uri == current.uri }
            ?: return Reply("Zum laufenden Titel fehlen mir die gespeicherten Track-Daten. Nach dem nächsten Bibliotheks-/Analyse-Scan kann ich ähnliche Titel daraus bauen.")

        val queueUris = tools.getQueue().map { it.uri }.toSet()
        val personal = loadPersonalSignals(infos)
        val genreProfile = GenreProfile.build(infos, tools.loadPlaylists(), personal, tools.loadMlGenres())
        val similar = withContext(Dispatchers.Default) {
            TrackSimilarity.rank(source, infos, excludedUris = queueUris + current.uri, limit = 12, genreProfile = genreProfile, minScore = 0.72f, personal = personal)
        }
        if (similar.isEmpty()) return Reply("Ich finde in deiner Library noch keine weiteren Titel, die sauber zum laufenden Track passen.")

        val ids = similar.map { it.track.id }
        if (tools.getQueue().isEmpty()) tools.replaceQueueAndPlay(ids) else tools.addToQueue(ids)
        lastResults = similar.map { it.track }
        lastSuggested = null

        val basis = buildList {
            source.analysis?.bpm?.takeIf { it > 0f }?.let { add("BPM ${it.toInt()}") }
            source.track.genre.takeIf { it.isNotBlank() }?.let { add(it) }
            if (source.analysis != null) add("Audioanalyse")
            if (source.lyricMood.isNotEmpty()) add("Songtext-Stimmung")
        }.take(3)
        val intro = if (basis.isEmpty()) "anhand der verfügbaren Track-Tags" else basis.joinToString(", ")
        val preview = similar.take(5).joinToString("\n") { "• ${it.track.title} – ${it.track.artist}" }
        return Reply("Ich habe ${similar.size} ähnliche Tracks zu \"${current.title}\" ergänzt – $intro. 🎶\n\n$preview")
    }

    private suspend fun whatsPlaying(): Reply {
        val t = tools.getCurrentTrack() ?: return Reply("Gerade läuft nichts. 🔇")
        val a = tools.getAnalysis(t.uri)
        val extra = buildList {
            if (a != null && a.bpm > 0f) add("${a.bpm.toInt()} BPM")
            if (a != null && !a.lufs.isNaN()) add(String.format(Locale.GERMANY, "%.1f LUFS", a.lufs))
        }
        val tail = if (extra.isNotEmpty()) "\n${extra.joinToString(" · ")}" else ""
        return Reply("Gerade läuft: ${t.title} – ${t.artist} (${t.album})$tail")
    }

    private suspend fun showStats(): Reply {
        val s = tools.getLibraryStats()
        if (s.trackCount == 0) return Reply(persona.emptyLibrary())
        val hours = s.totalDurationMs / 3_600_000L
        val min = (s.totalDurationMs / 60_000L) % 60
        val genres = if (s.topGenres.isNotEmpty()) "\nTop-Genres: " + s.topGenres.joinToString { "${it.first} (${it.second})" } else ""
        return Reply(
            "📚 ${s.trackCount} Titel · ${s.artistCount} Interpreten · ${s.albumCount} Alben\n" +
                "Gesamtlänge: $hours Std. $min Min.\n" +
                "Analysiert: ${s.analyzedCount} von ${s.trackCount} · Noch nie gehört: ${s.neverPlayedCount}$genres"
        )
    }

    private suspend fun showHistory(): Reply {
        val h = tools.getRecentlyPlayed(8)
        if (h.isEmpty()) return Reply("Noch kein Verlauf - ich merke mir ab jetzt, was du hörst. 📝")
        return Reply("Zuletzt gehört:\n" + h.joinToString("\n") { "• ${it.title} – ${it.artist}" })
    }

    private fun checkDuplicates(): Reply {
        val r = tools.getDuplicateGroups()
        if (r.running) return Reply("Der Duplikat-Scan läuft gerade noch. 🔍")
        if (!r.scanned) {
            pending = Pending.StartDuplicateScan
            return Reply("Ich habe noch keinen Duplikat-Scan gesehen. Soll ich einen starten? Er liest die Dateien nur, es wird nichts gelöscht.", yesNo)
        }
        if (r.groups.isEmpty()) return Reply("Keine Duplikate gefunden. 🎉")
        val identical = r.groups.count { it.kind == com.mp.player.DupKind.IDENTICAL }
        return Reply("${r.groups.size} Duplikat-Gruppen: $identical identisch, ${r.groups.size - identical} ähnlich.\nAufräumen machst du selbst im Duplikate-Bildschirm - ich lösche nie Dateien.")
    }

    private suspend fun checkMissing(): Reply {
        val r = tools.getMissingFiles()
        val scope = if (r.complete) "alle ${r.total}" else "${r.checked} von ${r.total}"
        if (r.missing.isEmpty()) return Reply("Geprüft: $scope Titel - keine fehlenden Dateien. ✅")
        val sample = r.missing.take(5).joinToString("\n") { "• ${it.title} – ${it.artist}" }
        val more = if (r.missing.size > 5) "\n… und ${r.missing.size - 5} weitere" else ""
        return Reply("Geprüft: $scope Titel - ${r.missing.size} Dateien fehlen:\n$sample$more\nEin neuer Scan unter Menü → Bibliothek räumt sie aus der Liste.")
    }

    // ------------------------------------------------------------------ Rueckfragen

    private suspend fun explainSound(topic: String): Reply {
        val track = tools.getCurrentTrack()
        val analysis = track?.let { tools.getAnalysis(it.uri) }
        val e = Explainer.explain(topic, tools.getAudioSettings(), analysis, track?.let { "${it.title} – ${it.artist}" }, tools.getActiveAudioDevice())
        if (e.offerBassReset) {
            pending = Pending.ResetBass
            return Reply(e.text, yesNo)
        }
        return Reply(e.text)
    }

    private fun askSavePlaylist(requested: String?): Reply {
        val queue = tools.getQueue()
        if (queue.isEmpty()) return Reply("Deine Queue ist leer - es gibt nichts zu speichern. 🤷")
        val name = requested ?: ("KI-Mix " + SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date()))
        pending = Pending.SavePlaylist(name, queue.map { it.id })
        return Reply("Soll ich aus den ${queue.size} Titeln der Queue die Playlist \"$name\" anlegen? Das speichert dauerhaft.", yesNo)
    }

    private suspend fun answerAnalysisQuery(q: UserIntent.AnalysisQuery): Reply {
        return try {
            when (q.kind) {
                AnalysisQueryKind.COVERAGE -> {
                    val c = tools.getAnalysisCoverage()
                    val pct = (c.ratio * 100).toInt()
                    Reply("Von ${c.total} Titeln sind ${c.analysed} analysiert ($pct %). Mit Songtext: ${c.withLyrics}.")
                }
                AnalysisQueryKind.CURRENT_TRACK -> {
                    val cur = tools.getCurrentTrack()
                        ?: return Reply("Gerade läuft nichts – starte einen Titel, dann zeige ich die Analyse.")
                    val d = tools.getTrackAnalysisDetail(cur.uri)
                        ?: return Reply("Zu „${cur.title}“ liegt noch keine Analyse vor. Sag „Analysiere die fehlenden Titel“.")
                    val bpmLine = buildString {
                        if (d.measuredBpm > 0f) append("gemessen ~${"%.0f".format(d.measuredBpm)} BPM")
                        if (d.personalBpm != null) append(", persönlich ${"%.0f".format(d.personalBpm)} BPM")
                        if (d.bpmConfidence > 0f) append(" (Konfidenz ${"%.0f".format(d.bpmConfidence * 100)} %)")
                    }
                    val loud = if (!d.lufs.isNaN()) "Lautheit ~${"%.1f".format(d.lufs)} LUFS" else "Lautheit offen"
                    Reply("„${d.track.title}“: $bpmLine. $loud. Bass/Mitten/Höhen ${"%.2f".format(d.bass)}/${"%.2f".format(d.mid)}/${"%.2f".format(d.high)}. Rhythmus ${"%.2f".format(d.rhythmRegularity)}. Quellen: ${d.sources.joinToString(", ")}.")
                }
                AnalysisQueryKind.BPM_RANGE -> {
                    val center = q.bpmAround ?: 120f
                    val rows = tools.findTracksByFeatures(FeatureQuery(bpmMin = center - 8f, bpmMax = center + 8f, limit = 15))
                    if (rows.isEmpty()) Reply("Keine Titel um ${center.toInt()} BPM (oder noch nicht analysiert).")
                    else Reply("Titel um ${center.toInt()} BPM:\n" + rows.take(8).joinToString("\n") { "• ${it.track.title} (~${"%.0f".format(it.bpm)} BPM)" })
                }
                AnalysisQueryKind.SIMILAR -> {
                    val cur = tools.getCurrentTrack()
                        ?: return Reply("Kein aktueller Titel – spiel etwas ab.")
                    val sim = tools.findSimilarByUri(cur.uri, 10)
                    if (sim.isEmpty()) Reply("Noch zu wenig Analysedaten für Ähnlichkeit.")
                    else Reply("Ähnlich zu „${cur.title}“:\n" + sim.take(8).joinToString("\n") { "• ${it.title}" })
                }
                AnalysisQueryKind.REQUEST_ANALYSIS -> {
                    if (tools.requestLibraryAnalysis()) Reply("Analyse läuft im Hintergrund. Fortschritt: Menü → Audioanalyse.")
                    else Reply("Analyse konnte nicht gestartet werden.")
                }
                AnalysisQueryKind.UNCERTAIN -> {
                    val rows = tools.findTracksByFeatures(FeatureQuery(onlyUncertainGenre = true, limit = 12))
                    if (rows.isEmpty()) Reply("Keine offensichtlichen Genre-Lücken in der Stichprobe.")
                    else Reply("Ohne klares Genre-Tag u.a.:\n" + rows.take(8).joinToString("\n") { "• ${it.track.title}" })
                }
                AnalysisQueryKind.WHY_PLAYLIST ->
                    Reply("Empfehlungen nutzen BPM, Lautheit, Spektrum und Rhythmus soweit analysiert, plus deine Korrekturen. Unanalysierte Titel zählen schwächer.")
                AnalysisQueryKind.FEATURE_HELP -> {
                    val tip = when {
                        q.raw.contains("bpm", true) -> "BPM = Tempo. 85 kann Half-Time von ~170 sein – Konfidenz beachten."
                        q.raw.contains("lufs", true) -> "LUFS = integrierte Lautheit (lokal)."
                        q.raw.contains("hi", true) -> "Hi-Res-Float ist Session-Ausgabe; Bluetooth bleibt meist komprimiert."
                        else -> "Merkmale kommen aus lokaler Audioanalyse, nicht aus der Cloud."
                    }
                    Reply(tip)
                }
            }
        } catch (e: Exception) {
            Reply("Analyseabfrage fehlgeschlagen: ${e.message ?: "unbekannt"}")
        }
    }

}
