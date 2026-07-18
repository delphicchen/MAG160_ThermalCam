package com.magnity.thermalcam.pipeline

import com.magnity.thermalcam.data.NpyArray
import java.io.InputStream

/**
 * Factory per-pixel radiometric NUC — Kotlin port of `factory_nuc_grid.py`.
 *
 * The camera's factory calibration (mag_cali.bin) encodes a per-pixel multi-segment
 * piecewise-linear NUC keyed on the FPA (sensor) temperature. The tables were reversed
 * from the firmware under ARM emulation and pre-stored over a grid of FPA temps in
 * `factory_nuc_grid.npz` (same asset as the Linux app). Per pixel i:
 *
 *     v   = (raw[i] - ref[i]) >> 1
 *     s   = first segment with v <= breakpoint[i, s]   (else the last segment)
 *     out = clamp( ((v * gain[i, s]) >> shift) + offset[i, s], 0, 65535 )
 *
 * IMPORTANT: `offsetRef` must be the camera's live shutter dark frame
 * (MagCamera.ffcRef, raw counts). The grid's own `ref` is a radiance-domain stand-in
 * that is only self-consistent inside the emulator.
 */
class FactoryNuc private constructor(
    private val fpa: IntArray,          // (N,) grid FPA temps, ascending
    private val ref: IntArray,          // (N,H,W)
    private val bp: IntArray,           // (N,H,W,nseg-1)
    private val gain: IntArray,         // (N,H,W,nseg)  u16
    private val offset: IntArray,       // (N,H,W,nseg)  u16
    val nseg: Int,
    val shift: Int,
    val height: Int,
    val width: Int,
) {
    private val pix = height * width
    private val fmin = fpa.first()
    private val fmax = fpa.last()

    // interpolated tables cache (rebuilt only when the quantised FPA temp changes)
    private var cachedFpa = Int.MIN_VALUE
    private var tRef = IntArray(0)
    private var tBp = IntArray(0)
    private var tGain = IntArray(0)
    private var tOffset = IntArray(0)
    private var tBaseMean = 0

    companion object {
        fun load(npz: InputStream): FactoryNuc {
            val d = NpyArray.readNpz(npz)
            val refArr = d.getValue("ref")
            val n = refArr.shape[0]; val h = refArr.shape[1]; val w = refArr.shape[2]
            return FactoryNuc(
                fpa = d.getValue("fpa").toIntArray(),
                ref = refArr.toIntArray(),
                bp = d.getValue("breakpoints").toIntArray(),
                gain = d.getValue("gain").toIntArray(),
                offset = d.getValue("offset").toIntArray(),
                nseg = d.getValue("nseg").scalarInt(),
                shift = d.getValue("shift").scalarInt(),
                height = h,
                width = w,
            ).also { check(it.fpa.size == n) }
        }
    }

    /** Select / interpolate the grid tables for a given FPA temp (anchor units). */
    private fun tables(fpaTemp: Int) {
        val f = fpaTemp.coerceIn(fmin, fmax)
        if (f == cachedFpa) return
        cachedFpa = f

        // searchsorted(side='left'): first j with fpa[j] >= f
        var j = fpa.indexOfFirst { it >= f }
        if (j < 0) j = fpa.size

        val nsegM1 = nseg - 1
        fun sliceRef(k: Int) = ref.copyOfRange(k * pix, (k + 1) * pix)
        fun sliceBp(k: Int) = bp.copyOfRange(k * pix * nsegM1, (k + 1) * pix * nsegM1)
        fun sliceGain(k: Int) = gain.copyOfRange(k * pix * nseg, (k + 1) * pix * nseg)
        fun sliceOff(k: Int) = offset.copyOfRange(k * pix * nseg, (k + 1) * pix * nseg)

        if (j <= 0 || j >= fpa.size) {
            val k = if (j <= 0) 0 else fpa.size - 1
            tRef = sliceRef(k); tBp = sliceBp(k); tGain = sliceGain(k); tOffset = sliceOff(k)
        } else {
            val lo = j - 1; val hi = j
            val w = if (fpa[hi] == fpa[lo]) 0.0 else (f - fpa[lo]).toDouble() / (fpa[hi] - fpa[lo])
            val nearest = if (w < 0.5) lo else hi
            // ref steps discretely at section boundaries -> use nearest, never blend
            tRef = sliceRef(nearest)
            val same = ref.regionEquals(lo * pix, hi * pix, pix)
            if (!same) {
                tBp = sliceBp(nearest); tGain = sliceGain(nearest); tOffset = sliceOff(nearest)
            } else {
                tBp = blend(bp, lo, hi, pix * nsegM1, w)
                tGain = blend(gain, lo, hi, pix * nseg, w)
                tOffset = blend(offset, lo, hi, pix * nseg, w)
            }
        }
        // scalar mean of the zero-signal baseline (offset segment 0), for level_baseline
        var sum = 0L
        for (p in 0 until pix) sum += tOffset[p * nseg]
        tBaseMean = (sum / pix).toInt()
    }

    private fun blend(src: IntArray, lo: Int, hi: Int, stride: Int, w: Double): IntArray {
        val out = IntArray(stride)
        val o1 = lo * stride; val o2 = hi * stride
        for (i in 0 until stride) {
            out[i] = Math.rint(src[o1 + i] * (1 - w) + src[o2 + i] * w).toInt()
        }
        return out
    }

    private fun IntArray.regionEquals(off1: Int, off2: Int, len: Int): Boolean {
        for (i in 0 until len) if (this[off1 + i] != this[off2 + i]) return false
        return true
    }

    /**
     * Apply the factory NUC to an UNCORRECTED raw frame (sensor orientation).
     * `offsetRef` = live shutter dark frame (strongly recommended); falls back to the
     * grid ref when null. `levelBaseline` removes the offset table's fixed per-pixel
     * pattern (incl. the column-128 readout seam) while keeping the per-pixel gain.
     */
    fun apply(
        raw: FloatArray,
        fpaTemp: Int,
        offsetRef: FloatArray?,
        levelBaseline: Boolean = true,
        out: FloatArray = FloatArray(pix),
    ): FloatArray {
        tables(fpaTemp)
        val nsegM1 = nseg - 1
        for (p in 0 until pix) {
            val oref = offsetRef?.get(p)?.toLong() ?: tRef[p].toLong()
            val v = (raw[p].toLong() - oref) shr 1
            var seg = 0
            while (seg < nsegM1 && v > tBp[p * nsegM1 + seg]) seg++
            var o = ((v * tGain[p * nseg + seg]) shr shift) + tOffset[p * nseg + seg]
            if (levelBaseline) o = o - tOffset[p * nseg] + tBaseMean
            out[p] = o.coerceIn(0, 65535).toFloat()
        }
        return out
    }
}
