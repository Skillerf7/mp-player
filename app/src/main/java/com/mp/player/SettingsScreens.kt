package com.mp.player
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState

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
    SettingsEntry("KI-Assistent (offline)", Icons.Filled.Chat, "assistant"),
    SettingsEntry("KI-Status & Lernen", Icons.Filled.SmartToy, "settings/ai"),
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
    var dsp by remember { mutableStateOf(Engine.currentDsp(store)) }

    fun update(new: Dsp) {
        Engine.sessionChanged = false
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
    var mode by remember { mutableStateOf(store.hiresOutputMode) }
    var rate by remember { mutableStateOf(store.hiresSampleRate) }
    var bits by remember { mutableStateOf(store.hiresBitDepth) }
    var info by remember { mutableStateOf(HiRes.audioInfoLine(context, store)) }

    fun persist() {
        store.hiresOutputMode = mode
        store.hiresSampleRate = rate
        store.hiresBitDepth = bits
        HiRes.applyToEngine(context, store)
        info = HiRes.audioInfoLine(context, store)
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Hi-Res / Ausgabe") { nav.popBackStack() }
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                "Hi-Res = EQ/DSP in hoher interner Präzision (Double), Ausgabe stabil 16-Bit an dein Gerät " +
                    "(S20 FE meldet oft nur 48 kHz – Float an AudioTrack bricht dort). " +
                    "So läuft Musik zuverlässig, mit vollem Bass/EQ-Headroom wie bei Poweramp intern.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (caps == null) {
                Text("Kein Audioausgang erkannt.")
            } else {
                Text("Aktueller Ausgang: ${caps.deviceName}", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        caps.isBluetooth -> "Bluetooth: oft komprimiert (SBC/AAC/LDAC) – kein kabel-Hi-Res."
                        caps.supportsHiRes -> "Dieser Ausgang meldet Hi-Res-fähige Raten/Tiefen."
                        else -> "Kein echtes Hi-Res laut Geräte-Caps (z. B. nur 48 kHz / 16 Bit)."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Sample Rates: " +
                        if (caps.sampleRates.isEmpty()) "System meldet keine"
                        else caps.sampleRates.joinToString { if (it >= 1000) "${it / 1000} kHz" else "$it" }
                )
                Text(
                    "Bit-Tiefen: " +
                        if (caps.bitDepths.isEmpty()) "System meldet keine"
                        else caps.bitDepths.joinToString()
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("Ausgabe-Modus", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    HiRes.MODE_STANDARD to "Standard",
                    HiRes.MODE_HIRES to "Hi-Res",
                    HiRes.MODE_AUTO to "Auto"
                ).forEach { (id, label) ->
                    FilterChip(
                        selected = mode == id,
                        onClick = { mode = id; persist() },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Sample-Rate (Präferenz)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "96/192 kHz auch wählbar, wenn Caps nur 48 kHz melden – wird erzwungen (Resample). " +
                    "Falls AudioTrack ablehnt → Fallback auf Quellrate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                HiRes.RATE_CHOICES.forEach { r ->
                    val label = if (r == 0) "Auto" else "${r / 1000} kHz"
                    // Alle Raten wählbar (erzwingen) – nicht an Caps sperren
                    FilterChip(
                        selected = rate == r,
                        onClick = { rate = r; persist() },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Bit-Tiefe", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(16 to "16 Bit", 24 to "24 Bit", 32 to "Float32").forEach { (b, label) ->
                    val enabled = caps == null || caps.bitDepths.isEmpty() ||
                        b in caps.bitDepths || (b == 32 && caps.bitDepths.any { it >= 24 })
                    FilterChip(
                        selected = bits == b,
                        onClick = { if (enabled) { bits = b; persist() } },
                        enabled = enabled,
                        label = { Text(label) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Audio-Info", style = MaterialTheme.typography.titleSmall)
            Text(info, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Hi-Res-Modus braucht keinen Float-Sink: interner DSP bleibt präzise, " +
                    "AudioTrack bekommt 16-Bit → kein Abbruch-Toast. Wirkt ab nächstem Titel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
