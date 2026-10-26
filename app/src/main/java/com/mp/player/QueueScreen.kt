package com.mp.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.navigation.NavController
import kotlin.math.roundToInt

private data class QueueRow(val index: Int, val title: String, val artist: String)

/** Zeigt die Queue in tatsaechlicher Abspielreihenfolge (bei Shuffle: in der gemischten Reihenfolge). */
private fun buildRows(c: MediaController?, shuffle: Boolean): List<QueueRow> {
    if (c == null) return emptyList()
    return try {
        val n = c.mediaItemCount
        val order = ArrayList<Int>(n)
        if (shuffle) {
            val tl = c.currentTimeline
            var i = tl.getFirstWindowIndex(true)
            while (i != C.INDEX_UNSET && order.size < n) {
                order.add(i)
                i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
            }
        }
        if (order.size != n) { order.clear(); order.addAll(0 until n) } // Fallback: Listenreihenfolge
        order.map {
            val md = c.getMediaItemAt(it).mediaMetadata
            QueueRow(it, md.title?.toString() ?: "Unbekannt", md.artist?.toString() ?: "")
        }
    } catch (e: Exception) {
        emptyList()
    }
}

@Composable
fun QueueScreen(nav: NavController) {
    val controller = PlayerBridge.controller
    val version = PlaybackState.queueVersion
    val current = PlaybackState.currentIndex
    val shuffle = PlaybackState.shuffle
    val rows = remember(controller, version, shuffle, current) { buildRows(controller, shuffle) }

    var confirmClear by remember { mutableStateOf(false) }
    val rowHeight = 64.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    var dragFrom by remember { mutableStateOf(-1) }
    var dragDy by remember { mutableStateOf(0f) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "Zurück") }
            Column(Modifier.weight(1f)) {
                Text("Warteschlange", style = MaterialTheme.typography.headlineSmall)
                Text("${rows.size} Titel", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { confirmClear = true }, enabled = rows.isNotEmpty()) { Text("Leeren") }
        }
        if (shuffle) {
            Text(
                "Zufallswiedergabe aktiv: Die Liste zeigt die gemischte Reihenfolge. Umsortieren ist dabei ausgeschaltet.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        Text(
            "Entfernen nimmt einen Titel nur aus der Warteschlange - die Musikdatei bleibt erhalten.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Die Warteschlange ist leer.") }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(rows, key = { pos, r -> "$pos-${r.index}" }) { pos, r ->
                    val isCurrent = r.index == current
                    val dragging = dragFrom == pos
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = if (dragging) dragDy else 0f }
                            .alpha(if (dragging) 0.85f else 1f)
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable {
                                controller?.let { c ->
                                    if (r.index in 0 until c.mediaItemCount) {
                                        c.seekToDefaultPosition(r.index)
                                        c.play()
                                    }
                                }
                            }
                            .padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                            if (isCurrent) Icon(Icons.Filled.PlayArrow, "Läuft gerade", tint = MaterialTheme.colorScheme.primary)
                            else Text("${pos + 1}", style = MaterialTheme.typography.bodySmall)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            Text(r.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = {
                            controller?.let { c -> if (r.index in 0 until c.mediaItemCount) c.removeMediaItem(r.index) }
                        }) { Icon(Icons.Filled.Close, "Aus Warteschlange entfernen") }

                        // Drag & Drop: Griff ziehen, beim Loslassen wird der Titel verschoben
                        if (!shuffle) {
                            Icon(
                                Icons.Filled.DragHandle, "Verschieben",
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(28.dp)
                                    .pointerInput(pos, rows.size) {
                                        detectDragGestures(
                                            onDragStart = { dragFrom = pos; dragDy = 0f },
                                            onDragCancel = { dragFrom = -1; dragDy = 0f },
                                            onDragEnd = {
                                                val to = (pos + (dragDy / rowPx).roundToInt()).coerceIn(0, rows.size - 1)
                                                val from = pos
                                                dragFrom = -1; dragDy = 0f
                                                if (to != from) {
                                                    controller?.let { c ->
                                                        if (from < c.mediaItemCount && to < c.mediaItemCount) c.moveMediaItem(from, to)
                                                    }
                                                }
                                            }
                                        ) { change, amount ->
                                            change.consume()
                                            dragDy += amount.y
                                        }
                                    }
                            )
                        } else {
                            Spacer(Modifier.width(12.dp))
                        }
                    }
                    Divider()
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Warteschlange leeren?") },
            text = { Text("Die Wiedergabe stoppt. Musikdateien werden nicht gelöscht.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; controller?.clearMediaItems() }) { Text("Leeren") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Abbrechen") } }
        )
    }
}
