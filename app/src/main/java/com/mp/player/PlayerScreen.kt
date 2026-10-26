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
import kotlinx.coroutines.withContext

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
    var showLyrics by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var lyricsText by remember { mutableStateOf<String?>(null) }
    var lyricsLoading by remember { mutableStateOf(false) }
    var coverRefresh by remember { mutableStateOf(0) }
    var showCoverPicker by remember { mutableStateOf(false) }
    var coverCandidates by remember { mutableStateOf<List<CoverCandidate>>(emptyList()) }
    var coverPickerLoading by remember { mutableStateOf(false) }

    // Alles aus der zentralen Quelle - gleicher Zustand wie Mini-Player, Benachrichtigung, Bluetooth
    val mediaId = PlaybackState.mediaId
    val title = PlaybackState.title
    val artist = PlaybackState.artist
    val isPlaying = PlaybackState.isPlaying
    val durMs = PlaybackState.durationMs
    val repeatMode = PlaybackState.repeatMode
    val shuffle = PlaybackState.shuffle
    val currentTrack = PlaybackState.currentTrack()
    val coverKey = "$mediaId|$coverRefresh"
    val cover = rememberCover(
        trackUri = mediaId,
        maxPx = 768,
        artist = currentTrack?.artist ?: artist,
        album = currentTrack?.album ?: "",
        title = currentTrack?.title ?: title,
        allowOnline = true,
        reloadToken = coverKey
    )

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
            onLyrics = {
                showCoverMenu = false
                showLyrics = true
                lyricsLoading = true
                lyricsText = null
            },
            onBookmark = {
                showCoverMenu = false
                val pos = PlaybackState.positionMs()
                Bookmarks.add(context, currentTrack, pos)
                Toast.makeText(context, "Lesezeichen bei ${Bookmarks.formatPos(pos)} gesetzt", Toast.LENGTH_SHORT).show()
            },
            onShowBookmarks = {
                showCoverMenu = false
                showBookmarks = true
            },
            onSimilar = {
                showCoverMenu = false
                val uri = currentTrack.uri
                Toast.makeText(context, "Suche ähnliche Songs…", Toast.LENGTH_SHORT).show()
                Thread {
                    val tools = try {
                        com.mp.player.ai.PlayerToolsImpl(context.applicationContext)
                    } catch (_: Exception) { null }
                    val list = try {
                        // blocking call of suspend: use runBlocking not available without deps - use similarity offline
                        val db = LibraryDb.get(context)
                        try { DeepSessionCache.ensureLoaded(db) } catch (_: Exception) {}
                        val analysis = db.analysisAll()
                        val src = currentTrack
                        val sInfo = com.mp.player.ai.TrackInfo(src, analysis[uri], null, 0, false)
                        val smp = com.mp.player.ai.MusicalProfile.from(sInfo, DeepSessionCache.get(uri))
                        val ranked = LibraryState.tracks.filter { it.uri != uri }.map { tr ->
                            val info = com.mp.player.ai.TrackInfo(tr, analysis[tr.uri], null, 0, false)
                            val mp = com.mp.player.ai.MusicalProfile.from(info, DeepSessionCache.get(tr.uri))
                            val sim = com.mp.player.ai.AdaptiveMusic.profileSimilarity(smp, mp)
                            val base = com.mp.player.ai.TrackSimilarity.score(sInfo, info)
                            tr to (0.5f * base + 0.5f * sim)
                        }.sortedByDescending { it.second }.take(25).map { it.first }
                        ranked
                    } catch (_: Exception) { emptyList() }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (list.isEmpty()) {
                            Toast.makeText(context, "Keine ähnlichen Titel (Analyse fehlt?)", Toast.LENGTH_LONG).show()
                        } else {
                            playTracks(list, 0)
                            Toast.makeText(context, "${list.size} ähnliche Songs geladen", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            },
            onFetchCover = {
                showCoverMenu = false
                val uri = currentTrack.uri
                val tr = currentTrack
                Toast.makeText(context, "Cover wird gesucht…", Toast.LENGTH_SHORT).show()
                coverPickerLoading = true
                coverCandidates = emptyList()
                showCoverPicker = true
                BgJobs.submitCover(uri) {
                    val list = OnlineCover.searchForPicker(tr.artist, tr.album, tr.title)
                    val best = list.firstOrNull { it.score >= 0.7f }
                    if (best != null && list.count { it.score >= 0.55f } <= 1) {
                        val f = OnlineCover.downloadToFile(context, best.artworkUrl, uri)
                        BgJobs.postMain {
                            if (!BgJobs.isCoverStillValid(uri)) return@postMain
                            coverPickerLoading = false
                            if (f != null) {
                                Covers.cacheExternal(context, uri, f)
                                coverRefresh++
                                showCoverPicker = false
                                Toast.makeText(context, "Cover geladen", Toast.LENGTH_SHORT).show()
                            } else {
                                coverCandidates = list
                            }
                        }
                    } else {
                        BgJobs.postMain {
                            if (!BgJobs.isCoverStillValid(uri)) return@postMain
                            coverPickerLoading = false
                            coverCandidates = list
                            if (list.isEmpty()) {
                                showCoverPicker = false
                                Toast.makeText(context, "Keine Cover gefunden (Netz/Metadaten)", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            },
            onDelete = {
                showCoverMenu = false
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

    // Songtext laden (DB oder Datei-Tags)
    if (showLyrics && currentTrack != null) {
        LaunchedEffect(currentTrack.uri) {
            lyricsLoading = true
            val fromDb = withContext(kotlinx.coroutines.Dispatchers.IO) {
                LibraryDb.get(context).lyricsText(currentTrack.uri)
                    ?: LyricsReader.read(context, currentTrack.uri)
            }
            lyricsText = fromDb
            lyricsLoading = false
        }
        AlertDialog(
            onDismissRequest = { showLyrics = false },
            title = { Text("Songtext") },
            text = {
                if (lyricsLoading) Text("Lade…")
                else if (lyricsText.isNullOrBlank()) Text(
                    "Kein eingebetteter Songtext in den Datei-Tags. " +
                        "Online-Songtexte werden aus rechtlichen Gründen nicht geladen – " +
                        "füge Lyrics in den Tags hinzu oder starte die Audioanalyse."
                )
                else androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    item { Text(lyricsText!!) }
                }
            },
            confirmButton = { TextButton(onClick = { showLyrics = false }) { Text("Schließen") } }
        )
    }

    if (showBookmarks) {
        val marks = remember { Bookmarks.all(context) }
        AlertDialog(
            onDismissRequest = { showBookmarks = false },
            title = { Text("Lesezeichen") },
            text = {
                if (marks.isEmpty()) Text("Noch keine Lesezeichen. Im Menü „Lesezeichen setzen“ bei der aktuellen Position.")
                else Column {
                    marks.take(30).forEach { b ->
                        TextButton(onClick = {
                            // Seek zur Position, wenn gleicher Track läuft
                            if (PlaybackState.mediaId == b.uri) {
                                PlayerBridge.controller?.seekTo(b.positionMs)
                            } else {
                                Toast.makeText(context, "Track starten, dann Lesezeichen nutzen: ${b.title}", Toast.LENGTH_LONG).show()
                            }
                            showBookmarks = false
                        }) {
                            Text("${b.title} – ${b.label}", maxLines = 2)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBookmarks = false }) { Text("Schließen") } }
        )
    }


    if (showCoverPicker && currentTrack != null) {
        AlertDialog(
            onDismissRequest = { showCoverPicker = false },
            title = { Text("Cover auswählen") },
            text = {
                if (coverPickerLoading) Text("Suche passende Cover…")
                else if (coverCandidates.isEmpty()) Text("Keine Treffer.")
                else {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(coverCandidates.size) { idx ->
                            val c = coverCandidates[idx]
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val uri = currentTrack.uri
                                        coverPickerLoading = true
                                        BgJobs.submitCover(uri) {
                                            val f = OnlineCover.downloadToFile(context, c.artworkUrl, uri)
                                            BgJobs.postMain {
                                                coverPickerLoading = false
                                                if (f != null && BgJobs.isCoverStillValid(uri)) {
                                                    Covers.cacheExternal(context, uri, f)
                                                    coverRefresh++
                                                    showCoverPicker = false
                                                    Toast.makeText(context, "Cover gespeichert", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "Download fehlgeschlagen", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    c.collectionName.ifBlank { c.trackName },
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    listOf(c.artistName, c.year).filter { it.isNotBlank() }.joinToString(" · ") +
                                        " · Score ${(c.score * 100).toInt()}%" +
                                        if (c.exactish) " · gut passend" else " · ähnlich",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCoverPicker = false }) { Text("Schließen") }
            }
        )
    }

    if (showInfo && currentTrack != null) {
        val st = remember(currentTrack.uri) { Store(context) }
        val playCount = remember(currentTrack.uri) {
            LibraryDb.get(context).playCountsAll()[currentTrack.uri] ?: 0
        }
        val skipCount = remember(currentTrack.uri) {
            LibraryDb.get(context).skipCountsAll()[currentTrack.uri] ?: 0
        }
        val lastPlayed = remember(currentTrack.uri) {
            LibraryDb.get(context).lastPlayedAt(currentTrack.uri)
        }
        val listenMs = remember(currentTrack.uri) {
            LibraryDb.get(context).listenTotalMs(currentTrack.uri)
        }
        val deep = remember(currentTrack.uri) {
            LibraryDb.get(context).loadDeepAnalysis(currentTrack.uri)
        }
        val base = remember(currentTrack.uri) {
            LibraryDb.get(context).analysisFor(currentTrack.uri)
        }
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
                    Text("Favorit: ${if (st.isFavorite(currentTrack)) "ja" else "nein"}")
                    Text("Wiedergaben (gezählt): $playCount")
                    Text("Übersprungen: $skipCount")
                    Text(
                        "Zuletzt gehört: " + (
                            lastPlayed?.let {
                                java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.GERMANY)
                                    .format(java.util.Date(it))
                            } ?: "—"
                            )
                    )
                    if (listenMs > 0L) {
                        val mins = listenMs / 60_000L
                        val secs = (listenMs % 60_000L) / 1000L
                        Text("Hörzeit gesamt: ${mins} min ${secs} s")
                    } else if (playCount > 0 && currentTrack.durationMs > 0) {
                        val mins = (playCount * currentTrack.durationMs / 1000L) / 60
                        Text("Geschätzte Hörzeit: ~$mins min (Plays × Dauer)")
                    }
                    Text(
                        "Hinweis: Eine Wiedergabe zählt erst nach ca. 30 s (kurze Titel: ~50 % der Länge).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(AudioHealth.shortLine(base, deep), style = MaterialTheme.typography.bodySmall)
                    if (deep != null && deep.frames > 0) {
                        Text("Deep-Analyse", style = MaterialTheme.typography.titleSmall)
                        Text(
                            AnalysisInsight.deepReport(base, deep),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            "Deep-Analyse: noch keine Daten – unter Audioanalyse Bibliothek scannen.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
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
    var fav by remember(track?.uri) {
        mutableStateOf(track != null && Store(context).isFavorite(track))
    }
    IconButton(enabled = track != null, onClick = {
        val tr = track ?: return@IconButton
        val st = Store(context)
        val next = !st.isFavorite(tr)
        st.setFavorite(tr, next)
        fav = next
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
    track: Track,
    onDismiss: () -> Unit,
    onInfo: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onLyrics: () -> Unit,
    onBookmark: () -> Unit,
    onShowBookmarks: () -> Unit,
    onFetchCover: () -> Unit,
    onSimilar: () -> Unit,
    onDelete: () -> Unit,
    onArtist: () -> Unit,
    onAlbum: () -> Unit,
    onFolder: () -> Unit
) {
    val context = LocalContext.current
    var isFav by remember(track.uri) { mutableStateOf(Store(context).isFavorite(track)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(track.title) },
        text = {
            Column {
                MenuRow(
                    if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    if (isFav) "Aus Favoriten entfernen" else "Zu Favoriten (Like)"
                ) {
                    val st = Store(context)
                    val next = !st.isFavorite(track)
                    st.setFavorite(track, next)
                    isFav = next
                    onDismiss()
                }
                MenuRow(Icons.Filled.Notes, "Songtext anzeigen", onLyrics)
                MenuRow(Icons.Filled.BookmarkAdd, "Lesezeichen setzen (Position)", onBookmark)
                MenuRow(Icons.Filled.Bookmarks, "Lesezeichen anzeigen", onShowBookmarks)
                MenuRow(Icons.Filled.CloudDownload, "Online-Cover suchen", onFetchCover)
                MenuRow(Icons.Filled.QueueMusic, "Ähnliche Songs", onSimilar)
                MenuRow(Icons.Filled.PlaylistAdd, "Zur Wiedergabeliste hinzufügen", onAddToPlaylist)
                MenuRow(Icons.Filled.Info, "Info/Tags", onInfo)
                MenuRow(Icons.Filled.Mic, "Interpret", onArtist)
                MenuRow(Icons.Filled.Album, "Album", onAlbum)
                MenuRow(Icons.Filled.Folder, "Ordner", onFolder)
                MenuRow(Icons.Filled.Delete, "Löschen", onDelete)
                Text(
                    "Songtext: aus Datei-Tags (lokal). Lesezeichen: lokal. Cover: eingebettet oder optional iTunes.",
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
