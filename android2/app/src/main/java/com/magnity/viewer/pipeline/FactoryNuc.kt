package com.magnity.viewer.pipeline

import com.magnity.viewer.data.NpyArray
import java.io.InputStream

/**
 * Factory per-pixel radiometric NUC — per docs/algorithm_spec.html §4.1:
 *
 *     v        = (raw[i] - offset_ref[i]) >> 1
 *     s        = first segment with v <= breakpoint[i,s]   (else last)
 *     out[i]   = clamp(((v * gain[i,s]) >> shift) + offset[i,s], 0, 65535)
 *
 * offset_ref MUST be the live shutter dark frame (ffcRef) — using the grid's own ref
 * saturates ~35% of pixels (measured). Grid tables are selected by nearest FPA point;
 * level continuity across table switches is handled by the caller's level-lock trim.
 */
class FactoryNuc private constructor(
    private val fpa: IntArray,          // (N,) grid FPA temps
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
    private val nsegM1 = nseg - 1
    private val fmin = fpa.first()
    private val fmax = fpa.last()

    // loaded tables cache (keyed by grid index)
    private var cachedIdx = Int.MIN_VALUE
    private var tRef = IntArray(0)
    private var tBp = IntArray(0)
    private var tGain = IntArray(0)
    private var tOffset = IntArray(0)

    companion object {
        /** Readout-block boundaries the factory gain table mis-decodes. See [flattenSeam]. */
        private const val SEAM_COL = 128
        private const val SEAM_ROW = 51
        /** Lines fitted on each side of a seam to extrapolate the step across it. */
        private const val SEAM_FIT = 16

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

    /** Nearest grid point index — exposed so callers can detect table switches. */
    fun nearestIndex(fpaTemp: Int): Int {
        val f = fpaTemp.coerceIn(fmin, fmax)
        var j = fpa.indexOfFirst { it >= f }
        if (j < 0) j = fpa.size
        return if (j <= 0 || j >= fpa.size) {
            if (j <= 0) 0 else fpa.size - 1
        } else {
            val lo = j - 1; val hi = j
            val wgt = if (fpa[hi] == fpa[lo]) 0.0 else (f - fpa[lo]).toDouble() / (fpa[hi] - fpa[lo])
            if (wgt < 0.5) lo else hi
        }
    }

    /**
     * Offset segment-0 pattern for the nearest grid table (per-pixel, mean-removed).
     * This is the fixed readout structure (column-128 seam, row-band edges) that the
     * live shutter reference cannot cancel — subtract it after the NUC apply.
     */
    fun offsetPattern(fpaTemp: Int): FloatArray {
        tablesAt(nearestIndex(fpaTemp))
        return offsetPatternAt()
    }

    /** Same, for an explicit grid index (level-lock evaluates previous config). */
    fun offsetPatternFor(gridIdx: Int): FloatArray {
        tablesAt(gridIdx)
        return offsetPatternAt()
    }

    private fun offsetPatternAt(): FloatArray {
        val pat = FloatArray(pix)
        var sum = 0.0
        for (p in 0 until pix) { pat[p] = tOffset[p * nseg].toFloat(); sum += pat[p] }
        val mean = (sum / pix).toFloat()
        for (p in 0 until pix) pat[p] -= mean
        return pat
    }

    /**
     * Seam residual = the per-pixel offset [applyLoaded] ACTUALLY added for THIS frame's
     * segment selection (offset[seg]) minus the segment-0 pattern ([offsetPattern]).
     * For cold pixels (seg 0) this is ~0; for warm pixels (higher segments) it is the
     * extra per-pixel offset the level-baseline misses — the "two-detector" left/right
     * step that only appears on warm objects. Subtract after [apply].
     */
    fun seamResidual(raw: FloatArray, fpaTemp: Int, offsetRef: FloatArray?): FloatArray {
        tablesAt(nearestIndex(fpaTemp))
        return seamResidualLoaded(raw, offsetRef)
    }

    fun seamResidualFor(raw: FloatArray, gridIdx: Int, offsetRef: FloatArray?): FloatArray {
        tablesAt(gridIdx)
        return seamResidualLoaded(raw, offsetRef)
    }

    private fun seamResidualLoaded(raw: FloatArray, offsetRef: FloatArray?): FloatArray {
        val full = appliedOffsetLoaded(raw, offsetRef)   // mean-removed offset[seg]
        val base = offsetPatternAt()                      // mean-removed offset[0]
        for (p in 0 until pix) full[p] -= base[p]
        return full
    }

    private fun appliedOffsetLoaded(raw: FloatArray, offsetRef: FloatArray?): FloatArray {
        val out = FloatArray(pix)
        var sum = 0.0
        for (p in 0 until pix) {
            val oref = offsetRef?.get(p)?.toLong() ?: tRef[p].toLong()
            val v = (raw[p].toLong() - oref) shr 1
            var seg = 0
            while (seg < nsegM1 && v > tBp[p * nsegM1 + seg]) seg++
            val o = tOffset[p * nseg + seg].toFloat()
            out[p] = o; sum += o
        }
        val mean = (sum / pix).toFloat()
        for (p in 0 until pix) out[p] -= mean
        return out
    }

    /** Load tables from an explicit grid index. */
    private fun tablesAt(k: Int) {
        if (k == cachedIdx) return
        cachedIdx = k
        tRef = ref.copyOfRange(k * pix, (k + 1) * pix)
        tBp = bp.copyOfRange(k * pix * nsegM1, (k + 1) * pix * nsegM1)
        tGain = gain.copyOfRange(k * pix * nseg, (k + 1) * pix * nseg)
        tOffset = offset.copyOfRange(k * pix * nseg, (k + 1) * pix * nseg)
        flattenSeams()
    }

    /**
     * Remove the spurious gain discontinuities at the two readout-block boundaries.
     *
     * The factory gain table steps hard across column 128 and row 51 while every other
     * boundary sits at the noise floor — 10-20x larger at col 128, 5-9x at row 51, and
     * consistent across every FPA anchor. Measured on the sensor itself, across three
     * captures at different levels (dRaw between the shutter and the scene, which cancels
     * offsets exactly), the responsivity across both boundaries is flat to within the
     * estimator's own noise:
     *
     *     boundary   sensor says        table claims
     *     col 128    -0.48% +/- 0.08%   +4.57%
     *     row  51    +0.05% +/- 0.13%   +2.51%
     *
     * So both steps are extraction artifacts, not sensor structure — the `ref` field from
     * the same extraction is provably broken too (it interleaves two fields, and
     * de-interleaving drops its horizontal roughness from 7486 to 43.9).
     */
    private fun flattenSeams() {
        flattenSeam(vertical = true, at = SEAM_COL)
        flattenSeam(vertical = false, at = SEAM_ROW)
    }

    /**
     * Scale the far side of one seam onto the near side, per segment.
     *
     * The step rides on a smooth gradient, so a plain block mean underestimates it (3.4%
     * vs the 4.5% that actually nulls col 128). Fit a line to the profile on each side and
     * extrapolate both to the boundary instead. The near side is the larger part of the
     * frame in both cases, so it is the one left untouched.
     */
    private fun flattenSeam(vertical: Boolean, at: Int) {
        val n = if (vertical) width else height
        val across = if (vertical) height else width
        if (at - SEAM_FIT < 0 || at + SEAM_FIT > n) return
        val prof = DoubleArray(n)
        for (s in 0 until nseg) {
            for (i in 0 until n) {
                var acc = 0.0
                for (j in 0 until across) {
                    val p = if (vertical) j * width + i else i * width + j
                    acc += tGain[p * nseg + s].toDouble()
                }
                prof[i] = acc / across
            }
            val edge = at - 0.5
            val near = fitAt(prof, at - SEAM_FIT, at, edge)
            val far = fitAt(prof, at, at + SEAM_FIT, edge)
            if (far <= 0.0) continue
            val ratio = near / far
            // a degenerate/empty anchor must not be "corrected" into nonsense
            if (ratio < 0.8 || ratio > 1.25) continue
            for (i in at until n) {
                for (j in 0 until across) {
                    val p = if (vertical) j * width + i else i * width + j
                    val idx = p * nseg + s
                    tGain[idx] = Math.round(tGain[idx] * ratio).toInt()
                }
            }
        }
    }

    /** Least-squares line through `prof[from, to)`, evaluated at position [at]. */
    private fun fitAt(prof: DoubleArray, from: Int, to: Int, at: Double): Double {
        val n = to - from
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (c in from until to) {
            val x = c.toDouble(); val y = prof[c]
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val den = n * sxx - sx * sx
        if (den == 0.0) return sy / n
        val slope = (n * sxy - sx * sy) / den
        return slope * at + (sy - slope * sx) / n
    }


    /** Apply with nearest table selection for [fpaTemp]. */
    fun apply(
        raw: FloatArray,
        fpaTemp: Int,
        offsetRef: FloatArray?,
        out: FloatArray = FloatArray(pix),
    ): FloatArray {
        tablesAt(nearestIndex(fpaTemp))
        return applyLoaded(raw, offsetRef, out)
    }

    /** Apply with an explicit grid index (level-lock evaluates previous config). */
    fun applyWith(
        raw: FloatArray,
        gridIdx: Int,
        offsetRef: FloatArray?,
        out: FloatArray = FloatArray(pix),
    ): FloatArray {
        tablesAt(gridIdx)
        return applyLoaded(raw, offsetRef, out)
    }

    private fun applyLoaded(raw: FloatArray, offsetRef: FloatArray?, out: FloatArray): FloatArray {
        for (p in 0 until pix) {
            val oref = offsetRef?.get(p)?.toLong() ?: tRef[p].toLong()
            val v = (raw[p].toLong() - oref) shr 1
            var seg = 0
            while (seg < nsegM1 && v > tBp[p * nsegM1 + seg]) seg++
            val o = ((v * tGain[p * nseg + seg]) shr shift) + tOffset[p * nseg + seg]
            out[p] = o.coerceIn(0, 65535).toFloat()
        }
        return out
    }
}
