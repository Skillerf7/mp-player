package com.mp.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

private val BUILT_IN: Map<String, Dsp> get() = Eq.BUILT_IN

/** Presets: auswaehlen (wirkt sofort auf die Wiedergabe), eigene Einstellung speichern, eigene loeschen. */
@Composable
fun PresetsScreen(nav: NavController) {
    val context = LocalContext.current
    val store = remember { Store(context) }
    var saved by remember { mutableStateOf(store.presets()) }
    var current by remember { mutableStateOf(store.dsp()) }
    var showSave by remember { mutableStateOf(false) }
    var deleteName by remember { mutableStateOf<String?>(null) }
    var renameName by remember { mutableStateOf<String?>(null) }

    fun apply(d: Dsp) {
        val on = d.copy(on = true)
        current = on
        store.saveDsp(on)
        Engine.proc.cfg = on
    }

    fun isActive(d: Dsp) = d.copy(on = current.on) == current

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "Zurück") }
            Text("Presets", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { showSave = true }) { Text("Aktuelle speichern") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item { Text("Integriert", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 8.dp)) }
            items(BUILT_IN.entries.toList(), key = { "b-" + it.key }) { (name, d) ->
                ListItem(
                    headlineContent = { Text(name) },
                    trailingContent = { if (isActive(d)) Icon(Icons.Filled.Check, "Aktiv") },
                    modifier = Modifier.clickable { apply(d) }
                )
                Divider()
            }
            item { Text("Eigene Presets", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp)) }
            if (saved.isEmpty()) {
                item { Text("Noch keine eigenen Presets. Stelle Klang ein und tippe oben auf „Aktuelle speichern“.", Modifier.padding(16.dp)) }
            }
            items(saved.entries.toList(), key = { "u-" + it.key }) { (name, d) ->
                ListItem(
                    headlineContent = { Text(name) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isActive(d)) Icon(Icons.Filled.Check, "Aktiv")
                            IconButton(onClick = { renameName = name }) { Icon(Icons.Filled.Edit, "Preset umbenennen") }
                            IconButton(onClick = { deleteName = name }) { Icon(Icons.Filled.Delete, "Preset löschen") }
                        }
                    },
                    modifier = Modifier.clickable { apply(d) }
                )
                Divider()
            }
        }
    }

    if (showSave) {
        NameDialog(
            title = "Preset speichern",
            confirmLabel = "Speichern",
            initial = "",
            validate = { n ->
                if (BUILT_IN.keys.any { it.equals(n, true) } || saved.keys.any { it.equals(n, true) }) "Diesen Namen gibt es schon." else null
            },
            onConfirm = { n ->
                showSave = false
                saved = saved + (n to current)
                store.savePresets(saved)
            },
            onDismiss = { showSave = false }
        )
    }

    renameName?.let { old ->
        NameDialog(
            title = "Preset umbenennen",
            confirmLabel = "Umbenennen",
            initial = old,
            validate = { n ->
                if (BUILT_IN.keys.any { it.equals(n, true) } || saved.keys.any { it.equals(n, true) && !it.equals(old, false) }) "Diesen Namen gibt es schon." else null
            },
            onConfirm = { n ->
                // Reihenfolge bleibt erhalten
                saved = saved.entries.associate { (k, v) -> (if (k == old) n else k) to v }
                store.savePresets(saved)
                renameName = null
            },
            onDismiss = { renameName = null }
        )
    }

    deleteName?.let { name ->
        AlertDialog(
            onDismissRequest = { deleteName = null },
            title = { Text("Preset löschen?") },
            text = { Text("„$name“ wird entfernt.") },
            confirmButton = {
                TextButton(onClick = {
                    saved = saved - name
                    store.savePresets(saved)
                    deleteName = null
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { deleteName = null }) { Text("Abbrechen") } }
        )
    }
}
