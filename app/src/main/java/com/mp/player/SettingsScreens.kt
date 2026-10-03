package com.mp.player

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

private data class SettingsEntry(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val route: String)

private val ENTRIES = listOf(
    SettingsEntry("Equalizer (32 Bänder)", Icons.Filled.GraphicEq, "eq"),
    SettingsEntry("Audio", Icons.Filled.VolumeUp, "settings/audio"),
    SettingsEntry("Wiedergabe & Lautstärke", Icons.Filled.Equalizer, "settings/playback"),
    SettingsEntry("Audioanalyse (BPM, Lautheit)", Icons.Filled.Analytics, "analysis"),
    SettingsEntry("Bibliothek", Icons.Filled.Folder, "settings/library"),
    SettingsEntry("Presets", Icons.Filled.Tune, "presets"),
    SettingsEntry("Hi-Res", Icons.Filled.HighQuality, "settings/hires")
)

@Composable
fun SettingsHomeScreen(nav: NavController) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Einstellungen") { nav.popBackStack() }
        LazyColumn {
            items(ENTRIES) { e ->
                ListItem(
                    headlineContent = { Text(e.title) },
                    leadingContent = { Icon(e.icon, null) },
                    modifier = Modifier.clickable { nav.navigate(e.route) }
                )
                Divider()
            }
        }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) }
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
fun AudioSettingsScreen(nav: NavController) {
    val context = LocalContext.current
    val store = Store(context)
    var dsp by remember { mutableStateOf(store.dsp()) }

    fun update(new: Dsp) {
        dsp = new
        store.saveDsp(new)
        Engine.proc.cfg = new
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Audio") { nav.popBackStack() }
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            item {
                SwitchRow("Equalizer/DSP aktiv", dsp.on) { update(dsp.copy(on = it)) }
                SliderRow("Preamp", dsp.pre, -12f..12f) { update(dsp.copy(pre = it)) }
                SliderRow("Bass", dsp.bass, -12f..12f) { update(dsp.copy(bass = it)) }
                SliderRow("Höhen", dsp.treble, -12f..12f) { update(dsp.copy(treble = it)) }
                SwitchRow("Bass-Boost", dsp.bassBoost) { update(dsp.copy(bassBoost = it)) }
                SwitchRow("Höhen-Boost", dsp.trebleBoost) { update(dsp.copy(trebleBoost = it)) }
                SwitchRow("Limiter", dsp.limiter) { update(dsp.copy(limiter = it)) }
                Divider(Modifier.padding(vertical = 12.dp))
                Text("Hall / Reverb", style = MaterialTheme.typography.titleMedium)
                SliderRow("Hall-Anteil", dsp.reverbMix, 0f..1f) { update(dsp.copy(reverbMix = it)) }
                SliderRow("Raumgröße", dsp.reverbSize, 0f..1f) { update(dsp.copy(reverbSize = it)) }
            }
        }
    }
}

@Composable
fun HiResSettingsScreen(nav: NavController) {
    val context = LocalContext.current
    val store = Store(context)
    val caps = remember { HiRes.activeOutput(context) }
    var hires by remember { mutableStateOf(store.hires) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Hi-Res") { nav.popBackStack() }
        Column(Modifier.padding(16.dp)) {
            if (caps == null) {
                Text("Kein Audioausgang erkannt.")
            } else {
                Text("Aktueller Ausgang: ${caps.deviceName}")
                Text(if (caps.supportsHiRes) "Dieser Ausgang unterstützt Hi-Res." else "Dieser Ausgang unterstützt kein echtes Hi-Res (z.B. Bluetooth = immer komprimiert).")
                Spacer(Modifier.height(12.dp))
                Text("Unterstützte Sample Rates: ${if (caps.sampleRates.isEmpty()) "vom System nicht gemeldet" else caps.sampleRates.joinToString()}")
                Text("Unterstützte Bit-Tiefen: ${if (caps.bitDepths.isEmpty()) "vom System nicht gemeldet" else caps.bitDepths.joinToString()}")
                Spacer(Modifier.height(12.dp))
                SwitchRow("32-Bit-Float-Ausgabe (statt 16 Bit)", hires) {
                    hires = it; store.hires = it
                    Engine.proc.floatOutput = it // wirkt ab dem naechsten Titel
                }
                Text("Wirkt ab dem nächsten Titel. Nimmt der Ausgang Float nicht an, stellt der Player automatisch auf 16 Bit zurück.", style = MaterialTheme.typography.bodySmall)
                if (hires && !caps.supportsHiRes) {
                    Text(
                        "Hinweis: aktueller Ausgang kann das technisch nicht wirklich nutzen - Schalter bleibt an, wirkt sich aber erst bei einem geeigneten Ausgang (kabelgebunden/USB-DAC) aus.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
fun LibrarySettingsScreen(nav: NavController) {
    val context = LocalContext.current
    var showFolderDialog by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Bibliothek") { nav.popBackStack() }
        Column(Modifier.padding(16.dp)) {
            Text("${LibraryState.folders.size} Ordner ausgewählt, ${LibraryState.tracks.size} Titel in der Bibliothek")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { showFolderDialog = true }) { Text("Ordner verwalten") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                scanning = true
                LibraryState.rescan(context) { scanning = false }
            }) { Text("Bibliothek neu einlesen") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { nav.navigate("duplicates") }) { Text("Duplikate suchen") }
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
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

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text("$label: %.1f".format(value))
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
