package com.mp.player

import com.mp.player.ai.ChatKind
import com.mp.player.ai.EqCommand
import com.mp.player.ai.EqKind
import com.mp.player.ai.EqPlanner
import com.mp.player.ai.EqSize
import com.mp.player.ai.IntentEngine
import com.mp.player.ai.LyricsMood
import com.mp.player.ai.LyricsProfile
import com.mp.player.ai.Mood
import com.mp.player.ai.QueueRequest
import com.mp.player.ai.Recommender
import com.mp.player.ai.TrackInfo
import com.mp.player.ai.UserIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTest {

    private fun parse(s: String) = IntentEngine.understand(s)

    private fun info(
        title: String, artist: String = "A", genre: String = "", folder: String = "",
        lufs: Float? = null, bpm: Float = 0f, durMin: Int = 3, last: Long? = null
    ): TrackInfo {
        val uri = "content://t/$title/$artist"
        val t = Track(
            uri = uri, title = title, artist = artist, albumArtist = artist, album = "Alb", year = 2020,
            durationMs = durMin * 60_000L, folder = folder, dateModified = 0L, genre = genre
        )
        val a = if (lufs != null || bpm > 0f) AnalysisResult(uri, 0L, bpm, lufs ?: Float.NaN, -1f, -20f, 44100, 2, false) else null
        return TrackInfo(t, a, last, 0, false)
    }

    // ---------------------------------------------------------------- Absichtserkennung

    @Test fun calmWithMinutes() {
        val i = parse("Mach mir 30 Minuten Musik zum Abschalten") as UserIntent.PlayMood
        assertEquals(Mood.CALM, i.mood)
        assertEquals(30, i.minutes)
    }

    @Test fun feelingBadGetsSupportNotJokes() {
        // Befinden mit Kontext: erst zuhoeren, NICHT automatisch Musik starten
        val i = parse("Fühl mich grad scheiße bro") as UserIntent.Emotion
        assertEquals(Mood.CALM, i.feel)
        // auch kurz: kein automatischer Start (Spec: "Mir geht's scheiße" startet keine Musik)
        assertTrue(parse("fühl mich scheiße") is UserIntent.Emotion)
        assertTrue(parse("Mir geht's scheiße") is UserIntent.Emotion)
    }

    @Test fun excludeSadSongs() {
        assertEquals(UserIntent.Exclude(setOf(Mood.SAD)), parse("Heute keine traurigen Songs."))
    }

    @Test fun aggressiveRequests() {
        assertEquals(Mood.AGGRESSIVE, (parse("Gib mir was Aggressives") as UserIntent.PlayMood).mood)
        assertEquals(Mood.AGGRESSIVE, (parse("Nee, ich brauch gerade eher Aggression") as UserIntent.PlayMood).mood)
    }

    @Test fun hardtekkSessionWithoutRepeats() {
        val i = parse("Mach mir eine Hardtekk-Session ohne Wiederholungen") as UserIntent.PlayMood
        assertEquals("Hardtekk", i.genreLabel)
        assertTrue(i.noRepeat)
        assertTrue(i.session)
        assertTrue(i.genres.contains("tekk"))
    }

    @Test fun unheardAndExplain() {
        assertTrue(parse("Spiel was, was ich lange nicht gehört habe") is UserIntent.PlayUnheard)
        assertEquals(UserIntent.ExplainSound("bass"), parse("Warum klingt das gerade so basslastig?"))
    }

    @Test fun confirmationsAndControls() {
        assertEquals(UserIntent.Confirm(true), parse("ja"))
        assertEquals(UserIntent.Confirm(false), parse("nee"))
        assertEquals(UserIntent.Pause, parse("pause"))
        assertEquals(UserIntent.Next, parse("nächster"))
        assertEquals(UserIntent.Resume, parse("play"))
    }

    @Test fun playWithNameIsASearchNotResume() {
        assertEquals(UserIntent.SearchPlay("bonez mc"), parse("play Bonez MC"))
    }

    @Test fun sleepTimer() {
        assertEquals(UserIntent.StartSleep(20), parse("Sleep-Timer 20 Minuten"))
    }

    @Test fun seriousStatementIsNeverTreatedAsMusicRequest() {
        assertEquals(UserIntent.Crisis, parse("ich will nicht mehr leben"))
    }

    @Test fun gibberishIsUnknown() {
        assertEquals(UserIntent.Unknown, parse("asdf qwertz"))
    }

    // ---------------------------------------------------------------- Kurzbefehle, Slang, EQ (Spezifikation)

    private fun eq(s: String): List<EqKind> = (parse(s) as UserIntent.EqChange).cmds.map { it.kind }

    @Test fun singleWordGenresAndMoods() {
        assertEquals("Rap", (parse("rap") as UserIntent.PlayMood).genreLabel)
        assertEquals("Techno", (parse("techno") as UserIntent.PlayMood).genreLabel)
        assertEquals("Hardtekk", (parse("hardtekk") as UserIntent.PlayMood).genreLabel)
        assertEquals("Deep", (parse("deep") as UserIntent.PlayMood).genreLabel)
        assertEquals(Mood.CALM, (parse("chill") as UserIntent.PlayMood).mood)
        assertEquals(Mood.PARTY, (parse("party") as UserIntent.PlayMood).mood)
    }

    @Test fun shortEmotionsStartMusicWithoutQuestions() {
        val sad = parse("sad") as UserIntent.PlayMood
        assertEquals(Mood.SAD, sad.mood); assertTrue(sad.support)
        assertEquals(Mood.SAD, (parse("traurig") as UserIntent.PlayMood).mood)
        val breakup = parse("freundin hat schluss gemacht") as UserIntent.Emotion
        assertEquals(Mood.SAD, breakup.feel)
        assertEquals("Beziehung", breakup.topic)
        assertEquals(Mood.SAD, (parse("herzschmerz") as UserIntent.PlayMood).mood)
        assertEquals(Mood.CALM, (parse("bin down") as UserIntent.Emotion).feel)
        assertEquals(Mood.CALM, (parse("bin komplett durch") as UserIntent.Emotion).feel)
        assertEquals(Mood.AGGRESSIVE, (parse("brauch Druck") as UserIntent.PlayMood).mood)
        assertEquals(Mood.ENERGETIC, (parse("zum wach werden") as UserIntent.PlayMood).mood)
        assertEquals(Mood.CALM, (parse("zum Abschalten") as UserIntent.PlayMood).mood)
    }

    @Test fun durationAloneIsAShortCommand() {
        assertEquals(UserIntent.Minutes(30), parse("30 min"))
        assertEquals(UserIntent.Minutes(60), parse("ne stunde"))
    }

    @Test fun contextShortcuts() {
        assertEquals(UserIntent.More, parse("noch bisschen"))
        assertEquals(UserIntent.More, parse("mehr"))
        assertEquals(UserIntent.Less, parse("bisschen weniger"))
        assertEquals(UserIntent.Surprise, parse("such du aus"))
        assertEquals(UserIntent.Surprise, parse("egal"))
        assertEquals(UserIntent.Surprise, parse("überrasch mich"))
        assertEquals(UserIntent.Surprise, parse("mach was"))
    }

    @Test fun eqCommands() {
        assertEquals(listOf(EqKind.BASS_UP), eq("mehr bass"))
        assertEquals(listOf(EqKind.BASS_UP), eq("bassiger"))
        assertEquals(listOf(EqKind.BASS_UP), eq("Bass bisschen tiefer"))
        assertEquals(listOf(EqKind.BASS_DOWN), eq("Bass runter"))
        assertEquals(listOf(EqKind.TREBLE_DOWN), eq("Höhen raus"))
        assertEquals(listOf(EqKind.TREBLE_DOWN), eq("zu scharf"))
        assertEquals(listOf(EqKind.WARM), eq("mach's wärmer"))
        assertEquals(listOf(EqKind.CLEAR), eq("klarer machen"))
        assertEquals(listOf(EqKind.RESET), eq("EQ zurücksetzen"))
        assertEquals(listOf(EqKind.BASS_DOWN, EqKind.TREBLE_UP), eq("bass runter höhen hoch"))
    }

    @Test fun eqSizeFollowsTheWording() {
        val small = (parse("bisschen mehr bass") as UserIntent.EqChange).cmds.first()
        val big = (parse("richtig viel mehr bass") as UserIntent.EqChange).cmds.first()
        assertEquals(EqSize.SMALL, small.size)
        assertEquals(EqSize.BIG, big.size)
    }

    @Test fun combinedCommandsDoQueueAndEqTogether() {
        val a = parse("30 Minuten chilliger Rap mit bisschen mehr Bass") as UserIntent.PlayMood
        assertEquals(Mood.CALM, a.mood); assertEquals("Rap", a.genreLabel); assertEquals(30, a.minutes)
        assertEquals(listOf(EqCommand(EqKind.BASS_UP, EqSize.SMALL)), a.eq)

        val b = parse("ruhiger Rap mit bisschen Bass") as UserIntent.PlayMood
        assertEquals(Mood.CALM, b.mood); assertEquals("Rap", b.genreLabel)
        assertEquals(listOf(EqKind.BASS_UP), b.eq.map { it.kind })

        val c = parse("Hardtekk aber nicht so scharf") as UserIntent.PlayMood
        assertEquals("Hardtekk", c.genreLabel)
        assertEquals(listOf(EqKind.TREBLE_DOWN), c.eq.map { it.kind })

        val d = parse("30 Minuten chilliger Rap") as UserIntent.PlayMood
        assertEquals(30, d.minutes); assertTrue(d.eq.isEmpty())
    }

    @Test fun typosAndSlangStillWork() {
        assertEquals(listOf(EqKind.BASS_UP), eq("mher bass"))
        assertEquals(listOf(EqKind.TREBLE_DOWN), eq("hohen raus"))
        assertEquals("Techno", (parse("tehcno") as UserIntent.PlayMood).genreLabel)
        assertEquals(Mood.SAD, (parse("traurik") as UserIntent.PlayMood).mood)
        assertEquals(Mood.CALM, (parse("chilig") as UserIntent.PlayMood).mood)
    }

    @Test fun whyQuestionsStayExplanationsNotEqCommands() {
        assertEquals(UserIntent.ExplainSound("bass"), parse("Warum klingt das so bassig?"))
    }

    @Test fun presetsAndSave() {
        assertEquals(UserIntent.EqPreset("Rock"), parse("eq preset rock"))
        assertEquals(UserIntent.EqPreset("Bass Boost"), parse("preset bass boost"))
        assertEquals(UserIntent.EqSave(null), parse("eq speichern"))
    }

    // ---------------------------------------------------------------- EQ-Planer

    @Test fun planUsesModerateSteps() {
        val p = EqPlanner.plan(Dsp(), listOf(EqCommand(EqKind.BASS_UP)))
        assertEquals(2.0f, p.bass!!, 0.001f)       // Regler +2 dB -> effektiv +3 dB (Schritte sind effektive dB)
        assertFalse(p.extreme)
        assertEquals(null, p.treble)
    }

    @Test fun planWarmLowersTrebleAndRaisesBass() {
        val p = EqPlanner.plan(Dsp(), listOf(EqCommand(EqKind.WARM)))
        assertTrue(p.bass!! > 0f); assertTrue(p.treble!! < 0f)
    }

    @Test fun planClearAddsPresenceBand() {
        val p = EqPlanner.plan(Dsp(), listOf(EqCommand(EqKind.CLEAR)))
        assertTrue(p.treble!! > 0f)
        assertEquals(1, p.bands.size)
        assertTrue(p.bands.first().first in 2500f..3500f); assertTrue(p.bands.first().second > 0f)
    }

    @Test fun extremeValuesAskFirstButLoweringNever() {
        val loud = Dsp(bass = 5f)
        assertTrue(EqPlanner.plan(loud, listOf(EqCommand(EqKind.BASS_UP, EqSize.BIG))).extreme)
        assertFalse(EqPlanner.plan(loud, listOf(EqCommand(EqKind.BASS_DOWN))).extreme)
    }

    @Test fun resetPlan() {
        assertTrue(EqPlanner.plan(Dsp(bass = 6f), listOf(EqCommand(EqKind.RESET))).reset)
    }

    // ---------------------------------------------------------------- Recommender

    @Test fun aggressivePrefersLoudHardTracks() {
        val loud = info("Rage", genre = "Hardtekk", lufs = -8f, bpm = 170f)
        val quiet = info("Sleepy", artist = "B", genre = "Piano", lufs = -22f, bpm = 70f)
        val r = Recommender.build(listOf(quiet, loud), QueueRequest(Mood.AGGRESSIVE, maxTracks = 1, seed = 1L))
        assertEquals("Rage", r.tracks.first().title)
    }

    @Test fun calmPrefersQuietTracks() {
        val loud = info("Rage", genre = "Hardtekk", lufs = -8f, bpm = 170f)
        val quiet = info("Sleepy", artist = "B", genre = "Piano", lufs = -22f, bpm = 70f)
        val r = Recommender.build(listOf(loud, quiet), QueueRequest(Mood.CALM, maxTracks = 1, seed = 1L))
        assertEquals("Sleepy", r.tracks.first().title)
    }

    @Test fun excludedMoodIsFilteredOut() {
        val sad = info("Tears Of Pain", artist = "C", genre = "Sad", lufs = -20f)
        val other = info("Fun Day", artist = "D", genre = "Pop", lufs = -12f, bpm = 120f)
        val r = Recommender.build(listOf(sad, other), QueueRequest(null, exclude = setOf(Mood.SAD), maxTracks = 10, seed = 1L))
        assertEquals(listOf("Fun Day"), r.tracks.map { it.title })
    }

    @Test fun timeTargetIsReachedWithoutOvershootingMuch() {
        val many = (1..20).map { info("T$it", artist = "Art$it", lufs = -14f, bpm = 100f) }
        val r = Recommender.build(many, QueueRequest(null, minutes = 10, seed = 3L))
        assertTrue(r.totalMs >= 10 * 60_000L)
        assertTrue(r.tracks.size <= 5)
    }

    @Test fun neverDuplicatesTheSameRecording() {
        val a = info("Same Song", artist = "X", lufs = -10f).copy(track = info("Same Song", artist = "X", lufs = -10f).track.copy(uri = "content://a"))
        val b = info("Same Song", artist = "X", lufs = -10f).copy(track = info("Same Song", artist = "X", lufs = -10f).track.copy(uri = "content://b"))
        val r = Recommender.build(listOf(a, b), QueueRequest(null, maxTracks = 10, seed = 1L))
        assertEquals(1, r.tracks.size)
    }

    @Test fun missingGenreIsReportedInsteadOfFaking() {
        val r = Recommender.build(listOf(info("Pop Song", genre = "Pop")), QueueRequest(null, genres = listOf("hardtek", "tekk")))
        assertTrue(r.genreNotFound)
        assertTrue(r.tracks.isEmpty())
    }

    @Test fun unheardPrefersNeverPlayedTracks() {
        val now = 10_000_000_000L
        val played = info("Played", artist = "P", lufs = -12f, last = now - 1_000L)
        val never = info("Never", artist = "N", lufs = -12f)
        val r = Recommender.build(listOf(played, never), QueueRequest(null, preferUnheard = true, maxTracks = 1, seed = 1L), now)
        assertEquals("Never", r.tracks.first().title)
    }

    @Test fun noAnalysisStillWorksViaKeywords() {
        val tekk = info("Track1", genre = "Hardtekk")
        val piano = info("Track2", artist = "B", genre = "Piano")
        val r = Recommender.build(listOf(piano, tekk), QueueRequest(Mood.AGGRESSIVE, maxTracks = 1, seed = 1L))
        assertEquals("Track1", r.tracks.first().title)
        assertFalse(r.analysisCoverage > 0f)
    }

    @Test fun oftenSkippedTracksComeLater() {
        val liked = info("Liked", artist = "L", lufs = -12f)
        val skipped = info("Skipped", artist = "S", lufs = -12f).copy(playCount = 5, skipCount = 4)
        val r = Recommender.build(listOf(skipped, liked), QueueRequest(null, maxTracks = 1, seed = 1L))
        assertEquals("Liked", r.tracks.first().title)
    }

    @Test fun singleSkipIsOnlyAMildSignal() {
        val one = info("One", lufs = -12f).copy(playCount = 10, skipCount = 1)
        assertTrue(Recommender.skipPenalty(one, 0L) < 0.05f)
        assertEquals(0f, Recommender.skipPenalty(info("None"), 0L), 0f)
    }

    // ---------------------------------------------------------------- Neue Gefuehlslagen

    @Test fun newFeelingsMapToTheirOwnMoods() {
        assertEquals(Mood.ANGRY, (parse("ich bin wütend") as UserIntent.PlayMood).mood)
        assertEquals(Mood.ANGRY, (parse("bin sauer") as UserIntent.PlayMood).mood)
        assertEquals(Mood.ROMANTIC, (parse("mach was romantisches") as UserIntent.PlayMood).mood)
        assertEquals(Mood.NOSTALGIC, (parse("was nostalgisches bitte") as UserIntent.PlayMood).mood)
        assertEquals(Mood.DARK, (parse("was düsteres") as UserIntent.PlayMood).mood)
        assertEquals(Mood.MOTIVATED, (parse("bin motiviert") as UserIntent.PlayMood).mood)
        assertEquals(Mood.LONELY, (parse("bin einsam") as UserIntent.Emotion).feel)
        // harter Klang bleibt getrennt von Wut
        assertEquals(Mood.AGGRESSIVE, (parse("brauch Druck") as UserIntent.PlayMood).mood)
    }

    @Test fun newMoodsCanBeExcluded() {
        val p = parse("keine wütenden Songs bitte")
        assertTrue((p is UserIntent.Exclude && Mood.ANGRY in p.moods) || (p is UserIntent.PlayMood && Mood.ANGRY in p.exclude))
    }

    // ---------------------------------------------------------------- Songtext-Stimmung

    private val sadText = "Ich weine jede Nacht und der Schmerz bleibt, ich vermisse dich so sehr, Tränen auf dem Kissen, " +
        "dein Abschied hat mich gebrochen, ich bin traurig und alles ist verloren"
    private val angryText = "Ich hasse dich und die Wut im Bauch brennt, verdammt, ich raste aus, Rache ist alles was ich will, " +
        "du Arschloch, ich hasse jedes Wort von dir, diese Wut lässt mich schreien"
    private val neutralText = "Heute kaufe ich Brot und Milch im Supermarkt und gehe danach nach Hause um etwas zu essen und fernzusehen"

    @Test fun lyricsMoodDetectsSadnessAndAnger() {
        val sad = LyricsMood.analyze(sadText)!!
        assertTrue((sad.scores[Mood.SAD] ?: 0f) > 0.5f)
        assertEquals(Mood.SAD, sad.top(1).first())
        val angry = LyricsMood.analyze(angryText)!!
        assertTrue((angry.scores[Mood.ANGRY] ?: 0f) > 0.5f)
        assertEquals(Mood.ANGRY, angry.top(1).first())
    }

    @Test fun lyricsMoodHandlesNeutralShortAndNegated() {
        assertTrue(LyricsMood.analyze(neutralText)!!.scores.isEmpty())
        assertEquals(null, LyricsMood.analyze("zu kurz"))
        val negated = LyricsMood.analyze("ich bin nicht traurig und nicht verloren, heute kaufe ich Brot und Milch im Laden und gehe danach nach Hause")!!
        assertTrue((negated.scores[Mood.SAD] ?: 0f) < 0.1f)
    }

    @Test fun lyricsProfileSurvivesStorage() {
        val p = LyricsMood.analyze(sadText)!!
        val back = LyricsProfile.decode(p.encode())
        assertEquals(p.scores.keys, back.keys)
        assertEquals(p.scores.getValue(Mood.SAD), back.getValue(Mood.SAD), 0.01f)
        assertTrue(LyricsProfile.decode("quatsch;SAD=x;GIBTSNICHT=0.5").isEmpty())
    }

    @Test fun lyricsRaiseMatchingTracksAndLowerOthers() {
        val angryTrack = info("Wut", artist = "W", lufs = -12f).copy(hasLyrics = true, lyricMood = mapOf(Mood.ANGRY to 0.9f))
        val plain = info("Plain", artist = "P", lufs = -12f)
        val happyTrack = info("Fröhlich", artist = "H", lufs = -12f).copy(hasLyrics = true, lyricMood = mapOf(Mood.HAPPY to 0.9f))
        val r = Recommender.build(listOf(plain, happyTrack, angryTrack), QueueRequest(Mood.ANGRY, maxTracks = 1, seed = 1L))
        assertEquals("Wut", r.tracks.first().title)
        assertTrue(Recommender.moodScore(angryTrack, Mood.CALM) < Recommender.moodScore(plain, Mood.CALM))
        assertEquals(2f / 3f, r.lyricsCoverage, 0.01f)
    }

    @Test fun clearlySadLyricsCountAsSadForExclusion() {
        val sadLyrics = info("Tränen", lufs = -10f).copy(hasLyrics = true, lyricMood = mapOf(Mood.SAD to 0.9f))
        assertTrue(Recommender.looksLike(sadLyrics, Mood.SAD))
        assertTrue(!Recommender.looksLike(info("Neutral", lufs = -10f), Mood.SAD))
    }

    // ---------------------------------------------------------------- Eingebettete Songtexte lesen

    private fun be32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    private fun le32(n: Int) = byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte())
    private fun syncsafe(n: Int) = byteArrayOf(((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte())

    private fun id3WithUslt(text: String, utf16: Boolean): ByteArray {
        val textBytes = if (utf16) byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE) else text.toByteArray(Charsets.UTF_8)
        val desc = if (utf16) byteArrayOf(0, 0) else byteArrayOf(0)
        val body = byteArrayOf((if (utf16) 1 else 3).toByte()) + "deu".toByteArray() + desc + textBytes
        val frame = "USLT".toByteArray() + be32(body.size) + byteArrayOf(0, 0) + body
        return "ID3".toByteArray() + byteArrayOf(3, 0, 0) + syncsafe(frame.size) + frame + ByteArray(32)
    }

    @Test fun readsId3UnsyncedLyricsUtf8AndUtf16() {
        assertEquals(sadText, LyricsParser.parseBytes(id3WithUslt(sadText, utf16 = false)))
        assertEquals(sadText, LyricsParser.parseBytes(id3WithUslt(sadText, utf16 = true)))
    }

    @Test fun readsFlacVorbisCommentLyrics() {
        val vendor = "test".toByteArray()
        val c1 = "TITLE=Song".toByteArray()
        val c2 = ("UNSYNCEDLYRICS=" + angryText).toByteArray(Charsets.UTF_8)
        val body = le32(vendor.size) + vendor + le32(2) + le32(c1.size) + c1 + le32(c2.size) + c2
        val header = byteArrayOf(0x84.toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte())
        assertEquals(angryText, LyricsParser.parseBytes("fLaC".toByteArray() + header + body))
    }

    @Test fun stripsTimestampsFromSyncedLyrics() {
        val synced = "[ar:Band]\n[00:12.30]Ich weine jede Nacht\n[00:15.80]der Schmerz bleibt bei mir"
        val out = LyricsParser.parseBytes(id3WithUslt(synced, utf16 = false))!!
        assertTrue(!out.contains("[00:") && !out.contains("[ar:"))
        assertTrue(out.contains("Ich weine jede Nacht"))
    }

    @Test fun missingOrBrokenLyricsGiveNullNotCrash() {
        assertEquals(null, LyricsParser.parseBytes(ByteArray(0)))
        assertEquals(null, LyricsParser.parseBytes("kein audio".toByteArray()))
        assertEquals(null, LyricsParser.parseBytes("ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, 0, 5, 1, 2)))
        assertEquals(null, LyricsParser.parseBytes("fLaC".toByteArray() + byteArrayOf(0x84.toByte(), 0, 0, 2, 1, 2)))
    }

    // ---------------------------------------------------------------- Offline-Garantie

    @Test fun appHasNoInternetPermission() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertFalse("Die App darf keine INTERNET-Berechtigung haben", manifest.contains("android.permission.INTERNET"))
    }

    // ---------------------------------------------------------------- Interpret-Suche

    @Test fun artistSearchIntent() {
        for (q in listOf("Such interpret hetzer", "such Interpret HEtZEr", "suche den interpreten hetzer", "mach mal alles von Hetzer an")) {
            assertEquals(q, UserIntent.SearchArtist("hetzer"), parse(q))
        }
    }

    @Test fun artistSearchMatchesArtistFieldCaseInsensitive() {
        fun tr(title: String, artist: String) = Track(
            uri = "u/$title/$artist", title = title, artist = artist, albumArtist = "", album = "", year = 0,
            durationMs = 1000, folder = "", dateModified = 0L
        )
        val lib = listOf(tr("Chill mix", "HeTzEr"), tr("i LOVE HARDTEKK", "HEtZEr"), tr("tekktestlove", "x"), tr("Blokktekk", "Unbekannter Interpret"))
        val hits = com.mp.player.ai.ArtistSearch.find(lib, "hetzer")
        assertEquals(2, hits.size)
        assertTrue(hits.none { it.title == "tekktestlove" })
    }

    // ---------------------------------------------------------------- Gespraech & Gedaechtnis

    @Test fun ventingIsEmotionNotPlayback() {
        val e = parse("Arbeit hat mich komplett fertig gemacht") as UserIntent.Emotion
        assertEquals("Arbeit", e.topic)
        assertTrue(parse("Bro, heute war richtig scheiße") is UserIntent.Emotion)
    }

    @Test fun smalltalkIsNotAMusicCommand() {
        assertEquals(UserIntent.Chat(ChatKind.AFFECTION), parse("Hab dich lieb"))
        assertEquals(UserIntent.Chat(ChatKind.HOW_ARE_YOU), parse("wie gehts dir"))
        assertEquals(UserIntent.Recommend, parse("Was würdest du jetzt hören?"))
    }

    @Test fun memoryIntents() {
        assertTrue(parse("Ich mag Hardtekk") is UserIntent.Remember)
        assertTrue(parse("Ich mag eigentlich viel Bass") is UserIntent.Remember) // kein EQ-Befehl
        assertTrue(parse("Heute aber keinen Hardtekk") is UserIntent.Remember)
        assertEquals(UserIntent.Forget("hardtekk"), parse("Vergiss, dass ich Hardtekk mag"))
        assertEquals(UserIntent.Forget(null), parse("Vergiss meine Musikpräferenzen"))
        assertEquals(UserIntent.WhatDoYouKnow, parse("Was weißt du über meinen Musikgeschmack?"))
        assertEquals(UserIntent.WhatDoYouKnow, parse("Was hast du dir über mich gemerkt?"))
        assertTrue(parse("Merk dir, dass ich sowas zum Abschalten mag") is UserIntent.Remember)
    }

    @Test fun moodExclusionStaysSessionOnly() {
        // "keine traurigen Songs" ist ein Sitzungs-Ausschluss, kein Gedaechtniseintrag
        assertEquals(UserIntent.Exclude(setOf(Mood.SAD)), parse("Aber nicht komplett traurig"))
    }

    @Test fun avoidTermsDropTracksFromQueue() {
        val infos = listOf(info("A", genre = "Hardtekk"), info("B", genre = "Rap"), info("C", genre = "Rap", artist = "X"))
        val r = Recommender.build(infos, QueueRequest(mood = null, avoidTerms = IntentEngine.termsFor("hardtekk")))
        assertTrue(r.tracks.none { it.title == "A" })
        assertEquals(2, r.tracks.size)
    }

    // ---------------------------------------------------------------- EQ-Regression (Spec 74): Mitten, Luft, "tiefer"

    private fun eqCmds(s: String) = (parse(s) as UserIntent.EqChange).cmds

    @Test fun midsAndBetweenPhrase() {
        assertEquals(listOf(EqKind.MID_DOWN), eqCmds("Die Mitte tiefer").map { it.kind })
        assertEquals(listOf(EqKind.MID_DOWN), eqCmds("Mitte runter").map { it.kind })
        // Screenshot-Fall inkl. Tippfehler "mbass"
        val c = eqCmds("Nein, Höhen bisschen höher und zwischen mbass und Höhen tiefer")
        assertEquals(listOf(EqKind.TREBLE_UP, EqKind.MID_DOWN), c.map { it.kind })
        assertEquals(EqSize.SMALL, c[0].size)
    }

    @Test fun trebleUpIsSmallNotEight() {
        val c = eqCmds("Höhen bisschen höher")
        assertEquals(listOf(EqCommand(EqKind.TREBLE_UP, EqSize.SMALL)), c)
        val p = EqPlanner.plan(Dsp(), c)
        assertEquals(1.5f, p.treble!!, 0.001f)
        assertFalse(p.extreme)
    }

    @Test fun threeInstructionsInOneSentence() {
        val a = eqCmds("unten mehr, Mitte weg, oben nur bisschen Luft")
        assertEquals(listOf(EqKind.BASS_UP, EqKind.MID_DOWN, EqKind.AIR_UP), a.map { it.kind })
        assertEquals(EqSize.BIG, a[1].size)
        assertEquals(EqSize.SMALL, a[2].size)
        val b = eqCmds("bisschen mehr bass, und mitten tiefer und nur minimal höhen")
        assertEquals(
            listOf(EqCommand(EqKind.BASS_UP, EqSize.SMALL), EqCommand(EqKind.MID_DOWN, EqSize.NORMAL), EqCommand(EqKind.TREBLE_UP, EqSize.TINY)),
            b
        )
    }

    @Test fun bassTiefgangIsStillBassUp() {
        assertEquals(listOf(EqKind.BASS_UP), eqCmds("Bass bisschen tiefer").map { it.kind })
    }

    @Test fun plainWordsAreNotEq() {
        // "oben"/"Mitte" ohne Richtungswort sind keine Befehle
        assertFalse(parse("ich bin oben") is UserIntent.EqChange)
        assertFalse(parse("in der Mitte") is UserIntent.EqChange)
    }

    @Test fun slightBassIsRelativeToTheCurrentState() {
        // Bass-Regler +4 (effektiv +6 dB), "bisschen mehr Bass" -> effektiv +1,5 dB, NICHT auf einen festen Wert
        val s = Dsp(bass = 4f)
        val p = EqPlanner.plan(s, listOf(EqCommand(EqKind.BASS_UP, EqSize.SMALL)))
        val after = s.copy(bass = p.bass!!)
        assertEquals(1.5f, Eq.bassGain(after) - Eq.bassGain(s), 0.01f)
        // und nochmal relativ
        val p2 = EqPlanner.plan(after, listOf(EqCommand(EqKind.BASS_UP, EqSize.SMALL)))
        assertEquals(1.5f, Eq.bassGain(after.copy(bass = p2.bass!!)) - Eq.bassGain(after), 0.01f)
    }

    @Test fun midsAndAirPlansTouchOnlyTheirBands() {
        val mids = EqPlanner.plan(Dsp(), listOf(EqCommand(EqKind.MID_DOWN, EqSize.BIG)))
        assertTrue(mids.bands.isNotEmpty())
        assertTrue(mids.bands.all { it.first in 200f..2500f && it.second < 0f })
        assertEquals(null, mids.bass); assertEquals(null, mids.treble)
        assertFalse(mids.extreme)
        val air = EqPlanner.plan(Dsp(), listOf(EqCommand(EqKind.AIR_UP, EqSize.SMALL)))
        assertTrue(air.bands.all { it.first >= 10_000f && it.second > 0f })
        assertTrue(air.summary.contains("Luft"))
    }

    @Test fun reverseOfMidsAndAir() {
        assertEquals(EqKind.MID_UP, EqPlanner.reverse(EqCommand(EqKind.MID_DOWN))!!.kind)
        assertEquals(EqKind.AIR_DOWN, EqPlanner.reverse(EqCommand(EqKind.AIR_UP))!!.kind)
    }

    @Test fun eqSentencesNeverNeedMoodGenreMinutes() {
        for (s in listOf("Mitte tiefer", "unten mehr", "Höhen bisschen höher", "nicht so schrill", "oben mehr Luft", "Mitte weg")) {
            assertTrue(s, parse(s) is UserIntent.EqChange)
        }
    }

    // ---------------------------------------------------------------- Lautstaerke (Spec 75)

    @Test fun volumeRelativeAndAbsolute() {
        assertEquals(UserIntent.Volume(10, null), parse("lauter"))
        assertEquals(UserIntent.Volume(10, null), parse("mach lauter"))
        assertEquals(UserIntent.Volume(-10, null), parse("leiser"))
        assertEquals(UserIntent.Volume(-5, null), parse("etwas leiser"))
        assertEquals(UserIntent.Volume(20, null), parse("viel lauter"))
        assertEquals(UserIntent.Volume(null, 10), parse("Lautstärke auf 10"))
        assertEquals(UserIntent.Volume(null, 100), parse("lautstärke auf 150"))
    }

    @Test fun bassLouderIsEqNotVolume() {
        assertTrue(parse("bass lauter") is UserIntent.EqChange)
        assertTrue(parse("warum ist das so laut") !is UserIntent.Volume)
    }

    @Test fun unknownIsNaturalNotAnError() {
        val p = com.mp.player.ai.Persona(kotlin.random.Random(1))
        repeat(20) {
            val u = p.unknown()
            assertFalse(u.contains("komm ich nicht mit")); assertFalse(u.contains("Kapier ich grad nicht"))
            assertTrue(u.contains("Musik") || u.contains("quatschen") || u.contains("reden"))
        }
    }
}
