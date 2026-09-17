package com.magnity.viewer.pipeline

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

    /**
     * Edge-preserving bilateral filter over a (2r+1)² window, r = d/2.
     *
     * It runs on every frame, so the inner loop is kept cheap: the range weight
     * exp(-dc²/2σc²) comes from [RANGE_LUT] instead of one exp() per tap, weights below
     * exp(-[RANGE_Q_MAX]) are skipped, and interior pixels read through precomputed index
     * offsets — only the r-wide border takes the clamped path. Output matches the direct
     * form to within the table step.
     */
    fun bilateral(src: FloatArray, w: Int, h: Int, d: Int, sigmaColor: Float, sigmaSpace: Float): FloatArray {
        val radius = max(1, d / 2)
        val n = (2 * radius + 1) * (2 * radius + 1)
        val spatial = FloatArray(n)
        val offset = IntArray(n)
        val ss = 2f * sigmaSpace * sigmaSpace
        var k = 0
        for (dy in -radius..radius) for (dx in -radius..radius) {
            spatial[k] = exp(-(dx * dx + dy * dy) / ss)
            offset[k++] = dy * w + dx
        }
        // dc² → table index; a huge dc saturates toInt() past the end and is skipped
        val qToIndex = RANGE_LUT_N / (RANGE_Q_MAX * 2f * sigmaColor * sigmaColor)
        val lut = RANGE_LUT
        val dst = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            val innerRow = y >= radius && y < h - radius
            for (x in 0 until w) {
                val i = row + x
                val c = src[i]
                var acc = 0f
                var wsum = 0f
                if (innerRow && x >= radius && x < w - radius) {
                    for (t in 0 until n) {
                        val v = src[i + offset[t]]
                        val dc = v - c
                        val qi = (dc * dc * qToIndex).toInt()
                        if (qi < RANGE_LUT_N) {
                            val wgt = spatial[t] * lut[qi]
                            acc += wgt * v
                            wsum += wgt
                        }
                    }
                } else {
                    var t = 0
                    for (dy in -radius..radius) {
                        val yy = (y + dy).coerceIn(0, h - 1) * w
                        for (dx in -radius..radius) {
                            val v = src[yy + (x + dx).coerceIn(0, w - 1)]
                            val dc = v - c
                            val qi = (dc * dc * qToIndex).toInt()
                            if (qi < RANGE_LUT_N) {
                                val wgt = spatial[t] * lut[qi]
                                acc += wgt * v
                                wsum += wgt
                            }
                            t++
                        }
                    }
                }
                dst[i] = if (wsum > 0f) acc / wsum else c
            }
        }
        return dst
    }

    private const val RANGE_LUT_N = 2048
    private const val RANGE_Q_MAX = 10f         // exp(-10) ≈ 4.5e-5: negligible weight
    /** exp(-q) sampled at bin centres over q ∈ [0, RANGE_Q_MAX). */
    private val RANGE_LUT = FloatArray(RANGE_LUT_N) { exp(-(it + 0.5f) * RANGE_Q_MAX / RANGE_LUT_N) }

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
        // column taps are identical for every output row — precompute them once
        val sx = w.toFloat() / w2
        val xIdx = IntArray(w2 * 4)
        val xW = FloatArray(w2 * 4)
        for (x2 in 0 until w2) {
            val fx = (x2 + 0.5f) * sx - 0.5f
            val ix = kotlin.math.floor(fx).toInt()
            val tx = fx - ix
            for (k in 0..3) {
                xIdx[x2 * 4 + k] = (ix + k - 1).coerceIn(0, w - 1)
                xW[x2 * 4 + k] = cubic(tx - (k - 1))
            }
        }
        val sy = h.toFloat() / h2
        // rows are independent → spread over cores (640×480 live upscale)
        java.util.stream.IntStream.range(0, h2).parallel().forEach { y2 ->
            val fy = (y2 + 0.5f) * sy - 0.5f
            val iy = kotlin.math.floor(fy).toInt()
            val ty = fy - iy
            val r0 = (iy - 1).coerceIn(0, h - 1) * w
            val r1 = iy.coerceIn(0, h - 1) * w
            val r2 = (iy + 1).coerceIn(0, h - 1) * w
            val r3 = (iy + 2).coerceIn(0, h - 1) * w
            val wy0 = cubic(ty + 1f); val wy1 = cubic(ty)
            val wy2 = cubic(ty - 1f); val wy3 = cubic(ty - 2f)
            val row = y2 * w2
            for (x2 in 0 until w2) {
                val b = x2 * 4
                var acc = 0f
                for (k in 0..3) {
                    val xi = xIdx[b + k]
                    acc += xW[b + k] * (wy0 * src[r0 + xi] + wy1 * src[r1 + xi] +
                                        wy2 * src[r2 + xi] + wy3 * src[r3 + xi])
                }
                dst[row + x2] = acc
            }
        }
        return dst
    }

    // ---- box filter (O(N) moving average, border-aware) ---------------------------

    fun boxFilter(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val dst = FloatArray(src.size)
        // horizontal pass
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            var count = 0
            for (x in 0 until minOf(radius + 1, w)) { sum += src[row + x]; count++ }
            tmp[row] = sum / count
            for (x in 1 until w) {
                val add = x + radius
                if (add < w) { sum += src[row + add]; count++ }
                val rem = x - radius - 1
                if (rem >= 0) { sum -= src[row + rem]; count-- }
                tmp[row + x] = sum / count
            }
        }
        // vertical pass
        val colSum = FloatArray(w)
        val colCnt = IntArray(w)
        for (x in 0 until w) {
            var sum = 0f; var count = 0
            for (y in 0 until minOf(radius + 1, h)) { sum += tmp[y * w + x]; count++ }
            colSum[x] = sum; colCnt[x] = count
            dst[x] = sum / count
        }
        for (y in 1 until h) {
            val row = y * w
            for (x in 0 until w) {
                val add = y + radius
                if (add < h) { colSum[x] += tmp[add * w + x]; colCnt[x]++ }
                val rem = y - radius - 1
                if (rem >= 0) { colSum[x] -= tmp[rem * w + x]; colCnt[x]-- }
                dst[row + x] = colSum[x] / colCnt[x]
            }
        }
        return dst
    }

    // ---- guided-filter detail boost (halo-free local contrast) ---------------------

    /**
     * Edge-preserving base/detail decomposition via a self-guided filter, then detail
     * amplification: out = base + (1 + boost) * (src - base). Unlike unsharp masking
     * the base layer follows strong edges, so boosting the detail layer sharpens
     * texture without ringing/halos. `eps` is relative to the value range of `src`.
     */
    fun guidedDetailBoost(
        src: FloatArray, w: Int, h: Int,
        radius: Int = 8, epsRel: Float = 0.02f, boost: Float = 0.6f,
    ): FloatArray {
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (v in src) { if (v < lo) lo = v; if (v > hi) hi = v }
        val range = (hi - lo).coerceAtLeast(1e-6f)
        val eps = (epsRel * range) * (epsRel * range)

        val meanI = boxFilter(src, w, h, radius)
        val sq = FloatArray(src.size) { src[it] * src[it] }
        val corrI = boxFilter(sq, w, h, radius)
        val a = FloatArray(src.size)
        val b = FloatArray(src.size)
        for (i in src.indices) {
            val varI = (corrI[i] - meanI[i] * meanI[i]).coerceAtLeast(0f)
            a[i] = varI / (varI + eps)
            b[i] = meanI[i] * (1f - a[i])
        }
        val meanA = boxFilter(a, w, h, radius)
        val meanB = boxFilter(b, w, h, radius)
        val out = FloatArray(src.size)
        for (i in src.indices) {
            val base = meanA[i] * src[i] + meanB[i]        // edge-aware base layer
            out[i] = base + (1f + boost) * (src[i] - base)
        }
        return out
    }

    // ---- CLAHE (contrast-limited adaptive histogram equalization) -------------------

    /**
     * CLAHE on a float image — the standard thermal-imaging AGC. The image is divided
     * into tiles; each tile gets a clip-limited equalization mapping, and each pixel is
     * bilinearly interpolated between the four neighbouring tile mappings (no tile
     * seams). Returns values in [0, 1]. `clipLimit` is the multiple of the uniform
     * histogram level above which bins are clipped (2..6 typical; higher = stronger).
     */
    fun clahe(
        src: FloatArray, w: Int, h: Int,
        tilesX: Int = 8, tilesY: Int = 6, clipLimit: Float = 3f, bins: Int = 256,
    ): FloatArray {
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (v in src) { if (v < lo) lo = v; if (v > hi) hi = v }
        val range = hi - lo
        if (range < 1e-9f) return FloatArray(src.size) { 0.5f }
        val binScale = (bins - 1) / range

        val tw = (w + tilesX - 1) / tilesX
        val th = (h + tilesY - 1) / tilesY

        // per-tile clip-limited CDF mappings (bin -> [0,1])
        val maps = Array(tilesX * tilesY) { FloatArray(bins) }
        val hist = IntArray(bins)
        for (ty in 0 until tilesY) {
            val y0 = ty * th; val y1 = minOf(y0 + th, h)
            for (tx in 0 until tilesX) {
                val x0 = tx * tw; val x1 = minOf(x0 + tw, w)
                java.util.Arrays.fill(hist, 0)
                var n = 0
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in x0 until x1) {
                        hist[((src[row + x] - lo) * binScale).toInt()]++
                        n++
                    }
                }
                if (n == 0) continue
                // clip histogram, redistribute the excess uniformly
                val limit = maxOf(1, (clipLimit * n / bins).toInt())
                var excess = 0
                for (i2 in 0 until bins) {
                    if (hist[i2] > limit) { excess += hist[i2] - limit; hist[i2] = limit }
                }
                val add = excess / bins
                var rem = excess - add * bins
                for (i2 in 0 until bins) {
                    hist[i2] += add
                    if (rem > 0) { hist[i2]++; rem-- }
                }
                // CDF -> mapping
                val map = maps[ty * tilesX + tx]
                var cum = 0
                for (i2 in 0 until bins) {
                    cum += hist[i2]
                    map[i2] = cum.toFloat() / n
                }
            }
        }

        // bilinear interpolation between the 4 neighbouring tile mappings
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            val fy = (y - th * 0.5f) / th
            var ty0 = kotlin.math.floor(fy).toInt()
            val wy = fy - ty0
            var ty1 = ty0 + 1
            ty0 = ty0.coerceIn(0, tilesY - 1); ty1 = ty1.coerceIn(0, tilesY - 1)
            val row = y * w
            for (x in 0 until w) {
                val fx = (x - tw * 0.5f) / tw
                var tx0 = kotlin.math.floor(fx).toInt()
                val wx = fx - tx0
                var tx1 = tx0 + 1
                tx0 = tx0.coerceIn(0, tilesX - 1); tx1 = tx1.coerceIn(0, tilesX - 1)
                val bin = ((src[row + x] - lo) * binScale).toInt()
                val m00 = maps[ty0 * tilesX + tx0][bin]
                val m01 = maps[ty0 * tilesX + tx1][bin]
                val m10 = maps[ty1 * tilesX + tx0][bin]
                val m11 = maps[ty1 * tilesX + tx1][bin]
                out[row + x] = (m00 * (1 - wx) + m01 * wx) * (1 - wy) +
                    (m10 * (1 - wx) + m11 * wx) * wy
            }
        }
        return out
    }

    // ---- bilinear resize (half-pixel centres — matches torch align_corners=False
    // and ONNX Resize coordinate_transformation_mode="half_pixel") -------------------

    fun resizeBilinear(src: FloatArray, w: Int, h: Int, w2: Int, h2: Int): FloatArray {
        require(w >= 2 && h >= 2) { "resizeBilinear needs at least 2x2 input" }
        val dst = FloatArray(w2 * h2)
        val sx = w.toFloat() / w2
        val sy = h.toFloat() / h2
        for (y2 in 0 until h2) {
            var fy = (y2 + 0.5f) * sy - 0.5f
            if (fy < 0f) fy = 0f
            var iy = fy.toInt()
            if (iy > h - 2) iy = h - 2
            val ty = (fy - iy).coerceIn(0f, 1f)
            val r0 = iy * w
            val r1 = (iy + 1) * w
            for (x2 in 0 until w2) {
                var fx = (x2 + 0.5f) * sx - 0.5f
                if (fx < 0f) fx = 0f
                var ix = fx.toInt()
                if (ix > w - 2) ix = w - 2
                val tx = (fx - ix).coerceIn(0f, 1f)
                val a = src[r0 + ix] * (1 - tx) + src[r0 + ix + 1] * tx
                val b = src[r1 + ix] * (1 - tx) + src[r1 + ix + 1] * tx
                dst[y2 * w2 + x2] = a * (1 - ty) + b * ty
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

    /**
     * Rotate clockwise by [deg] (0/90/180/270). For 90/270 the output dimensions are
     * swapped (h×w) — the caller must track the new geometry. Used to correct the fixed
     * mounting rotation between the USB thermal module and the phone display.
     */
    fun rotate(src: FloatArray, w: Int, h: Int, deg: Int): FloatArray {
        return when (((deg % 360) + 360) % 360) {
            90 -> FloatArray(w * h).also { d ->                 // out is h×w, stride = h
                for (y in 0 until h) for (x in 0 until w) d[x * h + (h - 1 - y)] = src[y * w + x]
            }
            180 -> FloatArray(w * h).also { d ->
                for (y in 0 until h) for (x in 0 until w) d[(h - 1 - y) * w + (w - 1 - x)] = src[y * w + x]
            }
            270 -> FloatArray(w * h).also { d ->                // out is h×w, stride = h
                for (y in 0 until h) for (x in 0 until w) d[(w - 1 - x) * h + y] = src[y * w + x]
            }
            else -> src.copyOf()
        }
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
