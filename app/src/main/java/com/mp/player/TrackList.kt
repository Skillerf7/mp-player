package com.mp.player

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Die gemeinsame Titelliste fuer Bibliothek, Suche und Playlists (eine Implementierung, ueberall gleich).
 *
 * - Tippen = abspielen ab diesem Titel, Lang-Druck = Mehrfachauswahl-Modus.
 * - Auswahl-Leiste: "Alle auswaehlen", "Zur Wiedergabeliste", "Zur Warteschlange" und - nur innerhalb
 *   einer Playlist - "Aus Playlist entfernen" (entfernt NUR die Zuordnung, nie die Musikdatei).
 * - [playlistName] gesetzt = Ansicht dieser Playlist (zusaetzlich: Play / Shuffle / Alle in die Warteschlange).
 */
@Composable
fun TrackListView(
    list: List<Track>,
    modifier: Modifier = Modifier,
    playlistName: String? = null,
    emptyText: String = "Keine Titel vorhanden."
) {
    val context = LocalContext.current
    // Doppelte URIs (z. B. durch ueberlappende Ordner) wuerden die LazyColumn-Schluessel verletzen
    val items = remember(list) { list.distinctBy { it.uri } }
    var selected by remember(items) { mutableStateOf(setOf<String>()) }
    var pickerFor by remember { mutableStateOf<List<Track>?>(null) }
    var deleteFor by remember { mutableStateOf<List<Track>?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val selecting = selected.isNotEmpty()
    val currentId = PlaybackState.mediaId

    BackHandler(enabled = selecting) { selected = emptySet() }

    fun chosen(): List<Track> = items.filter { it.uri in selected }

    Column(modifier.fillMaxSize()) {
        if (selecting) {
            SelectionBar(
                count = selected.size,
                allSelected = selected.size == items.size,
                inPlaylist = playlistName != null,
                onClose = { selected = emptySet() },
                onToggleAll = {
                    selected = if (selected.size == items.size) emptySet() else items.map { it.uri }.toSet()
                },
                onAddToPlaylist = { pickerFor = chosen() },
                onDelete = { deleteFor = chosen() },
                onAddToQueue = {
                    val c = chosen()
                    enqueueTracks(c, false)
                    Toast.makeText(context, "${c.size} Titel zur Warteschlange hinzugefügt", Toast.LENGTH_SHORT).show()
                    selected = emptySet()
                },
                onRemoveFromPlaylist = {
                    val name = playlistName
                    if (name != null) {
                        val c = chosen()
                        val n = PlaylistStore.removeTracks(context, name, c.map { it.id }.toSet())
                        Toast.makeText(
                            context,
                            "$n Titel aus der Wiedergabeliste entfernt. Die Musikdateien bleiben erhalten.",
                            Toast.LENGTH_LONG
                        ).show()
                        selected = emptySet()
                    }
                }
            )
        } else if (playlistName != null && items.isNotEmpty()) {
            PlaylistActions(
                onPlay = { playTracks(items, 0, shuffle = false) },
                onShuffle = { playTracks(items, items.indices.random(), shuffle = true) },
                onQueueAll = {
                    enqueueTracks(items, false)
                    Toast.makeText(context, "${items.size} Titel zur Warteschlange hinzugefügt", Toast.LENGTH_SHORT).show()
                }
            )
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(emptyText) }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(items, key = { _, t -> t.uri }) { index, t ->
                    // key(...): Marquee-Zustand (Scroll-Position, Animation) wird beim Zeilenwechsel sauber zurueckgesetzt
                    key(t.uri) {
                        TrackRow(
                            t = t,
                            selecting = selecting,
                            isSelected = t.uri in selected,
                            isCurrent = t.uri == currentId,
                            playlistName = playlistName,
                            onTap = {
                                if (selecting) selected = if (t.uri in selected) selected - t.uri else selected + t.uri
                                else playTracks(items, index)
                            },
                            onLongPress = { selected = if (t.uri in selected) selected - t.uri else selected + t.uri },
                            onAddToPlaylist = { pickerFor = listOf(t) },
                            onRemoveFromPlaylist = {
                                val name = playlistName
                                if (name != null) {
                                    PlaylistStore.removeTracks(context, name, setOf(t.id))
                                    Toast.makeText(
                                        context, "Aus der Wiedergabeliste entfernt. Die Musikdatei bleibt erhalten.", Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                        Divider()
                    }
                }
            }
        }
    }

    deleteFor?.let { tracks ->
        AlertDialog(
            onDismissRequest = { if (!deleting) deleteFor = null },
            title = { Text(if (tracks.size == 1) "Titel endgültig löschen?" else "${tracks.size} Titel endgültig löschen?") },
            text = {
                if (deleting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                        Spacer(Modifier.width(16.dp))
                        Text("Lösche Dateien…")
                    }
                } else {
                    Text(
                        "Die Dateien werden endgültig vom Speicher (intern bzw. SD-Karte) gelöscht und aus Bibliothek, " +
                            "Favoriten, Wiedergabelisten und Warteschlange entfernt. Das kann nicht rückgängig gemacht werden."
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    deleting = true
                    scope.launch {
                        // Dateien im Hintergrund loeschen (SAF ist langsam), Verweise danach auf dem Main-Thread
                        val gone = withContext(Dispatchers.IO) { tracks.filter { TrackOps.deleteFile(context, it) } }
                        TrackOps.removeReferencesBatch(context, gone)
                        deleting = false
                        deleteFor = null
                        selected = emptySet()
                        val failed = tracks.size - gone.size
                        Toast.makeText(
                            context,
                            "${gone.size} Titel gelöscht" +
                                if (failed > 0) " · $failed nicht möglich (keine Schreibrechte - Ordner in den Bibliotheks-Einstellungen erneut hinzufügen)" else "",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !deleting, onClick = { deleteFor = null }) { Text("Abbrechen") } }
        )
    }

    pickerFor?.let { tracks ->
        PlaylistPickerDialog(tracks = tracks, onDismiss = {
            pickerFor = null
            selected = emptySet()
        })
    }
}

@Composable
private fun TrackRow(
    t: Track,
    selecting: Boolean,
    isSelected: Boolean,
    isCurrent: Boolean,
    playlistName: String?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    // Der Gesten-Handler wird nur bei Titel-/Modus-Wechsel neu gestartet; mit rememberUpdatedState ruft er
    // immer die AKTUELLE Auswahl-Logik auf (sonst rechnet er mit einer veralteten Auswahl).
    val currentTap by rememberUpdatedState(onTap)
    val currentLongPress by rememberUpdatedState(onLongPress)
    val leading: (@Composable () -> Unit)? =
        if (selecting) { { Checkbox(checked = isSelected, onCheckedChange = null) } } else null

    ListItem(
        headlineContent = {
            MarqueeText(
                text = t.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) scheme.primary else androidx.compose.ui.graphics.Color.Unspecified,
                modifier = Modifier.fillMaxWidth()
            )
        },
        supportingContent = {
            Text(
                t.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = scheme.onSurface.copy(alpha = 0.6f)
            )
        },
        leadingContent = leading,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = scheme.onSurface.copy(alpha = 0.5f)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    trackDurationAndFormat(t),
                    color = scheme.onSurface.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall
                )
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Mehr") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Als Nächstes spielen") }, onClick = { menu = false; enqueueTrack(t, true) })
                        DropdownMenuItem(text = { Text("Zur Warteschlange hinzufügen") }, onClick = { menu = false; enqueueTrack(t, false) })
                        DropdownMenuItem(
                            text = { Text("Zur Wiedergabeliste hinzufügen") },
                            leadingIcon = { Icon(Icons.Filled.PlaylistAdd, null) },
                            onClick = { menu = false; onAddToPlaylist() }
                        )
                        if (playlistName != null) {
                            DropdownMenuItem(
                                text = { Text("Aus Playlist entfernen") },
                                leadingIcon = { Icon(Icons.Filled.RemoveCircleOutline, null) },
                                onClick = { menu = false; onRemoveFromPlaylist() }
                            )
                        }
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (isSelected) scheme.primary.copy(alpha = 0.22f) else scheme.surface
        ),
        modifier = Modifier.pointerInput(t.uri, selecting) {
            detectTapGestures(onTap = { currentTap() }, onLongPress = { currentLongPress() })
        }
    )
}

@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    inPlaylist: Boolean,
    onClose: () -> Unit,
    onToggleAll: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDelete: () -> Unit,
    onAddToQueue: () -> Unit,
    onRemoveFromPlaylist: () -> Unit
) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Auswahl beenden") }
            Text("$count ausgewählt", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onToggleAll) { Text(if (allSelected) "Auswahl aufheben" else "Alle auswählen") }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(onClick = onAddToPlaylist) {
                Icon(Icons.Filled.PlaylistAdd, null); Spacer(Modifier.width(4.dp)); Text("Zur Wiedergabeliste")
            }
            TextButton(onClick = onAddToQueue) {
                Icon(Icons.Filled.QueueMusic, null); Spacer(Modifier.width(4.dp)); Text("Zur Warteschlange")
            }
            TextButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(4.dp)); Text("Löschen", color = MaterialTheme.colorScheme.error)
            }
            if (inPlaylist) {
                TextButton(onClick = onRemoveFromPlaylist) {
                    Icon(Icons.Filled.RemoveCircleOutline, null); Spacer(Modifier.width(4.dp)); Text("Aus Playlist entfernen")
                }
            }
        }
    }
}

@Composable
private fun PlaylistActions(onPlay: () -> Unit, onShuffle: () -> Unit, onQueueAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Play") }
        OutlinedButton(onClick = onShuffle) { Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.width(4.dp)); Text("Shuffle") }
        OutlinedButton(onClick = onQueueAll) { Icon(Icons.Filled.QueueMusic, null); Spacer(Modifier.width(4.dp)); Text("Zur Warteschlange") }
    }
}

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Echte Dateiendung aus der tatsaechlichen URI, nicht hart codiert. */
private fun Track.fileExtension(): String = try {
    val decoded = java.net.URLDecoder.decode(uri.replace("+", "%2B"), "UTF-8")
    val name = decoded.substringBefore('?').substringAfterLast('/')
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) ext else ""
} catch (e: Exception) {
    ""
}

/** "3:41 | mp3" - Dauer nur bei bekannter Laenge, Format nur bei erkennbarer Endung. */
private fun trackDurationAndFormat(t: Track): String {
    val duration = if (t.durationMs > 0) formatDuration(t.durationMs) else ""
    val ext = t.fileExtension()
    return listOf(duration, ext).filter { it.isNotEmpty() }.joinToString(" | ")
}
