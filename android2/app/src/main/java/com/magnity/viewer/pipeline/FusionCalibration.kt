package com.magnity.viewer.pipeline

import java.util.Locale
import kotlin.math.hypot

/**
 * Distance-compensated fusion registration.
 *
 * The thermal camera and the phone camera are rigidly mounted ~10 cm apart, so
 * the overlay shift from parallax has a fixed direction and a magnitude that
 * grows linearly in 1/Z; the rest of the registration (zoom, rotation) does not
 * depend on object distance. This file holds the calibration samples the user
 * saves at known distances and the least-squares fit the viewer evaluates per
 * frame. Pure Kotlin (no Android types) so it can be unit-tested off-device.
 *
 * Model (Z in metres, invZ = 1/Z, 1/∞ = 0):
 *     zoom  = constant (mean of the samples)
 *     dx(Z) = dx∞ + kx·invZ        dy(Z) = dy∞ + ky·invZ
 */
object FusionCalibration {

    /** Where the object distance comes from (persisted choice in the drawer). */
    enum class DistanceSource { MANUAL, INFINITY, AUTO }

    /** Working range 1.5 m … ∞; the manual slider spans invZ linearly over this. */
    const val MAX_INVZ = 1f / 1.5f

    /** Manual-slider stops, spaced evenly in 1/Z: 1.5 / 2 / 3 / 5 / 10 m and ∞. */
    val DISTANCE_STOPS_M = floatArrayOf(1.5f, 2f, 3f, 5f, 10f, Float.POSITIVE_INFINITY)

    /** One saved alignment: the zoom/offset the user dialled in at distance 1/invZ. */
    data class CalibSample(val invZ: Float, val zoom: Float, val dx: Float, val dy: Float) {
        /** Sample distance in metres; invZ 0 (infinity) → +∞. */
        val distanceM: Float get() = if (invZ > 0f) 1f / invZ else Float.POSITIVE_INFINITY
    }

    /**
     * Least-squares fit over the samples: zoom is the sample mean, dx/dy are
     * lines against invZ (degrading to a constant for a single distinct
     * distance). [usable] is false until the first sample exists.
     */
    class Fit(samples: List<CalibSample>) {
        val count = samples.size
        val usable = samples.isNotEmpty()
        val zoom: Float = if (usable) samples.map { it.zoom }.average().toFloat() else 0f

        private val dxLine = line(samples.map { it.invZ }, samples.map { it.dx })
        private val dyLine = line(samples.map { it.invZ }, samples.map { it.dy })
        val dx0 = dxLine.first; val kx = dxLine.second
        val dy0 = dyLine.first; val ky = dyLine.second

        fun dxAt(invZ: Float) = dx0 + kx * invZ
        fun dyAt(invZ: Float) = dy0 + ky * invZ

        /**
         * This sample's distance from the fitted offset, in native thermal
         * pixels. An offset error of d maps to d·zoom of the oriented thermal
         * grid, so dx errors scale with [thermalW] and dy errors with [thermalH]
         * (160×120, swapped when the thermal grid is rotated 90°/270°).
         */
        fun residualPx(s: CalibSample, thermalW: Int, thermalH: Int): Float =
            hypot((s.dx - dxAt(s.invZ)) * zoom * thermalW,
                  (s.dy - dyAt(s.invZ)) * zoom * thermalH)
    }

    fun fit(samples: List<CalibSample>) = Fit(samples)

    /** Least-squares y = a + b·x; a single distinct x (or one point) → constant. */
    private fun line(xs: List<Float>, ys: List<Float>): Pair<Float, Float> {
        if (xs.isEmpty()) return 0f to 0f
        val mx = xs.average().toFloat()
        val my = ys.average().toFloat()
        var sxx = 0f; var sxy = 0f
        for (i in xs.indices) {
            val d = xs[i] - mx
            sxx += d * d
            sxy += d * (ys[i] - my)
        }
        if (sxx < 1e-12f) return my to 0f
        val k = sxy / sxx
        return (my - k * mx) to k
    }

    // ---- persistence: one small JSON string in SharedPreferences ----------------

    fun encodeSamples(samples: List<CalibSample>): String = buildString {
        append('[')
        samples.forEachIndexed { i, s ->
            if (i > 0) append(',')
            append("{\"invZ\":").append(num(s.invZ))
                .append(",\"zoom\":").append(num(s.zoom))
                .append(",\"dx\":").append(num(s.dx))
                .append(",\"dy\":").append(num(s.dy))
                .append('}')
        }
        append(']')
    }

    /** Tolerant inverse of [encodeSamples]; malformed entries are skipped. */
    fun decodeSamples(json: String): List<CalibSample> {
        if (json.isBlank()) return emptyList()
        val out = ArrayList<CalibSample>()
        for (m in Regex("\\{([^}]*)\\}").findAll(json)) {
            var invZ: Float? = null; var zoom: Float? = null
            var dx: Float? = null; var dy: Float? = null
            for (pair in m.groupValues[1].split(',')) {
                val kv = pair.split(':', limit = 2)
                if (kv.size != 2) continue
                val v = kv[1].trim().toFloatOrNull() ?: continue
                when (kv[0].trim().trim('"')) {
                    "invZ" -> invZ = v
                    "zoom" -> zoom = v
                    "dx" -> dx = v
                    "dy" -> dy = v
                }
            }
            val iz = invZ
            if (iz != null && iz >= 0f && zoom != null && dx != null && dy != null) {
                out.add(CalibSample(iz, zoom, dx, dy))
            }
        }
        return out
    }

    /** Compact number — enough digits to round-trip, none of Float's noise. */
    private fun num(v: Float): String =
        if (v == 0f) "0" else String.format(Locale.ROOT, "%.6g", v)
}
