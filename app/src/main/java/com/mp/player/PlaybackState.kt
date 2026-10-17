package com.mp.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController

/**
 * EINE zentrale Quelle fuer den Wiedergabezustand. Alle Screens (Mini-Player, Player, Queue, ...)
 * lesen nur von hier; aktualisiert wird ausschliesslich ueber den Listener des MediaControllers,
 * also aus dem echten Zustand des PlayerService (derselbe, den Benachrichtigung/Bluetooth steuern).
 */
object PlaybackState {
    var connected by mutableStateOf(false)
        private set
    var mediaId by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf("")
        private set
    var artist by mutableStateOf("")
        private set
    var album by mutableStateOf("")
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var repeatMode by mutableStateOf(Player.REPEAT_MODE_OFF)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var itemCount by mutableStateOf(0)
        private set
    var currentIndex by mutableStateOf(0)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    /** Wird bei jeder Aenderung der Queue erhoeht -> Queue-Ansicht berechnet neu. */
    var queueVersion by mutableStateOf(0)
        private set

    private var attached: MediaController? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) queueVersion++
            sync(player)
        }
    }

    fun attach(c: MediaController) {
        attached?.let { try { it.removeListener(listener) } catch (ignored: Exception) {} }
        attached = c
        c.addListener(listener)
        queueVersion++
        sync(c)
        connected = true
    }

    fun detach() {
        attached?.let { try { it.removeListener(listener) } catch (ignored: Exception) {} }
        attached = null
        connected = false
    }

    fun positionMs(): Long = try { attached?.currentPosition?.coerceAtLeast(0L) ?: 0L } catch (ignored: Exception) { 0L }

    /** Der aktuell laufende Titel aus der Bibliothek (Zuordnung ueber die eindeutige mediaId = URI). */
    fun currentTrack(): Track? {
        val id = mediaId ?: return null
        return LibraryState.tracks.firstOrNull { it.uri == id }
    }

    private fun sync(p: Player) {
        try {
            val item = p.currentMediaItem
            mediaId = item?.mediaId?.takeIf { it.isNotEmpty() }
            title = item?.mediaMetadata?.title?.toString() ?: ""
            artist = item?.mediaMetadata?.artist?.toString() ?: ""
            album = item?.mediaMetadata?.albumTitle?.toString() ?: ""
            isPlaying = p.isPlaying
            repeatMode = p.repeatMode
            shuffle = p.shuffleModeEnabled
            itemCount = p.mediaItemCount
            currentIndex = p.currentMediaItemIndex
            durationMs = p.duration.let { if (it == C.TIME_UNSET || it < 0) 0L else it }
        } catch (ignored: Exception) {
            // Controller wurde gerade getrennt -> naechster attach() synchronisiert neu
        }
    }
}
