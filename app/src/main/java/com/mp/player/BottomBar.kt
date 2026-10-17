package com.mp.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun BottomNavBar(
    onCategories: () -> Unit,
    onVisualizer: () -> Unit,
    onSearch: () -> Unit,
    onMenu: () -> Unit
) {
    NavigationBar {
        NavigationBarItem(false, onClick = onCategories, icon = { Icon(Icons.Filled.GridView, null) }, label = { Text("Bibliothek") })
        NavigationBarItem(false, onClick = onVisualizer, icon = { Icon(Icons.Filled.GraphicEq, null) }, label = { Text("Player") })
        NavigationBarItem(false, onClick = onSearch, icon = { Icon(Icons.Filled.Search, null) }, label = { Text("Suche") })
        // Punkt 8: dieser Button öffnet AUSSCHLIESSLICH die Einstellungen, nicht mehr "Wiedergabeliste hinzufügen".
        NavigationBarItem(false, onClick = onMenu, icon = { Icon(Icons.Filled.Menu, null) }, label = { Text("Menü") })
    }
}

/**
 * Globaler Mini-Player. Liest ausschliesslich den zentralen PlaybackState (kein eigener Listener),
 * deshalb ist auf allen Screens sofort derselbe Titel/Status sichtbar.
 */
@Composable
fun MiniPlayerBar(onOpen: () -> Unit) {
    val mediaId = PlaybackState.mediaId
    if (PlaybackState.itemCount == 0 || mediaId == null) return

    val controller = PlayerBridge.controller
    val isPlaying = PlaybackState.isPlaying
    val cover = rememberCover(mediaId, 96)

    var progress by remember { mutableStateOf(0f) }
    LaunchedEffect(mediaId, isPlaying, PlaybackState.durationMs) {
        while (true) {
            val d = PlaybackState.durationMs
            progress = if (d > 0) (PlaybackState.positionMs().toFloat() / d).coerceIn(0f, 1f) else 0f
            if (!isPlaying) break
            delay(500)
        }
    }

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onOpen() }
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (cover != null) Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.Filled.MusicNote, null)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(PlaybackState.title.ifBlank { "Unbekannter Titel" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text(PlaybackState.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { controller?.seekToPrevious() }, enabled = controller != null) {
                Icon(Icons.Filled.SkipPrevious, "Zurück")
            }
            IconButton(
                onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                enabled = controller != null
            ) {
                Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (isPlaying) "Pause" else "Wiedergabe")
            }
            IconButton(onClick = { controller?.seekToNext() }, enabled = controller != null) {
                Icon(Icons.Filled.SkipNext, "Weiter")
            }
        }
        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().height(2.dp))
    }
}
