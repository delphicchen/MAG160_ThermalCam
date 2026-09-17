package com.magnity.viewer.pipeline

/**
 * Thermal + visible (phone camera) sensor fusion.
 *
 * Two modes, applied to the palette-mapped display pixels:
 *  - EDGES ("MSX-style"): a soft-thresholded Sobel edge map of the RGB luma is overlaid
 *    in white on the thermal image, restoring scene structure/contours that the 160x120
 *    thermal sensor can't resolve — without disturbing the colour mapping.
 *  - BLEND: the thermal colour is mixed with the grayscale visible image
 *    (strength = thermal weight), useful for locating a hot spot on real objects.
 *
 * Registration between the two sensors is a fixed similarity transform (the USB thermal
 * camera is rigidly attached to the phone): user-set zoom, x/y offset and a constant
 * rotation (0/90/180/270), applied when sampling the luma frame per display pixel.
 * The visible camera's FOV is wider than the thermal lens, so zoom > 1 crops in.
 */
object Fusion {

    enum class Mode { EDGES, BLEND, SEARCH }

    /**
     * Result of [composeWide]: the full-FOV visible frame with the thermal inset, plus
     * the thermal footprint rect in normalized [0,1] coordinates of that frame (for
     * marker/tap remapping in the UI).
     */
    class WideResult(
        val pixels: IntArray, val width: Int, val height: Int,
        val rectL: Float, val rectT: Float, val rectW: Float, val rectH: Float,
    )

    // soft threshold for the |gx|+|gy| Sobel magnitude (in 0..255 luma units)
    private const val EDGE_T0 = 48f
    private const val EDGE_T1 = 280f

    /**
     * Soft edge map (0..1) from a luma frame via Sobel |gx|+|gy| with a soft threshold.
     * Border pixels are left 0.
     */
    fun edgeMap(luma: ByteArray, w: Int, h: Int, out: FloatArray = FloatArray(w * h)): FloatArray {
        java.util.Arrays.fill(out, 0f)
        for (y in 1 until h - 1) {
            val rm = (y - 1) * w
            val r0 = y * w
            val rp = (y + 1) * w
            for (x in 1 until w - 1) {
                val a = luma[rm + x - 1].toInt() and 0xFF; val b = luma[rm + x].toInt() and 0xFF
                val c = luma[rm + x + 1].toInt() and 0xFF
                val d = luma[r0 + x - 1].toInt() and 0xFF
                val f = luma[r0 + x + 1].toInt() and 0xFF
                val g = luma[rp + x - 1].toInt() and 0xFF; val hh = luma[rp + x].toInt() and 0xFF
                val i = luma[rp + x + 1].toInt() and 0xFF
                val gx = (c + 2 * f + i) - (a + 2 * d + g)
                val gy = (g + 2 * hh + i) - (a + 2 * b + c)
                val mag = (if (gx < 0) -gx else gx) + (if (gy < 0) -gy else gy)
                var e = (mag - EDGE_T0) / (EDGE_T1 - EDGE_T0)
                if (e < 0f) e = 0f else if (e > 1f) e = 1f
                out[r0 + x] = e
            }
        }
        return out
    }

    /**
     * Compose the fusion overlay into [pixels] (ARGB, dispW x dispH) in place.
     *
     * @param luma      sensor-orientation luma frame (lw x lh)
     * @param edge      edge map from [edgeMap] (required for EDGES mode, same dims as luma)
     * @param strength  EDGES: overlay opacity; BLEND: thermal weight (0..1)
     * @param zoom      registration scale (>1 crops into the wider visible FOV)
     * @param dx, dy    registration offset, in fractions of the upright visible frame
     * @param rotation  fixed thermal-to-visible mounting rotation: 0 / 90 / 180 / 270
     */
    fun compose(
        pixels: IntArray, dispW: Int, dispH: Int,
        luma: ByteArray, lw: Int, lh: Int,
        edge: FloatArray?,
        mode: Mode, strength: Float,
        zoom: Float, dx: Float, dy: Float, rotation: Int,
    ) {
        val uw = if (rotation == 90 || rotation == 270) lh else lw
        val uh = if (rotation == 90 || rotation == 270) lw else lh
        val invZoom = 1f / zoom.coerceAtLeast(0.05f)
        val s = strength.coerceIn(0f, 1f)

        for (y in 0 until dispH) {
            val ny = ((y + 0.5f) / dispH - 0.5f) * invZoom + 0.5f + dy
            val uy = (ny * uh).toInt()
            if (uy < 0 || uy >= uh) continue
            val rowBase = y * dispW
            for (x in 0 until dispW) {
                val nx = ((x + 0.5f) / dispW - 0.5f) * invZoom + 0.5f + dx
                val ux = (nx * uw).toInt()
                if (ux < 0 || ux >= uw) continue

                // upright (ux,uy) -> raw sensor index for the fixed mounting rotation
                val idx = when (rotation) {
                    90 -> (lh - 1 - ux) * lw + uy
                    180 -> (lh - 1 - uy) * lw + (lw - 1 - ux)
                    270 -> ux * lw + (lw - 1 - uy)
                    else -> uy * lw + ux
                }

                val i = rowBase + x
                val c = pixels[i]
                var r = (c shr 16) and 0xFF
                var g = (c shr 8) and 0xFF
                var b = c and 0xFF
                if (mode == Mode.EDGES) {
                    val e = (edge ?: return) [idx] * s
                    if (e > 0.004f) {
                        r += (e * (255 - r)).toInt()
                        g += (e * (255 - g)).toInt()
                        b += (e * (255 - b)).toInt()
                    }
                } else {
                    val vis = luma[idx].toInt() and 0xFF
                    r = ((1f - s) * vis + s * r).toInt()
                    g = ((1f - s) * vis + s * g).toInt()
                    b = ((1f - s) * vis + s * b).toInt()
                }
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /**
     * Wide-search composition: the OPPOSITE projection of [compose]. The full visible
     * frame (every pixel the phone camera sees, upright per the mounting rotation) is
     * the base layer in grayscale, and the thermal image is warped down into its true
     * footprint inside that wider FOV — using the SAME registration (zoom/dx/dy/
     * rotation) calibrated in overlay mode, inverted. Better for locating a target:
     * scan with the wide visible context, then switch to overlay to inspect.
     *
     * @param thermal   palette-mapped thermal display pixels (tw x th)
     * @param strength  thermal opacity inside the footprint (0..1)
     * @return the composed frame + the footprint rect (normalized) for marker mapping
     */
    fun composeWide(
        thermal: IntArray, tw: Int, th: Int,
        luma: ByteArray, lw: Int, lh: Int,
        strength: Float, zoom: Float, dx: Float, dy: Float, rotation: Int,
    ): WideResult {
        val uw = if (rotation == 90 || rotation == 270) lh else lw
        val uh = if (rotation == 90 || rotation == 270) lw else lh
        val z = zoom.coerceAtLeast(0.05f)
        val s = strength.coerceIn(0f, 1f)

        // thermal footprint in normalized upright-visible coords: the overlay mode maps
        // display-normalized n -> visible-normalized (n-0.5)/zoom + 0.5 + d, so the
        // thermal [0,1] square lands at centre (0.5+dx, 0.5+dy), half-size 0.5/zoom.
        val rectL = 0.5f + dx - 0.5f / z
        val rectT = 0.5f + dy - 0.5f / z
        val rectW = 1f / z
        val rectH = 1f / z
        // ceil the far edges: truncation would clip up to one row/col of valid thermal
        // coverage; the per-pixel txi/tyi bounds check below is the real clip.
        val x0 = (rectL * uw).toInt()
        val y0 = (rectT * uh).toInt()
        val x1 = kotlin.math.ceil((rectL + rectW) * uw.toDouble()).toInt()
        val y1 = kotlin.math.ceil((rectT + rectH) * uh.toDouble()).toInt()

        val out = IntArray(uw * uh)

        // 1) grayscale visible base — every pixel the phone camera captured
        for (y in 0 until uh) {
            val row = y * uw
            for (x in 0 until uw) {
                val idx = when (rotation) {
                    90 -> (lh - 1 - x) * lw + y
                    180 -> (lh - 1 - y) * lw + (lw - 1 - x)
                    270 -> x * lw + (lw - 1 - y)
                    else -> y * lw + x
                }
                val v = luma[idx].toInt() and 0xFF
                out[row + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }

        // 2) thermal inset, inverse-mapped into its footprint
        val ys = y0.coerceAtLeast(0)
        val ye = y1.coerceAtMost(uh)
        val xs = x0.coerceAtLeast(0)
        val xe = x1.coerceAtMost(uw)
        for (y in ys until ye) {
            val ty = (((y + 0.5f) / uh - 0.5f - dy) * z + 0.5f) * th
            val tyi = ty.toInt()
            if (tyi < 0 || tyi >= th) continue
            val row = y * uw
            val trow = tyi * tw
            for (x in xs until xe) {
                val tx = (((x + 0.5f) / uw - 0.5f - dx) * z + 0.5f) * tw
                val txi = tx.toInt()
                if (txi < 0 || txi >= tw) continue
                val c = thermal[trow + txi]
                val i = row + x
                val g = out[i] and 0xFF
                val r2 = ((1f - s) * g + s * ((c shr 16) and 0xFF)).toInt()
                val g2 = ((1f - s) * g + s * ((c shr 8) and 0xFF)).toInt()
                val b2 = ((1f - s) * g + s * (c and 0xFF)).toInt()
                out[i] = (0xFF shl 24) or (r2 shl 16) or (g2 shl 8) or b2
            }
        }

        // 3) thin outline so the inset is findable at a glance
        val frame = 0xFFE0E0E0.toInt()
        if (ys < ye && xs < xe) {
            for (x in xs until xe) {
                if (y0 in 0 until uh) out[y0 * uw + x] = frame
                if (y1 - 1 in 0 until uh) out[(y1 - 1) * uw + x] = frame
            }
            for (y in ys until ye) {
                if (x0 in 0 until uw) out[y * uw + x0] = frame
                if (x1 - 1 in 0 until uw) out[y * uw + x1 - 1] = frame
            }
        }
        return WideResult(out, uw, uh, rectL, rectT, rectW, rectH)
    }
}
