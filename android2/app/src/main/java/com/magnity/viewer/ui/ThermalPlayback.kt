package com.magnity.viewer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.magnity.viewer.media.ThermalStream
import com.magnity.viewer.pipeline.ImageOps
import com.magnity.viewer.pipeline.Palettes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A saved temperature capture (.mgt) opened for review: one decoded frame at a time,
 * palette-mapped for display, with the numbers still behind every pixel so a position
 * can be measured after the fact.
 *
 * Frames are read on demand (fixed stride = one seek), so scrubbing a long recording
 * costs one frame of memory, not the whole file.
 */
class ThermalPlayback(private val reader: ThermalStream.Reader, val name: String) {

    companion object {
        /** Display upscale, matching the live view's 4× output. */
        private const val SCALE = 4
    }

    val w = reader.width
    val h = reader.height
    val frameCount = reader.frameCount
    val emissivity = reader.emissivity
    val startEpochMs = reader.startEpochMs

    var index by mutableIntStateOf(0); private set
    var bitmap by mutableStateOf<android.graphics.Bitmap?>(null); private set
    var tempMin by mutableFloatStateOf(0f); private set
    var tempMax by mutableFloatStateOf(0f); private set
    var minPos by mutableIntStateOf(0); private set
    var maxPos by mutableIntStateOf(0); private set
    var scaleLo by mutableFloatStateOf(0f); private set
    var scaleHi by mutableFloatStateOf(1f); private set
    /** Time of the shown frame since the start of the capture. */
    var tMs by mutableLongStateOf(0L); private set
    /** Tapped position in the native grid, null = none. */
    var spot by mutableStateOf<Pair<Int, Int>?>(null)
    var showMin by mutableStateOf(true)
    var showMax by mutableStateOf(true)

    private var field: FloatArray? = null

    fun spotCelsius(): Float? {
        val (x, y) = spot ?: return null
        if (x < 0 || y < 0 || x >= w || y >= h) return null
        return field?.getOrNull(y * w + x)
    }

    fun seekTo(i: Int) { index = i.coerceIn(0, frameCount - 1) }
    fun step(d: Int) = seekTo(index + d)

    /** Decode and render frame [i]; file and pixel work stay off the main thread. */
    suspend fun show(i: Int, palette: String) {
        val idx = i.coerceIn(0, frameCount - 1)
        val r = withContext(Dispatchers.IO) {
            val f = reader.frame(idx)
            val t = f.temps
            var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
            var mnI = 0; var mxI = 0
            for (p in t.indices) {
                val v = t[p]
                if (v < mn) { mn = v; mnI = p }
                if (v > mx) { mx = v; mxI = p }
            }
            // same auto range as the live view, so a capture looks like what was on screen
            val (p1, p99) = ImageOps.percentileRange(t, 1f, 99f)
            val lo = p1
            val hi = if (p99 - p1 < 0.1f) p1 + 0.1f else p99
            val src = ImageOps.resizeBicubic(t, w, h, w * SCALE, h * SCALE)
            val lut = Palettes.lut(palette)
            val px = IntArray(src.size)
            val inv = 255f / (hi - lo)
            for (p in src.indices) {
                var v = (src[p] - lo) * inv
                if (v < 0f) v = 0f else if (v > 255f) v = 255f
                px[p] = lut[(v + 0.5f).toInt()]
            }
            Rendered(f.tMs, t, mn, mx, mnI, mxI, lo, hi,
                     android.graphics.Bitmap.createBitmap(px, w * SCALE, h * SCALE,
                         android.graphics.Bitmap.Config.ARGB_8888))
        }
        index = idx
        field = r.field
        tMs = r.tMs
        tempMin = r.min; tempMax = r.max
        minPos = r.minPos; maxPos = r.maxPos
        scaleLo = r.lo; scaleHi = r.hi
        bitmap = r.bitmap
    }

    private class Rendered(
        val tMs: Long, val field: FloatArray,
        val min: Float, val max: Float, val minPos: Int, val maxPos: Int,
        val lo: Float, val hi: Float, val bitmap: android.graphics.Bitmap,
    )

    fun close() = reader.close()
}
