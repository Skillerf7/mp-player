package com.mp.player

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

/** Uebersicht aller Wiedergabelisten: oeffnen, erstellen, umbenennen, loeschen (immer mit Rueckfrage). */
@Composable
fun PlaylistOverview(nav: NavController) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { PlaylistStore.ensureLoaded(context) }

    val lists = PlaylistStore.playlists
    var showCreate by remember { mutableStateOf(false) }
    var deleteName by remember { mutableStateOf<String?>(null) }
    var renameName by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        if (lists.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(if (PlaylistStore.ready) "Noch keine Wiedergabelisten." else "Wird geladen ...")
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(lists.entries.toList(), key = { it.key }) { (name, ids) ->
                    var menu by remember { mutableStateOf(false) }
                    ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = { Text("${ids.size} Titel") },
                        leadingContent = { Icon(Icons.Filled.QueueMusic, null) },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Mehr") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Umbenennen") },
                                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                        onClick = { menu = false; renameName = name }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Löschen") },
                                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                        onClick = { menu = false; deleteName = name }
                                    )
                                }
                            }
                        },
                        modifier = Modifier.clickable { nav.navigate(NavArgs.playlistRoute(name)) }
                    )
                    Divider()
                }
            }
        }
        Button(onClick = { showCreate = true }, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Icon(Icons.Filled.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Neue Wiedergabeliste erstellen")
        }
    }

    if (showCreate) {
        NameDialog(
            title = "Neue Wiedergabeliste erstellen",
            confirmLabel = "Erstellen",
            initial = "",
            validate = { n -> if (lists.keys.any { it.equals(n, ignoreCase = true) }) "Diesen Namen gibt es schon." else null },
            onConfirm = { n ->
                showCreate = false
                if (PlaylistStore.create(context, n) != PlaylistResult.OK) {
                    Toast.makeText(context, "Wiedergabeliste konnte nicht erstellt werden.", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showCreate = false }
        )
    }

    renameName?.let { old ->
        NameDialog(
            title = "Wiedergabeliste umbenennen",
            confirmLabel = "Umbenennen",
            initial = old,
            validate = { n ->
                if (!n.equals(old, ignoreCase = true) && lists.keys.any { it.equals(n, ignoreCase = true) }) "Diesen Namen gibt es schon." else null
            },
            onConfirm = { n ->
                renameName = null
                PlaylistStore.rename(context, old, n)
            },
            onDismiss = { renameName = null }
        )
    }

    deleteName?.let { name ->
        ConfirmDeletePlaylistDialog(
            onConfirm = {
                deleteName = null
                PlaylistStore.delete(context, name)
            },
            onDismiss = { deleteName = null }
        )
    }
}

/** Inhalt einer Wiedergabeliste. */
@Composable
fun PlaylistScreen(name: String, nav: NavController) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { PlaylistStore.ensureLoaded(context) }

    val exists = PlaylistStore.playlists.containsKey(name)
    val ids = PlaylistStore.playlists[name]
    val tracks = PlaylistStore.tracksOf(name)
    var menu by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    // Wurde die Playlist geloescht (z. B. ueber dieses Menue), zurueck zur Uebersicht
    LaunchedEffect(exists, PlaylistStore.ready) {
        if (PlaylistStore.ready && !exists && !leaving) nav.popBackStack()
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "Zurück") }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                Text("${ids?.size ?: 0} Titel", style = MaterialTheme.typography.bodySmall)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Mehr") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Umbenennen") },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = { menu = false; showRename = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Wiedergabeliste löschen") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menu = false; showDelete = true }
                    )
                }
            }
        }

        TrackListView(
            list = tracks,
            playlistName = name,
            emptyText = if (ids.isNullOrEmpty())
                "Diese Wiedergabeliste ist leer. Füge Titel über das Menü ⋮ → „Zur Wiedergabeliste hinzufügen“ hinzu."
            else
                "Die Titel dieser Wiedergabeliste sind in der Bibliothek nicht (mehr) vorhanden."
        )
    }

    if (showRename) {
        NameDialog(
            title = "Wiedergabeliste umbenennen",
            confirmLabel = "Umbenennen",
            initial = name,
            validate = { n ->
                if (!n.equals(name, ignoreCase = true) &&
                    PlaylistStore.playlists.keys.any { it.equals(n, ignoreCase = true) }
                ) "Diesen Namen gibt es schon." else null
            },
            onConfirm = { n ->
                showRename = false
                if (PlaylistStore.rename(context, name, n) == PlaylistResult.OK) {
                    // Die Route traegt den alten Namen -> auf den neuen wechseln.
                    // leaving verhindert, dass der Abgang des alten Bildschirms den neuen gleich wieder schliesst.
                    leaving = true
                    nav.popBackStack()
                    nav.navigate(NavArgs.playlistRoute(n.trim()))
                }
            },
            onDismiss = { showRename = false }
        )
    }

    if (showDelete) {
        ConfirmDeletePlaylistDialog(
            onConfirm = {
                showDelete = false
                PlaylistStore.delete(context, name) // loescht nur die Playlist, nie Musikdateien
            },
            onDismiss = { showDelete = false }
        )
    }
}

/** Auswahl, in welche Wiedergabeliste Titel gelegt werden - oder eine neue erstellen und direkt hinzufuegen. */
@Composable
fun PlaylistPickerDialog(tracks: List<Track>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { PlaylistStore.ensureLoaded(context) }

    val lists = PlaylistStore.playlists
    var creating by remember { mutableStateOf(false) }

    fun finish(name: String) {
        val added = PlaylistStore.addTracks(context, name, tracks)
        val skipped = tracks.size - added
        val msg = when {
            added == 0 -> "Bereits in „$name“ enthalten."
            skipped > 0 -> "$added Titel zu „$name“ hinzugefügt ($skipped waren schon enthalten)."
            else -> "$added Titel zu „$name“ hinzugefügt."
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        onDismiss()
    }

    if (creating) {
        NameDialog(
            title = "Neue Wiedergabeliste erstellen",
            confirmLabel = "Erstellen und hinzufügen",
            initial = "",
            validate = { n -> if (lists.keys.any { it.equals(n, ignoreCase = true) }) "Diesen Namen gibt es schon." else null },
            onConfirm = { n ->
                if (PlaylistStore.create(context, n) == PlaylistResult.OK) finish(n.trim())
                else {
                    creating = false
                    Toast.makeText(context, "Wiedergabeliste konnte nicht erstellt werden.", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { creating = false }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Zur Wiedergabeliste hinzufügen") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    ListItem(
                        headlineContent = { Text("Neue Wiedergabeliste erstellen") },
                        leadingContent = { Icon(Icons.Filled.Add, null) },
                        modifier = Modifier.clickable { creating = true }
                    )
                    Divider()
                    lists.forEach { (name, ids) ->
                        ListItem(
                            headlineContent = { Text(name) },
                            supportingContent = { Text("${ids.size} Titel") },
                            leadingContent = { Icon(Icons.Filled.QueueMusic, null) },
                            modifier = Modifier.clickable { finish(name) }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
        )
    }
}

@Composable
fun ConfirmDeletePlaylistDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wiedergabeliste löschen?") },
        text = { Text("Die Wiedergabeliste wird entfernt. Die Musikdateien bleiben erhalten.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Löschen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

/** Namenseingabe mit Pruefung (leer / schon vergeben). */
@Composable
fun NameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    validate: (String) -> String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val error = if (text.isBlank()) null else validate(text.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") },
                isError = error != null,
                supportingText = { if (error != null) Text(error) }
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && error == null, onClick = { onConfirm(text.trim()) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}
