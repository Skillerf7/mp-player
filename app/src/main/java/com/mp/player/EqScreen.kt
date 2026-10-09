package com.mp.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

private val GRID_F = DoubleArray(160) { Eq.F_MIN * (Eq.F_MAX / Eq.F_MIN).pow(it / 159.0) }
private val LABEL_F = listOf(20.0 to "20", 50.0 to "50", 100.0 to "100", 200.0 to "200", 500.0 to "500",
    1000.0 to "1k", 2000.0 to "2k", 5000.0 to "5k", 10000.0 to "10k", 20000.0 to "20k")

private fun fx(f: Double, w: Float): Float = (ln(f / Eq.F_MIN) / ln(Eq.F_MAX / Eq.F_MIN)).toFloat().coerceIn(0f, 1f) * w
private fun gy(g: Float, h: Float): Float = h * (1f - (g + Eq.MAX_GAIN) / (2 * Eq.MAX_GAIN))
private fun yToGain(y: Float, h: Float): Float =
    (((1f - y / h) * 2 * Eq.MAX_GAIN - Eq.MAX_GAIN).coerceIn(-Eq.MAX_GAIN, Eq.MAX_GAIN) * 10f).roundToInt() / 10f

private fun nearest(bands: List<Band>, x: Float, w: Float): Int {
    var best = 0; var bd = Float.MAX_VALUE
    bands.forEachIndexed { i, b -> val d = abs(fx(b.f.toDouble(), w) - x); if (d < bd) { bd = d; best = i } }
    return best
}

private fun fmtHz(f: Float) = if (f >= 1000f) "%.2f kHz".format(f / 1000f) else "%.0f Hz".format(f)

/**
 * 32-Band-Equalizer. Die Kurve ist der echte Gesamt-Frequenzgang der DSP-Kette (Baender + Bass/Hoehen).
 * Bedienung: Tippen = Band waehlen, Ziehen = Gain malen (Finger quer ueber mehrere Baender = Kurve zeichnen),
 * Doppeltipp = Band auf 0 dB. Frequenz, Gain und Guete des gewaehlten Bandes feinstellen ueber die Regler darunter.
 */
@Composable
fun EqScreen(nav: NavController) {
    val context = LocalContext.current
    val store = remember { Store(context) }
    var dsp by remember { mutableStateOf(Engine.currentDsp(store)) }
    var sel by remember { mutableStateOf(Eq.BAND_COUNT / 2) }
    var userPresets by remember { mutableStateOf(store.presets()) }
    var showSave by remember { mutableStateOf(false) }

    fun change(new: Dsp) { Engine.sessionChanged = false; dsp = new; Engine.proc.cfg = new }

    // Aelterer Stand mit anderer Bandzahl -> auf 32 Baender umrechnen (Klang bleibt ungefaehr gleich)
    LaunchedEffect(Unit) {
        if (dsp.bands.size != Eq.BAND_COUNT) change(dsp.copy(bands = Eq.resampleTo32(dsp.bands)))
    }
    // Speichern entprellt (beim Ziehen entstehen viele Aenderungen), und sicher beim Verlassen des Screens
    LaunchedEffect(dsp) { delay(400); if (!Engine.sessionChanged) store.saveDsp(dsp) }
    val latest by rememberUpdatedState(dsp)
    DisposableEffect(Unit) { onDispose { if (!Engine.sessionChanged) store.saveDsp(latest) } }

    val bandIdx = sel.coerceIn(0, (dsp.bands.size - 1).coerceAtLeast(0))
    fun setBand(i: Int, f: (Band) -> Band) {
        if (i !in dsp.bands.indices) return
        change(dsp.copy(bands = dsp.bands.toMutableList().also { it[i] = f(it[i]) }))
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "Zurück") }
            Text("Equalizer", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Switch(checked = dsp.on, onCheckedChange = { change(dsp.copy(on = it)) })
            Spacer(Modifier.width(12.dp))
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            // ---- Presets ----
            val allPresets = Eq.BUILT_IN.entries.map { it.key to it.value } + userPresets.entries.map { it.key to it.value }
            val matching = allPresets.firstOrNull { (_, d) -> d.copy(on = dsp.on) == dsp }?.first
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "custom") {
                    FilterChip(selected = matching == null, onClick = {}, label = { Text("Custom") })
                }
                items(allPresets, key = { it.first }) { (name, d) ->
                    FilterChip(
                        selected = matching == name,
                        onClick = { change(d.copy(on = true)) },
                        label = { Text(name) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // ---- Kurve ----
            EqGraph(
                dsp = dsp, sel = bandIdx,
                onSelect = { sel = it },
                onGain = { i, g -> setBand(i) { b -> b.copy(g = g) } },
                modifier = Modifier.fillMaxWidth().height(250.dp)
            )
            Text(
                "Tippen: Band wählen · Ziehen: Gain malen · Doppeltipp: Band auf 0 dB",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ---- gewaehltes Band ----
            if (dsp.bands.isNotEmpty()) {
                val b = dsp.bands[bandIdx]
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { sel = (bandIdx - 1).coerceAtLeast(0) }) { Icon(Icons.Filled.ChevronLeft, "Vorheriges Band") }
                    Text(
                        "Band ${bandIdx + 1}/${dsp.bands.size} · ${fmtHz(b.f)} · ${"%+.1f".format(b.g)} dB · Q ${"%.1f".format(b.q)}",
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { sel = (bandIdx + 1).coerceAtMost(dsp.bands.size - 1) }) { Icon(Icons.Filled.ChevronRight, "Nächstes Band") }
                }
                EqSlider("Gain", b.g, -Eq.MAX_GAIN..Eq.MAX_GAIN, "%+.1f dB".format(b.g)) { v -> setBand(bandIdx) { it.copy(g = (v * 10f).roundToInt() / 10f) } }
                val t = (ln(b.f / Eq.F_MIN) / ln(Eq.F_MAX / Eq.F_MIN)).toFloat().coerceIn(0f, 1f)
                EqSlider("Frequenz", t, 0f..1f, fmtHz(b.f)) { v ->
                    setBand(bandIdx) { it.copy(f = (Eq.F_MIN * (Eq.F_MAX / Eq.F_MIN).pow(v.toDouble())).toFloat()) }
                }
                EqSlider("Güte (Q)", b.q, 0.3f..12f, "%.1f".format(b.q)) { v -> setBand(bandIdx) { it.copy(q = v) } }
            }

            Divider(Modifier.padding(vertical = 8.dp))

            // ---- Preamp / Bass / Hoehen ----
            EqSlider("Preamp", dsp.pre, -12f..12f, "%+.1f dB".format(dsp.pre)) { change(dsp.copy(pre = it)) }
            EqSlider("Bass", dsp.bass, -12f..12f, "%+.1f dB".format(dsp.bass)) { change(dsp.copy(bass = it)) }
            EqSlider("Höhen", dsp.treble, -12f..12f, "%+.1f dB".format(dsp.treble)) { change(dsp.copy(treble = it)) }
            EqSwitch("Bass-Boost", dsp.bassBoost) { change(dsp.copy(bassBoost = it)) }
            EqSwitch("Höhen-Boost", dsp.trebleBoost) { change(dsp.copy(trebleBoost = it)) }
            EqSwitch("Limiter (Clipping-Schutz)", dsp.limiter) { change(dsp.copy(limiter = it)) }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    change(dsp.copy(bands = Eq.flatBands(), pre = 0f, bass = 0f, treble = 0f, bassBoost = false, trebleBoost = false))
                }) { Text("Zurücksetzen") }
                OutlinedButton(onClick = { showSave = true }) { Text("Als Preset speichern") }
                OutlinedButton(onClick = { nav.navigate(Routes.PRESETS) }) { Text("Presets verwalten") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSave) {
        NameDialog(
            title = "Preset speichern",
            confirmLabel = "Speichern",
            initial = "",
            validate = { n ->
                if (Eq.BUILT_IN.keys.any { it.equals(n, true) } || userPresets.keys.any { it.equals(n, true) }) "Diesen Namen gibt es schon." else null
            },
            onConfirm = { n ->
                showSave = false
                userPresets = userPresets + (n to dsp)
                store.savePresets(userPresets)
            },
            onDismiss = { showSave = false }
        )
    }
}

@Composable
private fun EqGraph(
    dsp: Dsp,
    sel: Int,
    onSelect: (Int) -> Unit,
    onGain: (Int, Float) -> Unit,
    modifier: Modifier
) {
    val cs = MaterialTheme.colorScheme
    val gridC = cs.outlineVariant
    val zeroC = cs.outline
    val lineC = cs.primary
    val dotC = cs.secondary
    val selC = cs.tertiary
    val specC = cs.primary.copy(alpha = 0.18f)
    val textC = cs.onSurfaceVariant
    val curve = remember(dsp) { Eq.curve(dsp, GRID_F) }
    val paint = remember { android.graphics.Paint().apply { isAntiAlias = true } }

    // Live-Spektrum nur waehrend der Wiedergabe abfragen (spart Akku); gezeichnet wird nur im Draw-Schritt
    var spec by remember { mutableStateOf(FloatArray(32)) }
    LaunchedEffect(Unit) {
        while (true) {
            if (PlaybackState.isPlaying) spec = Spectrum.bands
            delay(80)
        }
    }

    val bandsNow by rememberUpdatedState(dsp.bands)
    val onSelectNow by rememberUpdatedState(onSelect)
    val onGainNow by rememberUpdatedState(onGain)

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { o -> onSelectNow(nearest(bandsNow, o.x, size.width.toFloat())) },
                    onDoubleTap = { o ->
                        val i = nearest(bandsNow, o.x, size.width.toFloat())
                        onSelectNow(i); onGainNow(i, 0f)
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o ->
                        val i = nearest(bandsNow, o.x, size.width.toFloat())
                        onSelectNow(i); onGainNow(i, yToGain(o.y, size.height.toFloat()))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val i = nearest(bandsNow, change.position.x, size.width.toFloat())
                        onSelectNow(i); onGainNow(i, yToGain(change.position.y, size.height.toFloat()))
                    }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        paint.color = textC.toArgb()
        paint.textSize = 10.sp.toPx()

        // Gitter + Beschriftung
        for (g in listOf(-12f, -6f, 0f, 6f, 12f)) {
            val y = gy(g, h)
            drawLine(if (g == 0f) zeroC else gridC, Offset(0f, y), Offset(w, y), strokeWidth = if (g == 0f) 2f else 1f)
            val label = if (g > 0) "+${g.toInt()}" else "${g.toInt()}"
            drawIntoCanvas { it.nativeCanvas.drawText(label, 4f, (y - 4f).coerceAtLeast(paint.textSize), paint) }
        }
        for ((f, label) in LABEL_F) {
            val x = fx(f, w)
            drawLine(gridC, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
            drawIntoCanvas { it.nativeCanvas.drawText(label, (x + 3f).coerceAtMost(w - paint.textSize * 2), h - 4f, paint) }
        }

        // Live-Spektrum (Balken hinter der Kurve)
        val sp = spec
        val bw = w / 32f * 0.7f
        for (b in 0 until 32) {
            val fc = 40.0 * 400.0.pow((b + 0.5) / 32.0)
            val bh = sp[b].coerceIn(0f, 1f) * h * 0.45f
            drawRect(specC, Offset(fx(fc, w) - bw / 2f, h - bh), Size(bw, bh))
        }

        // Gesamtkurve
        val path = Path()
        GRID_F.forEachIndexed { i, f ->
            val x = fx(f, w)
            val y = gy(curve[i].toFloat().coerceIn(-14f, 14f), h)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineC, style = Stroke(width = 3.dp.toPx()))

        // Band-Punkte
        dsp.bands.forEachIndexed { i, b ->
            val c = Offset(fx(b.f.toDouble(), w), gy(b.g, h))
            if (i == sel) {
                drawLine(selC.copy(alpha = 0.45f), Offset(c.x, 0f), Offset(c.x, h), strokeWidth = 1.5f)
                drawCircle(selC, 6.dp.toPx(), c)
            } else {
                drawCircle(dotC, 2.5.dp.toPx(), c)
            }
        }
    }
}

@Composable
private fun EqSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(valueText, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun EqSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
