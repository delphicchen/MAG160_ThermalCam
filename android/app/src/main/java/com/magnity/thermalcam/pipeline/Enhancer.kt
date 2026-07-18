package com.magnity.thermalcam.pipeline

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sign

/**
 * Tier-1 image-quality pipeline — Kotlin port of `enhance.py`.
 *
 * Two stages, deliberately separated so temperature stays accurate:
 *   enhanceData(raw)  -> value-preserving cleanup for BOTH measurement and display base:
 *                        bad-pixel correction + motion-adaptive temporal denoising.
 *   enhanceDisplay(d) -> edge-preserving spatial smoothing for visuals only
 *                        (never used for the °C readout).
 *
 * All operations work in raw sensor-count space (float32).
 */
class Enhancer(val width: Int, val height: Int) {

    // toggles
    var bpc = true
    var flatfield = true
    var temporal = true
    var spatial = true

    // scene-based flat-field (shading/vignette + column-FPN) offset correction map
    var flatMap: FloatArray? = null
    // two-point per-pixel NUC: corrected = gainA*f + gainB (supersedes flatMap)
    var gainA: FloatArray? = null
    var gainB: FloatArray? = null

    // params (same defaults as the Linux app)
    var bpcK = 5.0f
    var temporalMax = 0.85f
    var temporalK = 3.0f
    var spatialD = 5
    var spatialSigmaMult = 2.0f

    // state
    private var avg: FloatArray? = null      // temporal accumulator
    var sigma = 30.0f; private set           // running noise estimate (counts)
    var lastNbad = 0; private set
    var staticBad = BooleanArray(width * height)   // learned persistent bad-pixel mask

    private val pix = width * height

    // ---- noise estimate -------------------------------------------------------

    private fun updateSigma(resid: FloatArray): Float {
        val s = ImageOps.madSigma(resid) + 1e-3f
        sigma = 0.9f * sigma + 0.1f * s
        return sigma
    }

    // ---- bad-pixel correction ---------------------------------------------------

    private fun correctBad(f: FloatArray): FloatArray {
        val med = ImageOps.median3x3(f, width, height)
        val dev = FloatArray(pix) { f[it] - med[it] }
        val absDev = FloatArray(pix) { abs(dev[it]) }
        val s = 1.4826f * ImageOps.median(absDev) + 1e-3f
        val out = f.copyOf()
        var nbad = 0
        val thr = bpcK * s
        for (i in 0 until pix) {
            if (abs(dev[i]) > thr || staticBad[i]) {
                out[i] = med[i]
                nbad++
            }
        }
        lastNbad = nbad
        return out
    }

    // ---- motion-adaptive temporal IIR -------------------------------------------

    private fun temporalStepInternal(f: FloatArray): FloatArray {
        val a = avg
        if (a == null || a.size != f.size) {
            avg = f.copyOf()
            return f
        }
        val diff = FloatArray(pix) { f[it] - a[it] }
        val s = updateSigma(diff)
        val denom = temporalK * s
        val out = FloatArray(pix)
        for (i in 0 until pix) {
            val t = diff[i] / denom
            val w = temporalMax * exp(-(t * t))
            out[i] = w * a[i] + (1f - w) * f[i]
        }
        avg = out
        return out
    }

    fun resetTemporal() { avg = null }

    // ---- persistent bad-pixel learning -------------------------------------------

    /** Build the static bad-pixel mask from a stack of frames. Returns the count. */
    fun learnBadPixels(frames: List<FloatArray>, k: Float = 4.0f): Int {
        if (frames.isEmpty()) return 0
        val mean = FloatArray(pix)
        for (fr in frames) for (i in 0 until pix) mean[i] += fr[i]
        for (i in 0 until pix) mean[i] /= frames.size
        val med = ImageOps.median3x3(mean, width, height)
        val dev = FloatArray(pix) { mean[it] - med[it] }
        val s = ImageOps.madSigma(dev) + 1e-3f
        var n = 0
        for (i in 0 until pix) {
            staticBad[i] = abs(dev[i]) > k * s
            if (staticBad[i]) n++
        }
        return n
    }

    // ---- scene-based flat-field / shading correction -------------------------------

    /**
     * Build the per-pixel shading map from frames of a uniform-temperature target.
     * Decomposes the reference into smooth vignette + column FPN + row FPN so temporal
     * noise is not baked into a permanent spatial grid. Returns the map's std.
     */
    fun captureFlatfield(frames: List<FloatArray>): Float {
        var ref = FloatArray(pix)
        for (fr in frames) for (i in 0 until pix) ref[i] += fr[i]
        for (i in 0 until pix) ref[i] /= frames.size

        // 1. remove bad pixels before analysis
        val refMed = ImageOps.median3x3(ref, width, height)
        var anyBad = false
        for (b in staticBad) if (b) { anyBad = true; break }
        ref = if (anyBad) FloatArray(pix) { if (staticBad[it]) refMed[it] else ref[it] } else refMed

        // 2. smooth vignette (lens shading)
        val vignette = ImageOps.gaussianBlur(ref, width, height, 15.0f)

        // 3. 1-D fixed-pattern noise
        val resid = FloatArray(pix) { ref[it] - vignette[it] }
        val colFpn = FloatArray(width)
        val colBuf = FloatArray(height)
        for (x in 0 until width) {
            for (y in 0 until height) colBuf[y] = resid[y * width + x]
            colFpn[x] = ImageOps.median(colBuf)
        }
        val rowFpn = FloatArray(height)
        val rowBuf = FloatArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) rowBuf[x] = resid[y * width + x] - colFpn[x]
            rowFpn[y] = ImageOps.median(rowBuf)
        }

        // 4. reconstruct the clean (noise-free) map
        val clean = FloatArray(pix)
        for (y in 0 until height) for (x in 0 until width) {
            clean[y * width + x] = vignette[y * width + x] + colFpn[x] + rowFpn[y]
        }
        val m = ImageOps.mean(clean)
        val map = FloatArray(pix) { clean[it] - m }
        flatMap = map
        resetTemporal()
        return ImageOps.std(map)
    }

    fun clearFlatfield() {
        flatMap = null
        gainA = null
        gainB = null
    }

    // ---- two-point per-pixel NUC (offset + responsivity gain) -----------------------

    /**
     * Build the per-pixel affine NUC `corrected = a*f + b` from two uniform-target
     * bursts at different levels. Returns (gainStd, spanCounts); span < 200 means the
     * two targets were too close in level and nothing was stored.
     */
    fun captureFlatfield2pt(
        coldFrames: List<FloatArray>, warmFrames: List<FloatArray>,
        clampLo: Float = 0.6f, clampHi: Float = 1.7f,
    ): Pair<Float, Float> {
        val c = FloatArray(pix); val w = FloatArray(pix)
        for (fr in coldFrames) for (i in 0 until pix) c[i] += fr[i]
        for (i in 0 until pix) c[i] /= coldFrames.size
        for (fr in warmFrames) for (i in 0 until pix) w[i] += fr[i]
        for (i in 0 until pix) w[i] /= warmFrames.size

        var anyBad = false
        for (b in staticBad) if (b) { anyBad = true; break }
        if (anyBad) {          // de-spike ONLY flagged bad pixels
            val cm = ImageOps.median3x3(c, width, height)
            val wm = ImageOps.median3x3(w, width, height)
            for (i in 0 until pix) if (staticBad[i]) { c[i] = cm[i]; w[i] = wm[i] }
        }
        val lc = ImageOps.median(c)
        val lw = ImageOps.median(w)
        val span = abs(lw - lc)
        if (span < 200f) return 0f to span

        val a = FloatArray(pix)
        for (i in 0 until pix) {
            var d = w[i] - c[i]
            if (abs(d) < 1f) d = sign(d) * 1f + 1e-3f
            a[i] = ((lw - lc) / d).coerceIn(clampLo, clampHi)
        }
        if (anyBad) {
            val am = ImageOps.median3x3(a, width, height)
            for (i in 0 until pix) if (staticBad[i]) a[i] = am[i]
        }
        val b = FloatArray(pix) { lc - a[it] * c[it] }
        gainA = a; gainB = b
        flatMap = null                       // affine supersedes the offset-only map
        resetTemporal()
        return ImageOps.std(a) to span
    }

    // ---- public -----------------------------------------------------------------

    /** Per-frame value-safe cleanup (bad-pixel + flat-field). No temporal blend. */
    fun clean(raw: FloatArray): FloatArray {
        var f = raw
        if (bpc) f = correctBad(f) else lastNbad = 0
        val ga = gainA; val gb = gainB; val fm = flatMap
        if (flatfield && ga != null && gb != null) {
            val out = if (f === raw) FloatArray(pix) else f
            for (i in 0 until pix) out[i] = ga[i] * f[i] + gb[i]
            f = out
        } else if (flatfield && fm != null) {
            val out = if (f === raw) FloatArray(pix) else f
            for (i in 0 until pix) out[i] = f[i] - fm[i]
            f = out
        }
        return if (f === raw) f.copyOf() else f
    }

    fun temporalStep(f: FloatArray): FloatArray = if (temporal) temporalStepInternal(f) else f

    fun enhanceData(raw: FloatArray): FloatArray = temporalStep(clean(raw))

    /** Correct only strong outliers + statically-flagged pixels (used after factory NUC). */
    fun correctBadOnly(f: FloatArray): FloatArray = correctBad(f)

    /** Bilateral pre-smooth used before neural SR and for display smoothing. */
    fun smooth(f: FloatArray): FloatArray {
        val sc = spatialSigmaMult * max(sigma, 1.0f)
        return ImageOps.bilateral(f, width, height, spatialD, sc, spatialD.toFloat())
    }

    fun enhanceDisplay(data: FloatArray): FloatArray = if (spatial) smooth(data) else data
}
