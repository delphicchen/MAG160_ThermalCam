package com.magnity.thermalcam.ui

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.magnity.thermalcam.camera.RgbCamera
import com.magnity.thermalcam.data.CalibrationStore
import com.magnity.thermalcam.data.Ddt
import com.magnity.thermalcam.data.OnnxExport
import com.magnity.thermalcam.pipeline.EspcnTrainer
import com.magnity.thermalcam.pipeline.Enhancer
import com.magnity.thermalcam.pipeline.FactoryNuc
import com.magnity.thermalcam.pipeline.Fusion
import com.magnity.thermalcam.pipeline.ImageOps
import com.magnity.thermalcam.pipeline.NeuralSR
import com.magnity.thermalcam.pipeline.Radiometry
import com.magnity.thermalcam.record.VideoRecorder
import com.magnity.thermalcam.usb.MagCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The viewer brain — Android port of viewer.py's Viewer class, with the Qt event
 * loop replaced by coroutines and the pixmap replaced by a Compose-observed Bitmap.
 */
class ThermalViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "ThermalVM"
        private val FULL_RECT = floatArrayOf(0f, 0f, 1f, 1f)
    }

    // ---- observable UI state -------------------------------------------------

    var status by mutableStateOf("waiting for camera…"); private set
    var connected by mutableStateOf(false); private set
    var frameW by mutableStateOf(160); private set
    var frameH by mutableStateOf(120); private set
    var displayBitmap by mutableStateOf<Bitmap?>(null); private set
    var displayAspect by mutableStateOf(4f / 3f); private set
    /** Thermal footprint inside the displayed bitmap, normalized (l, t, w, h).
     *  Full-frame except in wide-search fusion; markers/taps map through this. */
    var markerRect by mutableStateOf(floatArrayOf(0f, 0f, 1f, 1f)); private set
    var fpsDisplay by mutableStateOf(0f); private set
    var fpaTemp by mutableStateOf<Int?>(null); private set
    var fpaDrift by mutableStateOf<Int?>(null); private set
    var readout by mutableStateOf(""); private set

    // markers, in measurement-frame pixel coords
    var hotSpot by mutableStateOf<Pair<Int, Int>?>(null); private set
    var coldSpot by mutableStateOf<Pair<Int, Int>?>(null); private set
    var calTarget by mutableStateOf<Pair<Int, Int>?>(null)

    // controls (viewer.py parity)
    var paletteName by mutableStateOf("ironbow")
    var autoRange by mutableStateOf(true)
    var mirror by mutableStateOf(false)
    /** Fixed mounting rotation of the thermal module vs the phone (0/90/180/270°),
     *  applied at input so display, measurement and markers all stay consistent. */
    var thermalRotation by mutableStateOf(0); private set
    var paused by mutableStateOf(false)
    var autoFfc by mutableStateOf(false)
    var factoryNucAvailable by mutableStateOf(false); private set
    var factoryNucOn by mutableStateOf(false); private set
    var srOn by mutableStateOf(false)
    var srScale by mutableStateOf(4)
    var srBackend by mutableStateOf(""); private set
    /** Non-null while collecting/training the on-device SR model (progress text). */
    var srTrainingProgress by mutableStateOf<String?>(null); private set
    var bpcOn by mutableStateOf(true)
    var flatfieldOn by mutableStateOf(true)
    var temporalOn by mutableStateOf(true)
    var spatialOn by mutableStateOf(true)
    var detailOn by mutableStateOf(false)       // CLAHE + guided detail boost (display)
    var detailStrength by mutableStateOf(0.5f)
    var gainStepWarmPending by mutableStateOf(false); private set

    var calPointCount by mutableStateOf(0); private set
    var calibrated by mutableStateOf(false); private set
    var ddtReviewName by mutableStateOf<String?>(null); private set

    // video recording of the display stream (works in every mode)
    var recording by mutableStateOf(false); private set
    var recordingTime by mutableStateOf(""); private set

    // RGB sensor fusion (phone camera overlay)
    var fusionOn by mutableStateOf(false); private set
    var fusionMode by mutableStateOf(Fusion.Mode.EDGES)
    var fusionStrength by mutableStateOf(0.6f)
    var fusionZoom by mutableStateOf(1.5f)          // visible FOV is wider -> crop in
    var fusionDx by mutableStateOf(0f)
    var fusionDy by mutableStateOf(0f)
    var fusionRotation by mutableStateOf(0)         // fixed mounting rotation, 0/90/180/270

    // ---- internals -------------------------------------------------------------

    private val cam = MagCamera()
    private var enhancer = Enhancer(160, 120)
    private var radio: Radiometry? = null
    private var fnuc: FactoryNuc? = null
    private val srModels = java.util.concurrent.ConcurrentHashMap<Int, NeuralSR>()
    private val srRequested = java.util.Collections.synchronizedSet(HashSet<Int>())

    private val calStore = CalibrationStore(File(app.filesDir, "calibration.json"))
    private var calState = CalibrationStore.State()
    private val flatFile = File(app.filesDir, "flatfield.bin")
    private val gainFile = File(app.filesDir, "gain_nuc.bin")
    private val viewPrefs = File(app.filesDir, "view.json")   // rotation + mirror

    private var lastFrame: FloatArray? = null       // measurement-grade frame
    private var gainColdBurst: List<FloatArray>? = null
    private val ffcBusy = AtomicBoolean(false)

    // fusion internals: RGB source + per-luma-frame edge-map cache
    private val rgbCamera by lazy { RgbCamera(getApplication()) }
    private var edgeCacheId = -1L
    private var edgeCache: FloatArray? = null
    private val fusionPrefs = File(app.filesDir, "fusion.json")

    // recording internals (recorder is only touched by the processing thread + stop path)
    @Volatile private var recorder: VideoRecorder? = null
    private var recordingPfd: android.os.ParcelFileDescriptor? = null
    private var recordingUri: android.net.Uri? = null
    private var recordingName = ""
    private var recordingStartMs = 0L

    private val calBox = 2                          // 5x5 averaging window, like the viewer

    // auto-FFC (firmware-style: keyed on FPA drift with a time fallback)
    private val ffcTempThreshold = 120
    private val autoFfcMaxIntervalS = 120
    private var lastFfcTemp: Int? = null
    private var lastFfcTimeMs = 0L

    private var processJob: Job? = null
    private var fpsT = System.currentTimeMillis()
    private var fpsN = 0

    init {
        // Load the shared binary assets off the main thread.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                radio = Radiometry(app.assets.open("planck_luts.npy"))
                calState = calStore.load()
                if (calState.calibrated) {
                    radio?.restore(calState.a, calState.b, calState.lutIdx, true)
                }
                calPointCount = calState.points.size
                calibrated = calState.calibrated
            } catch (e: Exception) {
                Log.e(TAG, "radiometry load failed", e)
            }
            try {
                fnuc = FactoryNuc.load(app.assets.open("factory_nuc_grid.npz"))
                factoryNucAvailable = true
            } catch (e: Exception) {
                Log.e(TAG, "factory NUC grid load failed", e)
            }
            loadFusionPrefs()
        }
    }

    // ---- USB lifecycle -----------------------------------------------------------

    fun connect(usbManager: UsbManager, device: UsbDevice) {
        if (connected) return
        status = "opening camera…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                cam.open(usbManager, device)
                loadViewPrefs()                   // rotation/mirror before sizing
                val (ow, oh) = orientedDims()
                frameW = ow
                frameH = oh
                enhancer = Enhancer(ow, oh)
                loadPersistentMaps()
                cam.start()
                delay(500)
                status = "FFC…"
                cam.triggerFfc()                 // startup FFC for an immediately clean image
                lastFfcTemp = cam.sensorTempRaw()
                lastFfcTimeMs = System.currentTimeMillis()
                connected = true
                status = "streaming ${cam.width}x${cam.height} @ ${cam.fps}fps"
                startProcessing()
                launch { delay(400); learnBadPixels() }
                startAutoFfcLoop()
            } catch (e: Exception) {
                Log.e(TAG, "connect failed", e)
                status = "camera error: ${e.message}"
                runCatching { cam.stop(); cam.close() }
            }
        }
    }

    fun disconnect() {
        if (recording) stopRecording()
        connected = false
        processJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { cam.stop() }
            runCatching { cam.close() }
        }
        status = "camera disconnected"
    }

    // ---- processing loop -----------------------------------------------------------

    private fun startProcessing() {
        processJob?.cancel()
        processJob = viewModelScope.launch(Dispatchers.Default) {
            val frameMs = (1000L / cam.fps.coerceAtLeast(1)).coerceAtLeast(30L)
            while (connected) {
                val t0 = System.currentTimeMillis()
                try {
                    step()
                } catch (e: Exception) {
                    Log.e(TAG, "frame step failed", e)
                }
                val dt = System.currentTimeMillis() - t0
                if (dt < frameMs) delay(frameMs - dt)
            }
        }
    }

    /** One display update — port of viewer.update_frame(). */
    private fun step() {
        val w = frameW; val h = frameH
        val frame: FloatArray
        val disp: FloatArray
        var dispW = w; var dispH = h

        if (paused && lastFrame != null) {
            frame = lastFrame!!
            disp = enhancer.enhanceDisplay(frame)
        } else {
            if (ffcBusy.get()) return           // shutter closed — skip updates
            var clean: FloatArray
            val nuc = fnuc
            if (factoryNucOn && nuc != null) {
                // Factory NUC needs the UNCORRECTED raw + shutter dark reference, in
                // sensor orientation: apply in sensor orientation, THEN orient (rotate
                // + mirror) the result to the display frame.
                val rawNative = cam.getRaw(0) ?: return
                val fpa = cam.sensorTempRaw() ?: 20000
                clean = nuc.apply(rawNative, fpa, cam.ffcRef)
                clean = orient(clean, cam.width, cam.height)
                if (bpcOn) {
                    enhancer.bpc = true
                    clean = enhancer.correctBadOnly(clean)
                }
            } else {
                val raw = grab() ?: return
                syncToggles()
                clean = enhancer.clean(raw)
            }
            enhancer.temporal = temporalOn
            frame = enhancer.temporalStep(clean)
            lastFrame = frame

            disp = if (srOn) {
                val model = srModels[srScale]
                val pre = enhancer.smooth(frame)
                dispW = w * srScale; dispH = h * srScale
                val up = model?.upscale(pre, w, h)
                    ?: ImageOps.resizeBicubic(pre, w, h, dispW, dispH)
                // mild unsharp mask for perceived detail (enhance.py sharpen=0.15)
                val blur = ImageOps.gaussianBlur(up, dispW, dispH, 1.2f)
                FloatArray(up.size) { up[it] + 0.15f * (up[it] - blur[it]) }
            } else {
                enhancer.spatial = spatialOn
                enhancer.enhanceDisplay(frame)
            }
        }

        // detail enhancement (display-only): CLAHE + guided boost, at display resolution
        var dispOut = disp
        val detailApplied = detailOn
        if (detailApplied) {
            enhancer.detailStrength = detailStrength
            dispOut = enhancer.displayEnhance(disp, dispW, dispH)
        }

        // Render order matters: palette-mapping on the tiny native grid and letting the
        // GPU scale the COLORS looks blocky. When no SR is active, bicubic-upscale the
        // FLOAT data x2 first so quantization to the 256-colour palette happens on a
        // finer grid (SR outputs are already at display resolution).
        if (dispW == w && dispH == h) {
            dispOut = ImageOps.resizeBicubic(dispOut, dispW, dispH, dispW * 2, dispH * 2)
            dispW *= 2; dispH *= 2
        }

        // range: percentiles on the measurement frame (stable across SR scales) —
        // except after CLAHE, whose output lives in its own equalized [0,1] domain
        val lo: Float; val hi: Float
        if (detailApplied) {
            lo = ImageOps.percentile(dispOut, 1f)
            hi = ImageOps.percentile(dispOut, 99f)
        } else if (autoRange) {
            lo = ImageOps.percentile(frame, 1f)
            hi = ImageOps.percentile(frame, 99f)
        } else {
            lo = 0f; hi = 65535f
        }
        val span = (hi - lo).coerceAtLeast(if (detailApplied) 1e-6f else 1f)
        val lut = Palettes.lut(paletteName)
        val pixels = IntArray(dispW * dispH)
        for (i in pixels.indices) {
            var v = (dispOut[i] - lo) / span * 255f
            if (v < 0f) v = 0f else if (v > 255f) v = 255f
            pixels[i] = lut[v.toInt()]
        }
        // fusion: overlay modes edit `pixels` in place; wide-search replaces the frame
        var outPixels = pixels; var outW = dispW; var outH = dispH
        var rect = FULL_RECT
        if (fusionOn) {
            if (fusionMode == Fusion.Mode.SEARCH) {
                val fr = rgbCamera.latest()
                if (fr != null) {
                    val res = Fusion.composeWide(
                        pixels, dispW, dispH, fr.data, fr.width, fr.height,
                        fusionStrength, fusionZoom, fusionDx, fusionDy, fusionRotation,
                    )
                    outPixels = res.pixels; outW = res.width; outH = res.height
                    rect = floatArrayOf(res.rectL, res.rectT, res.rectW, res.rectH)
                }
            } else {
                applyFusion(pixels, dispW, dispH)
            }
        }
        val bmp = Bitmap.createBitmap(outPixels, outW, outH, Bitmap.Config.ARGB_8888)
        if (recording) {
            recorder?.let { rec ->
                runCatching { rec.encode(bmp) }.onFailure { Log.w(TAG, "record encode", it) }
                val s = (System.currentTimeMillis() - recordingStartMs) / 1000
                recordingTime = "%d:%02d".format(s / 60, s % 60)
            }
        }

        // hot/cold spots in measurement coords
        var mnI = 0; var mxI = 0
        for (i in frame.indices) {
            if (frame[i] < frame[mnI]) mnI = i
            if (frame[i] > frame[mxI]) mxI = i
        }

        // readout text
        val sb = StringBuilder()
        sb.append("max ").append(fmtCounts(frame[mxI])).append('\n')
        sb.append("min ").append(fmtCounts(frame[mnI])).append('\n')
        sb.append("ctr ").append(fmtCounts(frame[(h / 2) * w + w / 2]))
        calTarget?.let { (tx, ty) ->
            regionRaw(tx, ty)?.let { rv ->
                val n = 2 * calBox + 1
                sb.append("\nCAL (").append(tx).append(',').append(ty).append(") ")
                    .append(n).append('x').append(n).append("avg ").append(fmtCounts(rv.toFloat()))
            }
        }

        // fps meter
        fpsN++
        val now = System.currentTimeMillis()
        if (now - fpsT >= 1000) {
            fpsDisplay = fpsN * 1000f / (now - fpsT)
            fpsT = now; fpsN = 0
        }

        val st = cam.sensorTempRaw()

        viewModelScope.launch(Dispatchers.Main.immediate) {
            displayBitmap = bmp
            displayAspect = outW.toFloat() / outH
            markerRect = rect
            hotSpot = (mxI % w) to (mxI / w)
            coldSpot = (mnI % w) to (mnI / w)
            readout = sb.toString()
            fpaTemp = st
            fpaDrift = if (st != null && lastFfcTemp != null) st - lastFfcTemp!! else null
            if (ddtReviewName == null) {
                status = "%.1f fps   frames=%d".format(fpsDisplay, cam.frameCount)
            }
        }
    }

    private fun syncToggles() {
        enhancer.bpc = bpcOn
        enhancer.flatfield = flatfieldOn
        enhancer.temporal = temporalOn
        enhancer.spatial = spatialOn
    }

    /** Single frame entry point — applies the mounting rotation + mirror (viewer._grab). */
    private fun grab(timeoutMs: Long = 0): FloatArray? {
        val f = cam.getFrame(timeoutMs) ?: return null
        return orient(f, cam.width, cam.height)
    }

    /** Oriented (display) dimensions for the current rotation; 90/270 swap W/H. */
    private fun orientedDims(): Pair<Int, Int> =
        if (thermalRotation == 90 || thermalRotation == 270) cam.height to cam.width
        else cam.width to cam.height

    /** Apply the mounting rotation then the left-right mirror to a sensor-orientation
     *  frame, returning it in display orientation. */
    private fun orient(f: FloatArray, sensorW: Int, sensorH: Int): FloatArray {
        var r = if (thermalRotation != 0) ImageOps.rotate(f, sensorW, sensorH, thermalRotation) else f
        if (mirror) {
            val (ow, oh) = orientedDims()
            r = ImageOps.flipHorizontal(r, ow, oh)
        }
        return r
    }

    /** Cycle the thermal display rotation by 90° CW. Rebuilds the pipeline to the new
     *  geometry (learned flat-field/gain maps are orientation-specific, so they reset —
     *  re-capture after setting the mounting rotation once). */
    fun cycleThermalRotation() {
        if (!connected) return               // needs real sensor dims to resize the pipeline
        thermalRotation = (thermalRotation + 90) % 360
        val (ow, oh) = orientedDims()
        frameW = ow; frameH = oh
        // learned flat-field/gain/bad-pixel maps are orientation-specific — start fresh
        // (the shipped factory NUC grid is applied in sensor orientation, so it's fine)
        enhancer = Enhancer(ow, oh)
        calTarget = null
        lastFrame = null
        saveViewPrefs()
        status = "thermal rotation ${thermalRotation}° — re-capture flat-field if used"
    }

    fun setMirror(on: Boolean) {
        mirror = on
        saveViewPrefs()
    }

    private fun saveViewPrefs() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                viewPrefs.writeText(
                    org.json.JSONObject()
                        .put("rotation", thermalRotation)
                        .put("mirror", mirror)
                        .toString()
                )
            }
        }
    }

    private fun loadViewPrefs() {
        runCatching {
            if (!viewPrefs.exists()) return
            val d = org.json.JSONObject(viewPrefs.readText())
            thermalRotation = ((d.optInt("rotation", 0) / 90) % 4) * 90
            mirror = d.optBoolean("mirror", false)
        }
    }

    private fun fmtCounts(v: Float): String {
        val r = radio
        val c = v.toInt()
        return if (r != null && r.calibrated) {
            "%d cnt / %.1f°C".format(c, r.rawToCelsius(v.toDouble()))
        } else "$c cnt"
    }

    // ---- FFC -----------------------------------------------------------------------

    fun doFfc(light: Boolean = false) {
        if (!connected || ffcBusy.get()) return
        viewModelScope.launch(Dispatchers.IO) {
            ffcBusy.set(true)
            status = "FFC: closing shutter…"
            val ok = try { cam.triggerFfc() } finally { ffcBusy.set(false) }
            enhancer.resetTemporal()
            lastFfcTemp = cam.sensorTempRaw()
            lastFfcTimeMs = System.currentTimeMillis()
            if (!light) learnBadPixels()
            status = (if (light) "auto-FFC" else "FFC") + if (ok) " done" else " failed"
        }
    }

    private fun startAutoFfcLoop() {
        viewModelScope.launch(Dispatchers.IO) {
            while (connected) {
                delay(2000)
                if (!autoFfc || paused || ffcBusy.get()) continue
                val cur = cam.sensorTempRaw()
                val drift = if (cur != null && lastFfcTemp != null) {
                    kotlin.math.abs(cur - lastFfcTemp!!)
                } else null
                val elapsed = (System.currentTimeMillis() - lastFfcTimeMs) / 1000
                if ((drift != null && drift >= ffcTempThreshold) || elapsed >= autoFfcMaxIntervalS) {
                    doFfc(light = true)
                }
            }
        }
    }

    fun setAutoFfcEnabled(on: Boolean) {
        autoFfc = on
        if (on) {
            lastFfcTemp = cam.sensorTempRaw()
            lastFfcTimeMs = System.currentTimeMillis()
        }
    }

    // ---- bursts: bad pixels / flat-field / gain NUC -----------------------------------

    private suspend fun captureBurst(n: Int, maxMs: Long, bare: Boolean): List<FloatArray> {
        // `bare` grabs FFC-corrected frames with no flat/gain applied (viewer._capture_burst)
        val pa = enhancer.gainA; val pb = enhancer.gainB; val pf = enhancer.flatMap
        if (bare) { enhancer.gainA = null; enhancer.gainB = null; enhancer.flatMap = null }
        val frames = ArrayList<FloatArray>(n)
        val t0 = System.currentTimeMillis()
        try {
            while (frames.size < n && System.currentTimeMillis() - t0 < maxMs) {
                grab(200)?.let { frames.add(it) }
                delay(20)
            }
        } finally {
            if (bare) { enhancer.gainA = pa; enhancer.gainB = pb; enhancer.flatMap = pf }
        }
        return frames
    }

    fun learnBadPixels() {
        if (!connected) return
        viewModelScope.launch(Dispatchers.IO) {
            status = "learning bad-pixel map…"
            val frames = captureBurst(25, 2500, bare = false)
            if (frames.size >= 8) {
                val nb = enhancer.learnBadPixels(frames)
                status = "bad-pixel map: $nb pixels"
            } else status = "bad-pixel learn failed (not enough frames)"
        }
    }

    fun doFlatfield() {
        if (!connected) return
        viewModelScope.launch(Dispatchers.IO) {
            status = "flat-field: hold steady on the uniform surface…"
            val prev = enhancer.flatMap
            enhancer.flatMap = null
            val frames = captureBurst(40, 4000, bare = false)
            if (frames.size >= 10) {
                val res = enhancer.captureFlatfield(frames)
                savePersistentMaps()
                status = "flat-field captured (removed ±%.0f cnt shading)".format(res)
            } else {
                enhancer.flatMap = prev
                status = "flat-field failed (not enough frames)"
            }
        }
    }

    fun doGainNucStep() {
        if (!connected) return
        viewModelScope.launch(Dispatchers.IO) {
            if (gainColdBurst == null) {
                status = "Gain NUC 1/2: hold steady on a COOL uniform surface…"
                val frames = captureBurst(40, 4000, bare = true)
                if (frames.size < 10) { status = "gain NUC: not enough frames (try again)"; return@launch }
                gainColdBurst = frames
                gainStepWarmPending = true
                status = "COLD captured — now aim at a WARMER uniform surface and tap again"
            } else {
                status = "Gain NUC 2/2: hold steady on a WARMER uniform surface…"
                val frames = captureBurst(40, 4000, bare = true)
                if (frames.size < 10) { status = "gain NUC: not enough frames (try again)"; return@launch }
                val (gstd, span) = enhancer.captureFlatfield2pt(gainColdBurst!!, frames)
                gainColdBurst = null
                gainStepWarmPending = false
                if (span < 200f) {
                    status = "gain NUC: surfaces too close in level (Δ%.0f cnt) — use a warmer target".format(span)
                } else {
                    savePersistentMaps()
                    status = "gain NUC built (Δ%.0f cnt, gain std %.3f) — saved".format(span, gstd)
                }
            }
        }
    }

    fun clearGainNuc() {
        enhancer.gainA = null; enhancer.gainB = null
        gainColdBurst = null
        gainStepWarmPending = false
        gainFile.delete()
        status = "gain NUC cleared"
    }

    fun clearFlatfield() {
        enhancer.clearFlatfield()
        flatFile.delete(); gainFile.delete()
        gainColdBurst = null
        gainStepWarmPending = false
        status = "flat-field + gain NUC cleared"
    }

    // ---- persistence of learned maps (simple length-prefixed float blobs) --------------

    private fun saveFloats(file: File, vararg arrays: FloatArray) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(arrays.size)
            for (a in arrays) {
                out.writeInt(a.size)
                for (v in a) out.writeFloat(v)
            }
        }
    }

    private fun loadFloats(file: File): List<FloatArray>? {
        if (!file.exists()) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { inp ->
                val n = inp.readInt()
                List(n) {
                    val len = inp.readInt()
                    FloatArray(len) { inp.readFloat() }
                }
            }
        } catch (e: Exception) { null }
    }

    private fun savePersistentMaps() {
        enhancer.flatMap?.let { saveFloats(flatFile, it) } ?: flatFile.delete()
        val a = enhancer.gainA; val b = enhancer.gainB
        if (a != null && b != null) saveFloats(gainFile, a, b) else gainFile.delete()
    }

    private fun loadPersistentMaps() {
        loadFloats(flatFile)?.firstOrNull()?.takeIf { it.size == frameW * frameH }?.let {
            enhancer.flatMap = it
        }
        loadFloats(gainFile)?.takeIf { it.size == 2 && it[0].size == frameW * frameH }?.let {
            enhancer.gainA = it[0]; enhancer.gainB = it[1]
        }
    }

    // ---- factory NUC ---------------------------------------------------------------------

    fun setFactoryNuc(on: Boolean) {
        factoryNucOn = on && factoryNucAvailable
        if (factoryNucOn && cam.ffcRef == null) {
            status = "Factory NUC: capturing shutter reference…"
            doFfc(light = true)
        }
    }

    // ---- RGB sensor fusion ----------------------------------------------------------------

    /**
     * Enable/disable the phone-camera overlay. Call from the main thread with the
     * CAMERA permission already granted (MainActivity owns the permission flow).
     */
    fun setFusion(on: Boolean) {
        if (on == fusionOn) return
        if (on) {
            fusionOn = true
            rgbCamera.start(onError = { msg ->
                fusionOn = false
                status = "fusion camera error: $msg"
            })
            status = "RGB fusion on — align with the zoom/offset sliders"
        } else {
            fusionOn = false
            rgbCamera.stop()
            edgeCacheId = -1; edgeCache = null
        }
        saveFusionPrefs()
    }

    fun cycleFusionRotation() {
        fusionRotation = (fusionRotation + 90) % 360
        saveFusionPrefs()
    }

    fun saveFusionPrefs() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val d = org.json.JSONObject()
                d.put("mode", fusionMode.name)
                d.put("strength", fusionStrength.toDouble())
                d.put("zoom", fusionZoom.toDouble())
                d.put("dx", fusionDx.toDouble())
                d.put("dy", fusionDy.toDouble())
                d.put("rotation", fusionRotation)
                fusionPrefs.writeText(d.toString())
            }
        }
    }

    private fun loadFusionPrefs() {
        runCatching {
            if (!fusionPrefs.exists()) return
            val d = org.json.JSONObject(fusionPrefs.readText())
            fusionMode = runCatching { Fusion.Mode.valueOf(d.optString("mode", "EDGES")) }
                .getOrDefault(Fusion.Mode.EDGES)
            fusionStrength = d.optDouble("strength", 0.6).toFloat()
            fusionZoom = d.optDouble("zoom", 1.5).toFloat()
            fusionDx = d.optDouble("dx", 0.0).toFloat()
            fusionDy = d.optDouble("dy", 0.0).toFloat()
            fusionRotation = d.optInt("rotation", 0)
        }
    }

    /** Overlay the visible-camera fusion onto the palette-mapped pixels, in place. */
    private fun applyFusion(pixels: IntArray, dispW: Int, dispH: Int) {
        val fr = rgbCamera.latest() ?: return
        val edge = if (fusionMode == Fusion.Mode.EDGES) {
            if (fr.id != edgeCacheId || edgeCache == null) {
                edgeCache = Fusion.edgeMap(fr.data, fr.width, fr.height, edgeCache
                    ?.takeIf { it.size == fr.width * fr.height } ?: FloatArray(fr.width * fr.height))
                edgeCacheId = fr.id
            }
            edgeCache
        } else null
        Fusion.compose(
            pixels, dispW, dispH,
            fr.data, fr.width, fr.height, edge,
            fusionMode, fusionStrength,
            fusionZoom, fusionDx, fusionDy, fusionRotation,
        )
    }

    // ---- neural SR ------------------------------------------------------------------------

    fun setSuperres(on: Boolean) {
        srOn = on
        if (on) ensureSrModel(srScale)
    }

    fun setSrScale(scale: Int) {
        srScale = scale
        if (srOn) ensureSrModel(scale)
    }

    // ---- on-device SR training -------------------------------------------------------------

    @Volatile private var srTrainCancel = false

    /**
     * Collect frames from the live stream, train the ESPCN in-process (pure Kotlin,
     * same recipe as train_sr.py) and export the result as a local ONNX model that
     * NeuralSR then prefers over the shipped asset. Takes a few minutes; the live
     * view keeps running (training runs at low priority on the Default dispatcher).
     */
    fun trainSrModel(nFrames: Int = 300, epochs: Int = 40) {
        if (!connected || srTrainingProgress != null) return
        srTrainCancel = false
        val scale = srScale
        viewModelScope.launch(Dispatchers.Default) {
            try {
                // 1) collect distinct frames — user should pan across varied scenes
                val frames = ArrayList<FloatArray>(nFrames)
                var lastCount = cam.frameCount
                srTrainingProgress = "collecting 0/$nFrames — move the camera slowly"
                while (frames.size < nFrames && !srTrainCancel && connected) {
                    if (cam.frameCount == lastCount) { delay(10); continue }
                    lastCount = cam.frameCount
                    val f = grab(200) ?: continue
                    // per-frame normalise to [0,1] (train_sr.py load_and_normalise)
                    var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
                    for (v in f) { if (v < lo) lo = v; if (v > hi) hi = v }
                    val span = (hi - lo).coerceAtLeast(1e-6f)
                    for (i in f.indices) f[i] = (f[i] - lo) / span
                    frames.add(f)
                    if (frames.size % 25 == 0) {
                        srTrainingProgress = "collecting ${frames.size}/$nFrames — keep moving"
                    }
                }
                if (srTrainCancel || frames.size < 32) {
                    status = if (srTrainCancel) "SR training cancelled" else "SR training: not enough frames"
                    return@launch
                }

                // 2) train
                val trainer = EspcnTrainer(scale)
                val psnr = trainer.train(
                    frames, frameW, frameH, epochs = epochs, batch = 8,
                    lr0 = 2e-3f, gradWeight = 0.5f,
                    onProgress = { ep, total, loss, p ->
                        srTrainingProgress =
                            "training %d/%d  loss=%.4f  PSNR=%.1f dB".format(ep, total, loss, p)
                    },
                    isCancelled = { srTrainCancel || !connected },
                )
                if (srTrainCancel) { status = "SR training cancelled"; return@launch }

                // 3) export as a local ONNX model and swap it in
                val f = NeuralSR.localModelFile(getApplication(), scale)
                f.parentFile?.mkdirs()
                f.outputStream().use { OnnxExport.exportEspcn(trainer.model, it) }
                srModels.remove(scale)?.close()
                srRequested.remove(scale)
                if (srOn && srScale == scale) ensureSrModel(scale)
                status = "SR ${scale}x trained on-device (val PSNR %.1f dB) — model saved".format(psnr)
            } catch (e: Exception) {
                Log.e(TAG, "SR training failed", e)
                status = "SR training failed: ${e.message}"
            } finally {
                srTrainingProgress = null
            }
        }
    }

    fun cancelSrTraining() { srTrainCancel = true }

    /** Delete the on-device-trained model for the current scale, reverting to the asset. */
    fun resetSrModel() {
        val scale = srScale
        NeuralSR.localModelFile(getApplication(), scale).delete()
        srModels.remove(scale)?.close()
        srRequested.remove(scale)
        if (srOn) ensureSrModel(scale)
        status = "SR ${scale}x local model deleted — using shipped model"
    }

    private fun ensureSrModel(scale: Int) {
        if (!srRequested.add(scale)) return      // load once; bicubic until ready
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val m = NeuralSR(getApplication<Application>(), scale)
                srModels[scale] = m
                srBackend = m.backend
                status = "SR ${scale}x ready (${m.backend})"
            } catch (e: Exception) {
                Log.e(TAG, "SR model load failed", e)
                srBackend = "bicubic"
                status = "SR model unavailable — using bicubic"
            }
        }
    }

    // ---- temperature calibration -------------------------------------------------------------

    /** Averaged raw counts over the 5x5 window around (x,y) on the measurement frame. */
    fun regionRaw(x: Int, y: Int): Int? {
        val f = lastFrame ?: return null
        val w = frameW; val h = frameH
        val x0 = (x - calBox).coerceAtLeast(0); val x1 = (x + calBox + 1).coerceAtMost(w)
        val y0 = (y - calBox).coerceAtLeast(0); val y1 = (y + calBox + 1).coerceAtMost(h)
        var s = 0.0; var n = 0
        for (yy in y0 until y1) for (xx in x0 until x1) { s += f[yy * w + xx]; n++ }
        return if (n > 0) Math.round(s / n).toInt() else null
    }

    fun addCalPoint(knownCelsius: Double): Boolean {
        val target = calTarget ?: return false
        val raw = regionRaw(target.first, target.second) ?: return false
        val r = radio ?: return false
        calState.points.add(raw.toDouble() to knownCelsius)
        calPointCount = calState.points.size
        if (calState.points.size >= 2) {
            val rms = r.calibrate(calState.points)
            calibrated = true
            status = "calibrated: ${calState.points.size} pts, lut=${r.lutIdx}, rms=%.2f°C".format(rms)
        } else {
            status = "1 reference point stored (need ≥2 to calibrate)"
        }
        calState.calibrated = r.calibrated
        calState.a = r.a; calState.b = r.b; calState.lutIdx = r.lutIdx
        viewModelScope.launch(Dispatchers.IO) { runCatching { calStore.save(calState) } }
        return true
    }

    fun clearCalibration() {
        calState.points.clear()
        calState.calibrated = false
        calPointCount = 0
        calibrated = false
        radio?.clear()
        viewModelScope.launch(Dispatchers.IO) { runCatching { calStore.save(calState) } }
        status = "calibration cleared"
    }

    // ---- snapshot -------------------------------------------------------------------------------

    fun snapshot() {
        val bmp = displayBitmap ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // nearest-neighbour x4 so the PNG is comfortably viewable
                val big = Bitmap.createScaledBitmap(bmp, bmp.width * 4, bmp.height * 4, false)
                val name = "mag_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MagThermal")
                }
                val resolver = getApplication<Application>().contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    ?: throw RuntimeException("MediaStore insert failed")
                resolver.openOutputStream(uri)?.use { big.compress(Bitmap.CompressFormat.PNG, 100, it) }
                status = "saved Pictures/MagThermal/$name"
            } catch (e: Exception) {
                Log.e(TAG, "snapshot failed", e)
                status = "snapshot failed: ${e.message}"
            }
        }
    }

    // ---- video recording ------------------------------------------------------------------------

    /**
     * Start recording the display stream to Movies/MagThermal (MP4, HEVC with AVC
     * fallback). Records exactly what is shown in the current mode — palette, factory
     * NUC, SR, fusion, paused review — at a fixed 640x480 (nearest-neighbour scale;
     * all display modes share the 4:3 aspect so nothing is distorted).
     */
    fun startRecording() {
        if (recording || displayBitmap == null) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = "mag_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
                val cv = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MagThermal")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = getApplication<Application>().contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
                    ?: throw RuntimeException("MediaStore insert failed")
                val pfd = resolver.openFileDescriptor(uri, "rw")
                    ?: throw RuntimeException("openFileDescriptor failed")
                val rec = VideoRecorder(fps = cam.fps)
                rec.start(pfd.fileDescriptor)
                recorder = rec
                recordingPfd = pfd
                recordingUri = uri
                recordingName = name
                recordingStartMs = System.currentTimeMillis()
                recordingTime = "0:00"
                recording = true
                status = "recording (${rec.codecName}) → Movies/MagThermal/$name"
            } catch (e: Exception) {
                Log.e(TAG, "start recording failed", e)
                status = "recording failed: ${e.message}"
                cleanupRecording(deleteFile = true)
            }
        }
    }

    /** Stop and finalize the current recording. */
    fun stopRecording() {
        if (!recording) return
        recording = false                        // step() stops feeding frames first
        viewModelScope.launch(Dispatchers.IO) {
            val rec = recorder
            val frames = rec?.frameCount ?: 0
            runCatching { rec?.finish() }
            if (frames == 0L) {
                // nothing was written — remove the empty MediaStore entry
                cleanupRecording(deleteFile = true)
                status = "recording discarded (no frames)"
            } else {
                cleanupRecording(deleteFile = false)
                status = "saved Movies/MagThermal/$recordingName ($frames frames)"
            }
            recordingTime = ""
        }
    }

    private fun cleanupRecording(deleteFile: Boolean) {
        recorder = null
        runCatching { recordingPfd?.close() }
        recordingPfd = null
        val uri = recordingUri
        recordingUri = null
        if (uri != null) {
            val resolver = getApplication<Application>().contentResolver
            if (deleteFile) {
                runCatching { resolver.delete(uri, null, null) }
            } else {
                runCatching {
                    resolver.update(uri, ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }, null, null)
                }
            }
        }
    }

    // ---- DDT radiometric snapshots -------------------------------------------------------------

    fun saveDdt(out: OutputStream): Boolean {
        val f = lastFrame ?: return false
        val r = radio ?: return false
        return try {
            Ddt.save(out, f, frameW, frameH, cam.sensorTempRaw(), r.a, r.b, r.lutIdx, r.calibrated)
            status = "DDT saved"
            true
        } catch (e: Exception) {
            status = "save DDT failed: ${e.message}"
            false
        }
    }

    fun loadDdt(input: InputStream, name: String) {
        try {
            val d = Ddt.load(input, name)
            if (d.width != frameW || d.height != frameH) {
                status = "DDT geometry ${d.width}x${d.height} != camera ${frameW}x$frameH"
                return
            }
            ddtReviewName = name
            paused = true
            lastFrame = d.frame
            calTarget = null
            status = "reviewing $name — tap a known-temp spot, then add a cal point"
        } catch (e: Exception) {
            status = "load DDT failed: ${e.message}"
        }
    }

    fun exitDdtReview() {
        ddtReviewName = null
        paused = false
        status = "resumed live stream"
    }

    // ---- shutdown ---------------------------------------------------------------------------------

    override fun onCleared() {
        if (recording) stopRecording()
        connected = false
        processJob?.cancel()
        if (fusionOn) runCatching { rgbCamera.stop() }   // onCleared runs on main
        // ViewModel scope dies with us — tear down USB synchronously on a plain thread.
        Thread {
            runCatching { cam.stop() }
            runCatching { cam.close() }
            srModels.values.forEach { runCatching { it.close() } }
        }.start()
        super.onCleared()
    }
}
