package com.magnity.viewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Review screen for a saved temperature capture (.mgt): the same image the camera showed,
 * but every pixel still carries its °C — tap to read one, scrub to find the moment.
 * Shown in place of the live view while a capture is open; the stream keeps running.
 */
@Composable
fun PlaybackPane(vm: ViewerViewModel, pb: ThermalPlayback) {
    val palette = vm.paletteName
    // decoding and palette-mapping happen in show(); this only asks for a frame
    LaunchedEffect(pb, pb.index, palette) { pb.show(pb.index, palette) }
    BackHandler { vm.closePlayback() }      // back returns to the live view, not out

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0D1117))
            .safeDrawingPadding().padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(pb.name, color = ACCENT, fontSize = 14.sp, maxLines = 1)
                Text("%d×%d · %d frame%s · ε %.2f · %s".format(
                         pb.w, pb.h, pb.frameCount, if (pb.frameCount == 1) "" else "s",
                         pb.emissivity, stamp(pb.startEpochMs + pb.tMs)),
                     color = DIM, fontSize = 10.sp, maxLines = 1)
            }
            OutlinedButton(onClick = { vm.closePlayback() }) { Text("Close") }
        }

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f),
                           contentAlignment = Alignment.Center) {
            val bmp = pb.bitmap
            if (bmp != null) {
                val density = LocalDensity.current
                val barH = 40.dp
                val paneWpx = with(density) { maxWidth.toPx() }
                val paneHpx = with(density) { (maxHeight - barH).toPx() }
                val scale = min(paneWpx / bmp.width, paneHpx / bmp.height)
                val dispW = (bmp.width * scale).roundToInt()
                val dispH = (bmp.height * scale).roundToInt()
                val dispWdp = with(density) { dispW.toDp() }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PlaybackImage(pb, bmp, dispW, dispH,
                                  Modifier.size(dispWdp, with(density) { dispH.toDp() }))
                    Spacer(Modifier.height(6.dp))
                    ColorBar(palette, pb.scaleLo, pb.scaleHi, Modifier.width(dispWdp))
                }
            } else {
                Text("Reading capture…", color = DIM)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            Readout("MIN", pb.tempMin, COLD, pb.showMin) { pb.showMin = !pb.showMin }
            Readout("MAX", pb.tempMax, HOT, pb.showMax) { pb.showMax = !pb.showMax }
            Readout("SPOT", pb.spotCelsius(), Color.White, pb.spot != null) { pb.spot = null }
        }

        if (pb.frameCount > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("frame %d / %d".format(pb.index + 1, pb.frameCount),
                     color = FG, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("%.2f s".format(pb.tMs / 1000f), color = DIM, fontSize = 12.sp)
            }
            Slider(value = pb.index.toFloat(),
                   onValueChange = { pb.seekTo(it.roundToInt()) },
                   valueRange = 0f..(pb.frameCount - 1).toFloat())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pb.step(-1) }, modifier = Modifier.weight(1f),
                       contentPadding = PaddingValues(4.dp)) { Text("◀ prev") }
                Button(onClick = { pb.step(1) }, modifier = Modifier.weight(1f),
                       contentPadding = PaddingValues(4.dp)) { Text("next ▶") }
            }
        }

        Text("Tap the image to read a temperature, long-press to clear.\n" +
             "Colour range follows each frame; the numbers are absolute °C as captured.",
             color = DIM, fontSize = 10.sp)
    }
}

private fun stamp(ms: Long) =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

@Composable
private fun PlaybackImage(
    pb: ThermalPlayback, bmp: android.graphics.Bitmap, dispW: Int, dispH: Int,
    modifier: Modifier,
) {
    val image = remember(bmp) { bmp.asImageBitmap() }
    val cellW = dispW.toFloat() / pb.w
    val cellH = dispH.toFloat() / pb.h
    val labelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 34f
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }
    Canvas(modifier.pointerInput(pb, dispW, dispH) {
        detectTapGestures(
            onTap = { off ->
                val gx = (off.x / cellW).toInt()
                val gy = (off.y / cellH).toInt()
                if (gx in 0 until pb.w && gy in 0 until pb.h) pb.spot = gx to gy
            },
            onLongPress = { pb.spot = null },
        )
    }) {
        drawImage(image, dstSize = IntSize(dispW, dispH), filterQuality = FilterQuality.Low)
        fun marker(pos: Int, color: Color, v: Float) {
            val c = Offset((pos % pb.w + 0.5f) * cellW, (pos / pb.w + 0.5f) * cellH)
            drawCircle(color, 18f, c, style = Stroke(4f, cap = StrokeCap.Round))
            val label = "%.1f°C".format(v)
            labelPaint.color = color.toArgb()
            val tw = labelPaint.measureText(label)
            val tx = (c.x + 24f).coerceAtMost(size.width - tw - 4f)
            val ty = (c.y - 20f).coerceAtLeast(38f)
            drawIntoCanvas { it.nativeCanvas.drawText(label, tx, ty, labelPaint) }
        }
        if (pb.showMax) marker(pb.maxPos, HOT, pb.tempMax)
        if (pb.showMin) marker(pb.minPos, COLD, pb.tempMin)
        pb.spot?.let { (sx, sy) ->
            val c = Offset((sx + 0.5f) * cellW, (sy + 0.5f) * cellH)
            drawLine(Color.White, c - Offset(18f, 0f), c + Offset(18f, 0f), 3f)
            drawLine(Color.White, c - Offset(0f, 18f), c + Offset(0f, 18f), 3f)
        }
    }
}
