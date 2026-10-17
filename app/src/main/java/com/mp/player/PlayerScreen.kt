package com.mp.player

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.navigation.NavController
import kotlinx.coroutines.delay

// Punkt 12: "Zurück" auf dem Player-Bildschirm nutzt den echten Navigationsverlauf
// (popBackStack), NICHT die Song-Zurück-Funktion - die bleibt exklusiv beim
// Skip-Previous-Button.
@Composable
fun PlayerScreen(nav: NavController) {
    val controller = PlayerBridge.controller
    val context = LocalContext.current
    var showCoverMenu by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }

    // Alles aus der zentralen Quelle - gleicher Zustand wie Mini-Player, Benachrichtigung, Bluetooth
    val mediaId = PlaybackState.mediaId
    val title = PlaybackState.title
    val artist = PlaybackState.artist
    val isPlaying = PlaybackState.isPlaying
    val durMs = PlaybackState.durationMs
    val repeatMode = PlaybackState.repeatMode
    val shuffle = PlaybackState.shuffle
    val currentTrack = PlaybackState.currentTrack()
    val cover = rememberCover(mediaId, 768)

    var posMs by remember { mutableStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }

    LaunchedEffect(mediaId) {
        while (true) {
            if (!dragging) posMs = PlaybackState.positionMs()
            delay(500)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Zurück zur vorherigen Ansicht (Bibliothek/Album/Playlist/...), nicht "Song zurück".
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "Zurück") }
            Text("Wiedergabe", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            // Sleep-Timer (zeigt die Restzeit, solange er aktiv ist)
            SleepTimerButton(onClick = { showSleep = true })
            FavoriteButton(currentTrack)
            IconButton(onClick = { nav.navigate("eq") { launchSingleTop = true } }) { Icon(Icons.Filled.GraphicEq, "Equalizer") }
            IconButton(onClick = { nav.navigate(Routes.QUEUE) { launchSingleTop = true } }) {
                Icon(Icons.Filled.QueueMusic, "Warteschlange")
            }
        }

        Spacer(Modifier.height(16.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(currentTrack?.uri) {
                    detectTapGestures(onLongPress = { if (currentTrack != null) showCoverMenu = true })
                },
            contentAlignment = Alignment.Center
        ) {
            // Cover: eingebettetes Bild aus den Metadaten, falls vorhanden, sonst Platzhalter-Icon.
            if (cover != null) Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Filled.MusicNote, null, modifier = Modifier.fillMaxSize().padding(48.dp))
        }

        Spacer(Modifier.height(16.dp))
        Text(title.ifBlank { "Kein Titel" }, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)

        // Seek erst beim Loslassen (statt bei jedem Zwischenwert) -> keine Seek-Flut, kein Springen
        Slider(
            value = if (dragging) dragFraction else if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f,
            onValueChange = { dragging = true; dragFraction = it },
            onValueChangeFinished = {
                if (durMs > 0) controller?.seekTo((dragFraction * durMs).toLong())
                posMs = (dragFraction * durMs).toLong()
                dragging = false
            },
            enabled = durMs > 0 && controller != null
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(if (dragging) (dragFraction * durMs).toLong() else posMs), style = MaterialTheme.typography.bodySmall)
            Text(formatTime(durMs), style = MaterialTheme.typography.bodySmall)
        }

        // Transport: Shuffle | Previous | Play/Pause | Next | Repeat
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToggleIcon(
                active = shuffle,
                icon = Icons.Filled.Shuffle,
                description = if (shuffle) "Zufallswiedergabe an" else "Zufallswiedergabe aus",
                label = if (shuffle) "An" else "Aus",
                onClick = { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
            )
            // Previous: >3 s = zum Titelanfang, sonst vorheriger Titel (Verhalten von Media3/ExoPlayer,
            // identisch zur Benachrichtigung und zur Kopfhoerer-Taste)
            IconButton(onClick = { controller?.seekToPrevious() }) { Icon(Icons.Filled.SkipPrevious, "Vorheriger Titel", modifier = Modifier.size(40.dp)) }
            IconButton(onClick = {
                controller?.let { if (it.isPlaying) it.pause() else it.play() }
            }) {
                Icon(if (isPlaying) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle, if (isPlaying) "Pause" else "Wiedergabe", modifier = Modifier.size(64.dp))
            }
            IconButton(onClick = { controller?.seekToNext() }) { Icon(Icons.Filled.SkipNext, "Nächster Titel", modifier = Modifier.size(40.dp)) }
            ToggleIcon(
                active = repeatMode != Player.REPEAT_MODE_OFF,
                icon = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                description = when (repeatMode) {
                    Player.REPEAT_MODE_ALL -> "Alle wiederholen"
                    Player.REPEAT_MODE_ONE -> "Titel wiederholen"
                    else -> "Keine Wiederholung"
                },
                label = when (repeatMode) {
                    Player.REPEAT_MODE_ALL -> "Alle"
                    Player.REPEAT_MODE_ONE -> "Titel"
                    else -> "Aus"
                },
                onClick = {
                    controller?.let {
                        it.repeatMode = when (it.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                    }
                }
            )
        }
    }

    if (showCoverMenu && currentTrack != null) {
        CoverContextMenu(
            track = currentTrack,
            onDismiss = { showCoverMenu = false },
            onInfo = { showCoverMenu = false; showInfo = true },
            onAddToPlaylist = { showCoverMenu = false; showAddToPlaylist = true },
            onDelete = {
                showCoverMenu = false
                // Loescht die Datei UND entfernt den Titel aus Bibliothek, Favoriten, Playlists und Queue
                val ok = TrackOps.deleteEverywhere(context, currentTrack)
                Toast.makeText(
                    context,
                    if (ok) "Titel gelöscht" else "Löschen nicht möglich (keine Schreibrechte für diesen Ordner). Ordner in der Ordnerauswahl erneut hinzufügen.",
                    Toast.LENGTH_LONG
                ).show()
            },
            onArtist = { showCoverMenu = false; nav.navigate(NavArgs.filterRoute("artist", currentTrack.artist)) },
            onAlbum = { showCoverMenu = false; nav.navigate(NavArgs.filterRoute("albumArtist", currentTrack.albumArtist)) },
            onFolder = { showCoverMenu = false; nav.navigate(NavArgs.filterRoute("folder", currentTrack.folder.substringBefore('/'))) }
        )
    }

    if (showInfo && currentTrack != null) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("Info/Tags") },
            text = {
                Column {
                    Text("Titel: ${currentTrack.title}")
                    Text("Interpret: ${currentTrack.artist}")
                    Text("Album: ${currentTrack.album}")
                    Text("Jahr: ${currentTrack.year}")
                    if (currentTrack.genre.isNotBlank()) Text("Genre: ${currentTrack.genre}")
                    Text("Dauer: ${currentTrack.durationMs / 1000}s")
                    Text("Ordner: ${currentTrack.folder}")
                }
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("OK") } }
        )
    }

    if (showAddToPlaylist && currentTrack != null) {
        PlaylistPickerDialog(listOf(currentTrack), onDismiss = { showAddToPlaylist = false })
    }

    if (showSleep) SleepTimerDialog(onDismiss = { showSleep = false })
}

private fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Shuffle-/Repeat-Knopf mit eindeutigem Zustand: aktiv = Akzentfarbe + Beschriftung, inaktiv = abgedunkelt. */
@Composable
private fun ToggleIcon(active: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(
                icon, description,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.alpha(if (active) 1f else 0.5f)
            )
        }
        Text(
            label, style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun FavoriteButton(track: Track?) {
    val context = LocalContext.current
    val id = track?.id
    var fav by remember(id) { mutableStateOf(id != null && Store(context).favs().contains(id)) }
    IconButton(enabled = track != null, onClick = {
        val st = Store(context)
        val cur = st.favs()
        if (id != null) {
            val now = if (id in cur) cur - id else cur + id
            st.saveFavs(now)
            fav = id in now
        }
    }) {
        Icon(
            if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            if (fav) "Aus Favoriten entfernen" else "Zu Favoriten",
            tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SleepTimerButton(onClick: () -> Unit) {
    var remaining by remember { mutableStateOf(SleepTimer.remainingMs()) }
    LaunchedEffect(SleepTimer.endAtMs) {
        while (SleepTimer.endAtMs != 0L) {
            remaining = SleepTimer.remainingMs()
            delay(1000)
        }
    }
    TextButton(onClick = onClick) {
        Icon(Icons.Filled.Bedtime, "Sleep-Timer", tint = if (SleepTimer.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        when {
            SleepTimer.endAtMs != 0L -> Text(" " + formatTime(remaining), color = MaterialTheme.colorScheme.primary)
            SleepTimer.endOfTrack -> Text(" Titelende", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SleepTimerDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep-Timer") },
        text = {
            Column {
                listOf(5, 10, 15, 30, 45, 60).forEach { m ->
                    TextButton(onClick = { SleepTimer.startMinutes(m); onDismiss() }) { Text("$m Minuten") }
                }
                TextButton(onClick = { SleepTimer.startEndOfTrack(); onDismiss() }) { Text("Ende des aktuellen Titels") }
                if (SleepTimer.active) {
                    TextButton(onClick = { SleepTimer.cancel(); onDismiss() }) { Text("Timer ausschalten") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } }
    )
}

@Composable
private fun CoverContextMenu(
    track: Track, onDismiss: () -> Unit, onInfo: () -> Unit, onAddToPlaylist: () -> Unit,
    onDelete: () -> Unit, onArtist: () -> Unit, onAlbum: () -> Unit, onFolder: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(track.title) },
        text = {
            Column {
                MenuRow(Icons.Filled.Delete, "Löschen", onDelete)
                MenuRow(Icons.Filled.PlaylistAdd, "Zur Wiedergabeliste hinzufügen", onAddToPlaylist)
                MenuRow(Icons.Filled.Info, "Info/Tags", onInfo)
                MenuRow(Icons.Filled.Mic, "Interpret", onArtist)
                MenuRow(Icons.Filled.Album, "Album", onAlbum)
                MenuRow(Icons.Filled.Folder, "Ordner", onFolder)
                // Songtext/Albumcover-Download/Lesezeichen brauchen einen externen Dienst bzw.
                // Cloud-Anbindung, die es hier (offline/lokal) ehrlich nicht gibt - deshalb bewusst
                // nicht als Fake-Button vorgetäuscht, sondern klar benannt statt versteckt.
                Text(
                    "Songtext, Lesezeichen und Online-Albumcover sind in dieser rein lokalen App nicht verfügbar.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } }
    )
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, modifier = Modifier.padding(8.dp))
        Text(label)
    }
}
