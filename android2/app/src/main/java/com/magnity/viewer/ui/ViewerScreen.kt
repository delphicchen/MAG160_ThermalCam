package com.magnity.viewer.ui

import android.Manifest
import android.hardware.camera2.CameraMetadata
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magnity.viewer.R
import com.magnity.viewer.pipeline.Fusion
import com.magnity.viewer.pipeline.FusionCalibration
import com.magnity.viewer.pipeline.Palettes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal val DIM = Color(0xFF8B949E)
internal val FG = Color(0xFFE6EDF3)
internal val ACCENT = Color(0xFF58A6FF)
internal val HOT = Color(0xFFF85149)
internal val COLD = Color(0xFF3FB950)
/** AF window / attention colour. */
internal val WARN = Color(0xFFD6A93D)

@Composable
fun ViewerScreen(vm: ViewerViewModel = viewModel()) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    vm.playback?.let { pb ->
        PlaybackPane(vm, pb)
        return
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Color(0xFF161B22)) {
                DrawerControls(vm, onAlign = {
                    vm.fusionAligning = true
                    scope.launch { drawerState.close() }
                }, onCloseDrawer = { scope.launch { drawerState.close() } })
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
                    Box(Modifier.size(dispWdp, dispHdp)) {
                        ThermalImage(vm, fr, dispW, dispH, Modifier.fillMaxSize())
                        // on-screen timing: over the image, so it never shrinks it
                        if (vm.debugOsd && vm.debugInfo.isNotEmpty()) {
                            Text(vm.debugInfo, color = Color.White, fontSize = 10.sp,
                                 fontFamily = FontFamily.Monospace, lineHeight = 12.sp,
                                 modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                                     .background(Color(0x99000000))
                                     .padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    ColorBar(vm.paletteName, fr.scaleLoC, fr.scaleHiC, Modifier.width(dispWdp))
                }
            } else {
                Text(stringResource(R.string.waiting_camera), color = DIM)
            }
        }

        // readouts; each one is also its marker's on/off switch
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            Readout("MIN", fr?.tempMin, COLD, vm.showMinRoi) { vm.showMinRoi = !vm.showMinRoi }
            Readout("MAX", fr?.tempMax, HOT, vm.showMaxRoi) { vm.showMaxRoi = !vm.showMaxRoi }
            Readout("SPOT", vm.spotCelsius(), Color.White, vm.spot != null) { vm.spot = null }
            Spacer(Modifier.weight(1f))
            if (vm.fusionOn) Text(vm.fusionDistanceLabel, color = DIM, fontSize = 12.sp)
            Text("ε %.2f".format(vm.emissivity), color = DIM, fontSize = 12.sp)
        }

        if (vm.fusionOn && vm.calibPromptActive && !vm.fusionAligning) {
            CalibPromptBar(vm)
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
                Text(stringResource(R.string.range), color = DIM, fontSize = 11.sp)
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
                    Text(stringResource(if (vm.autoScale) R.string.auto else R.string.manual_short), fontSize = 11.sp,
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
                    Text(stringResource(if (vm.paused) R.string.resume else R.string.pause)) }
                FilledTonalButton(onClick = { vm.takeScreenshot() }, enabled = fr != null,
                                  modifier = Modifier.weight(1f),
                                  contentPadding = PaddingValues(4.dp)) { Text(stringResource(R.string.snap)) }
                Button(onClick = { vm.toggleRecording() }, enabled = fr != null || vm.recording,
                       colors = ButtonDefaults.buttonColors(
                           containerColor = if (vm.recording) Color(0xFFDA3633) else Color(0xFF5A2E2E),
                           contentColor = Color.White),
                       modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                    Text(stringResource(if (vm.recording) R.string.stop else R.string.rec)) }
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
            // timing overlay on the image — highlighted while it is on
            OutlinedButton(onClick = { vm.debugOsd = !vm.debugOsd },
                           contentPadding = PaddingValues(horizontal = 8.dp),
                           colors = ButtonDefaults.outlinedButtonColors(
                               containerColor = if (vm.debugOsd) Color(0xFF1F3A5F) else Color.Transparent)) {
                Text("Debug", fontSize = 12.sp, color = if (vm.debugOsd) ACCENT else FG)
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
        // visible-camera AF window: what the Auto object distance is measured on
        fr.afBox?.let { b ->
            val l = b.left * dispW; val t = b.top * dispH
            val rr = b.right * dispW; val bb = b.bottom * dispH
            val c = Offset((l + rr) / 2f, (t + bb) / 2f)
            if (vm.afCentreRegion) {
                drawRect(WARN.copy(alpha = 0.7f), Offset(l, t), Size(rr - l, bb - t),
                         style = Stroke(2f))
            }
            // gapped crosshair — the centre pixel stays readable
            drawLine(WARN, c - Offset(20f, 0f), c - Offset(6f, 0f), 3f)
            drawLine(WARN, c + Offset(6f, 0f), c + Offset(20f, 0f), 3f)
            drawLine(WARN, c - Offset(0f, 20f), c - Offset(0f, 6f), 3f)
            drawLine(WARN, c + Offset(0f, 6f), c + Offset(0f, 20f), 3f)
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
internal fun ColorBar(palette: String, lo: Float, hi: Float, modifier: Modifier) {
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
internal fun Readout(label: String, value: Float?, color: Color, on: Boolean,
                    onClick: () -> Unit) {
    val c = if (on) color else color.copy(alpha = 0.35f)
    Column(Modifier.clickable(onClick = onClick)) {
        Text(label, color = c, fontSize = 11.sp)
        Text(value?.let { "%.1f°C".format(it) } ?: "--", color = c, fontSize = 20.sp)
    }
}

@Composable
private fun DrawerControls(vm: ViewerViewModel, onAlign: () -> Unit,
                           onCloseDrawer: () -> Unit) {
    Column(
        Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("MagViewer", color = ACCENT, fontSize = 15.sp)

        // language names are written in their own language, so each is findable from any
        Column {
            Text(stringResource(R.string.language), color = DIM, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("" to stringResource(R.string.lang_system), "en" to "English",
                       "zh-TW" to "繁體中文", "zh-CN" to "简体中文").forEach { (tag, label) ->
                    Chip(label, vm.appLanguage == tag, Modifier.weight(1f)) { vm.setLanguage(tag) }
                }
            }
        }

        Column {
            Text(stringResource(R.string.upscaler), color = DIM, fontSize = 11.sp)
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
                Text(stringResource(R.string.sr_needs_model, vm.ncnnModelDir()),
                     color = DIM, fontSize = 9.sp)
            }
            // a model package = the zip of thermal_<w>x<h>_fp16.param/.bin from sr_train's
            // Colab export; it overrides the built-in model until removed
            val importModel = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> uri?.let { vm.importSrModel(it) } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { importModel.launch(arrayOf("*/*")) },
                               modifier = Modifier.weight(1f),
                               contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.load_sr_model), fontSize = 12.sp)
                }
                if (vm.srImportedSizes.isNotEmpty()) {
                    OutlinedButton(onClick = { vm.removeSrModel() },
                                   contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(stringResource(R.string.use_builtin), fontSize = 12.sp)
                    }
                }
            }
            Text(if (vm.srImportedSizes.isEmpty()) stringResource(R.string.sr_model_builtin)
                 else stringResource(R.string.sr_model_imported, vm.srImportedSizes.joinToString()),
                 color = DIM, fontSize = 9.sp)
        }

        Column {
            Text(stringResource(R.string.display_resolution), color = DIM, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(1, 4).forEach { k ->
                    val label = vm.lastFrame?.let { "${it.w * k}×${it.h * k}" } ?: "${k}×"
                    Chip(label, vm.upscale == k, Modifier.weight(1f)) { vm.upscale = k }
                }
            }
        }

        Column {
            Text(stringResource(R.string.rotate), color = DIM, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0, 90, 180, 270).forEach { deg ->
                    Chip("$deg°", vm.rotation == deg, Modifier.weight(1f)) { vm.rotation = deg }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(vm.mirror, onCheckedChange = { vm.mirror = it })
            Text(stringResource(R.string.mirror), color = FG)
        }

        HorizontalDivider(color = Color(0xFF30363D))

        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.emissivity), color = FG, modifier = Modifier.weight(1f))
                Text("%.2f".format(vm.emissivity), color = ACCENT, fontSize = 13.sp)
            }
            Slider(vm.emissivity,
                   onValueChange = { vm.emissivity = (it * 100).roundToInt() / 100f },
                   valueRange = 0.10f..1f)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("1.00" to 1f, stringResource(R.string.emis_skin) to 0.98f,
                       stringResource(R.string.emis_matte) to 0.95f,
                       stringResource(R.string.emis_wood) to 0.90f).forEach { (label, e) ->
                    Chip(label, kotlin.math.abs(vm.emissivity - e) < 0.005f,
                         Modifier.weight(1f)) { vm.emissivity = e }
                }
            }
            Text(stringResource(R.string.emis_metal_hint),
                 color = DIM, fontSize = 9.sp)
        }

        HorizontalDivider(color = Color(0xFF30363D))

        val locationPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { granted ->
            vm.geotag = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true
            if (!vm.geotag) vm.notice = vm.str(R.string.geotag_needs_permission)
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
                Text(stringResource(R.string.geotag), color = FG)
                if (vm.geotag) {
                    Text(vm.location?.let { stringResource(R.string.location_fix, it.accuracy) }
                             ?: stringResource(R.string.waiting_location),
                         color = DIM, fontSize = 10.sp)
                }
            }
        }

        HorizontalDivider(color = Color(0xFF30363D))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(vm.saveThermalData, onCheckedChange = { vm.saveThermalData = it })
            Column {
                Text(stringResource(R.string.save_thermal), color = FG)
                Text(stringResource(R.string.save_thermal_hint,
                                    vm.lastFrame?.let { it.w * it.h * 2 / 1024f } ?: 38f),
                     color = DIM, fontSize = 10.sp)
            }
        }
        val openCapture = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let { vm.openPlayback(it) } }
        OutlinedButton(onClick = { openCapture.launch(arrayOf("*/*")); onCloseDrawer() },
                       modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.open_capture))
        }

        HorizontalDivider(color = Color(0xFF30363D))

        val cameraPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            vm.fusionOn = granted
            if (!granted) vm.notice = vm.str(R.string.fusion_needs_camera)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(vm.fusionOn, onCheckedChange = { on ->
                if (on && !vm.hasCameraPermission()) {
                    cameraPermission.launch(Manifest.permission.CAMERA)
                } else {
                    vm.fusionOn = on
                }
            })
            Text(stringResource(R.string.fusion), color = FG)
        }
        if (vm.fusionOn) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(stringResource(R.string.fusion_edges) to Fusion.Mode.EDGES,
                       stringResource(R.string.fusion_blend) to Fusion.Mode.BLEND,
                       stringResource(R.string.fusion_wide) to Fusion.Mode.SEARCH).forEach { (label, m) ->
                    Chip(label, vm.fusionMode == m, Modifier.weight(1f)) { vm.fusionMode = m }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(when (vm.fusionMode) {
                         Fusion.Mode.EDGES -> R.string.edge_strength
                         Fusion.Mode.BLEND -> R.string.thermal_weight
                         Fusion.Mode.SEARCH -> R.string.thermal_opacity
                     }), color = FG, modifier = Modifier.weight(1f))
                Text("%.2f".format(vm.fusionStrength), color = DIM, fontSize = 11.sp)
            }
            Slider(vm.fusionStrength, onValueChange = { vm.fusionStrength = it })
            Text(stringResource(R.string.visible_rotation), color = DIM, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0, 90, 180, 270).forEach { deg ->
                    Chip("$deg°", vm.fusionRotation == deg, Modifier.weight(1f)) {
                        vm.fusionRotation = deg
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(vm.fusionCrosshair, onCheckedChange = { vm.fusionCrosshair = it })
                Column {
                    Text(stringResource(R.string.show_crosshair), color = FG, fontSize = 13.sp)
                    Text(stringResource(R.string.crosshair_hint),
                         color = DIM, fontSize = 10.sp)
                }
            }

            FocusLine(vm)

            Text(stringResource(R.string.object_distance), color = DIM, fontSize = 11.sp)
            val distSrc = vm.fusionDistanceSource
            val effSrc = vm.effectiveDistanceSource()
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Chip(stringResource(R.string.dist_auto), distSrc == FusionCalibration.DistanceSource.AUTO,
                     Modifier.weight(1f)) {
                    vm.fusionDistanceSource = FusionCalibration.DistanceSource.AUTO
                }
                Chip(stringResource(R.string.dist_manual), distSrc == FusionCalibration.DistanceSource.MANUAL,
                     Modifier.weight(1f)) {
                    vm.fusionDistanceSource = FusionCalibration.DistanceSource.MANUAL
                }
                Chip("∞", distSrc == FusionCalibration.DistanceSource.INFINITY,
                     Modifier.weight(1f)) {
                    vm.fusionDistanceSource = FusionCalibration.DistanceSource.INFINITY
                }
            }
            if (distSrc == FusionCalibration.DistanceSource.AUTO) {
                val learned = vm.focusMap
                when {
                    effSrc == FusionCalibration.DistanceSource.MANUAL ->
                        Text(stringResource(R.string.auto_unavailable),
                             color = Color(0xFFD6A93D), fontSize = 10.sp)
                    vm.focusTrusted() -> {}
                    learned.usable ->
                        Text(stringResource(R.string.auto_learned, learned.points),
                             color = DIM, fontSize = 10.sp)
                    else ->
                        Text(stringResource(R.string.focus_uncalibrated, learned.points),
                             color = DIM, fontSize = 10.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(vm.fusionCalibPrompt, onCheckedChange = { vm.fusionCalibPrompt = it })
                    Column {
                        Text(stringResource(R.string.offer_align), color = FG, fontSize = 12.sp)
                        Text(stringResource(R.string.offer_align_hint, FusionCalibration.RANGE_TOL_M),
                             color = DIM, fontSize = 10.sp)
                    }
                }
            }
            if (effSrc == FusionCalibration.DistanceSource.MANUAL) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.distance), color = FG, modifier = Modifier.weight(1f))
                    Text(vm.distanceText(vm.fusionManualInvZ), color = ACCENT, fontSize = 12.sp)
                }
                // linear in 1/Z: equal thumb travel = equal parallax change
                Slider(value = vm.fusionManualInvZ,
                       onValueChange = {
                           vm.fusionManualInvZ = it.coerceIn(0f, FusionCalibration.MAX_INVZ)
                       },
                       valueRange = 0f..FusionCalibration.MAX_INVZ)
                InvZStopLabels()
            }

            Button(onClick = onAlign, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.align_live))
            }
            Text(stringResource(R.string.zoom_offset, vm.fusionZoom, vm.fusionDx, vm.fusionDy),
                 color = DIM, fontSize = 10.sp)
            CalibrationSection(vm)
        }

        HorizontalDivider(color = Color(0xFF30363D))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(vm.temporalDenoise, onCheckedChange = { vm.temporalDenoise = it })
            Text(stringResource(R.string.temporal_denoise), color = FG, modifier = Modifier.weight(1f))
            Text("%.2f".format(vm.temporalStrength), color = DIM, fontSize = 11.sp)
        }
        if (vm.temporalDenoise) {
            Slider(vm.temporalStrength, onValueChange = { vm.temporalStrength = it },
                   valueRange = 0.5f..0.95f)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(vm.spatialDenoise && !vm.thermalSrActive,
                     onCheckedChange = { vm.spatialDenoise = it },
                     enabled = !vm.thermalSrActive)
            Column {
                Text(stringResource(R.string.spatial_denoise), color = if (vm.thermalSrActive) DIM else FG)
                if (vm.thermalSrActive) {
                    Text(stringResource(R.string.spatial_off_sr),
                         color = DIM, fontSize = 10.sp)
                }
            }
        }

        HorizontalDivider(color = Color(0xFF30363D))

        Text(stringResource(R.string.sdk_test_title), color = DIM, fontSize = 10.sp)
        Text(stringResource(R.string.sdk_test_interrupts), color = DIM, fontSize = 9.sp)
        Button(onClick = { vm.runSdkSmokeTest() }, enabled = !vm.sdkBusy,
               colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B3D2B),
                                                    contentColor = Color.White),
               modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (vm.sdkBusy) R.string.running else R.string.run_sdk_test))
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
               modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.disconnect)) }

        Text(stringResource(R.string.help_live),
             color = DIM, fontSize = 10.sp)
    }
}

/**
 * The out-of-range offer: a line and an Align button, not a panel that takes the screen
 * over by itself — a passing focus change must not interrupt what you are looking at.
 */
@Composable
private fun CalibPromptBar(vm: ViewerViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.calib_outside, vm.fusionDistanceLabel),
             color = WARN, fontSize = 11.sp, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { vm.startPromptedAlign() },
                       contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(stringResource(R.string.align), fontSize = 12.sp)
        }
        TextButton(onClick = { vm.dismissCalibPrompt() },
                   contentPadding = PaddingValues(horizontal = 6.dp)) { Text("✕") }
    }
}

/** Live registration sliders under the image — the drawer would cover what you align. */
@Composable
private fun AlignPanel(vm: ViewerViewModel) {
    var saveDlg by remember { mutableStateOf(false) }
    Column {
        if (vm.calibPromptActive) {
            Text(stringResource(R.string.calib_outside_align, vm.fusionDistanceLabel),
                 color = Color(0xFFD6A93D), fontSize = 11.sp)
        }
        AlignSlider("ZOOM", vm.fusionZoom, 1f..3f, "×%.2f") { vm.fusionZoom = it }
        AlignSlider("X", vm.fusionDx, -0.3f..0.3f, "%+.3f") { vm.fusionDx = it }
        AlignSlider("Y", vm.fusionDy, -0.3f..0.3f, "%+.3f") { vm.fusionDy = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.fusionZoom = 1.6f; vm.fusionDx = 0f; vm.fusionDy = 0f },
                           modifier = Modifier.weight(1f)) { Text(stringResource(R.string.reset)) }
            OutlinedButton(onClick = { saveDlg = true }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.save_ellipsis))
            }
            Button(onClick = { vm.endAligning() },
                   modifier = Modifier.weight(1f)) { Text(stringResource(R.string.done)) }
        }
    }
    if (saveDlg) SaveSampleDialog(vm, onClose = { saveDlg = false })
}

/** Distance entry for saving the current alignment as a calibration sample. */
@Composable
private fun SaveSampleDialog(vm: ViewerViewModel, onClose: () -> Unit) {
    // pre-fill from the lens when it can tell the distance
    var text by remember {
        mutableStateOf(vm.suggestedInvZ()?.let { iz ->
            if (iz <= 1e-4f) "∞" else "%.2f".format(java.util.Locale.ROOT, 1f / iz)
        } ?: "3")
    }
    var error by remember { mutableStateOf(false) }

    /** Metres (or "∞") → invZ; null = invalid. */
    fun parse(s: String): Float? = when (s.trim().lowercase()) {
        "∞", "inf", "infinity" -> 0f
        else -> s.trim().toFloatOrNull()?.takeIf { it > 0f }?.let { 1f / it }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.save_align_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.save_align_prompt),
                     color = DIM, fontSize = 12.sp)
                vm.focusDiopters?.let { d ->
                    Text(stringResource(R.string.lens_focus_saved, d),
                         color = DIM, fontSize = 10.sp)
                }
                TextField(value = text, onValueChange = { text = it; error = false },
                          singleLine = true,
                          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                // a phone keyboard has no ∞ key — offer the usual calibration distances
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("1.5", "3", "5", "∞").forEach { v ->
                        Chip(v, text.trim() == v, Modifier.weight(1f)) { text = v; error = false }
                    }
                }
                if (error) Text(stringResource(R.string.enter_metres), color = HOT, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val invZ = parse(text)
                if (invZ == null) {
                    error = true
                } else {
                    vm.addCalibSample(invZ, vm.fusionZoom, vm.fusionDx, vm.fusionDy)
                    onClose()
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
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
                 enabled: Boolean = true, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier.height(36.dp), enabled = enabled,
               contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
            Text(label, fontSize = 11.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.height(36.dp), enabled = enabled,
                       contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
            Text(label, fontSize = 11.sp, color = DIM)
        }
    }
}

private fun focusCalName(c: Int?): String = when (c) {
    CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "CALIBRATED"
    CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "APPROXIMATE"
    else -> "UNCALIBRATED"
}

/** One small diagnostics line: what the lens reports about its own focus distance. */
@Composable
private fun FocusLine(vm: ViewerViewModel) {
    val d = vm.focusDiopters
    val cal = focusCalName(vm.focusCalibration)
    val text = when {
        vm.focusCalibration == null && d == null -> stringResource(R.string.focus_not_reported)
        d == null || d < 0f -> stringResource(R.string.focus_cal_not_reported, cal)
        else -> stringResource(R.string.focus_reading, cal, d) +
                (vm.suggestedInvZ()?.let { " (" + vm.distanceText(it) + ")" } ?: "") +
                (if (vm.afScanning) " · " + stringResource(R.string.focusing)
                 else if (vm.afActive) " · " + stringResource(R.string.centre_af) else "")
    }
    Text(text, color = DIM, fontSize = 10.sp)
}

/**
 * Tick labels for the manual-distance slider, positioned by their 1/Z value so
 * the labels sit under the thumb stops (the slider itself is linear in 1/Z,
 * left edge = ∞, right edge = 1.5 m).
 */
@Composable
private fun InvZStopLabels() {
    val stops = FusionCalibration.DISTANCE_STOPS_M
        .map { (if (it.isInfinite()) 0f else 1f / it) to it }
        .sortedBy { it.first }
    val f = stops.map { it.first / FusionCalibration.MAX_INVZ }
    val mids = f.zipWithNext().map { (a, b) -> (a + b) / 2f }
    Row(Modifier.fillMaxWidth()) {
        f.indices.forEach { i ->
            val l = if (i == 0) 0f else mids[i - 1]
            val r = if (i == f.lastIndex) 1f else mids[i]
            val v = stops[i].second
            val label = if (v.isInfinite()) "∞"
                        else if (v == v.toInt().toFloat()) "${v.toInt()}" else "$v"
            Text(label, color = DIM, fontSize = 9.sp, textAlign = TextAlign.Center,
                 modifier = Modifier.weight((r - l).coerceAtLeast(0.01f)))
        }
    }
}

/** Saved distance samples, each with its fit residual in native thermal pixels. */
@Composable
private fun CalibrationSection(vm: ViewerViewModel) {
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { vm.exportCalibration(it) } }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importCalibration(it) } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { exportLauncher.launch("fusion_calibration.json") },
                       enabled = vm.calibSamples.isNotEmpty(),
                       modifier = Modifier.weight(1f)) { Text(stringResource(R.string.export), fontSize = 12.sp) }
        OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json",
                                                                 "text/plain", "*/*")) },
                       modifier = Modifier.weight(1f)) { Text(stringResource(R.string.import_), fontSize = 12.sp) }
    }
    val samples = vm.calibSamples
    if (samples.isEmpty()) {
        Text(stringResource(R.string.calib_tip),
             color = DIM, fontSize = 10.sp)
        return
    }
    val fit = vm.calibFitState
    val tw = vm.lastFrame?.w ?: 160
    val th = vm.lastFrame?.h ?: 120
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.distance_fit), color = DIM, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(LocalContext.current.resources.getQuantityString(
                 R.plurals.samples_zoom, samples.size, samples.size, fit.zoom),
             color = DIM, fontSize = 10.sp)
    }
    samples.forEachIndexed { i, s ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("%s · ×%.2f %+.3f %+.3f · Δ %.1f px".format(
                     vm.distanceText(s.invZ), s.zoom, s.dx, s.dy,
                     fit.residualPx(s, tw, th)),
                 color = FG, fontSize = 10.sp, maxLines = 1,
                 modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.deleteCalibSample(i) },
                       contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("✕", color = HOT, fontSize = 12.sp)
            }
        }
    }
    TextButton(onClick = { vm.clearCalibSamples() }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.clear_samples), color = HOT, fontSize = 11.sp)
    }
}
