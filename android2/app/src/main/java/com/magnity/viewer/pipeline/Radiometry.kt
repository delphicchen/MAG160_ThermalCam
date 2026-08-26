package com.magnity.viewer.pipeline

import com.magnity.viewer.data.NpyArray
import java.io.InputStream
import kotlin.math.floor

/**
 * Temperature conversion — factory-grade absolute radiometry, no user calibration.
 *
 * Chain (reverse-engineered, see docs/algorithm_spec.html):
 *     rad = A*out + B                       (NUC output -> radiance domain)
 *     U   = LUT_inverse(rad)                (646-entry Planck LUT, milli-Kelvin)
 *     °C  = U/1000 - 273.15
 *
 * Constants were derived by reverse engineering:
 *   - LUT selection: running the real libcoresdk.so build chain under Unicorn with the
 *     camera's own mag_cali.bin fills .bss radLUT @0x29d700 with planck_luts row 6
 *     (100% bit-identical). Not a fit.
 *   - A, B: one-time two-point calibration against this camera unit
 *     (aluminium pad = ambient 26.0 °C @ out=8378; skin 34.5 °C @ out=8610).
 */
class Radiometry(planckLutsNpy: InputStream) {

    companion object {
        const val U_OFFSET = -150000L
        const val U_STEP = 4096L
        const val KELVIN = 273.15
        const val U_PER_KELVIN = 1000.0

        /** Firmware-selected Planck curve (see class doc). */
        const val FACTORY_LUT_IDX = 6
        const val FACTORY_A = 42.7562
        const val FACTORY_B = -119255.6

        fun uToCelsius(u: Double): Double = u / U_PER_KELVIN - KELVIN
    }

    private val lut: LongArray
    private val slope: LongArray
    private val nIdx: Int

    var a: Double = FACTORY_A; private set
    var b: Double = FACTORY_B; private set

    init {
        val arr = NpyArray.read(planckLutsNpy)
        nIdx = arr.shape[1]
        val flat = arr.toLongArray()
        val li = FACTORY_LUT_IDX.coerceIn(0, arr.shape[0] - 1)
        lut = LongArray(nIdx) { i -> flat[li * nIdx + i] }
        slope = LongArray(nIdx - 1) { i ->
            val d = (lut[i + 1] - lut[i]).coerceAtLeast(1)
            (1L shl 24) / d
        }
    }

    /** Inverse radiance -> U, bit-faithful to the firmware's sub_3f970 simple path. */
    private fun uFromRadiance(rad: Double): Double {
        var ti = floor(rad).toLong()
        if (ti < lut[0]) ti = lut[0]
        if (ti > lut[nIdx - 1]) ti = lut[nIdx - 1]
        var lo = 0; var hi = nIdx
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lut[mid] <= ti) lo = mid + 1 else hi = mid
        }
        val idx = (lo - 1).coerceIn(0, nIdx - 2)
        val delta = ti - lut[idx]
        return (idx * U_STEP + U_OFFSET + ((slope[idx] * delta) shr 12)).toDouble()
    }

    /** NUC output (+trim) counts -> °C. */
    fun outToCelsius(v: Double): Double = uToCelsius(uFromRadiance(a * v + b))

    /**
     * Optional fine trim of the two constants against a known reference.
     * Keeps lut_idx fixed; re-solves (A,B) so the reference lands exactly.
     */
    fun refine(referenceOut: Double, referenceCelsius: Double) {
        // radiance required at that output value for the given temperature
        val targetU = (referenceCelsius + KELVIN) * U_PER_KELVIN
        val pos = ((targetU - U_OFFSET).toDouble() / U_STEP).coerceIn(0.0, nIdx - 1.0001)
        val i = pos.toInt(); val frac = pos - i
        val targetRad = lut[i] * (1 - frac) + lut[i + 1] * frac
        // keep slope a, shift b so the point matches
        b += targetRad - (a * referenceOut + b)
    }
}
