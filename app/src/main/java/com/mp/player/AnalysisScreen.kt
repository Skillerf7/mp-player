package com.mp.player

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mp.player.ai.LocalOnnxBridge
import com.mp.player.ai.LyricsProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class AnSort { BPM, LOUDNESS, TITLE }

@Composable
fun AnalysisScreen(nav: NavController) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { LibraryState.loadAsync(context); AnalysisState.loadResults(context) }
    // ONNX-Status: Modell-Init kopiert beim ersten Mal ~24 MB -> nie auf dem Main-Thread
    var onnxStatus by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        onnxStatus = withContext(Dispatchers.IO) {
            try { LocalOnnxBridge.refresh(context); LocalOnnxBridge.statusLine() } catch (e: Throwable) { "ONNX Genre: nicht verfügbar" }
        }
    }
    var sort by remember { mutableStateOf(AnSort.BPM) }
    var minBpm by remember { mutableStateOf(0f) }
    val results = AnalysisState.results

    val rows = remember(results, LibraryState.tracks, sort, minBpm) {
        val byUri = LibraryState.tracks.associateBy { it.uri }
        val list = results.values.mapNotNull { a -> byUri[a.uri]?.let { it to a } }
            .filter { (_, a) -> a.channels > 0 && (minBpm < 1f || a.bpm >= minBpm) }
        when (sort) {
            AnSort.BPM -> list.sortedByDescending { it.second.bpm }
            AnSort.LOUDNESS -> list.sortedByDescending { if (it.second.lufs.isNaN()) -999f else it.second.lufs }
            AnSort.TITLE -> list.sortedBy { it.first.title.lowercase() }
        }
    }
    val analyzed = results.values.count { it.channels > 0 }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "Zurück") }
            Text("Audioanalyse", style = MaterialTheme.typography.headlineSmall)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text(
                    "Misst pro Titel Lautheit (LUFS), Spitzenpegel, RMS und schätzt das Tempo (BPM). Liest außerdem eingebettete " +
                        "Songtexte aus den Datei-Tags (MP3, FLAC, OGG, M4A) und schätzt daraus die Stimmung - der Assistent nutzt das " +
                        "für Wut, Trauer, Einsamkeit, Romantik und mehr. Alles lokal. Läuft im Hintergrund mit niedriger Priorität, " +
                        "nur wenn du es startest, und stoppt bei Akku unter 15 %. Lange Titel: nur die ersten 5 Minuten Audio. " +
                        "BPM ist eine Schätzung - ohne klaren Beat steht „–“.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text("$analyzed von ${LibraryState.tracks.size} Titeln analysiert", style = MaterialTheme.typography.titleSmall)
                val withLyrics = AnalysisState.lyrics.values.count { it.hasText }
                val checked = AnalysisState.lyrics.size
                Text(
                    "$withLyrics mit eingebettetem Songtext" + if (checked > 0) " (von $checked geprüften Titeln)" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (onnxStatus.isNotBlank()) Text(onnxStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                if (AnalysisState.running) {
                    LinearProgressIndicator(
                        progress = if (AnalysisState.total > 0) AnalysisState.done.toFloat() / AnalysisState.total else 0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${AnalysisState.done} / ${AnalysisState.total} · ${AnalysisState.current}",
                        style = MaterialTheme.typography.bodySmall, maxLines = 1
                    )
                    OutlinedButton(onClick = { AnalysisState.cancel() }, modifier = Modifier.padding(top = 6.dp)) { Text("Abbrechen") }
                } else {
                    Button(onClick = { AnalysisState.start(context) }, enabled = LibraryState.tracks.isNotEmpty()) { Text("Analyse starten") }
                    if (AnalysisState.message.isNotBlank()) Text(AnalysisState.message, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(sort == AnSort.BPM, { sort = AnSort.BPM }, label = { Text("BPM") })
                    FilterChip(sort == AnSort.LOUDNESS, { sort = AnSort.LOUDNESS }, label = { Text("Lautheit") })
                    FilterChip(sort == AnSort.TITLE, { sort = AnSort.TITLE }, label = { Text("Titel") })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (minBpm < 1f) "Alle Tempi" else "Ab ${minBpm.roundToInt()} BPM", Modifier.width(110.dp), style = MaterialTheme.typography.bodyMedium)
                    Slider(value = minBpm, onValueChange = { minBpm = (it / 5f).roundToInt() * 5f }, valueRange = 0f..200f, modifier = Modifier.weight(1f))
                }
                if (minBpm >= 1f && rows.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { playTracks(rows.map { it.first }, 0) }) { Text("${rows.size} Titel abspielen") }
                        OutlinedButton(onClick = {
                            val name = "Songs ab ${minBpm.roundToInt()} BPM"
                            val res = PlaylistStore.create(context, name)
                            if (res == PlaylistResult.OK || res == PlaylistResult.NAME_EXISTS) {
                                val added = PlaylistStore.addTracks(context, name, rows.map { it.first })
                                Toast.makeText(context, "„$name“: $added Titel hinzugefügt", Toast.LENGTH_LONG).show()
                            }
                        }) { Text("Als Wiedergabeliste") }
                    }
                }
                Divider(Modifier.padding(top = 8.dp))
            }
            items(rows, key = { it.first.uri }) { (t, a) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text(t.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    val ly = AnalysisState.lyrics[t.uri]
                    if (ly != null && ly.hasText) {
                        val moods = LyricsProfile(LyricsProfile.decode(ly.profile), 0).top(2).joinToString(", ") { it.label }
                        Text(
                            "Songtext: " + (if (moods.isEmpty()) "neutral" else moods),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        listOf(
                            "BPM " + (if (a.bpm > 0f) "%.1f".format(a.bpm) else "–"),
                            if (a.lufs.isNaN()) "– LUFS" else "%.1f LUFS".format(a.lufs),
                            "Peak %.1f dBFS".format(a.peakDb),
                            "RMS %.1f dBFS".format(a.rmsDb)
                        ).joinToString(" · ") + if (a.partial) " · (nur Anfang)" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Divider()
            }
        }
    }
}
