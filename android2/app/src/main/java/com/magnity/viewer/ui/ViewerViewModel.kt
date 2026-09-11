package com.magnity.viewer.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.magnity.viewer.pipeline.FactoryNuc
import com.magnity.viewer.pipeline.ImageOps
import com.magnity.viewer.pipeline.Palettes
import com.magnity.viewer.pipeline.Radiometry
import com.magnity.viewer.usb.MagCamera
import com.magnity.viewer.usb.MagDeviceWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One processed display frame + measurement results. */
data class FrameResult(
    val image: IntArray,          // ARGB, w*h (oriented)
    val w: Int,
    val h: Int,
    val tempMin: Float,
    val tempMax: Float,
    val minPos: Int,              // index into oriented frame
    val maxPos: Int,
    val fpa: Long?,
)

class ViewerViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val TAG = "MagViewer"
        /** level-lock clamp — same magnitude as the firmware's ±1000 in sub_3d228 */
        const val TRIM_CLAMP = 1000f
        const val ACTION_USB_PERMISSION = "com.magnity.viewer.USB_PERMISSION"
    }

    /** Which frame source to use. The factory SDK is primary; ours is the fallback. */
    enum class Source { FACTORY_SDK, OWN_PIPELINE }

    val camera = MagCamera()

    /**
     * Factory SDK path. Its per-pixel output is already absolute temperature, so on this
     * path none of [FactoryNuc] / [seam2d] / flat-field / [trim] / [Radiometry] runs —
     * the value domain is °C rather than NUC counts. See [countsAreCelsius].
     */
    val sdk = MagDeviceWrapper()

    // ---- pipeline state -----------------------------------------------------
    private var nuc: FactoryNuc? = null
    private var radio: Radiometry? = null

    // ---- ui state -----------------------------------------------------------
    var status by mutableStateOf("disconnected"); private set
    var connected by mutableStateOf(false); private set
    var paused by mutableStateOf(false)
    var paletteName by mutableStateOf(Palettes.NAMES.first())
    var autoScale by mutableStateOf(true)
    var scaleLo by mutableFloatStateOf(0f)          // manual range, NUC-output counts
    var scaleHi by mutableFloatStateOf(65535f)
    var rotation by mutableIntStateOf(0)            // 0/90/180/270
    var mirror by mutableStateOf(false)
    var spot: Pair<Int, Int>? by mutableStateOf(null)   // oriented (x,y), null = none
    var spatialDenoise by mutableStateOf(true)      // display bilateral — hides residual readout FPN

    var lastFrame by mutableStateOf<FrameResult?>(null); private set
    var ffcBusy by mutableStateOf(false); private set
    var sdkBusy by mutableStateOf(false); private set
    /** Requested source. Takes effect on the next connect. */
    var source by mutableStateOf(Source.FACTORY_SDK); private set
    /** Source actually streaming right now; null when disconnected. */
    var activeSource by mutableStateOf<Source?>(null); private set
    /** Set when [Source.FACTORY_SDK] was asked for but failed and we fell back. */
    var sdkFellBack by mutableStateOf(false); private set

    /** True while the live value domain is °C (SDK path) rather than NUC counts. */
    val countsAreCelsius: Boolean get() = activeSource == Source.FACTORY_SDK
    /** Transcript of the last [runSdkSmokeTest]; null = never run. */
    var sdkLog by mutableStateOf<String?>(null); private set
    var flatFieldOn by mutableStateOf(false)
    var flatFieldReady by mutableStateOf(false); private set

    /** Per-pixel offset learned from a uniform scene (display+measurement FPN cleanup). */
    private var flatMap: FloatArray? = null

    /**
     * The averaged RAW frame [learnFlatField] was taught with. [flatMap] lives in the
     * processed-output domain, which is defined relative to `camera.ffcRef`, [seam2d]
     * and the active NUC grid table — an FFC replaces all three, so a map frozen at
     * learn time no longer matches and re-injects the very seams it removed (measured:
     * residual FPN sd 0.00 right after learning, 8.05 after the next FFC). Keeping the
     * raw lets [rebuildFlatField] re-derive the map under the current configuration
     * instead of forcing the user to re-learn.
     */
    private var flatLearnRaw: FloatArray? = null

    // ---- level-lock state ----------------------------------------------------
    private var trim = 0f
    private var lastGridIdx = -1
    private var lastFfcRef: Any? = null

    // FFC transient suppression + shutter level anchor
    private val postFfcDrop = java.util.concurrent.atomic.AtomicInteger(0)
    private var shutterAnchor: Float? = null       // previous FFC's shutter median

    // 1D readout pattern from the FFC shutter frame (mean-removed; additive + multiplicative)
    /** Full 2-D shutter offset map (mean-removed), subtracted additively after FFC. */
    private var seam2d: FloatArray? = null

    private var loop: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            try {
                nuc = FactoryNuc.load(ctx.assets.open("factory_nuc_grid.npz"))
                radio = Radiometry(ctx.assets.open("planck_luts.npy"))
                Log.i(TAG, "pipeline ready")
            } catch (e: Exception) {
                Log.e(TAG, "asset load failed", e)
                status = "asset load failed: ${e.message}"
            }
        }
        // Poll-based auto-connect — USB attach broadcasts are unreliable on newer
        // Androids; scanning UsbManager every 2 s always works.
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (!connected && !connecting.get() && System.currentTimeMillis() >= retryNotBefore) {
                    val ctx = getApplication<Application>()
                    val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
                    val dev = usb.deviceList.values.firstOrNull { MagCamera.isMagnity(it) }
                    if (dev != null) {
                        val devId = "${dev.vendorId}:${dev.productId}:${dev.deviceId}"
                        if (devId != lastDevId) {
                            retryNotBefore = 0L   // replug / re-enumeration → clear cooldown
                        }
                        // Only auto-connect a device we ALREADY have permission for.
                        // Permission is requested elsewhere (ATTACHED broadcast in
                        // MainActivity, and the manual Connect button) — NOT here — so
                        // we never spam the USB-permission dialog on every
                        // re-enumeration the way the old 2 s poll did.
                        if (usb.hasPermission(dev)) {
                            Log.i(TAG, "auto-connect")
                            connect(dev, true)
                        }
                    }
                }
                delay(2000)
            }
        }
    }

    // ---- connection -----------------------------------------------------------

    private val connecting = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var retryNotBefore = 0L        // cooldown after a failed open
    @Volatile private var lastDevId: String? = null   // detect replug (re-enumeration)

    fun connect(device: UsbDevice, permissionGranted: Boolean) {
        if (!MagCamera.isMagnity(device)) return
        if (!connecting.compareAndSet(false, true)) {
            Log.i(TAG, "connect already in progress — skipping")
            return
        }
        val ctx = getApplication<Application>()
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Factory SDK first when selected; on any failure fall through to our
                // own pipeline so the camera still works.
                if (source == Source.FACTORY_SDK && openFactorySdk(ctx)) {
                    lastDevId = "${device.vendorId}:${device.productId}:${device.deviceId}"
                    return@launch
                }
                status = "opening…"
                camera.open(usb, device)
                camera.start()
                connected = true
                activeSource = Source.OWN_PIPELINE
                lastDevId = "${device.vendorId}:${device.productId}:${device.deviceId}"
                status = if (sdkFellBack) "connected (自家管線 — SDK 失敗)"
                         else "connected — starting stream…"
                doFfc()
                startLoop()
            } catch (e: Exception) {
                Log.e(TAG, "connect failed", e)
                status = "connect failed: ${e.message}"
                // let the firmware settle before the next auto-attempt
                retryNotBefore = System.currentTimeMillis() + 5000
                runCatching { camera.stop(); camera.close() }
                activeSource = null
            } finally {
                connecting.set(false)
            }
        }
    }

    /**
     * Bring up the factory SDK path. Returns false (having cleaned up and set
     * [sdkFellBack]) if anything goes wrong, so the caller can use our own pipeline.
     *
     * USB is exclusive, so [camera] must not be holding the device — on this path it
     * never opens it at all.
     */
    private fun openFactorySdk(ctx: Context): Boolean {
        status = "opening factory SDK…"
        return try {
            MagDeviceWrapper.probeNativeLib()?.let { throw it }
            sdk.open(ctx)
            sdk.start()
            connected = true
            activeSource = Source.FACTORY_SDK
            sdkFellBack = false
            status = "connected (factory SDK) ${sdk.width}×${sdk.height}"
            startSdkLoop()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "factory SDK open failed — falling back to own pipeline", e)
            runCatching { sdk.close() }
            sdkFellBack = true
            connected = false
            activeSource = null
            false
        }
    }

    /**
     * Switch frame source. The value domain differs between the two (NUC counts vs °C),
     * so a manual display range no longer means anything — reset to auto. Reconnects via
     * the 2 s poll rather than inline, so USB is fully released first.
     */
    fun selectSource(s: Source) {
        if (s == source) return
        source = s
        autoScale = true
        sdkFellBack = false
        if (connected) {
            disconnect()
            retryNotBefore = System.currentTimeMillis() + 1500
        }
    }

    fun disconnect() {
        loop?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { camera.stop() }
            runCatching { camera.close() }
            runCatching { sdk.close() }
        }
        connected = false
        activeSource = null
        lastFrame = null
        status = "disconnected"
    }

    /** Drop the dead camera and let the 2 s poll reconnect (same devId retains USB
     *  permission, so no re-prompt). Used when the stream stalls without a clean
     *  re-enumeration — otherwise the loop would freeze on the last frame forever. */
    private fun recover() {
        Log.w(TAG, "stream recover: stop/close camera, poll will reconnect")
        runCatching { camera.stop() }
        runCatching { camera.close() }
        runCatching { sdk.close() }
        camera.clearFfc()
        connected = false
        activeSource = null
        retryNotBefore = System.currentTimeMillis() + 2000
    }

    /** Manual connect: scan for the camera, reuse remembered permission or ask. */
    fun requestConnect() {
        val ctx = getApplication<Application>()
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = usb.deviceList.values.firstOrNull { MagCamera.isMagnity(it) } ?: run {
            status = "camera not found — 插上後再按一次"
            return
        }
        if (usb.hasPermission(dev)) {
            connect(dev, true)
        } else {
            status = "requesting USB permission…"
            usb.requestPermission(
                dev,
                android.app.PendingIntent.getBroadcast(
                    ctx, 0,
                    Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName),
                    android.app.PendingIntent.FLAG_MUTABLE,
                )
            )
        }
    }

    fun doFfc() {
        if (!connected || ffcBusy) return
        if (activeSource == Source.FACTORY_SDK) {
            // The SDK owns the shutter and its own NUC reference; nothing of ours
            // (seam2d / trim / flat-field) is defined against it on this path.
            viewModelScope.launch(Dispatchers.IO) {
                ffcBusy = true
                try {
                    status = if (sdk.triggerFfc()) "FFC done (SDK)" else "FFC failed (SDK)"
                } finally {
                    ffcBusy = false
                }
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            ffcBusy = true
            try {
                val ok = camera.triggerFfc()
                if (ok) afterFfc()
            } catch (e: Throwable) {
                Log.e(TAG, "ffc failed", e)
            } finally {
                ffcBusy = false
                postFfcDrop.set(6)     // discard shutter-open transient frames
                lastFfcRef = null      // force trim re-lock after the drop
            }
        }
    }

    /**
     * After a successful FFC the shutter-closed dark frame gives us, for free, the
     * fixed 1D readout pattern: with offsetRef = dark, v = 0 and the NUC output is
     * exactly the offset-table structure. Its row/column profiles ARE the readout
     * seams (column ~128 etc.). Subtracting them is radiometrically sound — the
     * shutter is a uniform source. Also re-anchors the absolute level: the shutter
     * flag radiance is constant, so its median must not move between FFCs.
     */
    private fun afterFfc() {
        val fn = nuc ?: return
        val ref = camera.ffcRef ?: return
        val fpa = camera.sensorTempRaw() ?: 20000
        // shutter-closed output: v = (dark - dark)>>1 = 0 → the offset-table pattern
        val shut = fn.apply(ref, fpa, ref)
        val w = camera.width; val h = camera.height

        // The shutter is a uniform source, so its NUC output `shut` is purely the
        // sensor's FIXED 2-D offset pattern (incl. block/boundary seams) at the dark
        // level. Subtract it ADDITIVELY — NOT as a per-column "gain" (the old 1-D
        // col/row * ci/ri multiply treated the offset profile as gain and wrongly
        // scaled warm pixels, creating the warm-area block seam). seamResidual()
        // already removes (offset[seg]-offset[0]) for the live segment, so together
        // the full per-pixel offset is cancelled at every temperature.
        var sum = 0.0
        for (v in shut) sum += v
        val mean = (sum / shut.size).toFloat()
        seam2d = FloatArray(shut.size) { shut[it] - mean }
        // seam2d and camera.ffcRef both just moved — the learned flat-field is stated in
        // terms of them, so re-derive it here or it re-injects the old seam pattern.
        rebuildFlatField()
        // NOTE: no trim adjustment here — the frame-level level-lock (on the first
        // processed frame after the FFC transient) is the single source of trim
        // updates. Anchoring here AND there double-counts and drifts.
        Log.i(TAG, "afterFfc: 2-D seam map updated, trim=$trim")
    }

    override fun onCleared() {
        loop?.cancel()
        runCatching { camera.stop(); camera.close() }
        runCatching { sdk.close() }
    }

    // ---- processing loop --------------------------------------------------------

    private fun startLoop() {
        loop?.cancel()
        loop = viewModelScope.launch(Dispatchers.Default) {
            var dropLeft = 10                       // discard first saturated frames
            val startedAt = System.currentTimeMillis()
            var lastSeenFrames = 0L
            var lastSeenAt = System.currentTimeMillis()
            while (isActive && connected) {
                if (paused) { delay(50); continue }
                if (nuc == null || radio == null) { delay(100); continue }

                // discard shutter-open transient frames right after an FFC
                if (postFfcDrop.get() > 0) {
                    camera.getRaw(timeoutMs = 300)
                    postFfcDrop.decrementAndGet()
                    continue
                }

                // stream-health diagnostics — run even before the first FFC completes
                val fc = camera.frameCount
                val now = System.currentTimeMillis()
                if (fc == 0L && now - startedAt > 8000) {
                    Log.w(TAG, "no data on EP0x81 — recovering")
                    recover(); break
                }
                if (fc == lastSeenFrames && fc > 0L && now - lastSeenAt > 6000) {
                    Log.w(TAG, "stream stalled — recovering")
                    recover(); break
                }
                // Only re-arm the stall timer when the frame counter actually moved —
                // updating it unconditionally (as this did) made the 6 s stall check
                // dead code, so a frozen stream never triggered recover().
                if (fc != lastSeenFrames) { lastSeenFrames = fc; lastSeenAt = now }

                if (camera.ffcRef == null) { delay(50); continue }
                val raw = camera.getRaw(timeoutMs = 500)
                if (raw == null) continue
                if (dropLeft > 0) { dropLeft--; continue }
                if (dropLeft > 0) { dropLeft--; continue }

                val fpa = camera.sensorTempRaw() ?: 20000
                try {
                    val res = process(raw, fpa)
                    lastFrame = res
                    status = "streaming ${camera.width}×${camera.height}" +
                        " · frames=${camera.frameCount} err=${camera.readErrors}"
                } catch (e: Throwable) {
                    // surface pipeline errors on screen instead of crashing
                    Log.e(TAG, "process error", e)
                    status = "pipeline: ${e::class.java.simpleName}: ${e.message}" +
                        " · frames=${camera.frameCount} err=${camera.readErrors}"
                }
            }
        }
    }

    /**
     * Frame loop for the factory SDK path.
     *
     * The SDK pushes frames from its own thread into [MagDeviceWrapper]; this just picks
     * up the latest temperature map at display rate. No FFC bookkeeping, no drop-frame
     * handling — the SDK does its own shutter management internally.
     */
    private fun startSdkLoop() {
        loop?.cancel()
        loop = viewModelScope.launch(Dispatchers.Default) {
            val startedAt = System.currentTimeMillis()
            var lastSeen = 0L
            var lastSeenAt = startedAt
            val periodMs = 1000L / (if (sdk.fps > 0) sdk.fps else 15)
            while (isActive && connected) {
                if (paused) { delay(50); continue }

                val fc = sdk.frameCount
                val now = System.currentTimeMillis()
                if (fc == 0L && now - startedAt > 8000) {
                    Log.w(TAG, "SDK delivered no frames — recovering")
                    recover(); break
                }
                if (fc == lastSeen && fc > 0L && now - lastSeenAt > 6000) {
                    Log.w(TAG, "SDK stream stalled — recovering")
                    recover(); break
                }
                if (fc != lastSeen) { lastSeen = fc; lastSeenAt = now }

                val temp = sdk.getTemperatureData()
                if (temp == null) { delay(20); continue }
                try {
                    lastFrame = processSdk(temp)
                    status = "streaming ${sdk.width}×${sdk.height} (factory SDK)" +
                        " · frames=$fc"
                } catch (e: Throwable) {
                    Log.e(TAG, "SDK process error", e)
                    status = "SDK display: ${e::class.java.simpleName}: ${e.message}"
                }
                delay(periodMs)
            }
        }
    }

    /**
     * Display path for the factory SDK. Its values are already absolute temperature, so
     * the whole reconstruction chain is bypassed and the domain here is °C.
     *
     * Unlike [process], min/max are taken BEFORE the display bilateral — the smoothing
     * pulls in the extremes, and on this path the reading is the point.
     */
    private fun processSdk(tempMilliC: IntArray): FrameResult {
        val w = sdk.width; val h = sdk.height
        val degC = FloatArray(tempMilliC.size) { MagDeviceWrapper.degC(tempMilliC[it]) }

        var oriented = orient(degC, w, h)      // also publishes lastCounts (in °C)
        val ow: Int; val oh: Int
        if (rotation % 180 == 0) { ow = w; oh = h } else { ow = h; oh = w }

        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        var mnI = 0; var mxI = 0
        for (i in oriented.indices) {
            val v = oriented[i]
            if (v < mn) { mn = v; mnI = i }
            if (v > mx) { mx = v; mxI = i }
        }

        if (spatialDenoise) {
            // sigmaColor is in °C on this path, not counts — 80 counts of range
            // tolerance would be 80 °C here and flatten the whole scene.
            oriented = ImageOps.bilateral(oriented, ow, oh, 5, 0.5f, 5f)
        }

        val slo: Float; val shi: Float
        if (autoScale) {
            val p1 = ImageOps.percentile(oriented, 1f)
            val p99 = ImageOps.percentile(oriented, 99f)
            slo = p1
            shi = if (p99 - p1 < 0.1f) p1 + 0.1f else p99
            scaleLo = slo; scaleHi = shi
        } else {
            slo = scaleLo; shi = scaleHi
        }

        val pal = Palettes.lut(paletteName)
        val img = IntArray(oriented.size)
        val span = shi - slo
        for (i in oriented.indices) {
            var t = (oriented[i] - slo) / span
            if (t < 0f) t = 0f else if (t > 1f) t = 1f
            img[i] = pal[(t * 255).roundToInt()]
        }

        return FrameResult(
            image = img, w = ow, h = oh,
            tempMin = mn, tempMax = mx,
            minPos = mnI, maxPos = mxI,
            fpa = sdk.sensorTemp()?.toLong(),
        )
    }

    /**
     * Learn a per-pixel offset map from the current (uniform!) scene: average N frames,
     * subtract the global median, store the residual as the flat-field map.
     * Removes ALL static FPN — readout seams included — at this operating level.
     */
    fun learnFlatField() {
        if (!connected || ffcBusy) return
        if (activeSource == Source.FACTORY_SDK) {
            status = "flat-field 只用於自家管線"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            ffcBusy = true
            try {
                val w = camera.width; val h = camera.height
                val acc = FloatArray(w * h)
                var n = 0
                val t0 = System.currentTimeMillis()
                while (n < 16 && System.currentTimeMillis() - t0 < 4000) {
                    val f = camera.getRaw(timeoutMs = 500) ?: continue
                    for (i in acc.indices) acc[i] += f[i]
                    n++
                    delay(60)
                }
                if (n > 0) {
                    // Average the RAW frames and process once, rather than averaging
                    // processed outputs — keeps the stored raw and the map consistent,
                    // and keeps frame noise out of the NUC segment selection.
                    for (i in acc.indices) acc[i] /= n
                    flatLearnRaw = acc
                    flatMap = buildFlatMap(acc)
                    flatFieldReady = true
                    status = "flat-field learned ($n frames)"
                }
            } catch (e: Throwable) {
                Log.e(TAG, "learnFlatField failed", e)
                status = "learn failed: ${e.message}"
            } finally {
                ffcBusy = false
            }
        }
    }

    fun clearFlatField() {
        flatMap = null
        flatLearnRaw = null
        flatFieldOn = false
        flatFieldReady = false
    }

    /** Residual FPN of the learn scene under the CURRENT config, global level removed. */
    private fun buildFlatMap(learnRaw: FloatArray): FloatArray {
        val m = processCounts(learnRaw, camera.sensorTempRaw() ?: 20000)
        val global = ImageOps.median(m)
        for (i in m.indices) m[i] -= global
        return m
    }

    /**
     * Re-derive [flatMap] from the stored learn frame after anything the processed-output
     * domain is defined against has changed (a new shutter reference + [seam2d] from an
     * FFC, or a NUC grid-table switch). The map is median-removed, so [trim] cancels and
     * level-lock changes alone need no rebuild.
     */
    private fun rebuildFlatField() {
        val raw = flatLearnRaw ?: return
        if (camera.ffcRef == null || nuc == null) return
        flatMap = buildFlatMap(raw)
    }

    private fun processCounts(raw: FloatArray, fpa: Int): FloatArray {
        // NUC + trim + readout-pattern fix, WITHOUT orientation (internal use)
        val fn = nuc!!
        val ffc = camera.ffcRef ?: return raw.copyOf()
        val out = fn.apply(raw, fpa, ffc)
        val pat = fn.seamResidual(raw, fpa, ffc)
        for (i in out.indices) out[i] -= pat[i]
        for (i in out.indices) out[i] += trim
        val sd = seam2d
        if (sd != null) {
            val w = camera.width
            for (y in 0 until camera.height) {
                val base = y * w
                for (x in 0 until w) out[base + x] -= sd[base + x]
            }
        }
        return out
    }

    private fun process(raw: FloatArray, fpa: Int): FrameResult {
        val fn = nuc!!
        val radio = radio!!
        val w = camera.width; val h = camera.height
        val ffc = camera.ffcRef ?: throw IllegalStateException("no ffcRef")

        val gridIdx = currentGridIndex(fpa)
        val ffcChanged = camera.ffcRef !== lastFfcRef
        val gridChanged = gridIdx != lastGridIdx

        val out = fn.apply(raw, fpa, ffc)
        val pat = fn.seamResidual(raw, fpa, ffc)
        for (i in out.indices) out[i] -= pat[i]

        if ((gridChanged || ffcChanged) && lastGridIdx >= 0 && !ffcBusy) {
            // Level-lock: recompute the PREVIOUS configuration on this frame and fold the
            // median difference into trim so the reading never jumps at a table switch.
            val oldOut = fn.applyWith(raw, lastGridIdx, ffc)
            val patOld = fn.seamResidualFor(raw, lastGridIdx, ffc)
            for (i in oldOut.indices) oldOut[i] -= patOld[i]
            val newMed = ImageOps.median(out)
            val oldMed = ImageOps.median(oldOut)
            trim += (oldMed - newMed).coerceIn(-TRIM_CLAMP, TRIM_CLAMP)
        }
        val needFlatRebuild = gridChanged && lastGridIdx >= 0
        lastGridIdx = gridIdx
        lastFfcRef = camera.ffcRef
        // A grid-table switch changes the per-pixel offset structure the map was stated
        // against. (The FFC case is handled in afterFfc(), which also updates seam2d.)
        if (needFlatRebuild) rebuildFlatField()

        for (i in out.indices) out[i] += trim

        // subtract the 2-D shutter offset map (additive; radiometrically sound)
        val sd = seam2d
        if (sd != null) {
            for (y in 0 until h) {
                val base = y * w
                for (x in 0 until w) out[base + x] -= sd[base + x]
            }
        }

        // learned flat-field (per-pixel offset from a uniform scene)
        val fm = if (flatFieldOn) flatMap else null
        if (fm != null) for (i in out.indices) out[i] -= fm[i]

        // suppress hot/dead pixels for display & stats (3×3 median outlier replace)
        val clean = ImageOps.median3x3(out, w, h)
        for (i in clean.indices) {
            if (kotlin.math.abs(clean[i] - out[i]) > 60f) out[i] = clean[i]
        }

        // orient for display & stats
        var oriented = orient(out, w, h)
        val stats = oriented
        val ow: Int; val oh: Int
        if (rotation % 180 == 0) { ow = w; oh = h } else { ow = h; oh = w }

        // display bilateral smoothing (old app default; hides the ~600-count residual
        // readout FPN that even the factory NUC leaves — see exp_seam_trace.py)
        if (spatialDenoise) {
            oriented = ImageOps.bilateral(oriented, ow, oh, 5, 80f, 5f)
        }

        // stats on oriented grid
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        var mnI = 0; var mxI = 0
        for (i in oriented.indices) {
            val v = oriented[i]
            if (v < mn) { mn = v; mnI = i }
            if (v > mx) { mx = v; mxI = i }
        }

        // scale mapping (display only)
        val slo: Float; val shi: Float
        if (autoScale) {
            val p1 = ImageOps.percentile(oriented, 1f)
            val p99 = ImageOps.percentile(oriented, 99f)
            slo = p1
            shi = if (p99 - p1 < 1f) p1 + 1f else p99
            scaleLo = slo; scaleHi = shi
        } else {
            slo = scaleLo; shi = scaleHi
        }

        val pal = Palettes.lut(paletteName)
        val img = IntArray(oriented.size)
        val span = shi - slo
        for (i in oriented.indices) {
            var t = (oriented[i] - slo) / span
            if (t < 0f) t = 0f else if (t > 1f) t = 1f
            img[i] = pal[(t * 255).roundToInt()]
        }

        return FrameResult(
            image = img, w = ow, h = oh,
            tempMin = radio.outToCelsius(mn.toDouble()).toFloat(),
            tempMax = radio.outToCelsius(mx.toDouble()).toFloat(),
            minPos = mnI, maxPos = mxI,
            fpa = camera.sensorTempRaw()?.toLong(),
        )
    }

    private fun currentGridIndex(fpa: Int): Int {
        // mirrors FactoryNuc's nearest-grid selection for transition detection
        return nuc?.nearestIndex(fpa) ?: 0
    }

    fun spotCelsius(): Float? {
        val fr = lastFrame ?: return null
        val (x, y) = spot ?: return null
        if (x < 0 || y < 0 || x >= fr.w || y >= fr.h) return null
        // re-derive from stored image? we need counts; recompute cheaply from temp map:
        // keep it simple: ViewModel stores last oriented counts
        val i = y * fr.w + x
        val v = lastCounts?.get(i) ?: return null
        // On the SDK path lastCounts is already °C; on ours it is NUC counts.
        return if (countsAreCelsius) v else radio!!.outToCelsius(v.toDouble()).toFloat()
    }

    /**
     * One-point absolute anchor: the user places SPOT on an object of KNOWN temperature
     * and enters that temperature; the radiometry's offset (b) shifts so the spot reads
     * exactly right. Slope (a) is preserved.
     */
    fun refineSpot(trueCelsius: Float): Boolean {
        if (activeSource == Source.FACTORY_SDK) {
            status = "原廠 SDK 讀數已是絕對溫度，不需 refine"
            return false
        }
        val r = radio ?: return false
        val fr = lastFrame ?: return false
        val (x, y) = spot ?: return false
        if (x < 0 || y < 0 || x >= fr.w || y >= fr.h) return false
        val i = y * fr.w + x
        val v = lastCounts?.get(i) ?: return false
        r.refine(v.toDouble(), trueCelsius.toDouble())
        status = "refined: SPOT → %.1f°C".format(trueCelsius)
        return true
    }

    private var lastCounts: FloatArray? = null

    /** rotate then mirror, applied at input so markers/stats stay consistent. */
    private fun orient(src: FloatArray, w: Int, h: Int): FloatArray {
        var dst = src
        var cw = w; var ch = h
        if (rotation != 0) {
            dst = ImageOps.rotate(dst, w, h, rotation)
            if (rotation % 180 != 0) { cw = h; ch = w }
        }
        if (mirror) dst = ImageOps.flipHorizontal(dst, cw, ch)
        lastCounts = dst
        return dst
    }


    // ---- factory SDK smoke test ---------------------------------------------

    /**
     * One-shot check of the factory SDK path (`lib/arm64-v8a/libcoresdk.so`), kept
     * deliberately separate from the live pipeline: it tears the stream down, runs
     * init → link → prepare → stream → read, writes a transcript to [sdkLog], then
     * closes the SDK so the normal 2 s poll can reconnect [camera].
     *
     * USB is exclusive — [MagCamera] must be closed before the SDK links, and the
     * SDK opens its own UsbDeviceConnection from the fd it gets out of
     * `UsbManager.openDevice`.
     */
    fun runSdkSmokeTest() {
        if (sdkBusy) return
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            sdkBusy = true
            val log = StringBuilder()
            fun say(line: String) {
                Log.i(TAG, "[sdk] $line")
                log.append(line).append('\n')
                sdkLog = log.toString()
            }

            val wrapper = MagDeviceWrapper()
            try {
                // The live path may already hold the device through `sdk`; the test uses
                // its own MagDevice, and USB is exclusive.
                if (connected) {
                    say("… disconnecting live stream first")
                    disconnect()
                    delay(1200)
                }
                // 1. can the .so be linked at all?
                val loadErr = MagDeviceWrapper.probeNativeLib()
                if (loadErr != null) {
                    say("FAIL dlopen libcoresdk.so: ${loadErr.message}")
                    return@launch
                }
                say("OK  libcoresdk.so loaded")

                // 2. hand the USB device over
                val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
                val dev = usb.deviceList.values.firstOrNull { MagCamera.isMagnity(it) }
                if (dev == null) { say("FAIL camera not present"); return@launch }
                if (!usb.hasPermission(dev)) {
                    say("FAIL no USB permission — 先按 Connect 授權再試")
                    return@launch
                }
                loop?.cancel()
                runCatching { camera.stop(); camera.close() }
                camera.clearFfc()
                connected = false
                retryNotBefore = System.currentTimeMillis() + 60_000   // keep the poll off our USB
                status = "SDK smoke test…"
                // Releasing the device makes the firmware re-enumerate; give it a
                // moment so the SDK's first enumeration attempt usually hits.
                delay(750)
                say("OK  MagCamera released (poll suppressed 60 s)")

                // 3. init + link + prepare
                wrapper.open(ctx)
                say("OK  link + prepare")
                say("    fpa=${wrapper.width}x${wrapper.height} " +
                    "bmp=${wrapper.bmpWidth}x${wrapper.bmpHeight} fps=${wrapper.fps}")
                say("    name='${wrapper.cameraName}' type='${wrapper.cameraType}'")

                // 4. stream + read
                wrapper.start()
                val temp = wrapper.awaitTemperatureData(8000)
                if (temp == null) {
                    say("FAIL no getTemperatureData within 8 s (frames=${wrapper.frameCount})")
                    return@launch
                }
                say("OK  ${wrapper.frameCount} frames, ${temp.size} px")

                val cx = wrapper.width / 2
                val cy = wrapper.height / 2
                val centre = temp[cy * wrapper.width + cx]
                say("    centre[$cx,$cy] = $centre = %.2f°C".format(MagDeviceWrapper.degC(centre)))
                say("    frame min/max = ${temp.min()} / ${temp.max()} = %.2f / %.2f°C".format(
                    MagDeviceWrapper.degC(temp.min()), MagDeviceWrapper.degC(temp.max())))
                wrapper.probe(cx, cy)?.let {
                    say("    probe = $it = %.2f°C".format(MagDeviceWrapper.degC(it)))
                }
                wrapper.getFrameStats()?.let {
                    say("    stats min/ave/max = %.2f / %.2f / %.2f°C  netd=${it.aveNETDt}".format(
                        MagDeviceWrapper.degC(it.minTemperature),
                        MagDeviceWrapper.degC(it.aveTemperature),
                        MagDeviceWrapper.degC(it.maxTemperature)))
                }
                wrapper.sensorTemp()?.let {
                    say("    innerTemp = $it = %.2f°C".format(MagDeviceWrapper.degC(it)))
                }
                say("DONE — SDK path works")
            } catch (e: Throwable) {
                say("FAIL ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "sdk smoke test failed", e)
            } finally {
                runCatching { wrapper.close() }
                retryNotBefore = System.currentTimeMillis() + 2000   // let the poll take over
                status = "SDK test finished — 見 SDK log"
                sdkBusy = false
            }
        }
    }

    // auto FFC watchdog
    init {
        viewModelScope.launch(Dispatchers.IO) {
            var lastFpa = -1
            while (isActive) {
                delay(5000)
                // The SDK triggers its own FFC on its own schedule.
                if (!connected || ffcBusy || activeSource != Source.OWN_PIPELINE) {
                    lastFpa = -1; continue
                }
                val fpa = camera.sensorTempRaw() ?: continue
                if (lastFpa > 0 && kotlin.math.abs(fpa - lastFpa) >= 120) doFfc()
                lastFpa = fpa
            }
        }
    }
}
