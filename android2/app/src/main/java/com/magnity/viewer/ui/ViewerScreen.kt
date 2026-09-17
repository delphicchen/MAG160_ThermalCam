package com.magnity.viewer.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magnity.viewer.pipeline.Fusion
import com.magnity.viewer.pipeline.Palettes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val DIM = Color(0xFF8B949E)
private val FG = Color(0xFFE6EDF3)
private val ACCENT = Color(0xFF58A6FF)
private val HOT = Color(0xFFF85149)
private val COLD = Color(0xFF3FB950)

@Composable
fun ViewerScreen(vm: ViewerViewModel = viewModel()) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Color(0xFF161B22)) {
                DrawerControls(vm, onAlign = {
                    vm.fusionAligning = true
                    scope.launch { drawerState.close() }
                })
            }
        },
    ) {
        MainPane(vm, onMenu = { scope.launch { drawerState.open() } })
    }
}

@Composable
private fun MainPane(vm: ViewerViewModel, onMenu: () -> Unit) {
    val fr = vm.lastFrame
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0D1117))
            .safeDrawingPadding().padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // image + colour bar take everything the controls below don't need
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f),
                           contentAlignment = Alignment.Center) {
            if (fr != null) {
                val density = LocalDensity.current
                val barH = 40.dp
                val paneWpx = with(density) { maxWidth.toPx() }
                val paneHpx = with(density) { (maxHeight - barH).toPx() }
                // bitmap aspect, not the grid's: wide-search fusion shows the visible frame
                val bw = fr.bitmap.width.toFloat(); val bh = fr.bitmap.height.toFloat()
                val scale = min(paneWpx / bw, paneHpx / bh)
                val dispW = (bw * scale).roundToInt()
                val dispH = (bh * scale).roundToInt()
                val dispWdp = with(density) { dispW.toDp() }
                val dispHdp = with(density) { dispH.toDp() }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ThermalImage(vm, fr, dispW, dispH, Modifier.size(dispWdp, dispHdp))
                    Spacer(Modifier.height(6.dp))
                    ColorBar(vm.paletteName, fr.scaleLoC, fr.scaleHiC, Modifier.width(dispWdp))
                }
            } else {
                Text("Waiting for camera — plug it in and allow USB access", color = DIM)
            }
        }

        // readouts; each one is also its marker's on/off switch
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            Readout("MIN", fr?.tempMin, COLD, vm.showMinRoi) { vm.showMinRoi = !vm.showMinRoi }
            Readout("MAX", fr?.tempMax, HOT, vm.showMaxRoi) { vm.showMaxRoi = !vm.showMaxRoi }
            Readout("SPOT", vm.spotCelsius(), Color.White, vm.spot != null) { vm.spot = null }
            Spacer(Modifier.weight(1f))
            Text("ε %.2f".format(vm.emissivity), color = DIM, fontSize = 12.sp)
        }

        if (vm.fusionOn && vm.fusionAligning) {
            AlignPanel(vm)
        } else {
            // Display range in one fixed-height row: a thin two-thumb bar, draggable in
            // manual, tracking the auto range otherwise. No extra row appears, so the image
            // keeps its space.
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.height(48.dp)) {
                Text("RANGE", color = DIM, fontSize = 11.sp)
                RangeBar(
                    lo = vm.scaleLo, hi = vm.scaleHi,
                    domLo = vm.tempMinC, domHi = vm.tempMaxC,
                    enabled = !vm.autoScale,
                    onChange = { l, h -> vm.scaleLo = l; vm.scaleHi = h },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                OutlinedButton(onClick = { vm.autoScale = !vm.autoScale },
                               modifier = Modifier.height(34.dp),
                               contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (vm.autoScale) "AUTO" else "MAN", fontSize = 11.sp,
                         color = if (vm.autoScale) DIM else ACCENT)
                }
            }

            // actions
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.doFfc() }, enabled = vm.connected && !vm.ffcBusy,
                       modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                    Text(if (vm.ffcBusy) "FFC…" else "FFC") }
                Button(onClick = { vm.paused = !vm.paused }, enabled = vm.connected,
                       modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                    Text(if (vm.paused) "Resume" else "Pause") }
                FilledTonalButton(onClick = { vm.takeScreenshot() }, enabled = fr != null,
                                  modifier = Modifier.weight(1f),
                                  contentPadding = PaddingValues(4.dp)) { Text("Snap") }
                Button(onClick = { vm.toggleRecording() }, enabled = fr != null || vm.recording,
                       colors = ButtonDefaults.buttonColors(
                           containerColor = if (vm.recording) Color(0xFFDA3633) else Color(0xFF5A2E2E),
                           contentColor = Color.White),
                       modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                    Text(if (vm.recording) "Stop" else "Rec") }
            }

        }

        // menu + palette + REC timer + status
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onMenu, contentPadding = PaddingValues(0.dp),
                           modifier = Modifier.size(38.dp)) { Text("☰") }
            var exp by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { exp = true },
                               contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text(vm.paletteName, fontSize = 12.sp)
                }
                DropdownMenu(expanded = exp, onDismissRequest = { exp = false }) {
                    Palettes.NAMES.forEach { n ->
                        DropdownMenuItem(text = { Text(n) }, onClick = {
                            vm.paletteName = n; exp = false
                        })
                    }
                }
            }
            if (vm.recording) {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) {
                    while (true) { now = System.currentTimeMillis(); delay(500) }
                }
                val sec = ((now - vm.recordStartMs) / 1000).coerceAtLeast(0)
                Text("● %02d:%02d".format(sec / 60, sec % 60), color = HOT, fontSize = 13.sp)
            }
            val notice = vm.notice
            LaunchedEffect(notice) { if (notice != null) { delay(3000); vm.notice = null } }
            Column(Modifier.weight(1f)) {
                if (fr != null) {
                    Text("%.1f fps".format(vm.fps), color = ACCENT, fontSize = 13.sp)
                }
                Text(
                    notice ?: (vm.status + (fr?.let { " · FPA ${it.fpa}" } ?: "")),
                    color = if (notice != null) Color(0xFFD6A93D) else DIM,
                    fontSize = 10.sp, maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun ThermalImage(
    vm: ViewerViewModel, fr: FrameResult, dispW: Int, dispH: Int,
    modifier: Modifier,
) {
    // thermal grid ↔ screen, through the inset rect in wide-search fusion
    val r = fr.inset
    val left = (r?.left ?: 0f) * dispW; val top = (r?.top ?: 0f) * dispH
    val cellW = (r?.width() ?: 1f) * dispW / fr.w; val cellH = (r?.height() ?: 1f) * dispH / fr.h
    val image = remember(fr) { fr.bitmap.asImageBitmap() }
    val labelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 34f
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }
    Canvas(modifier.pointerInput(fr.w, fr.h, dispW, dispH, r) {
        detectTapGestures(
            onTap = { off ->
                val gx = ((off.x - left) / cellW).toInt()
                val gy = ((off.y - top) / cellH).toInt()
                // outside the thermal inset there is no temperature to read
                if (gx in 0 until fr.w && gy in 0 until fr.h) vm.spot = gx to gy
            },
            onLongPress = { vm.spot = null },
        )
    }) {
        drawImage(image, dstSize = IntSize(dispW, dispH), filterQuality = FilterQuality.Low)
        fun marker(pos: Int, color: Color, v: Float) {
            val c = Offset(left + (pos % fr.w + 0.5f) * cellW, top + (pos / fr.w + 0.5f) * cellH)
            drawCircle(color, 18f, c, style = Stroke(4f, cap = StrokeCap.Round))
            val label = "%.1f°C".format(v)
            labelPaint.color = color.toArgb()
            val tw = labelPaint.measureText(label)
            val tx = (c.x + 24f).coerceAtMost(size.width - tw - 4f)
            val ty = (c.y - 20f).coerceAtLeast(38f)
            drawIntoCanvas { it.nativeCanvas.drawText(label, tx, ty, labelPaint) }
        }
        if (vm.showMaxRoi) marker(fr.maxPos, HOT, fr.tempMax)
        if (vm.showMinRoi) marker(fr.minPos, COLD, fr.tempMin)
        vm.spot?.let { (sx, sy) ->
            val c = Offset(left + (sx + 0.5f) * cellW, top + (sy + 0.5f) * cellH)
            drawLine(Color.White, c - Offset(18f, 0f), c + Offset(18f, 0f), 3f)
            drawLine(Color.White, c - Offset(0f, 18f), c + Offset(0f, 18f), 3f)
        }
    }
}

/**
 * Thin two-thumb range bar over the module's full measurement span. Either thumb can be
 * grabbed anywhere on the bar (the nearer one wins), which M3's RangeSlider made fiddly,
 * and it is far thinner than the M3 control so the row stays short.
 */
@Composable
private fun RangeBar(
    lo: Float, hi: Float, domLo: Float, domHi: Float, enabled: Boolean,
    onChange: (Float, Float) -> Unit, modifier: Modifier,
) {
    val density = LocalDensity.current
    val padPx = with(density) { 10.dp.toPx() }
    val labelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = with(density) { 10.sp.toPx() }
        }
    }
    var barW by remember { mutableFloatStateOf(1f) }
    var active by remember { mutableIntStateOf(0) }   // 1 = low thumb, 2 = high thumb
    val span = (domHi - domLo).coerceAtLeast(0.1f)

    fun xOf(v: Float) = padPx + (v.coerceIn(domLo, domHi) - domLo) / span * (barW - 2 * padPx)
    fun valueAt(x: Float) =
        (domLo + (x - padPx) / (barW - 2 * padPx) * span).coerceIn(domLo, domHi)

    val gesture = if (!enabled) Modifier else Modifier.pointerInput(domLo, domHi) {
        detectDragGestures(
            onDragStart = { p ->
                active = if (kotlin.math.abs(p.x - xOf(lo)) <= kotlin.math.abs(p.x - xOf(hi))) 1 else 2
            },
            onDragEnd = { active = 0 },
        ) { change, _ ->
            change.consume()
            val v = valueAt(change.position.x)
            if (active == 1) onChange(v.coerceAtMost(hi - 0.5f), hi)
            else onChange(lo, v.coerceAtLeast(lo + 0.5f))
        }
    }

    Canvas(modifier.then(gesture)) {
        barW = size.width
        val cy = size.height / 2f
        val trackH = with(density) { 3.dp.toPx() }
        val r = with(density) { 7.dp.toPx() }
        val xLo = xOf(lo); val xHi = xOf(hi)
        val a = if (enabled) 1f else 0.55f

        drawLine(Color(0xFF30363D), Offset(padPx, cy), Offset(size.width - padPx, cy),
                 trackH, cap = StrokeCap.Round)
        drawLine(ACCENT.copy(alpha = a), Offset(xLo, cy), Offset(xHi, cy),
                 trackH, cap = StrokeCap.Round)
        drawCircle(COLD.copy(alpha = a), r, Offset(xLo, cy))
        drawCircle(HOT.copy(alpha = a), r, Offset(xHi, cy))

        drawIntoCanvas { c ->
            // The two moving labels sit on opposite sides of the track — above for the
            // high thumb, below for the low one — so they cannot overlap however close
            // the thumbs get. The fixed span ends share the bottom line, and the low
            // label is clamped to stay clear of them.
            val ty = cy - r - with(density) { 5.dp.toPx() }
            val by = cy + r + with(density) { 13.dp.toPx() }

            labelPaint.color = DIM.toArgb()
            val domLoTxt = "%.0f°C".format(domLo)
            val domHiTxt = "%.0f°C".format(domHi)
            val domLoW = labelPaint.measureText(domLoTxt)
            val domHiW = labelPaint.measureText(domHiTxt)
            c.nativeCanvas.drawText(domLoTxt, 0f, by, labelPaint)
            c.nativeCanvas.drawText(domHiTxt, size.width - domHiW, by, labelPaint)

            val gap = with(density) { 6.dp.toPx() }
            labelPaint.color = HOT.copy(alpha = a).toArgb()
            val hiTxt = "%.1f".format(hi)
            val hiW = labelPaint.measureText(hiTxt)
            c.nativeCanvas.drawText(hiTxt, (xHi - hiW / 2f).coerceIn(0f, size.width - hiW),
                                    ty, labelPaint)

            labelPaint.color = COLD.copy(alpha = a).toArgb()
            val loTxt = "%.1f".format(lo)
            val loW = labelPaint.measureText(loTxt)
            c.nativeCanvas.drawText(loTxt,
                (xLo - loW / 2f).coerceIn(domLoW + gap, size.width - domHiW - gap - loW),
                by, labelPaint)
        }
    }
}

@Composable
private fun ColorBar(palette: String, lo: Float, hi: Float, modifier: Modifier) {
    val lut = remember(palette) {
        android.graphics.Bitmap.createBitmap(Palettes.lut(palette), 256, 1,
            android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            drawImage(lut, srcOffset = IntOffset.Zero, srcSize = IntSize(256, 1),
                      dstOffset = IntOffset.Zero,
                      dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                      filterQuality = FilterQuality.Low)
        }
        Row(Modifier.fillMaxWidth()) {
            Text("%.1f°C".format(lo), color = Color(0xFFC9D1D9), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text("%.1f".format((lo + hi) / 2f), color = Color(0xFFC9D1D9), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text("%.1f°C".format(hi), color = Color(0xFFC9D1D9), fontSize = 12.sp)
        }
    }
}

/** A readout that doubles as its marker's on/off switch — dimmed when the marker is off. */
@Composable
private fun Readout(label: String, value: Float?, color: Color, on: Boolean,
                    onClick: () -> Unit) {
    val c = if (on) color else color.copy(alpha = 0.35f)
    Column(Modifier.clickable(onClick = onClick)) {
        Text(label, color = c, fontSize = 11.sp)
        Text(value?.let { "%.1f°C".format(it) } ?: "--", color = c, fontSize = 20.sp)
    }
}

@Composable
private fun DrawerControls(vm: ViewerViewModel, onAlign: () -> Unit) {
    Column(
        Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("MagViewer", color = ACCENT, fontSize = 15.sp)

        Column {
            Text("Upscaler", color = DIM, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()) {
                Chip("Anime4K", vm.upscaler == ViewerViewModel.Upscaler.ANIME4K,
                     Modifier.weight(1f)) { vm.upscaler = ViewerViewModel.Upscaler.ANIME4K }
                Chip("Thermal SR", vm.upscaler == ViewerViewModel.Upscaler.NCNN,
                     Modifier.weight(1f)) { vm.upscaler = ViewerViewModel.Upscaler.NCNN }
                Chip("Bicubic", vm.upscaler == ViewerViewModel.Upscaler.BICUBIC,
                     Modifier.weight(1f)) { vm.upscaler = ViewerViewModel.Upscaler.BICUBIC }
            }
            if (!vm.ncnnModelInstalled()) {
                Text("Thermal SR needs a trained model — push the .param/.bin into\n" +
                     vm.ncnnModelDir(),
                     color = DIM, fontSize = 9.sp)
            }
        }

        Column {
            Text("Display resolution", color = DIM, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(1, 4).forEach { k ->
                    val label = vm.lastFrame?.let { "${it.w * k}×${it.h * k}" } ?: "${k}×"
                    Chip(label, vm.upscale == k, Modifier.weight(1f)) { vm.upscale = k }
                }
            }
        }

        Column {
            Text("Rotate", color = DIM, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0, 90, 180, 270).forEach { deg ->
                    Chip("$deg°", vm.rotation == deg, Modifier.weight(1f)) { vm.rotation = deg }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(vm.mirror, onCheckedChange = { vm.mirror = it })
            Text("Mirror ↔", color = FG)
        }

        HorizontalDivider(color = Color(0xFF30363D))

        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Emissivity ε", color = FG, modifier = Modifier.weight(1f))
                Text("%.2f".format(vm.emissivity), color = ACCENT, fontSize = 13.sp)
            }
            Slider(vm.emissivity,
                   onValueChange = { vm.emissivity = (it * 100).roundToInt() / 100f },
                   valueRange = 0.10f..1f)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("1.00" to 1f, "Skin .98" to 0.98f, "Matte .95" to 0.95f,
                       "Wood .90" to 0.90f).forEach { (label, e) ->
                    Chip(label, kotlin.math.abs(vm.emissivity - e) < 0.005f,
                         Modifier.weight(1f)) { vm.emissivity = e }
                }
            }
            Text("Shiny metal reads far too cold at any ε — tape or paint a matte patch.",
                 color = DIM, fontSize = 9.sp)
        }

        HorizontalDivider(color = Color(0xFF30363D))

        val locationPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { granted ->
            vm.geotag = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true
            if (!vm.geotag) vm.notice = "Geo-tag needs precise location permission"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(vm.geotag, onCheckedChange = { on ->
                if (on && !vm.hasLocationPermission()) {
                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                                                      Manifest.permission.ACCESS_COARSE_LOCATION))
                } else {
                    vm.geotag = on
                }
            })
            Column {
                Text("Geo-tag snapshots & video", color = FG)
                if (vm.geotag) {
                    Text(vm.location?.let { "fix ±%.0f m".format(it.accuracy) }
                             ?: "waiting for location fix…",
                         color = DIM, fontSize = 10.sp)
                }
            }
        }

        HorizontalDivider(color = Color(0xFF30363D))

        val cameraPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            vm.fusionOn = granted
            if (!granted) vm.notice = "Visible fusion needs camera permission"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(vm.fusionOn, onCheckedChange = { on ->
                if (on && !vm.hasCameraPermission()) {
                    cameraPermission.launch(Manifest.permission.CAMERA)
                } else {
                    vm.fusionOn = on
                }
            })
            Text("Visible fusion (beta)", color = FG)
        }
        if (vm.fusionOn) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("MSX edges" to Fusion.Mode.EDGES, "Blend" to Fusion.Mode.BLEND,
                       "Wide" to Fusion.Mode.SEARCH).forEach { (label, m) ->
                    Chip(label, vm.fusionMode == m, Modifier.weight(1f)) { vm.fusionMode = m }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(when (vm.fusionMode) {
                         Fusion.Mode.EDGES -> "Edge strength"
                         Fusion.Mode.BLEND -> "Thermal weight"
                         Fusion.Mode.SEARCH -> "Thermal opacity"
                     }, color = FG, modifier = Modifier.weight(1f))
                Text("%.2f".format(vm.fusionStrength), color = DIM, fontSize = 11.sp)
            }
            Slider(vm.fusionStrength, onValueChange = { vm.fusionStrength = it })
            Text("Visible camera rotation", color = DIM, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0, 90, 180, 270).forEach { deg ->
                    Chip("$deg°", vm.fusionRotation == deg, Modifier.weight(1f)) {
                        vm.fusionRotation = deg
                    }
                }
            }
            Button(onClick = onAlign, modifier = Modifier.fillMaxWidth()) {
                Text("Align on live image")
            }
            Text("zoom ×%.2f · offset %+.3f / %+.3f".format(vm.fusionZoom, vm.fusionDx, vm.fusionDy),
                 color = DIM, fontSize = 10.sp)
        }

        HorizontalDivider(color = Color(0xFF30363D))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(vm.temporalDenoise, onCheckedChange = { vm.temporalDenoise = it })
            Text("Temporal denoise", color = FG, modifier = Modifier.weight(1f))
            Text("%.2f".format(vm.temporalStrength), color = DIM, fontSize = 11.sp)
        }
        if (vm.temporalDenoise) {
            Slider(vm.temporalStrength, onValueChange = { vm.temporalStrength = it },
                   valueRange = 0.5f..0.95f)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(vm.spatialDenoise, onCheckedChange = { vm.spatialDenoise = it })
            Text("Spatial denoise", color = FG)
        }

        HorizontalDivider(color = Color(0xFF30363D))

        Text("Factory SDK test (libcoresdk.so arm64)", color = DIM, fontSize = 10.sp)
        Text("Interrupts the live stream", color = DIM, fontSize = 9.sp)
        Button(onClick = { vm.runSdkSmokeTest() }, enabled = !vm.sdkBusy,
               colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B3D2B),
                                                    contentColor = Color.White),
               modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.sdkBusy) "Running…" else "Run SDK smoke test")
        }
        vm.sdkLog?.let { logText ->
            Text(logText, color = Color(0xFF9DD69D), fontSize = 9.sp,
                 fontFamily = FontFamily.Monospace,
                 modifier = Modifier
                     .fillMaxWidth()
                     .heightIn(max = 220.dp)
                     .verticalScroll(rememberScrollState()))
        }

        HorizontalDivider(color = Color(0xFF30363D))

        Button(onClick = { vm.disconnect() }, enabled = vm.connected,
               colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A2E2E),
                                                    contentColor = Color.White),
               modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }

        Text("Tap the image to place SPOT, long-press to clear.\n" +
             "Tap MIN / MAX / SPOT under the image to hide a marker.\n" +
             "Red ring = MAX, green ring = MIN.",
             color = DIM, fontSize = 10.sp)
    }
}

/** Live registration sliders under the image — the drawer would cover what you align. */
@Composable
private fun AlignPanel(vm: ViewerViewModel) {
    Column {
        AlignSlider("ZOOM", vm.fusionZoom, 1f..3f, "×%.2f") { vm.fusionZoom = it }
        AlignSlider("X", vm.fusionDx, -0.3f..0.3f, "%+.3f") { vm.fusionDx = it }
        AlignSlider("Y", vm.fusionDy, -0.3f..0.3f, "%+.3f") { vm.fusionDy = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.fusionZoom = 1.6f; vm.fusionDx = 0f; vm.fusionDy = 0f },
                           modifier = Modifier.weight(1f)) { Text("Reset") }
            Button(onClick = { vm.fusionAligning = false },
                   modifier = Modifier.weight(1f)) { Text("Done") }
        }
    }
}

@Composable
private fun AlignSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>,
                        fmt: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(36.dp)) {
        Text(label, color = DIM, fontSize = 11.sp, modifier = Modifier.width(40.dp))
        Slider(value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(fmt.format(value), color = FG, fontSize = 11.sp, modifier = Modifier.width(52.dp))
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier,
                 onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier.height(36.dp),
               contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
            Text(label, fontSize = 11.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.height(36.dp),
                       contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
            Text(label, fontSize = 11.sp, color = DIM)
        }
    }
}
