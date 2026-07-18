package com.magnity.thermalcam.pipeline

import com.magnity.thermalcam.data.NpyArray
import java.io.InputStream
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Temperature conversion — Kotlin port of `radiometry.py`, a faithful reimplementation
 * of the camera's built-in radiometric chain (reverse-engineered from libcoresdk.so):
 *
 *     v      = raw*(k+1) + b                       (folded here into rad = a*raw + b)
 *     target = v << (7 - shift)
 *     idx    = binary_search(radLUT, target)       radLUT = 646-entry Planck radiance LUT
 *     U      = idx*4096 - 150000 + (slopeLUT[idx]*(target-radLUT[idx])) >> 12
 *     °C     = U/1000 - 273.15                     (U is milli-Kelvin)
 *
 * The 8 built-in Planck curves come from `planck_luts.npy` (extracted from the APK's
 * .so); the per-camera linear term (a, b) is recovered from >=2 (raw, known °C)
 * reference points via [calibrate], exactly like the Linux app.
 */
class Radiometry(planckLutsNpy: InputStream) {

    companion object {
        const val U_OFFSET = -150000L
        const val U_STEP = 4096L
        const val KELVIN = 273.15
        const val U_PER_KELVIN = 1000.0

        fun celsiusToU(tc: Double): Double = (tc + KELVIN) * U_PER_KELVIN
        fun uToCelsius(u: Double): Double = u / U_PER_KELVIN - KELVIN
    }

    val nLut: Int
    val nIdx: Int
    private val luts: Array<LongArray>      // (8, 646) radiance curves
    private val slopes: Array<LongArray>    // firmware-exact: 2^24 / (L[i+1]-L[i])

    var a: Double = 1.0; private set
    var b: Double = 0.0; private set
    var lutIdx: Int = 0; private set
    var calibrated: Boolean = false; private set

    init {
        val arr = NpyArray.read(planckLutsNpy)
        nLut = arr.shape[0]
        nIdx = arr.shape[1]
        val flat = arr.toLongArray()
        luts = Array(nLut) { li -> LongArray(nIdx) { i -> flat[li * nIdx + i] } }
        slopes = Array(nLut) { li ->
            LongArray(nIdx - 1) { i ->
                val d = (luts[li][i + 1] - luts[li][i]).coerceAtLeast(1)
                (1L shl 24) / d
            }
        }
        lutIdx = nLut / 2
    }

    /** Restore a previously saved calibration. */
    fun restore(a: Double, b: Double, lutIdx: Int, calibrated: Boolean) {
        this.a = a; this.b = b
        this.lutIdx = lutIdx.coerceIn(0, nLut - 1)
        this.calibrated = calibrated
    }

    fun clear() { calibrated = false }

    // radiance at a given U (temperature-grid value), forward float interpolation
    private fun radianceAtU(lut: LongArray, u: Double): Double {
        var pos = (u - U_OFFSET) / U_STEP
        if (pos < 0) pos = 0.0
        if (pos > nIdx - 1.0001) pos = nIdx - 1.0001
        val i = pos.toInt()
        val frac = pos - i
        return lut[i] * (1 - frac) + lut[i + 1] * frac
    }

    /** Inverse radiance -> U, bit-faithful to the firmware's sub_3f970 simple path. */
    private fun uFromRadiance(li: Int, rad: Double): Double {
        val lut = luts[li]; val slope = slopes[li]
        var ti = floor(rad).toLong()
        if (ti < lut[0]) ti = lut[0]
        if (ti > lut[nIdx - 1]) ti = lut[nIdx - 1]
        // searchsorted(side='right') - 1, clamped to [0, nIdx-2]
        var lo = 0; var hi = nIdx        // first index with lut[idx] > ti
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lut[mid] <= ti) lo = mid + 1 else hi = mid
        }
        val idx = (lo - 1).coerceIn(0, nIdx - 2)
        val delta = ti - lut[idx]
        val u = idx * U_STEP + U_OFFSET + ((slope[idx] * delta) shr 12)
        return u.toDouble()
    }

    /** raw counts -> °C via the calibrated linear term + the camera Planck LUT. */
    fun rawToCelsius(raw: Double): Double = uToCelsius(uFromRadiance(lutIdx, a * raw + b))

    /**
     * points: list of (raw, known °C). Tries every built-in LUT, least-squares fits
     * radiance = a*raw + b, keeps the LUT with the smallest °C residual.
     * Returns rms °C error.
     */
    fun calibrate(points: List<Pair<Double, Double>>): Double {
        require(points.size >= 2) { "need >= 2 reference points" }
        val raws = DoubleArray(points.size) { points[it].first }
        val temps = DoubleArray(points.size) { points[it].second }

        var bestRms = Double.MAX_VALUE
        var bestLi = 0; var bestA = 1.0; var bestB = 0.0
        for (li in 0 until nLut) {
            val lut = luts[li]
            val target = DoubleArray(raws.size) { radianceAtU(lut, celsiusToU(temps[it])) }
            // least-squares fit target = a*raw + b  (2x2 normal equations)
            val n = raws.size.toDouble()
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            for (i in raws.indices) {
                sx += raws[i]; sy += target[i]
                sxx += raws[i] * raws[i]; sxy += raws[i] * target[i]
            }
            val det = n * sxx - sx * sx
            if (det == 0.0) continue
            val fa = (n * sxy - sx * sy) / det
            val fb = (sy * sxx - sx * sxy) / det
            // residual measured back in °C
            var sse = 0.0
            for (i in raws.indices) {
                val pred = uToCelsius(uFromRadiance(li, fa * raws[i] + fb))
                sse += (pred - temps[i]) * (pred - temps[i])
            }
            val rms = sqrt(sse / raws.size)
            if (rms < bestRms) { bestRms = rms; bestLi = li; bestA = fa; bestB = fb }
        }
        a = bestA; b = bestB; lutIdx = bestLi; calibrated = true
        return bestRms
    }
}
