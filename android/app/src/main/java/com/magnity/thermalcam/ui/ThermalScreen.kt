package com.magnity.thermalcam.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas as ComposeCanvas

/** Full-screen viewer UI — the Android counterpart of viewer.py's window. */
@Composable
fun ThermalScreen(
    vm: ThermalViewModel,
    onSaveDdt: () -> Unit,
    onLoadDdt: () -> Unit,
    onRetryConnect: () -> Unit,
) {
    var showCalDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding(),
    ) {
        ImageArea(vm)
        StatusBar(vm)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Readout(vm)
            if (!vm.connected) {
                Button(onClick = onRetryConnect, modifier = Modifier.fillMaxWidth()) {
                    Text("Connect camera")
                }
            }
            ViewControls(vm)
            EnhanceControls(vm)
            TemperatureControls(
                vm,
                onAddCal = {
                    if (vm.calTarget == null) {
                        // same guidance as the desktop app: first tap the image to lock a marker
                    } else showCalDialog = true
                },
                onSaveDdt = onSaveDdt,
                onLoadDdt = onLoadDdt,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showCalDialog) {
        AddCalDialog(vm, onDismiss = { showCalDialog = false })
    }
}

// ---- image + overlay -------------------------------------------------------------

@Composable
private fun ImageArea(vm: ThermalViewModel) {
    val bmp = vm.displayBitmap
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(vm.frameW.toFloat() / vm.frameH.toFloat())
            .background(Color(0xFF111111))
            .pointerInput(vm.frameW, vm.frameH) {
                detectTapGestures(
                    onTap = { pos ->
                        val fx = (pos.x / size.width * vm.frameW).toInt()
                        val fy = (pos.y / size.height * vm.frameH).toInt()
                        if (fx in 0 until vm.frameW && fy in 0 until vm.frameH) {
                            vm.calTarget = fx to fy       // tap locks the CAL marker
                        }
                    },
                    onLongPress = { vm.calTarget = null }, // long-press clears it
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "thermal image",
                modifier = Modifier.fillMaxSize(),
                filterQuality = FilterQuality.None,
            )
        } else {
            Text("no signal", color = Color.Gray)
        }
        ComposeCanvas(modifier = Modifier.fillMaxSize()) {
            val sx = size.width / vm.frameW
            val sy = size.height / vm.frameH
            vm.hotSpot?.let { (x, y) -> crosshair(x * sx, y * sy, Color(0xFFFF3C3C)) }
            vm.coldSpot?.let { (x, y) -> crosshair(x * sx, y * sy, Color(0xFF50A0FF)) }
            vm.calTarget?.let { (x, y) ->
                val cx = x * sx; val cy = y * sy
                val bw = 2.5f * sx; val bh = 2.5f * sy   // the 5x5 averaging box
                crosshair(cx, cy, Color(0xFF3CFF78))
                drawRect(
                    color = Color(0xFF3CFF78),
                    topLeft = Offset(cx - bw, cy - bh),
                    size = androidx.compose.ui.geometry.Size(2 * bw, 2 * bh),
                    style = Stroke(width = 3f),
                )
            }
        }
    }
}

private fun DrawScope.crosshair(x: Float, y: Float, color: Color) {
    val r = 18f
    drawLine(color, Offset(x - r, y), Offset(x + r, y), strokeWidth = 4f)
    drawLine(color, Offset(x, y - r), Offset(x, y + r), strokeWidth = 4f)
}

// ---- status bar -------------------------------------------------------------------

@Composable
private fun StatusBar(vm: ThermalViewModel) {
    val fpa = vm.fpaTemp
    val drift = vm.fpaDrift
    val extra = buildString {
        if (fpa != null) {
            append("  FPA=").append(fpa)
            if (drift != null) append(" (Δ%+d)".format(drift))
        }
        if (vm.srOn && vm.srBackend.isNotEmpty()) append("  SR:").append(vm.srBackend)
    }
    Text(
        text = vm.status + extra,
        color = Color(0xFF9ECFFF),
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF16202B))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun Readout(vm: ThermalViewModel) {
    if (vm.readout.isNotEmpty()) {
        Text(
            text = vm.readout,
            color = Color(0xFFE8E8E8),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
    vm.ddtReviewName?.let {
        Text("DDT review: $it (frozen)", color = Color(0xFFFFC53D), fontSize = 12.sp)
    }
}

// ---- control groups ----------------------------------------------------------------

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Color(0xFF8FA3B8), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label, color = if (enabled) Color.White else Color.Gray, fontSize = 14.sp)
    }
}

@Composable
private fun ViewControls(vm: ThermalViewModel) {
    SectionLabel("View")
    PaletteSelector(vm)
    ToggleRow("Auto range", vm.autoRange) { vm.autoRange = it }
    ToggleRow("Auto-FFC (FPA drift)", vm.autoFfc) { vm.setAutoFfcEnabled(it) }
    ToggleRow("Mirror (left-right)", vm.mirror) { vm.mirror = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.doFfc(false) }, enabled = vm.connected) { Text("FFC (shutter)") }
        OutlinedButton(onClick = { vm.paused = !vm.paused }) {
            Text(if (vm.paused) "Resume" else "Pause")
        }
        OutlinedButton(onClick = { vm.snapshot() }, enabled = vm.displayBitmap != null) {
            Text("Snapshot")
        }
    }
}

@Composable
private fun PaletteSelector(vm: ThermalViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("Palette: ${vm.paletteName}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Palettes.NAMES.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { vm.paletteName = name; open = false },
                )
            }
        }
    }
}

@Composable
private fun EnhanceControls(vm: ThermalViewModel) {
    SectionLabel("Enhance")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.doFlatfield() }, enabled = vm.connected) {
            Text("Flat-field cal")
        }
        OutlinedButton(onClick = { vm.doGainNucStep() }, enabled = vm.connected) {
            Text(if (vm.gainStepWarmPending) "Gain NUC: WARM" else "Gain NUC: COLD")
        }
        TextButton(onClick = { vm.clearGainNuc() }) { Text("✕") }
    }
    ToggleRow(
        if (vm.factoryNucAvailable) "Factory NUC (radiometric)" else "Factory NUC (grid missing)",
        vm.factoryNucOn, enabled = vm.factoryNucAvailable,
    ) { vm.setFactoryNuc(it) }
    ToggleRow("Flat-field correct", vm.flatfieldOn) { vm.flatfieldOn = it }
    ToggleRow("Bad-pixel correct", vm.bpcOn) { vm.bpcOn = it }
    ToggleRow("Temporal denoise", vm.temporalOn) { vm.temporalOn = it }
    ToggleRow("Spatial denoise", vm.spatialOn) { vm.spatialOn = it }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked = vm.srOn, onCheckedChange = { vm.setSuperres(it) })
        Text("Neural super-res", color = Color.White, fontSize = 14.sp)
        Spacer(Modifier.width(4.dp))
        listOf(2, 4).forEach { s ->
            val selected = vm.srScale == s
            OutlinedButton(
                onClick = { vm.setSrScale(s) },
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                ),
            ) { Text("${s}x") }
        }
    }
}

@Composable
private fun TemperatureControls(
    vm: ThermalViewModel,
    onAddCal: () -> Unit,
    onSaveDdt: () -> Unit,
    onLoadDdt: () -> Unit,
) {
    SectionLabel("Temperature (°C)")
    Text(
        "① tap the image to drop a CAL marker on a known-temperature object\n" +
            "② tap “Add cal point” and enter its °C   (long-press image clears the marker)",
        color = Color(0xFFB0B8C0), fontSize = 12.sp,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onAddCal, enabled = vm.calTarget != null && vm.connected || vm.ddtReviewName != null) {
            Text("+ Add cal point (${vm.calPointCount})")
        }
        OutlinedButton(onClick = { vm.clearCalibration() }) { Text("Clear cal") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onSaveDdt, enabled = vm.ddtReviewName == null && vm.connected) {
            Text("Save DDT")
        }
        OutlinedButton(onClick = {
            if (vm.ddtReviewName != null) vm.exitDdtReview() else onLoadDdt()
        }) {
            Text(if (vm.ddtReviewName != null) "Resume live ▶" else "Load DDT…")
        }
    }
}

// ---- calibration dialog --------------------------------------------------------------

@Composable
private fun AddCalDialog(vm: ThermalViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("25.0") }
    val target = vm.calTarget
    val raw = target?.let { vm.regionRaw(it.first, it.second) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add calibration point") },
        text = {
            Column {
                Text(
                    if (target != null && raw != null)
                        "Locked point (${target.first},${target.second}), 5×5 avg raw = $raw\n" +
                            "Enter the known temperature of that spot (°C):"
                    else "Tap the image first to lock a CAL marker.",
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("°C") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                text.toDoubleOrNull()?.let { t ->
                    if (t in -50.0..1000.0) vm.addCalPoint(t)
                }
                onDismiss()
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
