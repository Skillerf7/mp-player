package com.mp.player

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

/**
 * Zeigt Duplikatgruppen mit allen Fakten. Es wird NIE automatisch geloescht:
 * jede Datei hat einen eigenen "Loeschen"-Knopf mit Sicherheitsabfrage.
 */
@Composable
fun DuplicatesScreen(nav: NavController) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { LibraryState.loadAsync(context) }
    var confirm by remember { mutableStateOf<DupFile?>(null) }
    val groups = DuplicateState.groups

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, null) }
            Text("Duplikate", style = MaterialTheme.typography.headlineSmall)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    "Hier wird nichts automatisch gelöscht. Du siehst jede Gruppe mit Pfad, Größe, Dauer und Audio-Daten " +
                        "und entscheidest selbst, welche Datei weg soll.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                if (DuplicateState.running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(DuplicateState.status, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { DuplicateState.cancel() }, modifier = Modifier.padding(top = 6.dp)) { Text("Abbrechen") }
                } else {
                    Button(
                        onClick = { DuplicateState.start(context) },
                        enabled = LibraryState.tracks.isNotEmpty()
                    ) { Text(if (DuplicateState.finished) "Erneut suchen" else "Duplikate suchen") }
                    if (DuplicateState.finished) {
                        Text(
                            if (groups.isEmpty()) "Keine Duplikate gefunden."
                            else "${groups.size} Gruppen gefunden.",
                            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall
                        )
                    }
                }
            }
            items(groups, key = { it.id }) { g ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (g.kind == DupKind.IDENTICAL) "Identisch (bitgenau gleicher Inhalt) · ${g.files.size} Dateien"
                            else "Ähnlich (gleicher Titel/Interpret, fast gleiche Dauer) · ${g.files.size} Dateien",
                            style = MaterialTheme.typography.titleSmall
                        )
                        g.files.forEach { f ->
                            Divider(Modifier.padding(vertical = 8.dp))
                            DupFileRow(f, onPlay = { playTracks(listOf(f.track), 0) }, onDelete = { confirm = f })
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    confirm?.let { f ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Diese Datei löschen?") },
            text = {
                Text(
                    f.path + "\n\nDie Datei wird endgültig gelöscht. Die anderen Dateien dieser Gruppe bleiben erhalten."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    val ok = TrackOps.deleteEverywhere(context, f.track)
                    if (ok) DuplicateState.removeFile(f.track.uri)
                    Toast.makeText(
                        context,
                        if (ok) "Datei gelöscht"
                        else "Löschen nicht möglich (keine Schreibrechte für diesen Ordner). Ordner in der Ordnerauswahl erneut hinzufügen.",
                        Toast.LENGTH_LONG
                    ).show()
                }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Abbrechen") } }
        )
    }
}

@Composable
private fun DupFileRow(f: DupFile, onPlay: () -> Unit, onDelete: () -> Unit) {
    Column {
        Text(f.track.title, style = MaterialTheme.typography.bodyLarge)
        Text(f.track.artist + " · " + f.track.album, style = MaterialTheme.typography.bodySmall)
        Text(f.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(specs(f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.padding(top = 2.dp)) {
            TextButton(onClick = onPlay) {
                Icon(Icons.Filled.PlayArrow, null)
                Spacer(Modifier.width(4.dp))
                Text("Anhören")
            }
            TextButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(4.dp))
                Text("Löschen", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Nur Werte, die wirklich erkannt wurden - nichts erfinden. */
private fun specs(f: DupFile): String {
    val parts = mutableListOf<String>()
    parts.add(f.format)
    if (f.sizeBytes > 0) parts.add("%.1f MB".format(f.sizeBytes / 1048576.0))
    if (f.track.durationMs > 0) parts.add(formatDurationMs(f.track.durationMs))
    if (f.bitrateKbps > 0) parts.add("${f.bitrateKbps} kbit/s")
    if (f.sampleRate > 0) parts.add("%.1f kHz".format(f.sampleRate / 1000.0))
    if (f.bitDepth > 0) parts.add("${f.bitDepth} Bit")
    if (f.channels > 0) parts.add(if (f.channels == 2) "Stereo" else if (f.channels == 1) "Mono" else "${f.channels} Kanäle")
    return parts.joinToString(" · ")
}

private fun formatDurationMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
