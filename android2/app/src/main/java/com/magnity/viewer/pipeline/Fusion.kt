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
     * Soft Sobel edges (|gx|+|gy|, soft-thresholded to 0..1) of [luma] inside [roi]
     * (x0, y0, x1, y1 in sensor pixels, ends exclusive), folded straight into the temporal
     * average: sm = keep·sm + (1−keep)·edge. Only the patch [compose] samples is worth
     * computing — at zoom 1.6 that is under 40 % of the frame. A pixel outside [prev] (the
     * rect the previous call covered) has no history in [sm], so it starts from this
     * frame's edge instead of a stale value. The frame border, which has no full 3×3
     * neighbourhood, is 0. Rows run in parallel; [sm] outside [roi] is left untouched.
     */
    fun smoothEdges(luma: ByteArray, w: Int, h: Int, roi: IntArray, prev: IntArray?,
                    sm: FloatArray, keep: Float) {
        val px0 = prev?.get(0) ?: 0; val py0 = prev?.get(1) ?: 0
        val px1 = prev?.get(2) ?: 0; val py1 = prev?.get(3) ?: 0
        val x0 = roi[0]; val x1 = roi[2]
        java.util.stream.IntStream.range(roi[1], roi[3]).parallel().forEach { y ->
            val rm = (y - 1) * w
            val r0 = y * w
            val rp = (y + 1) * w
            val inner = y > 0 && y < h - 1
            val histRow = y >= py0 && y < py1
            for (x in x0 until x1) {
                var e = 0f
                if (inner && x > 0 && x < w - 1) {
                    val a = luma[rm + x - 1].toInt() and 0xFF; val b = luma[rm + x].toInt() and 0xFF
                    val c = luma[rm + x + 1].toInt() and 0xFF
                    val d = luma[r0 + x - 1].toInt() and 0xFF
                    val f = luma[r0 + x + 1].toInt() and 0xFF
                    val g = luma[rp + x - 1].toInt() and 0xFF; val hh = luma[rp + x].toInt() and 0xFF
                    val i = luma[rp + x + 1].toInt() and 0xFF
                    val gx = (c + 2 * f + i) - (a + 2 * d + g)
                    val gy = (g + 2 * hh + i) - (a + 2 * b + c)
                    val mag = (if (gx < 0) -gx else gx) + (if (gy < 0) -gy else gy)
                    e = (mag - EDGE_T0) / (EDGE_T1 - EDGE_T0)
                    if (e < 0f) e = 0f else if (e > 1f) e = 1f
                }
                val i = r0 + x
                sm[i] = if (histRow && x >= px0 && x < px1) keep * sm[i] + (1f - keep) * e else e
            }
        }
    }

    // ---- sampling tables ----------------------------------------------------------
    //
    // compose() maps display → upright visible independently per axis, and for every
    // mounting rotation the raw sensor index splits into a row part plus a column part.
    // So one pass over the display width and one over its height build everything the
    // per-pixel loop needs: idx = rowOff[y] + colOff[x] — two table reads per pixel
    // instead of float maths, a rotation branch and bounds checks.

    /** Upright visible index each display position samples along one axis, −1 where it
     *  falls outside the frame. Same arithmetic (and truncation) as the per-pixel form. */
    private fun axisTable(n: Int, invZoom: Float, d: Float, un: Int): IntArray =
        IntArray(n) { i ->
            val u = ((((i + 0.5f) / n - 0.5f) * invZoom + 0.5f + d) * un).toInt()
            if (u < 0 || u >= un) -1 else u
        }

    /** Row part of the raw sensor index of upright (ux, uy); see [colPart]. */
    private fun rowPart(uy: Int, rotation: Int, lw: Int, lh: Int) = when (rotation) {
        90 -> uy
        180 -> (lh - 1 - uy) * lw
        270 -> lw - 1 - uy
        else -> uy * lw
    }

    /** Column part: rowPart(uy) + colPart(ux) is the raw index for every rotation. */
    private fun colPart(ux: Int, rotation: Int, lw: Int, lh: Int) = when (rotation) {
        90 -> (lh - 1 - ux) * lw
        180 -> lw - 1 - ux
        270 -> ux * lw
        else -> ux
    }

    /**
     * Sensor rect (x0, y0, x1, y1; ends exclusive) holding every luma pixel [compose]
     * reads for this registration — what [smoothEdges] has to cover. Null when the
     * overlay lands entirely outside the visible frame.
     */
    fun sampledRect(dispW: Int, dispH: Int, lw: Int, lh: Int,
                    zoom: Float, dx: Float, dy: Float, rotation: Int): IntArray? {
        val uw = if (rotation == 90 || rotation == 270) lh else lw
        val uh = if (rotation == 90 || rotation == 270) lw else lh
        val invZoom = 1f / zoom.coerceAtLeast(0.05f)
        val xs = axisTable(dispW, invZoom, dx, uw).filter { it >= 0 }
        val ys = axisTable(dispH, invZoom, dy, uh).filter { it >= 0 }
        if (xs.isEmpty() || ys.isEmpty()) return null
        val ux0 = xs.min(); val ux1 = xs.max()
        val uy0 = ys.min(); val uy1 = ys.max()
        return when (rotation) {                     // upright rect → sensor rect
            90 -> intArrayOf(uy0, lh - 1 - ux1, uy1 + 1, lh - ux0)
            180 -> intArrayOf(lw - 1 - ux1, lh - 1 - uy1, lw - ux0, lh - uy0)
            270 -> intArrayOf(lw - 1 - uy1, ux0, lw - uy0, ux1 + 1)
            else -> intArrayOf(ux0, uy0, ux1 + 1, uy1 + 1)
        }
    }

    /**
     * Compose the fusion overlay into [pixels] (ARGB, dispW x dispH) in place.
     *
     * @param luma      sensor-orientation luma frame (lw x lh)
     * @param edge      edge map from [smoothEdges] (required for EDGES mode, same dims as
     *                  luma; only the [sampledRect] patch is read)
     * @param gate      EDGES: thermal-gradient gating field (gw x gh, 1 = flat thermal,
     *                  overlay shows fully; →0 where the thermal gradient already
     *                  carries structure — suppresses double edges). Null = no gating.
     * @param gw, gh    dimensions of [gate] (the oriented thermal grid)
     * @param strength  EDGES: overlay opacity; BLEND: thermal weight (0..1)
     * @param zoom      registration scale (>1 crops into the wider visible FOV)
     * @param dx, dy    registration offset, in fractions of the upright visible frame
     * @param rotation  fixed thermal-to-visible mounting rotation: 0 / 90 / 180 / 270
     */
    fun compose(
        pixels: IntArray, dispW: Int, dispH: Int,
        luma: ByteArray, lw: Int, lh: Int,
        edge: FloatArray?,
        gate: FloatArray?, gw: Int, gh: Int,
        mode: Mode, strength: Float,
        zoom: Float, dx: Float, dy: Float, rotation: Int,
    ) {
        val uw = if (rotation == 90 || rotation == 270) lh else lw
        val uh = if (rotation == 90 || rotation == 270) lw else lh
        val invZoom = 1f / zoom.coerceAtLeast(0.05f)
        val s = strength.coerceIn(0f, 1f)
        val em = if (mode == Mode.EDGES) edge ?: return else null

        // upright (ux,uy) → raw sensor index for the fixed mounting rotation, per axis
        val colOff = axisTable(dispW, invZoom, dx, uw)
            .let { t -> IntArray(dispW) { if (t[it] < 0) -1 else colPart(t[it], rotation, lw, lh) } }
        val rowOff = axisTable(dispH, invZoom, dy, uh)
            .let { t -> IntArray(dispH) { if (t[it] < 0) -1 else rowPart(t[it], rotation, lw, lh) } }
        // gate column per display column (identity when display == thermal grid)
        val gCol = if (gate != null) IntArray(dispW) { it * gw / dispW } else null

        // rows are independent → spread over cores (640×480 at the 4× display)
        java.util.stream.IntStream.range(0, dispH).parallel().forEach { y ->
            val ro = rowOff[y]
            if (ro < 0) return@forEach
            val rowBase = y * dispW
            // gate row for this display row
            val gRow = if (gate != null) (y * gh / dispH) * gw else 0
            for (x in 0 until dispW) {
                val co = colOff[x]
                if (co < 0) continue
                val idx = ro + co

                val i = rowBase + x
                val c = pixels[i]
                var r = (c shr 16) and 0xFF
                var g = (c shr 8) and 0xFF
                var b = c and 0xFF
                if (mode == Mode.EDGES) {
                    // thermal-aware gating: the overlay fills in where the thermal
                    // field is flat; where the thermal gradient already shows
                    // structure the visible edge is suppressed (no double edges)
                    val gv = if (gate != null && gCol != null) gate[gRow + gCol[x]] else 1f
                    val e = em!![idx] * s * gv
                    if (e > 0.004f) {
                        // adaptive contrast colour: pull toward the OPPOSITE extreme
                        // of the local thermal luminance — visible on both bright and
                        // dark palette colours, where fixed white vanished on hot
                        // yellows/whites
                        val lum = (r * 299 + g * 587 + b * 114) / 1000
                        if (lum < 128) {
                            r += (e * (255 - r)).toInt()
                            g += (e * (255 - g)).toInt()
                            b += (e * (255 - b)).toInt()
                        } else {
                            r -= (e * r).toInt()
                            g -= (e * g).toInt()
                            b -= (e * b).toInt()
                        }
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
     * @param reuse     buffer to compose into when it is the right size (reused per frame)
     * @return the composed frame + the footprint rect (normalized) for marker mapping
     */
    fun composeWide(
        thermal: IntArray, tw: Int, th: Int,
        luma: ByteArray, lw: Int, lh: Int,
        strength: Float, zoom: Float, dx: Float, dy: Float, rotation: Int,
        reuse: IntArray? = null,
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

        val out = reuse?.takeIf { it.size == uw * uh } ?: IntArray(uw * uh)
        val ys = y0.coerceAtLeast(0)
        val ye = y1.coerceAtMost(uh)
        val xs = x0.coerceAtLeast(0)
        val xe = x1.coerceAtMost(uw)

        // per-column tables: sensor-index column part, and the thermal column the
        // footprint maps there (−1 outside it) — so the per-pixel loop only reads tables
        val colOff = IntArray(uw) { colPart(it, rotation, lw, lh) }
        val tCol = IntArray(uw) { x ->
            if (x < xs || x >= xe) -1 else {
                val txi = ((((x + 0.5f) / uw - 0.5f - dx) * z + 0.5f) * tw).toInt()
                if (txi < 0 || txi >= tw) -1 else txi
            }
        }

        // grayscale visible base — every pixel the phone camera captured — with the
        // thermal inset blended in where its footprint lies; rows spread over cores
        java.util.stream.IntStream.range(0, uh).parallel().forEach { y ->
            val row = y * uw
            val ro = rowPart(y, rotation, lw, lh)
            val tyi = if (y < ys || y >= ye) -1
                      else ((((y + 0.5f) / uh - 0.5f - dy) * z + 0.5f) * th).toInt()
            val trow = if (tyi in 0 until th) tyi * tw else -1
            for (x in 0 until uw) {
                val g = luma[ro + colOff[x]].toInt() and 0xFF
                val txi = if (trow >= 0) tCol[x] else -1
                out[row + x] = if (txi < 0) {
                    (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                } else {
                    val c = thermal[trow + txi]
                    val r2 = ((1f - s) * g + s * ((c shr 16) and 0xFF)).toInt()
                    val g2 = ((1f - s) * g + s * ((c shr 8) and 0xFF)).toInt()
                    val b2 = ((1f - s) * g + s * (c and 0xFF)).toInt()
                    (0xFF shl 24) or (r2 shl 16) or (g2 shl 8) or b2
                }
            }
        }

        // thin outline so the inset is findable at a glance
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
