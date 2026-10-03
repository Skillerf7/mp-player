package com.mp.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlin.math.roundToInt

/** Lautstaerke-Normalisierung (ReplayGain) und Sleep-Timer-Ausblenden. */
@Composable
fun PlaybackSettingsScreen(nav: NavController) {
    val context = LocalContext.current
    val store = remember { Store(context) }
    var mode by remember { mutableStateOf(store.rgMode) }
    var fallback by remember { mutableStateOf(store.rgFallback) }
    var fade by remember { mutableStateOf(store.sleepFadeSec) }

    fun applyRg() { PlayerService.refreshReplayGain?.invoke() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "Zurück") }
            Text("Wiedergabe & Lautstärke", style = MaterialTheme.typography.headlineSmall)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text("ReplayGain / Normalisierung", style = MaterialTheme.typography.titleMedium)
            Text(
                "Gleicht die Lautstärke zwischen Titeln an (Ziel −18 LUFS). Liest ReplayGain-Tags aus der Datei; " +
                    "ohne Tag kann die Lautheit aus der Audioanalyse verwendet werden.",
                style = MaterialTheme.typography.bodySmall
            )
            listOf(0 to "Aus", 1 to "Titel-Gain", 2 to "Album-Gain (sonst Titel-Gain)").forEach { (v, label) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == v, onClick = { mode = v; store.rgMode = v; applyRg() })
                    Text(label)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ohne Tag: Lautheit aus Analyse nutzen", Modifier.weight(1f))
                Switch(checked = fallback, onCheckedChange = { fallback = it; store.rgFallback = it; applyRg() })
            }
            Text(
                "Positive Anhebungen sind auf +6 dB begrenzt; bei Analysewerten wird zusätzlich so begrenzt, dass der Spitzenpegel nicht über 0 dBFS steigt.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Divider(Modifier.padding(vertical = 16.dp))

            Text("Sleep-Timer: Ausblenden", style = MaterialTheme.typography.titleMedium)
            Text(
                if (fade == 0) "Aus – Musik stoppt abrupt" else "Lautstärke sinkt in den letzten $fade Sekunden auf 0",
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = fade.toFloat(),
                onValueChange = { fade = it.roundToInt(); store.sleepFadeSec = fade; SleepTimer.fadeSeconds = fade },
                valueRange = 0f..30f
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
