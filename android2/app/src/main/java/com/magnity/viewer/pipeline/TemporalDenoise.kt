package com.magnity.viewer.pipeline

import kotlin.math.abs
import kotlin.math.exp

/**
 * Motion-adaptive temporal IIR — port of enhance.py `_temporal`, rebuilt to cost < 1 ms
 * on 160×120:
 *  - noise σ from a sparse (1/16) MAD sample, refreshed every 8 frames instead of a full
 *    sort per frame;
 *  - exp() replaced by a lookup table;
 *  - the history buffer is reused, nothing is allocated per frame.
 *
 * Per pixel: w = strength · exp(-(d / (k·σ))²), out = w·history + (1-w)·frame. Static
 * pixels average over several frames, moving ones follow the new frame (no ghosting).
 */
class TemporalDenoise {
    var strength = 0.85f      // max history weight in fully static areas
    var k = 3f                // motion sensitivity, in σ

    private var avg: FloatArray? = null
    private var key = 0
    private var sigma = 0f
    private var frame = 0
    private var sample = FloatArray(0)

    private companion object {
        const val Q_MAX = 6f          // exp(-6) ≈ 0.0025 → treat as full motion beyond
        const val LUT_N = 1024
        val LUT = FloatArray(LUT_N) { exp(-(it.toFloat() / (LUT_N - 1)) * Q_MAX) }
    }

    fun reset() { avg = null }

    /**
     * Returns the filtered field. The array is the internal history buffer: read it before
     * the next call and never modify it.
     * @param key anything that invalidates history when it changes (orientation, source)
     */
    fun apply(f: FloatArray, key: Int): FloatArray {
        val a = avg
        if (a == null || a.size != f.size || key != this.key) {
            this.key = key; sigma = 0f; frame = 0
            return f.copyOf().also { avg = it }
        }

        if (frame++ % 8 == 0) {
            val n = f.size / 16
            if (sample.size != n) sample = FloatArray(n)
            for (i in 0 until n) {
                val j = i * 16 + (i and 15)          // stagger the stride so it isn't column-locked
                sample[i] = abs(f[j] - a[j])
            }
            sample.sort()
            val s = 1.4826f * sample[n / 2] + 1e-4f
            sigma = if (sigma <= 0f) s else 0.8f * sigma + 0.2f * s
        }

        val inv = 1f / (k * sigma)
        val lutScale = (LUT_N - 1) / Q_MAX
        val st = strength
        for (i in f.indices) {
            val v = f[i]
            val d = (v - a[i]) * inv
            val q = d * d
            val w = if (q >= Q_MAX) 0f else st * LUT[(q * lutScale).toInt()]
            a[i] = v + w * (a[i] - v)
        }
        return a
    }
}
