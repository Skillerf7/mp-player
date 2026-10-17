package com.mp.player

import android.content.Context
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer

/**
 * Telefon-Audio ohne aggressives Ducken, mit maximal **einem** Play-Restart pro Anruf
 * (weniger kurze Hänger als bei Restart bei jedem Fokus-Event).
 */
object CallDucking {
    private const val TAG = "CallDucking"
    private const val MODE_POLL_MS = 500L
    private const val RESTART_DELAY_MS = 500L

    @Volatile private var inCallOrTransient = false
    @Volatile private var fadeGain = 1f
    /** Nur 1× Restart pro Anruf-Session → weniger Hänger. */
    private var restartedThisCall = false
    private var holdingFocus = false
    private var focusRequest: AudioFocusRequest? = null
    private var am: AudioManager? = null
    private var player: ExoPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private var modePoll: Runnable? = null
    private var restartRunnable: Runnable? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post { onFocusChange(change) }
    }

    fun attach(context: Context, p: ExoPlayer) {
        player = p
        am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fadeGain = 1f
        inCallOrTransient = false
        restartedThisCall = false
        applyVolume()
    }

    fun detach() {
        cancelRestart()
        stopModePoll()
        abandonFocus()
        player = null
        am = null
        inCallOrTransient = false
        restartedThisCall = false
        fadeGain = 1f
        holdingFocus = false
    }

    fun ensureFocus(): Boolean {
        if (holdingFocus) {
            applyVolume()
            return true
        }
        return requestFocus()
    }

    fun onPlaybackPaused() {
        cancelRestart()
        stopModePoll()
        inCallOrTransient = false
        applyVolume()
        abandonFocus()
    }

    fun setFadeGain(g: Float) {
        fadeGain = g.coerceIn(0f, 1f)
        applyVolume()
    }

    fun normalVolume(): Float = 1f

    @Deprecated("no duck")
    val isDucked: Boolean get() = false

    private fun applyVolume() {
        player?.volume = fadeGain.coerceIn(0f, 1f)
    }

    private fun forceFullVolume() {
        if (fadeGain < 0.05f) {
            applyVolume()
            return
        }
        fadeGain = 1f
        player?.volume = 1f
    }

    private fun requestFocus(): Boolean {
        val manager = am ?: return false
        return try {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = PlatformAudioAttributes.Builder()
                    .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                    .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(focusListener, main)
                    .setAcceptsDelayedFocusGain(true)
                    .setWillPauseWhenDucked(false)
                    .build()
                focusRequest = req
                manager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
            when (result) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                    holdingFocus = true
                    if (isInCallMode()) {
                        inCallOrTransient = true
                        schedulePlayRestartOnce("focus-in-call")
                    } else {
                        forceFullVolume()
                    }
                    true
                }
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                    holdingFocus = true
                    inCallOrTransient = true
                    schedulePlayRestartOnce("focus-delayed")
                    true
                }
                else -> false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "requestAudioFocus failed", t)
            false
        }
    }

    private fun abandonFocus() {
        holdingFocus = false
        val manager = am ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { manager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(focusListener)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "abandon failed", t)
        }
        focusRequest = null
    }

    private fun onFocusChange(change: Int) {
        val p = player ?: return
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                inCallOrTransient = true
                applyVolume()
                // Nur einmal pro Anruf – verhindert mehrfache kurze Hänger
                if (p.isPlaying || p.playWhenReady) {
                    schedulePlayRestartOnce("transient")
                }
                startModePoll()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                cancelRestart()
                stopModePoll()
                inCallOrTransient = false
                restartedThisCall = false
                forceFullVolume()
                holdingFocus = false
                focusRequest = null
                if (p.isPlaying || p.playWhenReady) p.playWhenReady = false
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                cancelRestart()
                inCallOrTransient = false
                restartedThisCall = false
                stopModePoll()
                forceFullVolume()
                if (p.playWhenReady && !p.isPlaying) {
                    try {
                        p.play()
                    } catch (t: Throwable) {
                        Log.w(TAG, "play after gain", t)
                    }
                }
            }
        }
    }

    /** Maximal ein Restart pro Anruf-Session. */
    private fun schedulePlayRestartOnce(reason: String) {
        if (restartedThisCall) {
            Log.d(TAG, "skip restart ($reason) – already done this call")
            return
        }
        cancelRestart()
        val r = Runnable {
            restartRunnable = null
            val p = player ?: return@Runnable
            if (restartedThisCall) return@Runnable
            if (!isInCallMode() && !inCallOrTransient) return@Runnable
            if (!(p.isPlaying || p.playWhenReady)) return@Runnable
            try {
                restartedThisCall = true
                if (fadeGain >= 0.05f) p.volume = 1f
                // Sanfter: nur play() erzwingen, ohne playWhenReady-Toggle wenn schon playing
                if (p.isPlaying) {
                    // Schon am Laufen – kein zweites pause/play (weniger Hänger)
                    p.volume = 1f
                    Log.i(TAG, "already playing – volume only ($reason)")
                } else {
                    p.playWhenReady = true
                    if (p.playbackState == androidx.media3.common.Player.STATE_IDLE) p.prepare()
                    p.play()
                    Log.i(TAG, "restart once ($reason)")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "restart failed", t)
                restartedThisCall = false
            }
        }
        restartRunnable = r
        main.postDelayed(r, RESTART_DELAY_MS)
    }

    private fun cancelRestart() {
        restartRunnable?.let { main.removeCallbacks(it) }
        restartRunnable = null
    }

    private fun isInCallMode(): Boolean {
        val mode = am?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun startModePoll() {
        stopModePoll()
        val r = object : Runnable {
            override fun run() {
                if (!inCallOrTransient && !isInCallMode()) return
                if (!isInCallMode()) {
                    inCallOrTransient = false
                    restartedThisCall = false
                    forceFullVolume()
                    val p = player
                    if (p != null && p.playWhenReady) {
                        holdingFocus = false
                        ensureFocus()
                        try {
                            if (!p.isPlaying) p.play()
                        } catch (_: Throwable) {
                        }
                    }
                    return
                }
                val p = player
                if (p != null && fadeGain > 0.05f && p.volume < 0.85f) {
                    p.volume = 1f
                }
                modePoll = this
                main.postDelayed(this, MODE_POLL_MS)
            }
        }
        modePoll = r
        main.postDelayed(r, MODE_POLL_MS)
    }

    private fun stopModePoll() {
        modePoll?.let { main.removeCallbacks(it) }
        modePoll = null
    }
}
