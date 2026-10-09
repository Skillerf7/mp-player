package com.mp.player

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Zentrale, im Speicher gehaltene Bibliothek - wird beim ersten Öffnen aus dem
// Store (Cache) geladen, "Neu einlesen" stößt Library.scan() an (Punkt 6).
object LibraryState {
    var tracks by mutableStateOf<List<Track>>(emptyList())
    var folders by mutableStateOf<List<FolderRef>>(emptyList())
    @Volatile var loaded = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Laedt den Cache im Hintergrund (keine Parser-Arbeit auf dem Main-Thread). */
    suspend fun loadAsync(context: android.content.Context) {
        if (loaded) return
        val app = context.applicationContext
        val (t, f) = withContext(Dispatchers.IO) {
            val st = Store(app)
            st.library() to st.folders()
        }
        if (!loaded) {
            tracks = t
            folders = f
            loaded = true
        }
    }

    /** Scan im Hintergrund (Metadaten lesen kann bei grossen Bibliotheken Minuten dauern -> nie auf dem UI-Thread). */
    fun rescan(context: android.content.Context, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        val foldersSnapshot = folders
        val tracksSnapshot = tracks
        scope.launch {
            val fresh = withContext(Dispatchers.IO) {
                try { Library.scan(app, foldersSnapshot, tracksSnapshot) } catch (e: Exception) { null }
            }
            if (fresh != null) {
                tracks = fresh
                withContext(Dispatchers.IO) { Store(app).saveLibrary(fresh) }
            }
            onDone()
        }
    }

    fun addFolder(context: android.content.Context, uri: Uri) {
        val st = Store(context)
        val f = Library.takeFolder(context, uri)
        if (folders.none { it.treeUri == f.treeUri }) {
            folders = folders + f
            st.saveFolders(folders)
        }
    }

    fun removeFolder(context: android.content.Context, f: FolderRef) {
        val st = Store(context)
        folders = folders.filter { it.treeUri != f.treeUri }
        st.saveFolders(folders)
    }
}

/** Startet die Wiedergabe einer Liste. Ungueltige Eingaben (leere Liste, falscher Index, kein Controller) fuehren nie zum Absturz. */
fun playTracks(list: List<Track>, startIndex: Int, shuffle: Boolean? = null) {
    val controller = PlayerBridge.controller ?: return
    if (list.isEmpty()) return
    try {
        controller.setMediaItems(list.map { MediaItems.from(it) }, startIndex.coerceIn(0, list.size - 1), 0L)
        // Nur wenn ausdruecklich verlangt (Play = in Reihenfolge, Shuffle = gemischt); sonst bleibt der Modus
        if (shuffle != null) controller.shuffleModeEnabled = shuffle
        controller.prepare()
        controller.play()
    } catch (e: Exception) { /* Verbindung zum Service gerade weg -> naechster Versuch nach Reconnect */ }
}

/** Als naechstes bzw. ans Ende der laufenden Queue (startet bei leerer Queue die Wiedergabe). */
fun enqueueTracks(tracks: List<Track>, next: Boolean) {
    val c = PlayerBridge.controller ?: return
    if (tracks.isEmpty()) return
    try {
        val items = tracks.map { MediaItems.from(it) }
        if (c.mediaItemCount == 0) {
            c.setMediaItems(items); c.prepare(); c.play()
        } else if (next) {
            c.addMediaItems((c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount), items)
        } else {
            c.addMediaItems(items)
        }
    } catch (e: Exception) { }
}

fun enqueueTrack(track: Track, next: Boolean) = enqueueTracks(listOf(track), next)

private data class Category(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val kind: String)

private val CATEGORIES = listOf(
    Category("Wiedergabelisten", Icons.Filled.QueueMusic, "playlists"),
    Category("Favoriten", Icons.Filled.Favorite, "favs"),
    Category("Alle Titel", Icons.Filled.MusicNote, "all"),
    Category("Interpreten", Icons.Filled.Mic, "artist"),
    Category("Alben", Icons.Filled.Album, "album"),
    Category("Album-Interpreten", Icons.Filled.Mic, "albumArtist"),
    Category("Genres", Icons.Filled.Category, "genre"),
    Category("Jahre", Icons.Filled.CalendarToday, "year"),
    Category("Ordnerhierarchie", Icons.Filled.Folder, "folder"),
    Category("Kürzlich hinzugefügt", Icons.Filled.AddCircle, "recent")
)

@Composable
fun LibraryHomeScreen(nav: NavController) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { LibraryState.loadAsync(context) }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        items(CATEGORIES) { c ->
            ListItem(
                headlineContent = { Text(c.title) },
                supportingContent = { Text("${countFor(c.kind, context)} Einträge") },
                leadingContent = { Icon(c.icon, null) },
                modifier = Modifier.clickable { nav.navigate("category/${c.kind}") }
            )
            Divider()
        }
    }
}

private fun countFor(kind: String, context: android.content.Context): Int = when (kind) {
    "all" -> LibraryState.tracks.size
    "artist" -> LibraryState.tracks.map { it.artist }.distinct().size
    "albumArtist" -> LibraryState.tracks.map { it.albumArtist }.distinct().size
    "year" -> LibraryState.tracks.map { it.year }.distinct().size
    "album" -> LibraryState.tracks.map { it.album }.distinct().size
    "genre" -> LibraryState.tracks.map { it.genre }.distinct().size
    "favs" -> { val f = Store(context).favs(); LibraryState.tracks.count { it.id in f } }
    "folder" -> LibraryState.tracks.map { it.folder.substringBefore('/') }.distinct().size
    "recent" -> LibraryState.tracks.size
    "playlists" -> PlaylistStore.playlists.size
    else -> 0
}

@Composable
fun CategoryScreen(kind: String, nav: NavController) {
    val context = LocalContext.current
    var showFolderDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, null) } // Punkt 1: Navigation zurück
            Text(
                titleFor(kind),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            if (kind == "recent") {
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, null) }
                    RecentAddedMenu(
                        expanded = showMenu,
                        onDismiss = { showMenu = false },
                        onFolders = { showMenu = false; showFolderDialog = true },
                        onRescan = {
                            showMenu = false; scanning = true
                            LibraryState.rescan(context) { scanning = false }
                        },
                        onClear = {
                            showMenu = false
                            LibraryState.tracks = emptyList()
                            Store(context).saveLibrary(emptyList())
                        }
                    )
                }
            }
        }

        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())

        when (kind) {
            "all" -> TrackListView(LibraryState.tracks.sortedBy { it.title })
            "recent" -> TrackListView(LibraryState.tracks.sortedByDescending { it.dateModified })
            "favs" -> {
                val f = Store(context).favs()
                TrackListView(
                    LibraryState.tracks.filter { it.id in f }.sortedBy { it.title },
                    emptyText = "Noch keine Favoriten. Tippe im Player auf das Herz."
                )
            }
            "album" -> GroupList(LibraryState.tracks.groupBy { it.album }, nav, "album")
            "genre" -> GroupList(LibraryState.tracks.groupBy { it.genre }, nav, "genre")
            "artist" -> GroupList(LibraryState.tracks.groupBy { it.artist }, nav, "artist")
            "albumArtist" -> GroupList(LibraryState.tracks.groupBy { it.albumArtist }, nav, "albumArtist")
            "year" -> GroupList(LibraryState.tracks.groupBy { it.year.toString() }, nav, "year")
            "folder" -> GroupList(LibraryState.tracks.groupBy { it.folder.substringBefore('/') }, nav, "folder")
            "playlists" -> PlaylistOverview(nav)
            else -> if (kind.startsWith("filter:")) {
                // Format: filter:<feld>:<wert, URL-sicher kodiert> (siehe NavArgs)
                val parts = kind.removePrefix("filter:").split(":", limit = 2)
                val field = parts.getOrNull(0) ?: ""
                val value = NavArgs.decode(parts.getOrNull(1))
                val filtered = LibraryState.tracks.filter {
                    when (field) {
                        "artist" -> it.artist == value
                        "albumArtist" -> it.albumArtist == value
                        "year" -> it.year.toString() == value
                        "folder" -> it.folder.substringBefore('/') == value
                        "album" -> it.album == value
                        "genre" -> it.genre == value
                        else -> false
                    }
                }
                // Alben in Albumreihenfolge (CD, Track), alles andere nach Titel
                TrackListView(
                    if (field == "album") filtered.sortedWith(compareBy({ it.discNo }, { it.trackNo }, { it.title }))
                    else filtered
                )
            }
        }
    }

    if (showFolderDialog) {
        FolderPickerDialog(onDismiss = { showFolderDialog = false }, onSaveAndScan = {
            showFolderDialog = false
            scanning = true
            LibraryState.rescan(context) { scanning = false }
        })
    }
}

private fun titleFor(kind: String) = when {
    kind == "all" -> "Alle Titel"
    kind == "recent" -> "Kürzlich hinzugefügt"
    kind == "artist" -> "Interpreten"
    kind == "albumArtist" -> "Album-Interpreten"
    kind == "year" -> "Jahre"
    kind == "folder" -> "Ordnerhierarchie"
    kind == "playlists" -> "Wiedergabelisten"
    kind == "favs" -> "Favoriten"
    kind == "album" -> "Alben"
    kind == "genre" -> "Genres"
    kind.startsWith("filter:") -> NavArgs.decode(kind.removePrefix("filter:").split(":", limit = 2).getOrNull(1)).ifBlank { "Unbekannt" }
    else -> "Bibliothek"
}

@Composable
private fun GroupList(groups: Map<String, List<Track>>, nav: NavController, field: String) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(groups.entries.toList().sortedBy { it.key }) { (key, list) ->
            ListItem(
                headlineContent = { Text(key.ifBlank { "Unbekannt" }) },
                supportingContent = { Text("${list.size} Titel") },
                modifier = Modifier.clickable { nav.navigate(NavArgs.filterRoute(field, key)) }
            )
            Divider()
        }
    }
}

@Composable
private fun RecentAddedMenu(
    expanded: Boolean, onDismiss: () -> Unit,
    onFolders: () -> Unit, onRescan: () -> Unit, onClear: () -> Unit
) {
    val context = LocalContext.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Ordner auswählen") }, leadingIcon = { Icon(Icons.Filled.Folder, null) }, onClick = onFolders)
        DropdownMenuItem(text = { Text("Neu einlesen") }, leadingIcon = { Icon(Icons.Filled.Refresh, null) }, onClick = onRescan)
        DropdownMenuItem(text = { Text("Zu Launcher hinzufügen") }, leadingIcon = { Icon(Icons.Filled.InstallMobile, null) }, onClick = {
            onDismiss()
            pinAppShortcut(context)
        })
        DropdownMenuItem(text = { Text("Listenoptionen") }, leadingIcon = { Icon(Icons.Filled.Sort, null) }, onClick = onDismiss)
        DropdownMenuItem(text = { Text("Alle Anzeigen") }, leadingIcon = { Icon(Icons.Filled.History, null) }, onClick = onDismiss)
        DropdownMenuItem(text = { Text("Leeren") }, leadingIcon = { Icon(Icons.Filled.Close, null) }, onClick = onClear)
    }
}

private fun pinAppShortcut(context: android.content.Context) {
    val mgr = context.getSystemService(android.content.pm.ShortcutManager::class.java)
    if (mgr != null && mgr.isRequestPinShortcutSupported) {
        val shortcut = android.content.pm.ShortcutInfo.Builder(context, "mp_recent")
            .setShortLabel("Secret Player")
            .setIntent(android.content.Intent(context, MainActivity::class.java).setAction(android.content.Intent.ACTION_MAIN))
            .build()
        mgr.requestPinShortcut(shortcut, null)
    }
}

@Composable
fun FolderPickerDialog(onDismiss: () -> Unit, onSaveAndScan: () -> Unit) {
    val context = LocalContext.current
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) LibraryState.addFolder(context, uri)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ordnerauswahl") },
        text = {
            Column {
                LibraryState.folders.forEach { f ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Folder, null, modifier = Modifier.padding(end = 8.dp))
                        Text(f.displayName, Modifier.weight(1f))
                        IconButton(onClick = { LibraryState.removeFolder(context, f) }) { Icon(Icons.Filled.Close, null) }
                    }
                }
                TextButton(onClick = { pickTree.launch(null) }) { Text("Ordner oder Speicher hinzufügen") }
            }
        },
        confirmButton = { TextButton(onClick = onSaveAndScan) { Text("Speichern und Scannen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Schließen") } }
    )
}
