package com.mp.player.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * KI-Status, Lernstatistik und Datenschutz.
 * Liest die **gleiche** [AssistantSession]-Instanz (kein zweites Memory).
 */
@Composable
fun AiSettingsScreen(nav: NavController) {
    val context = LocalContext.current
    val privacy = remember { AiPrivacyStore(context) }
    var learning by remember { mutableStateOf(privacy.learningEnabled) }
    var memoryOn by remember { mutableStateOf(privacy.memoryEnabled) }
    var storeChat by remember { mutableStateOf(privacy.storeConversations) }
    var storeEphemeral by remember { mutableStateOf(privacy.storeEphemeralPrefs) }

    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<AiModelStatus?>(null) }
    var stats by remember { mutableStateOf<AiLearningStats?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var clearResult by remember { mutableStateOf<String?>(null) }

    val epoch = AssistantSession.statsEpoch

    LaunchedEffect(epoch, learning, memoryOn) {
        loading = true
        val pair = withContext(Dispatchers.Default) {
            runCatching { AssistantSession.snapshotStatsOrInit(context) }.getOrNull()
        }
        status = pair?.first
        stats = pair?.second
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Zurück")
            }
            Text("KI & Lernen", style = MaterialTheme.typography.headlineSmall)
        }

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text("Modellstatus", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (loading && status == null) {
                Text("Lade Status…", style = MaterialTheme.typography.bodyMedium)
            } else {
                val s = status
                StatusLine("PlayerBrain (lokal, Intent/Regeln)", if (s?.playerBrainActive == true) "aktiv" else "noch nicht verfügbar")
                StatusLine("Intent-Modell (assets)", if (s?.intentModelLoaded == true) "geladen" else "noch nicht verfügbar")
                StatusLine("Offline-Modus", "aktiv")
                StatusLine("Memory", if (memoryOn) "aktiviert" else "deaktiviert")
                StatusLine("Personal Learning", if (learning) "aktiviert" else "deaktiviert")
                Text(
                    s?.coordinatorLabel ?: "Status wird nach dem ersten KI-Start aktualisiert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "Kein generatives Cloud-LLM. Intent und Empfehlungen laufen lokal auf dem Gerät.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(12.dp))

            Text("Lernstatistik", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (loading && stats == null) {
                Text("Lade Statistik…", style = MaterialTheme.typography.bodyMedium)
            } else if (stats == null) {
                Text(
                    "Noch nicht verfügbar – öffne einmal den KI-Chat oder warte auf den Ladevorgang.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                val st = stats!!
                StatusLine("Musikpräferenzen (Like/Dislike)", st.preferenceCount.toString())
                StatusLine("Feedback-Ereignisse", st.feedbackEvents.toString())
                StatusLine("Gesprächserinnerungen", st.memoryItemCount.toString())
                AffinityLine("Bass", st.bassAffinity)
                AffinityLine("Tempo", st.tempoAffinity)
                AffinityLine("Energy", st.energyAffinity)
                AffinityLine("Mood", st.moodAffinity)
            }

            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(12.dp))

            Text("Datenschutz", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SwitchRow("Personal Learning (Hörverhalten & Dimensionen)", learning) {
                learning = it
                privacy.learningEnabled = it
            }
            Text(
                "Aus: kein PersonalLearning-Bonus und kein Chat-Dimensions-Lernen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SwitchRow("Memory speichern (Vorlieben & Fakten)", memoryOn) {
                memoryOn = it
                privacy.memoryEnabled = it
            }
            Text(
                "Aus: MemoryEngine schreibt keine neuen Einträge.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SwitchRow("Gesprächsinhalte merken", storeChat) {
                storeChat = it
                privacy.storeConversations = it
            }
            SwitchRow("Situative Prefs („heute…“)", storeEphemeral) {
                storeEphemeral = it
                privacy.storeEphemeralPrefs = it
            }
            Text(
                "Ausgeschaltete Schalter stoppen nur neue Speicherung. Vorhandene Daten bleiben.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                privacy.resetLearningFlags()
                learning = privacy.learningEnabled
                memoryOn = privacy.memoryEnabled
                storeChat = privacy.storeConversations
                storeEphemeral = privacy.storeEphemeralPrefs
            }) { Text("Schalter auf Standard") }
            TextButton(onClick = { showClearConfirm = true }) {
                Text("Gespeicherte Erinnerungen löschen…")
            }
            clearResult?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Erinnerungen löschen?") },
            text = {
                Text("Alle Memory-Einträge (Vorlieben, Fakten, Muster) werden unwiderruflich gelöscht. Musik und Playlists bleiben.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    val n = AssistantSession.clearMemoryConfirmed(context)
                    clearResult = "$n Einträge gelöscht."
                }) { Text("Löschen") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Abbrechen") }
            }
        )
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AffinityLine(label: String, value: Float?) {
    val text = when {
        value == null -> "—"
        value > 0.05f -> "+${"%.2f".format(value)}"
        value < -0.05f -> "%.2f".format(value)
        else -> "neutral"
    }
    StatusLine(label, text)
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
