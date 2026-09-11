package com.magnity.viewer.pipeline

import com.magnity.viewer.data.NpyArray
import java.io.InputStream

/**
 * Factory per-die (2x2 stitched-die) flat-field — derived by reversing the live SDK
 * pipeline. The sensor's four dies each have a distinct gain/offset; the factory app
 * cancels this in the radiometric step (sub_3f970 LUT, keyed by FPA temperature) so a
 * uniform scene renders flat. The per-pixel NUC ([FactoryNuc]) only removes high-frequency
 * FPN and leaves the 2x2 partition intact, which is why the port showed a stitched-die seam.
 *
 * Calibration source: mag_cali.bin groups 1..5 (gain maps 8/16/24/32/40, offset maps
 * 10/18/26/34/42) at the header anchor FPA temps. For each group we take the per-die
 * (quadrant) mean gain/offset; the flat-field per die d is
 *
 *     out[p] = (raw[p] - off_d) * (gref / gain_d)
 *
 * selected by nearest FPA grid point. This equalises the four dies (range->~0 on a
 * synthetic uniform frame); [FactoryNuc] then removes the remaining per-pixel FPN.
 *
 * Validated against the emulated firmware: gate-0 NUC alone leaves a 2x2 range of ~50000
 * counts; with this flat-field pre-applied the 2x2 collapses to a few hundred counts.
 */
class FactoryFlatField private constructor(
    private val fpa: IntArray,           // (N,) grid FPA temps
    private val dieGain: FloatArray,     // (N*4,) per-die gain (TL,TR,BL,BR)
    private val dieOff: FloatArray,      // (N*4,) per-die offset
    private val gref: FloatArray,        // (N,) reference gain per grid point
    val height: Int,
    val width: Int,
) {
    private val pix = height * width
    private val fmin = fpa.first()
    private val fmax = fpa.last()
    private val dw = width / 2
    private val dh = height / 2

    companion object {
        fun load(npz: InputStream): FactoryFlatField {
            val d = NpyArray.readNpz(npz)
            val f = d.getValue("fpa").toIntArray()
            val n = f.size
            val h = d.getValue("height").scalarInt()
            val w = d.getValue("width").scalarInt()
            return FactoryFlatField(
                fpa = f,
                dieGain = d.getValue("die_gain").toFloatArray(),
                dieOff = d.getValue("die_off").toFloatArray(),
                gref = d.getValue("gref").toFloatArray(),
                height = h, width = w,
            ).also { check(it.dieGain.size == n * 4) }
        }
    }

    fun nearestIndex(fpaTemp: Int): Int {
        val t = fpaTemp.coerceIn(fmin, fmax)
        var j = fpa.indexOfFirst { it >= t }
        if (j < 0) j = fpa.size
        return if (j <= 0 || j >= fpa.size) {
            if (j <= 0) 0 else fpa.size - 1
        } else {
            val lo = j - 1; val hi = j
            val wgt = if (fpa[hi] == fpa[lo]) 0.0 else (t - fpa[lo]).toDouble() / (fpa[hi] - fpa[lo])
            if (wgt < 0.5) lo else hi
        }
    }

    fun apply(raw: FloatArray, fpaTemp: Int, out: FloatArray = FloatArray(pix)): FloatArray {
        val k = nearestIndex(fpaTemp) * 4
        var p = 0
        for (y in 0 until height) {
            val dy = if (y < dh) 0 else 2
            for (x in 0 until width) {
                val d = dy + if (x < dw) 0 else 1
                val g = dieGain[k + d]
                val factor = if (g > 0f) gref[k / 4] / g else 1f
                out[p] = (raw[p] - dieOff[k + d]) * factor
                p++
            }
        }
        return out
    }
}
