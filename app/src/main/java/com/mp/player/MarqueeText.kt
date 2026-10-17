package com.mp.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay

/**
 * Zeigt Text einzeilig an. Passt er nicht in die verfuegbare Breite,
 * scrollt er nach kurzer Pause langsam nach links, pausiert am Ende
 * und scrollt wieder zurueck - genau wie bei Poweramp. Passt der Text
 * hinein, bleibt er einfach stehen (kein Scrollen, keine Animation).
 *
 * Misst den Text intern UNBEGRENZT (um seine wahre, volle Breite zu
 * kennen), meldet nach aussen aber immer nur die vom Aufrufer
 * vorgegebene Breite - so bleibt das umgebende Zeilenlayout stabil,
 * waehrend der Text selbst per Offset "darueber hinaus" gezeichnet
 * und sauber abgeschnitten wird (clipToBounds).
 *
 * Performance: Die LazyColumn komponiert ohnehin nur sichtbare
 * Zeilen, startet also auch nur fuer sichtbare Titel ueberhaupt einen
 * LaunchedEffect. Ein nicht ueberlaufender Text animiert gar nicht.
 */
@Composable
fun MarqueeText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified
) {
    var containerWidthPx by remember(text) { mutableIntStateOf(0) }
    var textWidthPx by remember(text) { mutableIntStateOf(0) }
    val offsetX = remember(text) { Animatable(0f) }

    val overflowPx = (textWidthPx - containerWidthPx).coerceAtLeast(0)

    LaunchedEffect(text, overflowPx, containerWidthPx) {
        offsetX.snapTo(0f)
        if (overflowPx <= 0 || containerWidthPx <= 0) return@LaunchedEffect

        while (true) {
            delay(1200) // Pause am Anfang
            offsetX.animateTo(
                targetValue = -overflowPx.toFloat(),
                animationSpec = tween(
                    durationMillis = (overflowPx * 18).coerceAtLeast(1200),
                    easing = LinearEasing
                )
            )
            delay(900) // Pause am Ende
            offsetX.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = (overflowPx * 18).coerceAtLeast(1200),
                    easing = LinearEasing
                )
            )
            delay(600)
        }
    }

    Layout(
        content = {
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.offset { IntOffset(offsetX.value.toInt(), 0) }
            )
        },
        modifier = modifier.clipToBounds()
    ) { measurables, constraints ->
        containerWidthPx = constraints.maxWidth
        val placeable = measurables.first().measure(constraints.copy(maxWidth = Constraints.Infinity))
        textWidthPx = placeable.width
        val height = placeable.height
        layout(constraints.maxWidth, height) {
            placeable.placeRelative(0, 0)
        }
    }
}
