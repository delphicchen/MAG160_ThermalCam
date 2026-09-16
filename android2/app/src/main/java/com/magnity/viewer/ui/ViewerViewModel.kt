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
import com.magnity.viewer.media.FrameComposer
import com.magnity.viewer.media.MediaSaver
import com.magnity.viewer.media.VideoRecorder
import com.magnity.viewer.pipeline.Anime4kGpu
import com.magnity.viewer.pipeline.ImageOps
import com.magnity.viewer.pipeline.Palettes
import com.magnity.viewer.pipeline.TemporalDenoise
import com.magnity.viewer.usb.MagDeviceWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One processed display frame + measurement results. */
data class FrameResult(
    val bitmap: android.graphics.Bitmap,  // palette-mapped, oriented, upscaled (w·k × h·k)
    val w: Int,                   // native oriented grid — stats, markers, SPOT live here
    val h: Int,
    val tempMin: Float,
    val tempMax: Float,
    val minPos: Int,              // index into the oriented native grid
    val maxPos: Int,
    val fpa: Long?,
    val scaleLoC: Float,          // display range in °C (colour bar labels)
    val scaleHiC: Float,
)

/**
 * Live viewer state. Temperature comes from the factory SDK only — it reports absolute
 * °C and runs its own NUC and shutter management.
 *
 * Our own reconstruction pipeline (FactoryNuc + Radiometry + flat-field + seam fixes)
 * was removed on 2026-09-16; see docs/OWN_PIPELINE_REMOVED.md for what it did and how to
 * bring it back from git history.
 */
class ViewerViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val TAG = "MagViewer"
        const val ACTION_USB_PERMISSION = "com.magnity.viewer.USB_PERMISSION"
    }

    enum class Upscaler { BICUBIC, ANIME4K }

    val sdk = MagDeviceWrapper()

    // ---- UI state ---------------------------------------------------------------

    var status by mutableStateOf("disconnected"); private set
    var connected by mutableStateOf(false); private set
    var paused by mutableStateOf(false)
    var paletteName by mutableStateOf(Palettes.NAMES.first())
    var autoScale by mutableStateOf(true)
    // Module spec measurement range — the SDK's CameraInfo does not report one, so the
    // manual scale slider spans this fixed domain.
    val tempMinC = -20f
    val tempMaxC = 150f
    var scaleLo by mutableFloatStateOf(0f)          // manual display range, °C
    var scaleHi by mutableFloatStateOf(100f)
    // default = camera plugged straight into the phone's USB-C port, portrait UI
    var rotation by mutableIntStateOf(90)           // 0/90/180/270 clockwise
    var mirror by mutableStateOf(true)
    var upscale by mutableIntStateOf(4)             // display upscale 1/2/4 (4 → 640×480)
    var upscaler by mutableStateOf(Upscaler.ANIME4K)
    var spatialDenoise by mutableStateOf(true)
    var temporalDenoise by mutableStateOf(true)     // display only — readouts stay per-frame
    var temporalStrength by mutableFloatStateOf(0.85f)
    var showMaxRoi by mutableStateOf(true)
    var showMinRoi by mutableStateOf(true)
    var spot: Pair<Int, Int>? by mutableStateOf(null)   // oriented (x,y), null = none

    var lastFrame by mutableStateOf<FrameResult?>(null); private set
    var ffcBusy by mutableStateOf(false); private set
    var sdkBusy by mutableStateOf(false); private set
    var fps by mutableFloatStateOf(0f); private set     // processed (= displayed) frames/s
    var recording by mutableStateOf(false); private set
    var recordStartMs by mutableStateOf(0L); private set
    /** transient user message (screenshot saved, …); the UI clears it */
    var notice by mutableStateOf<String?>(null)
    /** Transcript of the last [runSdkSmokeTest]; null = never run. */
    var sdkLog by mutableStateOf<String?>(null); private set

    private var loop: Job? = null

    init {
        // Poll-based auto-connect — USB attach broadcasts are unreliable on newer
        // Androids; scanning UsbManager every 2 s always works.
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (!connected && !connecting.get() && System.currentTimeMillis() >= retryNotBefore) {
                    val ctx = getApplication<Application>()
                    val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
                    val dev = usb.deviceList.values.firstOrNull { MagDeviceWrapper.isMagnity(it) }
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
        if (!MagDeviceWrapper.isMagnity(device)) return
        if (!connecting.compareAndSet(false, true)) {
            Log.i(TAG, "connect already in progress — skipping")
            return
        }
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                status = "opening factory SDK…"
                MagDeviceWrapper.probeNativeLib()?.let { throw it }
                sdk.open(ctx)
                sdk.start()
                connected = true
                lastDevId = "${device.vendorId}:${device.productId}:${device.deviceId}"
                status = "connected ${sdk.width}×${sdk.height}"
                startSdkLoop()
            } catch (e: Throwable) {
                Log.e(TAG, "connect failed", e)
                status = "connect failed: ${e.message}"
                runCatching { sdk.close() }
                connected = false
                // let the firmware settle before the next auto-attempt
                retryNotBefore = System.currentTimeMillis() + 5000
            } finally {
                connecting.set(false)
            }
        }
    }

    fun disconnect() {
        loop?.cancel()
        stopRecording()
        viewModelScope.launch(Dispatchers.IO) { runCatching { sdk.close() } }
        connected = false
        lastFrame = null
        fps = 0f; fpsWindowStart = 0L; fpsWindowFrames = 0
        status = "disconnected"
    }

    /** Drop the dead camera and let the 2 s poll reconnect (same devId retains USB
     *  permission, so no re-prompt). Used when the stream stalls without a clean
     *  re-enumeration — otherwise the loop would freeze on the last frame forever. */
    private fun recover() {
        Log.w(TAG, "stream recover: close SDK, poll will reconnect")
        runCatching { sdk.close() }
        connected = false
        retryNotBefore = System.currentTimeMillis() + 2000
    }

    /** Manual connect: scan for the camera, reuse remembered permission or ask. */
    fun requestConnect() {
        val ctx = getApplication<Application>()
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = usb.deviceList.values.firstOrNull { MagDeviceWrapper.isMagnity(it) } ?: run {
            status = "camera not found — plug it in and try again"
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

    /** The SDK owns the shutter and its own NUC reference. */
    fun doFfc() {
        if (!connected || ffcBusy) return
        viewModelScope.launch(Dispatchers.IO) {
            ffcBusy = true
            try {
                status = if (sdk.triggerFfc()) "FFC done" else "FFC failed"
            } finally {
                ffcBusy = false
            }
        }
    }

    override fun onCleared() {
        loop?.cancel()
        synchronized(recLock) { recorder?.also { recorder = null } }?.stop()
        anime4k?.close()
        runCatching { sdk.close() }
    }

    // ---- processing loop --------------------------------------------------------

    /**
     * The SDK pushes frames from its own thread into [MagDeviceWrapper]; this picks each
     * new one up as soon as it lands. No FFC bookkeeping — the SDK does its own.
     */
    private fun startSdkLoop() {
        loop?.cancel()
        loop = viewModelScope.launch(Dispatchers.Default) {
            val startedAt = System.currentTimeMillis()
            var lastSeen = 0L
            var lastSeenAt = startedAt
            var lastProcessed = -1L
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

                // process each SDK frame once, as soon as it lands (no fixed period)
                if (fc == lastProcessed) { delay(4); continue }
                val temp = sdk.getTemperatureData()
                if (temp == null) { delay(20); continue }
                lastProcessed = fc
                try {
                    val t0 = System.nanoTime()
                    val res = process(temp)
                    lastFrame = res
                    feedRecorder(res)
                    tickFps()
                    status = "${sdk.width}×${sdk.height} · frames=$fc" +
                        " · ${(System.nanoTime() - t0) / 1_000_000} ms"
                } catch (e: Throwable) {
                    Log.e(TAG, "display error", e)
                    status = "display: ${e::class.java.simpleName}: ${e.message}"
                }
            }
        }
    }

    /**
     * SDK values are already absolute temperature, so the whole domain here is °C.
     *
     * min/max are taken BEFORE any smoothing — the denoisers pull in the extremes, and
     * on this path the reading is the point.
     */
    private fun process(tempMilliC: IntArray): FrameResult {
        val w = sdk.width; val h = sdk.height
        val degC = FloatArray(tempMilliC.size) { MagDeviceWrapper.degC(tempMilliC[it]) }

        var oriented = orient(degC, w, h)      // also publishes lastCounts
        val ow: Int; val oh: Int
        if (rotation % 180 == 0) { ow = w; oh = h } else { ow = h; oh = w }

        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        var mnI = 0; var mxI = 0
        for (i in oriented.indices) {
            val v = oriented[i]
            if (v < mn) { mn = v; mnI = i }
            if (v > mx) { mx = v; mxI = i }
        }

        oriented = temporal(oriented)
        if (spatialDenoise) {
            // sigmaColor is in °C — 80 counts of range tolerance would be 80 °C here
            // and would flatten the whole scene.
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

        return FrameResult(
            bitmap = render(oriented, ow, oh, slo, shi), w = ow, h = oh,
            tempMin = mn, tempMax = mx,
            minPos = mnI, maxPos = mxI,
            fpa = sdk.sensorTemp()?.toLong(),
            scaleLoC = slo, scaleHiC = shi,
        )
    }

    /**
     * Upscale the (still scalar) field, then palette-map into a Bitmap — all on the
     * processing thread, so the UI only draws. Interpolating before colouring keeps
     * edges clean instead of blending palette colours.
     */
    private fun render(field: FloatArray, w: Int, h: Int, lo: Float, hi: Float)
            : android.graphics.Bitmap {
        val k = upscale.coerceIn(1, 4)
        if (k > 1 && upscaler == Upscaler.ANIME4K) {
            try {
                val gpu = anime4k ?: Anime4kGpu(getApplication()).also { anime4k = it }
                return gpu.render(field, w, h, lo, hi, if (k >= 4) 2 else 1,
                                  Palettes.lut(paletteName))
            } catch (e: Throwable) {
                Log.e(TAG, "Anime4K GPU failed — falling back to bicubic", e)
                runCatching { anime4k?.close() }
                anime4k = null
                upscaler = Upscaler.BICUBIC
                notice = "Anime4K GPU failed, using bicubic: ${e.message}"
            }
        }
        val src = if (k > 1) ImageOps.resizeBicubic(field, w, h, w * k, h * k) else field
        val pal = Palettes.lut(paletteName)
        val img = IntArray(src.size)
        val inv = 255f / (hi - lo)
        for (i in src.indices) {
            var t = (src[i] - lo) * inv
            if (t < 0f) t = 0f else if (t > 255f) t = 255f
            img[i] = pal[(t + 0.5f).toInt()]
        }
        return android.graphics.Bitmap.createBitmap(img, w * k, h * k,
            android.graphics.Bitmap.Config.ARGB_8888)
    }

    private var anime4k: Anime4kGpu? = null
    private val temporalFilter = TemporalDenoise()

    /** Motion-adaptive temporal IIR on the display field (history resets on orientation
     *  change). The returned array is the filter's buffer — read-only. */
    private fun temporal(field: FloatArray): FloatArray {
        if (!temporalDenoise) { temporalFilter.reset(); return field }
        temporalFilter.strength = temporalStrength
        return temporalFilter.apply(field, rotation * 2 + (if (mirror) 1 else 0))
    }

    private var fpsWindowStart = 0L
    private var fpsWindowFrames = 0

    /** Processing thread: count delivered frames, publish the rate once per second. */
    private fun tickFps() {
        val now = System.nanoTime()
        if (fpsWindowFrames == 0 && fpsWindowStart == 0L) fpsWindowStart = now
        fpsWindowFrames++
        val dt = now - fpsWindowStart
        if (dt >= 1_000_000_000L) {
            fps = fpsWindowFrames * 1e9f / dt
            fpsWindowStart = now; fpsWindowFrames = 0
        }
    }

    // ---- screenshot / video ---------------------------------------------------

    private val recLock = Any()
    private var recorder: VideoRecorder? = null

    private fun composite(fr: FrameResult) = FrameComposer.compose(
        fr, Palettes.lut(paletteName), showMaxRoi, showMinRoi, spot, spotCelsius())

    fun takeScreenshot() {
        val fr = lastFrame ?: return
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            notice = try {
                MediaSaver.savePng(ctx, composite(fr))
                    ?.let { "Snapshot saved to Pictures/MagViewer" } ?: "Snapshot failed"
            } catch (e: Throwable) {
                Log.e(TAG, "screenshot failed", e); "Snapshot failed: ${e.message}"
            }
        }
    }

    fun toggleRecording() {
        if (recording) { stopRecording(); return }
        val fr = lastFrame ?: return
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val (w, h) = FrameComposer.outSize(fr)
            try {
                synchronized(recLock) { recorder = VideoRecorder(ctx, w, h) }
                recordStartMs = System.currentTimeMillis()
                recording = true
            } catch (e: Throwable) {
                Log.e(TAG, "recorder start failed", e)
                notice = "Recording failed to start: ${e.message}"
            }
        }
    }

    fun stopRecording() {
        val r = synchronized(recLock) { recorder.also { recorder = null } } ?: return
        recording = false
        viewModelScope.launch(Dispatchers.IO) {
            val uri = r.stop()
            notice = if (uri != null)
                         "Video saved to Movies/MagViewer (${r.codecName}, ${r.frames} frames)"
                     else "Recording failed (no usable frames)"
        }
    }

    /** Called on the processing thread right after a frame is built. */
    private fun feedRecorder(fr: FrameResult) {
        synchronized(recLock) {
            val r = recorder ?: return
            val (w, h) = FrameComposer.outSize(fr)
            if (w != r.width || h != r.height) return     // rotated mid-recording: skip
            try {
                r.addFrame(composite(fr))
                return
            } catch (e: Throwable) {
                Log.e(TAG, "video frame failed", e)
                notice = "Recording stopped: ${e.message}"
            }
        }
        stopRecording()          // encoder broke — finalise what we have
    }

    // ---- measurement ----------------------------------------------------------

    fun spotCelsius(): Float? {
        val fr = lastFrame ?: return null
        val (x, y) = spot ?: return null
        if (x < 0 || y < 0 || x >= fr.w || y >= fr.h) return null
        return lastCounts?.get(y * fr.w + x)
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

    // ---- factory SDK smoke test -----------------------------------------------

    /**
     * One-shot check of the SDK path (`lib/arm64-v8a/libcoresdk.so`), kept deliberately
     * separate from the live stream: it tears the stream down, runs init → link →
     * prepare → stream → read, writes a transcript to [sdkLog], then closes its own
     * MagDevice so the normal 2 s poll can reconnect.
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
                // The live path holds the device through `sdk`, and USB is exclusive.
                if (connected) {
                    say("… disconnecting live stream first")
                    disconnect()
                    delay(1200)
                }
                val loadErr = MagDeviceWrapper.probeNativeLib()
                if (loadErr != null) {
                    say("FAIL dlopen libcoresdk.so: ${loadErr.message}")
                    return@launch
                }
                say("OK  libcoresdk.so loaded")

                val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
                val dev = usb.deviceList.values.firstOrNull { MagDeviceWrapper.isMagnity(it) }
                if (dev == null) { say("FAIL camera not present"); return@launch }
                if (!usb.hasPermission(dev)) {
                    say("FAIL no USB permission — grant it once via Connect, then retry")
                    return@launch
                }
                loop?.cancel()
                connected = false
                retryNotBefore = System.currentTimeMillis() + 60_000   // keep the poll off USB
                status = "SDK smoke test…"
                delay(750)

                wrapper.open(ctx)
                say("OK  link + prepare")
                say("    fpa=${wrapper.width}x${wrapper.height} " +
                    "bmp=${wrapper.bmpWidth}x${wrapper.bmpHeight} fps=${wrapper.fps}")
                say("    name='${wrapper.cameraName}' type='${wrapper.cameraType}'")

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
                status = "SDK test finished — see log"
                sdkBusy = false
            }
        }
    }
}
