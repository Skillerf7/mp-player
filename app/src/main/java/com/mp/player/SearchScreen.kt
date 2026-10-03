package com.mp.player

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.delay

/**
 * Suche in Titel, Interpret, Album, Genre und Dateiname. Der Suchtext jedes Titels wird nur einmal pro
 * Bibliotheksstand aufgebaut (nicht pro Tastendruck), die Eingabe wird 150 ms entprellt, mehrere Woerter
 * muessen alle vorkommen. Gesucht wird komplett im Speicher - keine Datei- oder Datenbankzugriffe.
 */
@Composable
fun SearchScreen(nav: NavController) {
    var query by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf("") }
    LaunchedEffect(query) { delay(150); applied = query }

    val index = remember(LibraryState.tracks) {
        LibraryState.tracks.map { t ->
            t to (t.title + "\n" + t.artist + "\n" + t.album + "\n" + t.genre + "\n" + t.fileName).lowercase()
        }
    }
    val results = remember(applied, index) {
        val terms = applied.lowercase().split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) emptyList() else index.filter { (_, k) -> terms.all { k.contains(it) } }.map { it.first }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "Zurück") }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Titel, Interpret, Album, Genre, Datei…") },
                singleLine = true,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
        }
        TrackListView(
            results,
            emptyText = if (applied.isBlank()) "" else "Keine Treffer."
        )
    }
}
