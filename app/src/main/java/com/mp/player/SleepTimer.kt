package com.mp.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Sleep-Timer. Laeuft im Prozess des PlayerService (nicht an die Activity gebunden) und arbeitet
 * direkt auf dem echten Player. "Ende des Titels" nutzt ExoPlayer.setPauseAtEndOfMediaItems
 * (sauberer Stopp exakt am Titelende, kein Anspielen des naechsten Titels).
 * Fade-Out: Die Lautstaerke wird in [fadeSeconds] Sekunden VOR Ablauf linear auf 0 gesenkt, dann wird pausiert
 * und die Lautstaerke wieder auf 100 % gestellt. Die Restzeit-Anzeige zaehlt bis zum Ende der Fade-Phase.
 * Hinweis: Der Timer ueberlebt keinen Prozess-Tod (bei Crash/Kill ist er weg).
 */
object SleepTimer {
    /** 0 = kein Zeit-Timer aktiv; sonst SystemClock.elapsedRealtime()-Zielzeit */
    var endAtMs by mutableStateOf(0L)
        private set
    var endOfTrack by mutableStateOf(false)
        private set

    /** 0 = kein Ausblenden. Wird vom Service aus den Einstellungen gesetzt. */
    @Volatile var fadeSeconds = 10

    private val handler = Handler(Looper.getMainLooper())
    private var fading = false
    private var fadeStartMs = 0L
    private var fadeLenMs = 0L

    private val fire = Runnable { beginFade() }

    private val stepper = object : Runnable {
        override fun run() {
            val p = PlayerService.playerRef
            if (!fading || p == null) { finish(); return }
            if (!p.isPlaying) { // Nutzer hat selbst pausiert -> Lautstaerke zuruecksetzen, Timer beenden
                p.volume = 1f
                finish()
                return
            }
            val t = (SystemClock.elapsedRealtime() - fadeStartMs).toFloat() / fadeLenMs
            if (t >= 1f) {
                p.pause()
                p.volume = 1f
                finish()
            } else {
                p.volume = (1f - t).coerceIn(0f, 1f)
                handler.postDelayed(this, 100)
            }
        }
    }

    val active: Boolean get() = endAtMs != 0L || endOfTrack

    fun remainingMs(): Long = if (endAtMs == 0L) 0L else (endAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    fun startMinutes(minutes: Int) {
        cancel()
        val ms = minutes * 60_000L
        val fade = (fadeSeconds * 1000L).coerceIn(0L, ms)
        endAtMs = SystemClock.elapsedRealtime() + ms
        fadeLenMs = fade
        handler.postDelayed(fire, ms - fade)
    }

    private fun beginFade() {
        val p = PlayerService.playerRef
        if (p == null || fadeLenMs <= 0L || !p.isPlaying) {
            p?.pause()
            finish()
            return
        }
        fading = true
        fadeStartMs = SystemClock.elapsedRealtime()
        handler.post(stepper)
    }

    private fun finish() {
        fading = false
        handler.removeCallbacks(stepper)
        endAtMs = 0L
    }

    fun startEndOfTrack() {
        cancel()
        val p = PlayerService.playerRef ?: return
        p.setPauseAtEndOfMediaItems(true)
        endOfTrack = true
    }

    fun cancel() {
        handler.removeCallbacks(fire)
        handler.removeCallbacks(stepper)
        if (fading) PlayerService.playerRef?.volume = 1f
        fading = false
        endAtMs = 0L
        if (endOfTrack) {
            endOfTrack = false
            PlayerService.playerRef?.setPauseAtEndOfMediaItems(false)
        }
    }

    /** Vom Service aufgerufen, wenn am Titelende pausiert wurde. */
    fun onPausedAtEndOfItem() {
        if (endOfTrack) {
            endOfTrack = false
            PlayerService.playerRef?.setPauseAtEndOfMediaItems(false)
        }
    }
}
