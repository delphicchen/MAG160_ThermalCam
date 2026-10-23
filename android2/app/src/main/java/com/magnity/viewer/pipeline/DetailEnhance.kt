package com.magnity.viewer.pipeline

import kotlin.math.abs

/**
 * Digital detail enhancement (DDE) for the display — the look of a commercial thermal
 * camera. The field is split into an edge-preserving base (self-guided filter) and a
 * detail layer:
 *  - the base is tone-mapped with CLAHE blended into the linear map, so one warm object
 *    no longer squeezes the rest of the scene into a couple of colours;
 *  - the detail layer is cored (soft threshold at [CORE] × its noise σ) and amplified,
 *    so texture and edges show while flat areas come out quieter than the linear map.
 *
 * Display only: the result is a field in [lo, hi] for the unchanged render path, but the
 * palette no longer maps linearly to °C. The readouts come from the raw field.
 * Tuned offline on the 2026-10-12 fusion dumps (cup / fan).
 */
class DetailEnhance {
    private var sigma = 0f
    private var sample = FloatArray(0)

    private companion object {
        const val EPS_REL = 0.1f      // guided-filter ε, as a fraction of the display range
        const val CORE = 2f           // detail below this many σ is treated as noise
        const val GAIN = 1.5f         // detail shown at (1 + GAIN)× its linear contrast
        const val ALPHA = 0.7f        // CLAHE share of the base tone map (rest linear)
        const val CLIP = 2.5f         // CLAHE clip limit
        const val STRIDE = 7          // σ from every 7th pixel (odd: no row aliasing)
        const val SIGMA_EMA = 0.2f    // σ smoothing per frame, so the coring doesn't flicker
    }

    fun reset() { sigma = 0f }

    fun apply(src: FloatArray, w: Int, h: Int, lo: Float, hi: Float): FloatArray {
        val n = src.size
        val range = (hi - lo).coerceAtLeast(1e-3f)
        val r = maxOf(2, minOf(w, h) / 30)
        val eps = (EPS_REL * range) * (EPS_REL * range)

        // self-guided filter: base = mean(a)·I + mean(b)
        val meanI = ImageOps.boxFilter(src, w, h, r)
        val corrI = ImageOps.boxFilter(FloatArray(n) { src[it] * src[it] }, w, h, r)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val v = (corrI[i] - meanI[i] * meanI[i]).coerceAtLeast(0f)
            a[i] = v / (v + eps)
            b[i] = meanI[i] * (1f - a[i])
        }
        val meanA = ImageOps.boxFilter(a, w, h, r)
        val meanB = ImageOps.boxFilter(b, w, h, r)
        val base = FloatArray(n) { meanA[it] * src[it] + meanB[it] }

        // noise σ of the detail layer (mostly noise in flat areas), robust and sparse
        val m = (n + STRIDE - 1) / STRIDE
        if (sample.size != m) sample = FloatArray(m)
        for (k in 0 until m) sample[k] = abs(src[k * STRIDE] - base[k * STRIDE])
        sample.sort()
        val s = 1.4826f * sample[m / 2]
        sigma = if (sigma == 0f) s else sigma + SIGMA_EMA * (s - sigma)

        val landscape = w >= h
        val eq = ImageOps.clahe(base, w, h, if (landscape) 4 else 3, if (landscape) 3 else 4,
                                CLIP, rangeLo = lo, rangeHi = hi)
        val core = CORE * sigma
        val g = (1f + GAIN) / range
        val out = a                                   // done with a: reuse it
        for (i in 0 until n) {
            var lin = (base[i] - lo) / range
            if (lin < 0f) lin = 0f else if (lin > 1f) lin = 1f
            var d = src[i] - base[i]
            d = if (d > core) d - core else if (d < -core) d + core else 0f
            var t = (1f - ALPHA) * lin + ALPHA * eq[i] + g * d
            if (t < 0f) t = 0f else if (t > 1f) t = 1f
            out[i] = lo + t * range
        }
        return out
    }
}
