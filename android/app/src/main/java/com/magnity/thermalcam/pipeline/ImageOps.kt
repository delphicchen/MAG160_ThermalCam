package com.magnity.thermalcam.pipeline

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Float-image primitives used by the enhancement pipeline — replacements for the
 * OpenCV calls the Linux app makes (medianBlur, GaussianBlur, bilateralFilter,
 * INTER_CUBIC resize). Images are row-major FloatArray of size w*h.
 * Borders are replicated, matching OpenCV's default BORDER_REPLICATE behaviour
 * closely enough for a 160x120 thermal stream.
 */
object ImageOps {

    // ---- statistics ----------------------------------------------------------

    /** Median of the array (does not modify the input). */
    fun median(a: FloatArray): Float {
        if (a.isEmpty()) return 0f
        val c = a.copyOf()
        c.sort()
        val n = c.size
        return if (n % 2 == 1) c[n / 2] else 0.5f * (c[n / 2 - 1] + c[n / 2])
    }

    /** 1.4826 * median(|a - median(a)|) — the robust sigma used throughout enhance.py. */
    fun madSigma(a: FloatArray): Float {
        val m = median(a)
        val dev = FloatArray(a.size) { abs(a[it] - m) }
        return 1.4826f * median(dev)
    }

    // ---- 3x3 median (bad-pixel correction) ------------------------------------

    fun median3x3(src: FloatArray, w: Int, h: Int, dst: FloatArray = FloatArray(src.size)): FloatArray {
        val win = FloatArray(9)
        for (y in 0 until h) {
            val ym = max(y - 1, 0) * w
            val y0 = y * w
            val yp = min(y + 1, h - 1) * w
            for (x in 0 until w) {
                val xm = max(x - 1, 0)
                val xp = min(x + 1, w - 1)
                win[0] = src[ym + xm]; win[1] = src[ym + x]; win[2] = src[ym + xp]
                win[3] = src[y0 + xm]; win[4] = src[y0 + x]; win[5] = src[y0 + xp]
                win[6] = src[yp + xm]; win[7] = src[yp + x]; win[8] = src[yp + xp]
                dst[y0 + x] = median9(win)
            }
        }
        return dst
    }

    /** Median of 9 via a partial selection network (fast, allocation-free). */
    private fun median9(p: FloatArray): Float {
        fun sw(i: Int, j: Int) { if (p[i] > p[j]) { val t = p[i]; p[i] = p[j]; p[j] = t } }
        sw(1, 2); sw(4, 5); sw(7, 8); sw(0, 1); sw(3, 4); sw(6, 7); sw(1, 2); sw(4, 5)
        sw(7, 8); sw(0, 3); sw(5, 8); sw(4, 7); sw(3, 6); sw(1, 4); sw(2, 5); sw(4, 7)
        sw(4, 2); sw(6, 4); sw(4, 2)
        return p[4]
    }

    // ---- separable Gaussian blur ----------------------------------------------

    fun gaussianBlur(src: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
        if (sigma <= 0f) return src.copyOf()
        val radius = max(1, ceil(4.0 * sigma).toInt())
        val kernel = FloatArray(2 * radius + 1)
        var sum = 0f
        for (i in kernel.indices) {
            val d = (i - radius).toFloat()
            kernel[i] = exp(-(d * d) / (2f * sigma * sigma))
            sum += kernel[i]
        }
        for (i in kernel.indices) kernel[i] /= sum

        val tmp = FloatArray(src.size)
        val dst = FloatArray(src.size)
        // horizontal
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0f
                for (k in -radius..radius) {
                    val xx = (x + k).coerceIn(0, w - 1)
                    acc += kernel[k + radius] * src[row + xx]
                }
                tmp[row + x] = acc
            }
        }
        // vertical
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0f
                for (k in -radius..radius) {
                    val yy = (y + k).coerceIn(0, h - 1)
                    acc += kernel[k + radius] * tmp[yy * w + x]
                }
                dst[y * w + x] = acc
            }
        }
        return dst
    }

    // ---- bilateral filter (edge-preserving spatial denoise) ---------------------

    fun bilateral(src: FloatArray, w: Int, h: Int, d: Int, sigmaColor: Float, sigmaSpace: Float): FloatArray {
        val radius = max(1, d / 2)
        val spatial = FloatArray((2 * radius + 1) * (2 * radius + 1))
        var idx = 0
        val ss = 2f * sigmaSpace * sigmaSpace
        for (dy in -radius..radius) for (dx in -radius..radius) {
            spatial[idx++] = exp(-(dx * dx + dy * dy) / ss)
        }
        val sc = 2f * sigmaColor * sigmaColor
        val dst = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = src[y * w + x]
                var acc = 0f
                var wsum = 0f
                idx = 0
                for (dy in -radius..radius) {
                    val yy = (y + dy).coerceIn(0, h - 1) * w
                    for (dx in -radius..radius) {
                        val xx = (x + dx).coerceIn(0, w - 1)
                        val v = src[yy + xx]
                        val dc = v - c
                        val wgt = spatial[idx++] * exp(-(dc * dc) / sc)
                        acc += wgt * v
                        wsum += wgt
                    }
                }
                dst[y * w + x] = if (wsum > 0f) acc / wsum else c
            }
        }
        return dst
    }

    // ---- bicubic resize (INTER_CUBIC-style, a = -0.75) ---------------------------

    private fun cubic(t: Float): Float {
        val a = -0.75f
        val at = abs(t)
        return when {
            at <= 1f -> (a + 2f) * at * at * at - (a + 3f) * at * at + 1f
            at < 2f -> a * at * at * at - 5f * a * at * at + 8f * a * at - 4f * a
            else -> 0f
        }
    }

    fun resizeBicubic(src: FloatArray, w: Int, h: Int, w2: Int, h2: Int): FloatArray {
        val dst = FloatArray(w2 * h2)
        val sx = w.toFloat() / w2
        val sy = h.toFloat() / h2
        val wx = FloatArray(4)
        val wy = FloatArray(4)
        for (y2 in 0 until h2) {
            val fy = (y2 + 0.5f) * sy - 0.5f
            val iy = kotlin.math.floor(fy).toInt()
            val ty = fy - iy
            for (k in 0..3) wy[k] = cubic(ty - (k - 1))
            for (x2 in 0 until w2) {
                val fx = (x2 + 0.5f) * sx - 0.5f
                val ix = kotlin.math.floor(fx).toInt()
                val tx = fx - ix
                for (k in 0..3) wx[k] = cubic(tx - (k - 1))
                var acc = 0f
                for (ky in 0..3) {
                    val yy = (iy + ky - 1).coerceIn(0, h - 1) * w
                    var rowAcc = 0f
                    for (kx in 0..3) {
                        val xx = (ix + kx - 1).coerceIn(0, w - 1)
                        rowAcc += wx[kx] * src[yy + xx]
                    }
                    acc += wy[ky] * rowAcc
                }
                dst[y2 * w2 + x2] = acc
            }
        }
        return dst
    }

    // ---- misc -------------------------------------------------------------------

    fun flipHorizontal(src: FloatArray, w: Int, h: Int): FloatArray {
        val dst = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) dst[row + x] = src[row + (w - 1 - x)]
        }
        return dst
    }

    fun mean(a: FloatArray): Float {
        var s = 0.0
        for (v in a) s += v
        return (s / a.size).toFloat()
    }

    fun std(a: FloatArray): Float {
        val m = mean(a)
        var s = 0.0
        for (v in a) s += (v - m).toDouble() * (v - m)
        return kotlin.math.sqrt(s / a.size).toFloat()
    }

    /** Percentile via sorting a copy (p in 0..100), numpy-style linear interpolation. */
    fun percentile(a: FloatArray, p: Float): Float {
        if (a.isEmpty()) return 0f
        val c = a.copyOf()
        c.sort()
        val pos = (p / 100f) * (c.size - 1)
        val i = pos.toInt().coerceIn(0, c.size - 1)
        val frac = pos - i
        return if (i + 1 < c.size) c[i] * (1 - frac) + c[i + 1] * frac else c[i]
    }
}
