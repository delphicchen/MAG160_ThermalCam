package com.magnity.viewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magnity.viewer.pipeline.Palettes
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun ViewerScreen(vm: ViewerViewModel = viewModel()) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Color(0xFF161B22)) {
                DrawerControls(vm)
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
        Modifier.fillMaxSize().background(Color(0xFF0D1117)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // compact top bar: menu + FFC + Pause + status
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onMenu, contentPadding = PaddingValues(0.dp),
                           modifier = Modifier.size(40.dp)) { Text("☰") }
            Button(onClick = { vm.doFfc() }, enabled = vm.connected && !vm.ffcBusy) {
                Text(if (vm.ffcBusy) "FFC…" else "FFC") }
            Button(onClick = { vm.paused = !vm.paused }, enabled = vm.connected) {
                Text(if (vm.paused) "Resume" else "Pause") }
            Text(
                vm.status + (fr?.let { "  ·  FPA ${it.fpa}" } ?: ""),
                color = Color(0xFF8B949E), fontSize = 11.sp, modifier = Modifier.weight(1f)
            )
        }

        val bitmap = remember(fr) {
            fr?.let {
                val bmp = android.graphics.Bitmap.createBitmap(it.w, it.h,
                    android.graphics.Bitmap.Config.ARGB_8888)
                for (y in 0 until it.h) for (x in 0 until it.w)
                    bmp.setPixel(x, y, it.image[y * it.w + x])
                bmp.asImageBitmap()
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (bitmap != null && fr != null) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    val paneWpx = with(density) { maxWidth.toPx() }
                    val paneHpx = with(density) { maxHeight.toPx() }
                    val scale = min(paneWpx / fr.w, paneHpx / fr.h)
                    val dispW = (fr.w * scale).roundToInt()
                    val dispH = (fr.h * scale).roundToInt()
                    val dispWdp = with(density) { dispW.toDp() }
                    val dispHdp = with(density) { dispH.toDp() }

                    Canvas(Modifier.size(dispWdp, dispHdp)) {
                        drawImage(bitmap, dstSize = IntSize(dispW, dispH))
                        fun xy(i: Int): Offset =
                            Offset((i % fr.w) * scale, (i / fr.w) * scale)
                        drawCircle(Color(0xFF3FB950), 7f, xy(fr.minPos),
                            style = Stroke(2.5f, cap = StrokeCap.Round))
                        drawCircle(Color(0xFFF85149), 7f, xy(fr.maxPos),
                            style = Stroke(2.5f, cap = StrokeCap.Round))
                        vm.spot?.let { (sx, sy) ->
                            val c = Offset(sx * scale, sy * scale)
                            drawLine(Color.White, c - Offset(12f, 0f), c + Offset(12f, 0f), 2f)
                            drawLine(Color.White, c - Offset(0f, 12f), c + Offset(0f, 12f), 2f)
                        }
                    }
                    Box(
                        Modifier.size(dispWdp, dispHdp).pointerInput(fr.w, fr.h) {
                            detectTapGestures(
                                onTap = { off ->
                                    val gx = (off.x / scale).roundToInt().coerceIn(0, fr.w - 1)
                                    val gy = (off.y / scale).roundToInt().coerceIn(0, fr.h - 1)
                                    vm.spot = gx to gy
                                },
                                onLongPress = { vm.spot = null },
                            )
                        }
                    )
                }
            } else {
                Text("等待相機…（插入後允許 USB 權限）", color = Color(0xFF8B949E))
            }
        }

        // readouts
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            if (fr != null) {
                Readout("MIN", fr.tempMin, Color(0xFF3FB950))
                Readout("MAX", fr.tempMax, Color(0xFFF85149))
                Readout("SPOT", vm.spotCelsius(), Color.White)
            }
        }
    }
}

@Composable
private fun DrawerControls(vm: ViewerViewModel) {
    Column(
        Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("MagViewer", color = Color(0xFF58A6FF), fontSize = 15.sp)

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { vm.doFfc() }, enabled = vm.connected && !vm.ffcBusy,
                   modifier = Modifier.weight(1f)) { Text(if (vm.ffcBusy) "FFC…" else "FFC") }
            Button(onClick = { vm.paused = !vm.paused }, enabled = vm.connected,
                   modifier = Modifier.weight(1f)) { Text(if (vm.paused) "Resume" else "Pause") }
        }

        Column {
            Text("Palette", color = Color(0xFF8B949E), fontSize = 11.sp)
            var exp by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { exp = true }) { Text(vm.paletteName) }
                DropdownMenu(expanded = exp, onDismissRequest = { exp = false }) {
                    Palettes.NAMES.forEach { n ->
                        DropdownMenuItem(text = { Text(n) }, onClick = {
                            vm.paletteName = n; exp = false
                        })
                    }
                }
            }
        }

        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Scale", color = Color(0xFF8B949E), fontSize = 11.sp,
                     modifier = Modifier.weight(1f))
                Text("auto", color = Color(0xFF8B949E), fontSize = 11.sp)
                Switch(vm.autoScale, onCheckedChange = { vm.autoScale = it })
                Text("manual", color = Color(0xFF8B949E), fontSize = 11.sp)
            }
            if (!vm.autoScale) {
                // The manual range lives in whatever domain the active source emits:
                // °C on the factory-SDK path, NUC counts on ours.
                val range = if (vm.countsAreCelsius) -20f..200f else 0f..65535f
                val gap = if (vm.countsAreCelsius) 0.5f else 1f
                Slider(vm.scaleLo, onValueChange = {
                    vm.scaleLo = it.coerceAtMost(vm.scaleHi - gap)
                }, valueRange = range)
                Slider(vm.scaleHi, onValueChange = {
                    vm.scaleHi = it.coerceAtLeast(vm.scaleLo + gap)
                }, valueRange = range)
            }
            Text(
                if (vm.countsAreCelsius)
                    "range %.1f – %.1f °C".format(vm.scaleLo, vm.scaleHi)
                else "range ${vm.scaleLo.roundToInt()} – ${vm.scaleHi.roundToInt()} counts",
                color = Color(0xFF8B949E), fontSize = 11.sp)
        }

        Column {
            Text("Rotate", color = Color(0xFF8B949E), fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0, 90, 180, 270).forEach { deg ->
                    val sel = vm.rotation == deg
                    OutlinedButton(
                        onClick = { vm.rotation = deg },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (sel) Color(0xFF1F4258)
                                             else Color.Transparent),
                        modifier = Modifier.weight(1f).height(36.dp),
                        contentPadding = PaddingValues(2.dp),
                    ) { Text("$deg°", fontSize = 11.sp) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(vm.mirror, onCheckedChange = { vm.mirror = it })
            Text("Mirror ↔", color = Color(0xFFE6EDF3))
        }
        Text("溫度來源", color = Color(0xFF8B949E), fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()) {
            SourceChip("原廠 SDK", vm.source == ViewerViewModel.Source.FACTORY_SDK,
                       Modifier.weight(1f)) {
                vm.selectSource(ViewerViewModel.Source.FACTORY_SDK)
            }
            SourceChip("自家管線", vm.source == ViewerViewModel.Source.OWN_PIPELINE,
                       Modifier.weight(1f)) {
                vm.selectSource(ViewerViewModel.Source.OWN_PIPELINE)
            }
        }
        Text(
            when (vm.activeSource) {
                ViewerViewModel.Source.FACTORY_SDK ->
                    "使用中：原廠 SDK（絕對溫度，我方 NUC/輻射校正全部停用）"
                ViewerViewModel.Source.OWN_PIPELINE ->
                    if (vm.sdkFellBack) "使用中：自家管線（SDK 啟動失敗，已自動退回）"
                    else "使用中：自家管線（FactoryNuc + Radiometry）"
                null -> "未連線"
            },
            color = if (vm.sdkFellBack) Color(0xFFD6A93D) else Color(0xFF8B949E),
            fontSize = 9.sp,
        )

        HorizontalDivider(color = Color(0xFF30363D))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(vm.spatialDenoise, onCheckedChange = { vm.spatialDenoise = it })
            Text("Spatial denoise", color = Color(0xFFE6EDF3))
        }

        HorizontalDivider(color = Color(0xFF30363D))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(vm.flatFieldOn, onCheckedChange = { vm.flatFieldOn = it },
                     enabled = vm.flatFieldReady)
            Text("Flat-field", color = Color(0xFFE6EDF3), modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.learnFlatField() },
                       enabled = vm.connected && !vm.ffcBusy) {
                Text(if (vm.flatFieldReady) "Re-learn" else "Learn") }
        }
        Text("學習前請先對準均勻場景（牆面/手掌）",
             color = Color(0xFF8B949E), fontSize = 10.sp)

        HorizontalDivider(color = Color(0xFF30363D))

        if (vm.spot != null) {
            var txt by remember { mutableStateOf("") }
            Text("SPOT 已放置 — 輸入真實溫度錨定絕對值",
                 color = Color(0xFF8B949E), fontSize = 10.sp)
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = txt,
                    onValueChange = { txt = it },
                    label = { Text("真實溫度 °C", fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).height(52.dp)
                )
                Button(onClick = {
                    txt.toFloatOrNull()?.let { vm.refineSpot(it) }
                }) { Text("Refine", fontSize = 12.sp) }
            }
        }

        HorizontalDivider(color = Color(0xFF30363D))

        Text("原廠 SDK 測試 (libcoresdk.so arm64)",
             color = Color(0xFF8B949E), fontSize = 10.sp)
        Text("測試會先中斷現有串流", color = Color(0xFF8B949E), fontSize = 9.sp)
        Button(onClick = { vm.runSdkSmokeTest() }, enabled = !vm.sdkBusy,
               colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B3D2B)),
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
               colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3D2B2B)),
               modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }

        Text("點擊畫面放置 SPOT\n長按清除\n紅圈=MAX 綠圈=MIN",
             color = Color(0xFF8B949E), fontSize = 10.sp)
    }
}

@Composable
private fun SourceChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier,
               contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
            Text(label, fontSize = 11.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier,
                       contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
            Text(label, fontSize = 11.sp, color = Color(0xFF8B949E))
        }
    }
}

@Composable
private fun Readout(label: String, value: Float?, color: Color) {
    Column {
        Text(label, color = color, fontSize = 11.sp)
        Text(value?.let { "%.1f°C".format(it) } ?: "--", color = color, fontSize = 20.sp)
    }
}
