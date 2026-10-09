package com.mp.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import android.view.KeyEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Der einzige Besitzer des Players. Die UI verbindet sich nur per MediaController.
 * Hier laufen: Wiedergabe, Queue, Repeat/Shuffle, Headset-Tasten, Fehlerbehandlung und die
 * crash-sichere Speicherung/Wiederherstellung des Zustands. Media3 zeigt daraus die
 * Benachrichtigung (Titel, Interpret, Cover, Play/Pause/Next/Prev).
 */
class PlayerService : MediaSessionService() {

    companion object {
        private const val TAG = "PlayerService"
        private const val SKIP_MAX_LISTEN_MS = 45_000L
        private const val CLICK_WINDOW_MS = 350L
        private const val DOUBLE_PREV_MS = 800L
        private const val MAX_CONSECUTIVE_ERRORS = 8

        /** Prozessweit (UI und Service laufen im selben Prozess): fuer den SleepTimer. */
        @Volatile var playerRef: ExoPlayer? = null

        /** Wird von den Einstellungen aufgerufen, damit eine geaenderte ReplayGain-Einstellung sofort wirkt. */
        @Volatile var refreshReplayGain: (() -> Unit)? = null
    }

    private var session: MediaSession? = null
    private var player: ExoPlayer? = null
    private lateinit var store: PlaybackStore
    private val main = Handler(Looper.getMainLooper())

    /** Solange true, wird nichts gespeichert (sonst ueberschreibt der leere Start-Zustand den gespeicherten Stand). */
    private var restoring = true
    private var consecutiveErrors = 0
    private var headsetClicks = 0
    private var lastPrevPressAt = 0L
    private val shuffleRound = HashSet<String>()

    private val saveQueueRunnable = Runnable { saveQueueNow() }
    private val clickRunnable = Runnable { dispatchHeadsetClicks() }
    private val ticker = object : Runnable {
        override fun run() {
            if (player?.isPlaying == true) saveState(false)
            main.postDelayed(this, 5000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val st = Store(this)
        Engine.proc.cfg = st.dsp()
        // true = DSP gibt 32-Bit-Float aus (Hi-Res), false = 16 Bit. Faellt der Audiopfad damit aus,
        // stellt handleError() automatisch auf 16 Bit zurueck.
        Engine.proc.floatOutput = st.hires
        SleepTimer.fadeSeconds = st.sleepFadeSec
        refreshReplayGain = { Handler(Looper.getMainLooper()).post { updateReplayGain(true) } }
        store = PlaybackStore(this)

        val rf = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf<AudioProcessor>(Engine.proc))
                // Bewusst aus: Mit Float-Ausgabe im Sink wuerden die Audio-Prozessoren (Equalizer/DSP)
                // uebergangen. Die Float-Ausgabe uebernimmt stattdessen die DSP selbst (Engine.proc.floatOutput).
                .setEnableFloatOutput(false)
                .build()
        }
        val p = ExoPlayer.Builder(this, rf)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            // haelt die CPU waehrend der Wiedergabe wach (Bildschirm aus / andere App im Vordergrund)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player = p
        playerRef = p
        p.addListener(listener)
        session = MediaSession.Builder(this, p).setCallback(sessionCallback).build()

        restoreAsync()
        main.postDelayed(ticker, 5000)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // ------------------------------------------------------------------
    // Headset / Bluetooth: 1x = Play/Pause, 2x = Naechster, 3x = Vorheriger
    // ------------------------------------------------------------------

    private val sessionCallback = object : MediaSession.Callback {
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            if (intent.action != Intent.ACTION_MEDIA_BUTTON) return false
            @Suppress("DEPRECATION")
            val ke: KeyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
            return when (ke.keyCode) {
                KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (ke.action == KeyEvent.ACTION_DOWN && ke.repeatCount == 0) {
                        headsetClicks++
                        main.removeCallbacks(clickRunnable)
                        main.postDelayed(clickRunnable, CLICK_WINDOW_MS)
                    }
                    true // Event verarbeitet (auch ACTION_UP), damit Media3 nicht zusaetzlich reagiert
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (ke.action == KeyEvent.ACTION_DOWN && ke.repeatCount == 0) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        val doublePress = now - lastPrevPressAt < DOUBLE_PREV_MS
                        lastPrevPressAt = now
                        if (doublePress) {
                            // 1. Druck sprang zum Titelanfang -> 2. Druck direkt zum vorherigen Titel
                            player?.seekToPreviousMediaItem()
                            return true
                        }
                    }
                    false // einfacher Druck: Standardverhalten (>3 s = Titelanfang, sonst vorheriger Titel)
                }
                else -> false // NEXT/PLAY/PAUSE/STOP: Standardverarbeitung ueber die MediaSession
            }
        }
    }

    private fun dispatchHeadsetClicks() {
        val n = headsetClicks
        headsetClicks = 0
        val p = player ?: return
        try {
            when {
                n <= 0 -> Unit
                n == 1 -> togglePlayPause(p)
                n == 2 -> p.seekToNext()
                else -> p.seekToPrevious()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Headset-Aktion fehlgeschlagen", t)
        }
    }

    private fun togglePlayPause(p: ExoPlayer) {
        if (p.mediaItemCount == 0) return
        if (p.isPlaying) { p.pause(); return }
        when (p.playbackState) {
            Player.STATE_IDLE -> p.prepare()
            Player.STATE_ENDED -> p.seekToDefaultPosition(0)
        }
        p.play()
    }

    // ------------------------------------------------------------------
    // Player-Ereignisse
    // ------------------------------------------------------------------

    /**
     * ReplayGain-Tags lesen (ID3 TXXX / Vorbis-Kommentar / MP4) bzw. Analysewert nutzen und in die DSP geben.
     * Alles in try/catch: ein unerwartetes Tag-Format darf die Wiedergabe nie beeintraechtigen.
     */
    private fun updateReplayGain(useTags: Boolean) {
        val p = player ?: return
        try {
            val st = Store(this)
            val mode = st.rgMode
            if (mode == 0) { Engine.proc.replayGainDb = 0f; return }
            var trackTag: Float? = null
            var albumTag: Float? = null
            if (useTags) {
                for (g in p.currentTracks.groups) {
                    if (g.type != C.TRACK_TYPE_AUDIO) continue
                    for (i in 0 until g.length) {
                        val md = g.getTrackFormat(i).metadata ?: continue
                        for (e in 0 until md.length()) {
                            val parsed = ReplayGain.parseTag(md.get(e).toString()) ?: continue
                            if (parsed.first == 0) { if (trackTag == null) trackTag = parsed.second }
                            else { if (albumTag == null) albumTag = parsed.second }
                        }
                    }
                }
            }
            val uri = p.currentMediaItem?.mediaId
            val analysis = if (uri != null && st.rgFallback) LibraryDb.get(this).analysisFor(uri) else null
            Engine.proc.replayGainDb = ReplayGain.resolve(mode, trackTag, albumTag, analysis, st.rgFallback)
        } catch (e: Exception) {
            Engine.proc.replayGainDb = 0f
        }
    }

    // Hoerverlauf (SQLite, Tabelle play_history). Pause/Fortsetzen desselben Titels zaehlt nicht doppelt.
    private var lastHistoryId: String? = null

    private fun noteHistoryStart(item: MediaItem?) {
        val id = item?.mediaId?.takeIf { it.isNotEmpty() } ?: return
        if (id == lastHistoryId) return
        lastHistoryId = id
        try { LibraryDb.get(this).recordPlayAsync(id) } catch (e: Exception) { /* Verlauf ist optional */ }
    }

    /**
     * Skip = der Nutzer wechselt per Weiter-/Zurueck-Taste oder Antippen zu einem anderen Titel, obwohl der alte
     * noch keine 45 s lief. Natuerliches Ende (AUTO_TRANSITION), Queue-Austausch (REMOVE/INTERNAL) und Fehler zaehlen nicht.
     */
    private fun noteSkipIfAny(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
        if (reason != Player.DISCONTINUITY_REASON_SKIP && reason != Player.DISCONTINUITY_REASON_SEEK) return
        if (old.mediaItemIndex == new.mediaItemIndex) return
        val id = old.mediaItem?.mediaId?.takeIf { it.isNotEmpty() } ?: return
        if (old.positionMs !in 0 until SKIP_MAX_LISTEN_MS) return
        try { LibraryDb.get(this).recordSkipAsync(id) } catch (e: Exception) { /* optional */ }
    }

    private val listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) { scheduleQueueSave() }

        override fun onTracksChanged(tracks: Tracks) { updateReplayGain(true) }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Hoerverlauf: neuer Titel laeuft -> sofort eintragen; sonst beim naechsten Play (onIsPlayingChanged)
            lastHistoryId = null
            if (player?.isPlaying == true) noteHistoryStart(mediaItem)
            updateReplayGain(false) // Tags des neuen Titels kommen gleich in onTracksChanged; bis dahin nur Analysewert
            // Hi-Res-Wunsch aus Store erneut anwenden (Titelwechsel darf Präferenz nicht „schlucken“)
            applyHiResPreference()
            if (player?.shuffleModeEnabled == true) handleShuffleTransition(mediaItem, reason)
            saveState(false)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) consecutiveErrors = 0
            saveState(false)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                SleepTimer.onPausedAtEndOfItem()
            }
            saveState(false)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) noteHistoryStart(player?.currentMediaItem)
            saveState(false)
        }
        override fun onRepeatModeChanged(repeatMode: Int) { saveState(false) }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (shuffleModeEnabled) applyFreshShuffleOrder() else shuffleRound.clear()
            saveState(false)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            noteSkipIfAny(oldPosition, newPosition, reason)
            saveState(false)
        }

        override fun onPlayerError(error: PlaybackException) { handleError(error) }
    }

    // ------------------------------------------------------------------
    // Shuffle: echte Zufallsreihenfolge (Permutation), aktueller Titel zuerst, jede Runde neu
    // ------------------------------------------------------------------

    private fun applyFreshShuffleOrder() {
        val p = player ?: return
        val n = p.mediaItemCount
        if (n == 0) return
        try {
            val cur = p.currentMediaItemIndex.coerceIn(0, n - 1)
            val rest = (0 until n).filter { it != cur }.shuffled()
            p.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(intArrayOf(cur) + rest.toIntArray(), System.nanoTime()))
            shuffleRound.clear()
            p.currentMediaItem?.mediaId?.let { shuffleRound.add(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "Shuffle-Reihenfolge konnte nicht gesetzt werden", t)
        }
    }

    private fun handleShuffleTransition(item: MediaItem?, reason: Int) {
        val p = player ?: return
        val id = item?.mediaId ?: return
        when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> applyFreshShuffleOrder()
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> {
                if (shuffleRound.size >= p.mediaItemCount && shuffleRound.contains(id)) {
                    // alle Titel dieser Runde gespielt (Wiederholung "Alle") -> neue zufaellige Runde
                    applyFreshShuffleOrder()
                } else {
                    shuffleRound.add(id)
                }
            }
            else -> Unit // REPEAT (Einzelwiederholung): Runde unveraendert
        }
    }

    // ------------------------------------------------------------------
    // Fehlerbehandlung: ein kaputter Titel darf weder abstuerzen noch die Wiedergabe stoppen
    // ------------------------------------------------------------------

    /** Liest die gespeicherte Hi-Res-Präferenz und setzt die DSP-Ausgabe entsprechend. */
    private fun applyHiResPreference() {
        try {
            val want = Store(this).hires
            if (Engine.proc.floatOutput != want) {
                Engine.proc.floatOutput = want
                // onConfigure der DSP greift floatOutput beim nächsten Format-Wechsel
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Hi-Res-Präferenz nicht anwendbar", e)
        }
    }

    private fun handleError(error: PlaybackException) {
        Log.w(TAG, "Player-Fehler ${error.errorCodeName}", error)
        val p = player ?: return
        try {
            val n = p.mediaItemCount
            if (n == 0) return
            if (++consecutiveErrors > MAX_CONSECUTIVE_ERRORS) {
                p.pause() // Endlosschleife bei lauter kaputten Titeln verhindern
                return
            }
            val sinkError = error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
            if (sinkError) {
                if (Engine.proc.floatOutput) {
                    // Nur Session-Fallback auf 16 Bit – Nutzer-Präferenz (Store.hires) bleibt erhalten
                    // und wird beim nächsten Titelwechsel erneut versucht.
                    Engine.proc.floatOutput = false
                    main.post {
                        Toast.makeText(
                            this,
                            "Hi-Res-Float für diesen Titel/Ausgang nicht möglich – vorübergehend 16 Bit. Präferenz bleibt aktiv.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                // Ausgabegeraet gewechselt/nicht bereit: denselben Titel erneut versuchen
                p.prepare()
                return
            }
            // Datei geloescht/nicht zugreifbar/defekt: nur aus der Queue nehmen (Datei bleibt unangetastet)
            val idx = p.currentMediaItemIndex
            if (idx in 0 until n) p.removeMediaItem(idx)
            if (p.mediaItemCount > 0) p.prepare()
        } catch (t: Throwable) {
            Log.w(TAG, "Fehlerbehandlung fehlgeschlagen", t)
        }
    }

    // ------------------------------------------------------------------
    // Speichern / Wiederherstellen
    // ------------------------------------------------------------------

    private fun scheduleQueueSave() {
        if (restoring) return
        main.removeCallbacks(saveQueueRunnable)
        main.postDelayed(saveQueueRunnable, 400)
    }

    private fun saveQueueNow() {
        val p = player ?: return
        if (restoring) return
        try {
            val items = (0 until p.mediaItemCount).map { MediaItems.toSaved(p.getMediaItemAt(it)) }
            store.saveQueueAsync(items)
            saveState(false)
        } catch (t: Throwable) {
            Log.w(TAG, "Queue speichern fehlgeschlagen", t)
        }
    }

    private fun saveState(blocking: Boolean) {
        val p = player ?: return
        if (restoring) return
        try {
            val ended = p.playbackState == Player.STATE_ENDED
            val s = SavedState(
                mediaId = p.currentMediaItem?.mediaId,
                index = p.currentMediaItemIndex.coerceAtLeast(0),
                positionMs = if (ended) 0L else p.currentPosition.coerceAtLeast(0L),
                wasPlaying = p.playWhenReady && !ended,
                repeatMode = p.repeatMode,
                shuffle = p.shuffleModeEnabled
            )
            if (blocking) store.saveStateBlocking(s) else store.saveStateAsync(s)
        } catch (t: Throwable) {
            Log.w(TAG, "Zustand speichern fehlgeschlagen", t)
        }
    }

    private fun readable(uriStr: String): Boolean = try {
        contentResolver.query(Uri.parse(uriStr), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) {
        false // geloescht oder keine Berechtigung mehr
    }

    /** Laedt den gespeicherten Stand im Hintergrund, prueft die Dateien und setzt ihn dann auf dem Main-Thread. */
    private fun restoreAsync() {
        Thread({
            var saved: SavedPlayback? = null
            var valid: List<SavedItem> = emptyList()
            try {
                saved = store.load()
                valid = saved?.items?.filter { readable(it.uri) } ?: emptyList()
            } catch (t: Throwable) {
                Log.w(TAG, "Wiederherstellung: Lesen fehlgeschlagen", t)
            }
            main.post { applyRestore(saved, valid) }
        }, "playback-restore").start()
    }

    private fun applyRestore(saved: SavedPlayback?, valid: List<SavedItem>) {
        val p = player
        try {
            if (p != null && saved != null && p.mediaItemCount == 0) {
                val s = saved.state
                p.repeatMode = s.repeatMode
                p.shuffleModeEnabled = s.shuffle
                if (valid.isNotEmpty()) {
                    val byId = valid.indexOfFirst { it.uri == s.mediaId }
                    val idx = if (byId >= 0) byId else s.index.coerceIn(0, valid.size - 1)
                    // Position nur uebernehmen, wenn genau der gespeicherte Titel noch existiert
                    val pos = if (byId >= 0) s.positionMs.coerceAtLeast(0L) else 0L
                    p.setMediaItems(valid.map { MediaItems.from(it) }, idx, pos)
                    // bewusst NICHT automatisch abspielen (Android 12+ verbietet das Starten aus dem
                    // Hintergrund, und ungewollter Ton nach einem Neustart waere stoerend)
                    p.playWhenReady = false
                    p.prepare()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Wiederherstellung fehlgeschlagen", t)
        } finally {
            restoring = false
            saveQueueNow() // schreibt die bereinigte Queue (ohne geloeschte Dateien) zurueck
        }
    }

    // ------------------------------------------------------------------

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveState(true)
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) stopSelf()
    }

    override fun onDestroy() {
        try { saveState(true) } catch (ignored: Throwable) {}
        main.removeCallbacksAndMessages(null)
        SleepTimer.cancel()
        playerRef = null
        refreshReplayGain = null
        session?.run {
            player.release()
            release()
        }
        session = null
        player = null
        super.onDestroy()
    }
}
