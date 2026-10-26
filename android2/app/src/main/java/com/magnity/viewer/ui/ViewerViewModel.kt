package com.magnity.viewer.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.hardware.camera2.CameraMetadata
import android.app.LocaleManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.LocaleList
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.magnity.viewer.R
import com.magnity.viewer.camera.RgbCamera
import com.magnity.viewer.media.FrameComposer
import com.magnity.viewer.media.MediaSaver
import com.magnity.viewer.media.ThermalStream
import com.magnity.viewer.media.VideoRecorder
import com.magnity.viewer.pipeline.Anime4kGpu
import com.magnity.viewer.pipeline.ArgbImage
import com.magnity.viewer.pipeline.Fusion
import com.magnity.viewer.pipeline.FusionCalibration
import com.magnity.viewer.pipeline.ImageOps
import com.magnity.viewer.pipeline.DetailEnhance
import com.magnity.viewer.pipeline.NcnnUpscaler
import com.magnity.viewer.pipeline.Palettes
import com.magnity.viewer.pipeline.SrModelDownloader
import com.magnity.viewer.pipeline.SrTier
import com.magnity.viewer.pipeline.TemporalDenoise
import com.magnity.viewer.usb.Hik4117Source
import com.magnity.viewer.usb.MagDeviceWrapper
import com.magnity.viewer.usb.ThermalSource
import com.magnity.viewer.usb.ThermalSources
import kotlin.math.abs
import kotlin.math.exp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val emissivity: Float,        // ε in effect for this frame (stamped into captures)
    /** Oriented native grid of absolute °C, before any denoising — what SPOT reads and
     *  what the .mgt temperature file stores. Read-only: it is the pipeline's buffer. */
    val temps: FloatArray,
    /** Where the thermal grid sits inside [bitmap], normalized; null = it fills it
     *  (everything except the wide-search fusion mode). Markers and taps map through it. */
    val inset: android.graphics.RectF? = null,
    /** The visible camera's AF window in normalized [bitmap] coordinates — the patch the
     *  AUTO object distance is measured on. Null = fusion off or the crosshair is off. */
    val afBox: android.graphics.RectF? = null,
    /** AF regions the HAL reports using, in the same coordinates as [afBox]
     *  (diagnostics). Null = not reported, fusion off or the crosshair is off. */
    val afHalBoxes: List<android.graphics.RectF>? = null,
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
        /** LocationManager.FUSED_PROVIDER, which the SDK only has from Android 12. */
        private const val FUSED_PROVIDER = "fused"
        /** AUTO 1/Z smoothing factor per thermal frame (~0.3 s time constant). */
        private const val AUTO_INVZ_ALPHA = 0.15f
        /** 2 Hz ticks out of the calibrated range before the align panel opens (1.5 s). */
        private const val PROMPT_TICKS = 3
        /** Share of the previous smoothed edge map kept per visible frame — kills
         *  frame-to-frame Sobel flicker at the cost of one frame of trail. */
        private const val EDGE_IIR_KEEP = 0.5f
        /** Thermal gradient, as a fraction of the display range per pixel, at which
         *  the EDGES overlay is fully suppressed (double-edge suppression knee). */
        private const val GATE_GRAD_REL = 0.03f
        /** Sensor pixels added around the sampled patch when computing EDGES, so a small
         *  registration drift stays inside what already has history. */
        private const val EDGE_ROI_MARGIN = 8
        /** Per-frame processing stages timed for the status line, in order. */
        private val STAGES = arrayOf("in", "dn", "rng", "up", "fuse", "bmp", "rec")
        private const val ST_IN = 0; private const val ST_DN = 1; private const val ST_RNG = 2
        private const val ST_UP = 3; private const val ST_FUSE = 4; private const val ST_BMP = 5
        private const val ST_REC = 6
        /** Smoothing of the stage timings (≈ the last 10 frames). */
        private const val STAGE_EMA = 0.1f
        /** Calibration wizard: suggested distance per step (m; the first, far one also takes ∞). */
        val WIZARD_STEPS_M = floatArrayOf(3f, 1f, 0.5f, 0.3f)
        /** A wizard sample further than this from the fitted line is flagged for a redo. */
        const val WIZARD_MAX_RESIDUAL_PX = 3f
        /** Consecutive frames one press of the fusion debug dump saves. */
        private const val FUSION_DUMP_FRAMES = 10
        /** Auto range time constant: the 1–99 % range glides instead of jumping per frame. */
        private const val RANGE_TAU_S = 0.4f
        /** Default [minSpan], °C: a few times an uncooled core's noise (~50 mK), still
         *  narrow enough to show half-degree differences in the middle of the palette. */
        const val DEFAULT_MIN_SPAN = 1.0f
        /** Thermal SR speed guard: frames skipped after it loads (Vulkan warm-up), frames
         *  measured before judging, and the share of the camera's rate it must keep. */
        private const val SR_WARMUP_FRAMES = 25
        private const val SR_CHECK_FRAMES = 50
        private const val SR_MIN_RATE_SHARE = 0.65f
    }

    enum class Upscaler { BICUBIC, ANIME4K, NCNN }

    /** The connected camera's source; swapped in [connect] when another kind is plugged in. */
    @Volatile var sdk: ThermalSource = MagDeviceWrapper(); private set

    // ---- UI state ---------------------------------------------------------------

    /** UI text in the app's current language. Status and notices are built with it, so a
     *  line already on screen stays in the old language until it is next replaced. */
    fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    private fun plural(@PluralsRes id: Int, n: Int, vararg args: Any): String =
        getApplication<Application>().resources.getQuantityString(id, n, *args)

    // Per-app language (Android 13+ LocaleManager): the system stores it, shows it under
    // Settings → Apps → Language too, and applies it without restarting the activity.
    // "" = follow the system — the only choice before Android 13, where the drawer hides it.
    val languageSelectable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var appLanguage by mutableStateOf(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            app.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        else ""); private set

    fun setLanguage(tag: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        getApplication<Application>().getSystemService(LocaleManager::class.java)
            .applicationLocales = LocaleList.forLanguageTags(tag)
        appLanguage = tag
    }

    var status by mutableStateOf(str(R.string.st_disconnected)); private set
    var connected by mutableStateOf(false); private set
    var paused by mutableStateOf(false)

    // User settings persist across runs (SharedPreferences); runtime state below does not.
    private val prefs = app.getSharedPreferences("viewer_settings", Context.MODE_PRIVATE)
    private fun pref(key: String, def: Boolean, onSet: (Boolean) -> Unit = {}) =
        Persisted(prefs, key, def, { k, d -> getBoolean(k, d) }, { k, v -> putBoolean(k, v) },
                  onSet = onSet)
    private fun pref(key: String, def: Int) =
        Persisted(prefs, key, def, { k, d -> getInt(k, d) }, { k, v -> putInt(k, v) })
    private fun pref(key: String, def: Float, skipWrite: () -> Boolean = { false },
                     onSet: (Float) -> Unit = {}) =
        Persisted(prefs, key, def, { k, d -> getFloat(k, d) }, { k, v -> putFloat(k, v) },
                  skipWrite, onSet)

    var paletteName by Persisted(prefs, "palette", Palettes.NAMES.first(),
        { k, d -> getString(k, d)?.takeIf { it in Palettes.NAMES } ?: d },
        { k, v -> putString(k, v) })
    var autoScale: Boolean by pref("auto_scale", true) { auto ->
        // the auto range is kept in memory only; save it when it becomes the manual range
        if (!auto) prefs.edit().putFloat("scale_lo", scaleLo).putFloat("scale_hi", scaleHi).apply()
    }
    // Module spec measurement range — the SDK's CameraInfo does not report one, so the
    // manual scale slider spans this fixed domain.
    val tempMinC = -20f
    val tempMaxC = 150f
    var scaleLo: Float by pref("scale_lo", 0f, skipWrite = { autoScale })   // manual display range, °C
    var scaleHi: Float by pref("scale_hi", 100f, skipWrite = { autoScale })
    /** Auto range is never narrower than this (°C); see [ImageOps.autoRange]. */
    var minSpan: Float by pref("min_span", DEFAULT_MIN_SPAN)
    // default = camera plugged straight into the phone's USB-C port, portrait UI
    var rotation by pref("rotation", 90)            // 0/90/180/270 clockwise
    var mirror by pref("mirror", true)
    var upscale by pref("upscale", 4)               // display upscale 1/2/4 (4 → 640×480)
    var upscaler by Persisted(prefs, "upscaler", Upscaler.ANIME4K,
        { k, d -> runCatching { Upscaler.valueOf(getString(k, d.name)!!) }.getOrDefault(d) },
        { k, v -> putString(k, v.name) },
        // picking Thermal SR again after the speed guard switched it off = keep it, slow or not
        onSet = { if (it == Upscaler.NCNN && srSwitchedForSpeed) srKeepSlow = true })
    /** The user chose Thermal SR over frame rate; the speed guard stays out of it. */
    var srKeepSlow by pref("sr_keep_slow", false)
    /** Thermal SR model size the user picked ([chooseSrTier]); a lighter one runs cooler.
     *  When none of that size is installed, the full model runs. */
    var srTier by Persisted(prefs, "sr_tier", SrTier.FULL,
        { k, d -> runCatching { SrTier.valueOf(getString(k, d.name)!!) }.getOrDefault(d) },
        { k, v -> putString(k, v.name) },
        onSet = { ncnnReload = true }); private set

    /** The drawer's pick; after the speed guard stepped down a size, picking again = keep it. */
    fun chooseSrTier(t: SrTier) {
        if (srSteppedForSpeed) srKeepSlow = true
        srTier = t
    }
    var spatialDenoise by pref("spatial_denoise", true)
    /**
     * The thermal SR model was trained on noisy, FPN-degraded input and denoises on its
     * own; a bilateral pass in front of it only removes detail the model reconstructs from
     * and costs frame time. Spatial denoise is skipped while this is true — the saved
     * setting is untouched, so it comes back with Anime4K / bicubic. Temporal denoise
     * stays: a single-frame model cannot remove frame-to-frame flicker.
     */
    val thermalSrActive: Boolean get() = upscaler == Upscaler.NCNN && upscale > 1
    var temporalDenoise by pref("temporal_denoise", true)   // display only — readouts stay per-frame
    var temporalStrength by pref("temporal_strength", 0.85f)
    /** DDE on the display ([DetailEnhance]); palette no longer linear in °C while on. */
    var detailEnhance by pref("detail_enhance", false)
    var showMaxRoi by pref("show_max", true)
    var showMinRoi by pref("show_min", true)
    var showCenter by pref("show_center", true)
    /** Target emissivity handed to the SDK, (0, 1]; applied on connect and on change. */
    var emissivity: Float by pref("emissivity", 1.0f, onSet = { applyEmissivity() })
    /** Write the phone's location into snapshots and videos (needs precise location). */
    var geotag: Boolean by pref("geotag", false) { updateLocationUpdates() }
    /** Latest fix while [geotag] is on; null = none yet. */
    var location by mutableStateOf<Location?>(null); private set
    // the compat listener: before Android 11 LocationListener's other callbacks are abstract,
    // and a bare lambda dies with AbstractMethodError when the system calls one
    private val locationListener = LocationListenerCompat { location = it }
    private var locationActive = false

    // ---- visible-camera fusion (beta) ----
    var fusionOn: Boolean by pref("fusion_on", false) { updateFusionCamera() }
    var fusionMode by Persisted(prefs, "fusion_mode", Fusion.Mode.EDGES,
        { k, d -> runCatching { Fusion.Mode.valueOf(getString(k, d.name)!!) }.getOrDefault(d) },
        { k, v -> putString(k, v.name) })
    var fusionStrength by pref("fusion_strength", 0.6f)
    // manual registration: the visible FOV (~75°) is wider than the thermal lens (~50°)
    var fusionZoom by pref("fusion_zoom", 1.6f)
    var fusionDx by pref("fusion_dx", 0f)
    var fusionDy by pref("fusion_dy", 0f)
    var fusionRotation by pref("fusion_rotation", 90)   // visible sensor → portrait
    /** The align sliders replace the range/action rows while this is on. */
    var fusionAligning by mutableStateOf(false)

    // ---- distance-compensated registration ----
    /** Where the object distance comes from; AUTO degrades to MANUAL while the
     *  lens focus distance is unreported or untrusted (see [effectiveDistanceSource]). */
    var fusionDistanceSource by Persisted(prefs, "fusion_dist_src",
        FusionCalibration.DistanceSource.MANUAL,
        { k, d -> runCatching {
            FusionCalibration.DistanceSource.valueOf(getString(k, d.name)!!) }.getOrDefault(d) },
        { k, v -> putString(k, v.name) })
    /** Manual slider position, linear in 1/Z over 0…[FusionCalibration.MAX_INVZ]. */
    var fusionManualInvZ by pref("fusion_manual_invz", 1f / 3f)
    /** Saved alignments at known distances, as a small JSON string. */
    var fusionCalibJson: String by Persisted(prefs, "fusion_calib", "",
        { k, d -> getString(k, d) ?: d }, { k, v -> putString(k, v) },
        onSet = { FusionCalibration.decodeSamples(it).let { ss ->
            calibFit = FusionCalibration.fit(ss); focusMap = FusionCalibration.FocusMap(ss) } })
    var calibSamples by mutableStateOf(FusionCalibration.decodeSamples(fusionCalibJson))
        private set
    @Volatile private var calibFit = FusionCalibration.fit(calibSamples)
    /** Fit over [calibSamples]; not [FusionCalibration.Fit.usable] until the first sample. */
    val calibFitState: FusionCalibration.Fit get() = calibFit
    /** Raw lens reading → 1/Z learned from the samples (UNCALIBRATED lenses). */
    @Volatile var focusMap = FusionCalibration.FocusMap(calibSamples); private set
    /** Offer to re-align when the focus leaves the calibrated range (AUTO). */
    var fusionCalibPrompt: Boolean by pref("fusion_calib_prompt", true)
    /** [ThermalSource.kind] of the last camera connected; the registration above
     *  (zoom / offset / samples) belongs to it — see [switchCameraProfile]. */
    var cameraKind by Persisted(prefs, "camera_kind", "mag160",
        { k, d -> getString(k, d) ?: d }, { k, v -> putString(k, v) })
    /** Draw the visible camera's AF window over the fused image. */
    var fusionCrosshair: Boolean by pref("fusion_crosshair", true)
    /** Snap/Rec also write the per-pixel temperatures as a .mgt file. */
    var saveThermalData: Boolean by pref("save_thermal_data", false)
    /** Timing overlay on the thermal image (the main screen's Debug button). */
    var debugOsd: Boolean by pref("debug_osd", false)
    /** The align panel is open because of that prompt (not the drawer button). */
    var calibPromptActive by mutableStateOf(false); private set
    private var calibPromptSnoozed = false
    private var outOfRangeTicks = 0
    /** Distance currently driving the overlay ("≈3.0 m" / "∞"), updated per frame in fuse(). */
    var fusionDistanceLabel by mutableStateOf("∞"); private set

    // RGB focus diagnostics, mirrored from RgbCamera (~2 Hz) for the drawer line.
    var focusCalibration by mutableStateOf<Int?>(null); private set
    var focusDiopters by mutableStateOf<Float?>(null); private set
    var afActive by mutableStateOf(false); private set
    var afScanning by mutableStateOf(false); private set
    /** The centred AF window is in force (else the HAL meters the whole frame). */
    var afCentreRegion by mutableStateOf(false); private set
    /** Last CONTROL_AF_STATE, null = not reported. */
    var afState by mutableStateOf<Int?>(null); private set
    /** How many weighted AF regions the HAL reports; 0 = whole frame, null = not reported. */
    var halAfRegionCount by mutableStateOf<Int?>(null); private set
    /** Physical lens behind the logical camera (null = not reported) and how often it changed. */
    var activePhysicalId by mutableStateOf<String?>(null); private set
    var lensSwitches by mutableStateOf(0); private set
    private val rgbCameraLazy = lazy { RgbCamera(getApplication()) }
    private val rgbCamera by rgbCameraLazy
    private var appVisible = true

    var spot: Pair<Int, Int>? by mutableStateOf(null)   // oriented (x,y), null = none

    var lastFrame by mutableStateOf<FrameResult?>(null); private set
    var ffcBusy by mutableStateOf(false); private set
    var sdkBusy by mutableStateOf(false); private set
    var fps by mutableFloatStateOf(0f); private set     // processed (= displayed) frames/s
    var recording by mutableStateOf(false); private set
    var recordStartMs by mutableStateOf(0L); private set
    /** transient user message (screenshot saved, …); the UI clears it */
    var notice by mutableStateOf<String?>(null)
    /** Per-stage timing text for the debug overlay; only built while [debugOsd] is on. */
    var debugInfo by mutableStateOf(""); private set
    /** Transcript of the last [runSdkSmokeTest], or the HIK 4117 diagnostic report (kept
     *  current while that camera is connected); null = neither. */
    var sdkLog by mutableStateOf<String?>(null); private set
    /** Open temperature capture (.mgt) being reviewed; null = live view. */
    var playback by mutableStateOf<ThermalPlayback?>(null); private set
    // above init, which loads them on Dispatchers.IO (see `connecting` below)
    /** Imported Thermal SR models: input sizes ("120x160", …) per model size; empty = only
     *  the APK's own. */
    var srImported by mutableStateOf<Map<SrTier, List<String>>>(emptyMap()); private set
    /** Model sizes installed (bundled or imported) — the ones the drawer lets you pick. */
    var srTiers by mutableStateOf(setOf(SrTier.FULL)); private set

    private var loop: Job? = null

    // Declared ABOVE init: init launches the auto-connect poll on Dispatchers.IO, which can
    // run before the constructor reaches properties declared further down — `connecting`
    // was still null there (NPE on launch, only on some starts).
    private val connecting = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var retryNotBefore = 0L        // cooldown after a failed open
    @Volatile private var lastDevId: String? = null   // detect replug (re-enumeration)
    /** A UVC device under an unknown id that failed to open or stream as a Tiny2C (a
     *  webcam, say): skipped by the poll until it is replugged (new deviceId). */
    @Volatile private var rejectedCandidate: String? = null
    @Volatile private var currentDevice: UsbDevice? = null

    /** One attached USB device, for the list shown while no camera streams. */
    data class UsbEntry(val id: String, val label: String?, val name: String?)
    /** Everything on the USB bus, refreshed by the 2 s poll. */
    var usbDevices by mutableStateOf<List<UsbEntry>>(emptyList()); private set
    /** "0A0A:2602 · Tiny2C" of the streaming camera; null when none. */
    var connectedUsb by mutableStateOf<String?>(null); private set

    private fun devKey(d: UsbDevice) = "${d.vendorId}:${d.productId}:${d.deviceId}"

    /** VID/PID pairs a Tiny2C has streamed from, "0a0a:2602,…" (see [ThermalSources.learned]). */
    private var tiny2cIds by Persisted(prefs, "tiny2c_ids", "",
        { k, d -> getString(k, d) ?: d }, { k, v -> putString(k, v) })

    init {
        // permission may have been revoked in system settings since the last run
        if (geotag && !hasLocationPermission()) geotag = false else updateLocationUpdates()
        if (fusionOn && !hasCameraPermission()) fusionOn = false else updateFusionCamera()
        refreshSrModelInfo()

        ThermalSources.learned = parseUsbIds(tiny2cIds)

        // Poll-based auto-connect — USB attach broadcasts are unreliable on newer
        // Androids; scanning UsbManager every 2 s always works.
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            while (isActive) {
                val all = usb.deviceList.values.toList()
                usbDevices = all.map { d ->
                    UsbEntry(ThermalSources.idText(d), ThermalSources.label(d),
                             runCatching { d.productName }.getOrNull())
                }
                if (!connected && !connecting.get() && System.currentTimeMillis() >= retryNotBefore) {
                    val dev = all.firstOrNull { ThermalSources.isKnown(it) }
                        ?: all.firstOrNull {
                            ThermalSources.isCandidate(it) && devKey(it) != rejectedCandidate
                        }
                    if (dev != null) {
                        val devId = devKey(dev)
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

        // Mirror the RGB focus diagnostics into Compose state (~2 Hz) so the drawer
        // line updates without the camera executor touching UI state itself.
        viewModelScope.launch {
            while (isActive) {
                if (rgbCameraLazy.isInitialized()) {
                    focusCalibration = rgbCamera.focusCalibration
                    focusDiopters = rgbCamera.focusDiopters
                    afActive = rgbCamera.afActive
                    afScanning = rgbCamera.afScanning
                    afCentreRegion = rgbCamera.afCentreRegion
                    afState = rgbCamera.afState
                    halAfRegionCount = rgbCamera.halAfRegions?.size
                    activePhysicalId = rgbCamera.activePhysicalId
                    lensSwitches = rgbCamera.lensSwitches
                    checkCalibRange()
                }
                delay(500)
            }
        }
    }

    // ---- connection -----------------------------------------------------------


    fun connect(device: UsbDevice, permissionGranted: Boolean) {
        if (!ThermalSources.isCandidate(device)) return
        if (!connecting.compareAndSet(false, true)) {
            Log.i(TAG, "connect already in progress — skipping")
            return
        }
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                status = str(R.string.st_opening)
                sdk = ThermalSources.forDevice(device, sdk)
                switchCameraProfile(sdk.kind)
                sdk.open(ctx) { p -> status = str(R.string.st_cali_download, p) }
                sdk.start()
                if (!sdk.setEmissivity(emissivity)) Log.w(TAG, "emissivity not applied on connect")
                connected = true
                lastDevId = devKey(device)
                currentDevice = device
                connectedUsb = ThermalSources.idText(device) + " · " + when (sdk) {
                    is MagDeviceWrapper -> "MAG160"
                    is Hik4117Source -> "HIK 4117"
                    else -> "Tiny2C"
                }
                (sdk as? Hik4117Source)?.let { sdkLog = it.report() }
                status = str(R.string.st_connected, sdk.width, sdk.height)
                startSdkLoop()
            } catch (e: Throwable) {
                Log.e(TAG, "connect failed", e)
                status = str(R.string.st_connect_failed,
                             "${ThermalSources.idText(device)}: ${e.message ?: ""}")
                runCatching { sdk.close() }
                connected = false
                if (!ThermalSources.isKnown(device)) rejectedCandidate = devKey(device)
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
        // Hold the connect gate until close() finishes, or the 2 s poll can start a new
        // open() while the old link is still being torn down.
        val gated = connecting.compareAndSet(false, true)
        viewModelScope.launch(Dispatchers.IO) {
            try { runCatching { sdk.close() } } finally { if (gated) connecting.set(false) }
        }
        connected = false
        connectedUsb = null
        lastFrame = null
        fps = 0f; fpsWindowStart = 0L; fpsWindowFrames = 0
        status = str(R.string.st_disconnected)
    }

    /** Drop the dead camera and let the 2 s poll reconnect (same devId retains USB
     *  permission, so no re-prompt). Used when the stream stalls without a clean
     *  re-enumeration — otherwise the loop would freeze on the last frame forever. */
    private fun recover() {
        Log.w(TAG, "stream recover: close SDK, poll will reconnect")
        runCatching { sdk.close() }
        connected = false
        connectedUsb = null
        // an unknown UVC device that never streamed is not a Tiny2C — don't loop on it
        currentDevice?.takeIf { !ThermalSources.isKnown(it) }?.let {
            rejectedCandidate = devKey(it)
            status = str(R.string.st_connect_failed, "${ThermalSources.idText(it)}: no frames")
        }
        retryNotBefore = System.currentTimeMillis() + 2000
    }

    /** A USB permission step that ended before [connect] (shown on the waiting screen). */
    fun reportUsb(message: String) {
        if (!connected) status = message
    }

    /** Manual connect: scan for the camera, reuse remembered permission or ask. */
    fun requestConnect() {
        val ctx = getApplication<Application>()
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        // a UVC device under an unknown id may still be a Tiny2C — tried only from here
        val dev = usb.deviceList.values.firstOrNull { ThermalSources.isKnown(it) }
            ?: usb.deviceList.values.firstOrNull { ThermalSources.isCandidate(it) } ?: run {
            status = str(R.string.st_not_found)
            return
        }
        if (usb.hasPermission(dev)) {
            connect(dev, true)
        } else {
            status = str(R.string.st_requesting_usb)
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
                val ok = sdk.triggerFfc()
                temporalFilter.requestReset()   // don't blend pre-FFC frames into the new NUC
                status = str(if (ok) R.string.st_ffc_done else R.string.st_ffc_failed)
            } finally {
                ffcBusy = false
            }
        }
    }

    private var emissivityJob: Job? = null

    /** Slider drags fire on every step — push only the value it settles on. */
    private fun applyEmissivity() {
        emissivityJob?.cancel()
        emissivityJob = viewModelScope.launch(Dispatchers.IO) {
            delay(150)
            if (connected && !sdk.setEmissivity(emissivity)) notice = str(R.string.n_emis_not_applied)
        }
    }

    // ---- geo-tag --------------------------------------------------------------

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Listen for fixes only while geo-tagging is on; a listener on the main executor just
     *  stores the fix, so nothing touches the processing loop. */
    @SuppressLint("MissingPermission")
    private fun updateLocationUpdates() {
        val ctx = getApplication<Application>()
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        val want = geotag && hasLocationPermission()
        if (want == locationActive) return
        if (!want) {
            removeLocationUpdates(lm)
            locationActive = false
            location = null
            return
        }
        // "fused" is a platform provider from Android 12; older phones may still list one
        val providers = if (LocationManagerCompat.hasProvider(lm, FUSED_PROVIDER))
            listOf(FUSED_PROVIDER)
        else listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { LocationManagerCompat.hasProvider(lm, it) }
        val req = LocationRequestCompat.Builder(10_000)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateDistanceMeters(5f)
            .build()
        try {
            location = providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            providers.forEach {
                LocationManagerCompat.requestLocationUpdates(lm, it, req, ctx.mainExecutor, locationListener)
            }
            locationActive = true
        } catch (e: Exception) {
            Log.e(TAG, "location updates failed", e)
            notice = str(R.string.n_location_unavailable, e.message ?: "")
        }
    }

    /** Removing a listener needs no location permission (the platform call asks for
     *  none); the compat wrapper is only annotated as if it did. */
    @SuppressLint("MissingPermission")
    private fun removeLocationUpdates(lm: LocationManager) {
        runCatching { LocationManagerCompat.removeUpdates(lm, locationListener) }
    }

    /** The fix to embed in a capture: null when geo-tag is off or the fix is over 10 min old. */
    private fun captureLocation(): Location? =
        location?.takeIf { geotag && System.currentTimeMillis() - it.time < 10 * 60_000 }

    // ---- visible-camera fusion ------------------------------------------------

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** Main thread. The phone camera runs only while fusion is on AND the app is in the
     *  foreground — Android revokes background camera access anyway. */
    private fun updateFusionCamera() {
        if (!fusionOn) fusionAligning = false
        val want = fusionOn && appVisible && hasCameraPermission()
        if (want == (rgbCameraLazy.isInitialized() && rgbCamera.running)) return
        if (want) {
            rgbCamera.start { msg -> fusionOn = false; notice = str(R.string.n_visible_failed, msg) }
        } else {
            rgbCamera.stop()
            edgeSmooth = null
            edgeRoi = null
        }
    }

    /** From the Activity's onStart / onStop. */
    fun onAppVisible(visible: Boolean) {
        appVisible = visible
        updateFusionCamera()
    }

    // ---- distance compensation --------------------------------------------------

    /** The lens focus distance is trustworthy only at these calibration levels. */
    fun focusTrusted(): Boolean =
        focusCalibration == CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE ||
        focusCalibration == CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED

    /** AUTO can drive the overlay whenever the lens reports a focus reading. */
    private fun autoDistanceReady(): Boolean = focusDiopters != null

    /** The lens reading is used as diopters directly: a trusted lens, or an UNCALIBRATED
     *  one (often close in practice) until [focusMap] has learned from 2+ alignments. */
    fun focusRawAsDiopters(): Boolean = focusTrusted() || !focusMap.usable

    /** Lens reading → 1/Z: as-is (see [focusRawAsDiopters]), else through [focusMap]. */
    private fun focusToInvZ(d: Float): Float =
        if (focusRawAsDiopters()) d.coerceAtLeast(0f) else focusMap.invZAt(d)

    /** Distance source actually driving the overlay: AUTO falls back to MANUAL
     *  while the focus distance is unreported. */
    fun effectiveDistanceSource(): FusionCalibration.DistanceSource =
        if (fusionDistanceSource == FusionCalibration.DistanceSource.AUTO && !autoDistanceReady())
            FusionCalibration.DistanceSource.MANUAL
        else fusionDistanceSource

    /** Display text for an inverse distance: "∞", "1.5 m", "10 m". */
    fun distanceText(invZ: Float): String {
        if (invZ <= 1e-4f) return "∞"
        val z = 1f / invZ
        return if (z >= 10f) "%.0f m".format(z) else "%.1f m".format(z)
    }

    /** Save the current slider alignment as a sample at 1/invZ; the same distance
     *  (within 1 cm⁻¹… tolerance) replaces its previous sample. */
    fun addCalibSample(invZ: Float, zoom: Float, dx: Float, dy: Float) {
        val rest = calibSamples.filter { kotlin.math.abs(it.invZ - invZ) > 1e-4f }
        // record the lens reading too: it teaches AUTO the focus → distance map
        calibSamples = (rest + FusionCalibration.CalibSample(invZ, zoom, dx, dy, checkedFocus(invZ, rest)))
            .sortedByDescending { it.invZ }
        fusionCalibJson = FusionCalibration.encodeSamples(calibSamples)
        calibPromptActive = false
    }

    /** Distance the Save dialog should suggest from the lens; null = no reading. */
    fun suggestedInvZ(): Float? = focusDiopters?.let { focusToInvZ(it) }

    /** The align panel's Done. Leaving a prompt without saving snoozes it until the
     *  focus comes back into the calibrated range. */
    fun endAligning() {
        if (calibPromptActive) calibPromptSnoozed = true
        calibPromptActive = false
        fusionAligning = false
    }

    /** The prompt's Align button: seed the sliders from the fit's guess at the current
     *  distance — only a nudge should be needed — and open the panel. */
    fun startPromptedAlign() {
        val f = calibFit
        if (f.usable) {
            val d = focusDiopters
            val iz = if (d != null && autoDistanceReady()) focusToInvZ(d) else fusionManualInvZ
            val (z, x, y) = f.at(iz)
            fusionZoom = z; fusionDx = x; fusionDy = y
        }
        fusionAligning = true
    }

    /** The prompt's dismiss: stay as we are until the focus is back in range. */
    fun dismissCalibPrompt() {
        calibPromptSnoozed = true
        calibPromptActive = false
    }

    /** ~2 Hz (main thread): raise the "align?" offer when the AUTO object distance sits
     *  more than [FusionCalibration.RANGE_TOL_M] outside the saved distances for
     *  [PROMPT_TICKS] ticks. The panel is NOT opened — the offer is a button over the
     *  image, so a passing focus change can't interrupt what you are looking at. */
    private fun checkCalibRange() {
        val d = focusDiopters
        if (!fusionOn || fusionDistanceSource != FusionCalibration.DistanceSource.AUTO ||
            d == null || afScanning) { outOfRangeTicks = 0; return }
        // compare in 1/Z: on an untrusted lens the reading goes through the learned map
        if (FusionCalibration.inRange(focusToInvZ(d), calibSamples.map { it.invZ })) {
            outOfRangeTicks = 0
            calibPromptSnoozed = false
            calibPromptActive = false
            return
        }
        if (!fusionCalibPrompt || calibPromptSnoozed || fusionAligning) return
        if (++outOfRangeTicks < PROMPT_TICKS) return
        outOfRangeTicks = 0
        calibPromptActive = true
    }

    private fun parseUsbIds(s: String): Set<Pair<Int, Int>> = s.split(',').mapNotNull { e ->
        val (v, p) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        (v.toIntOrNull(16) ?: return@mapNotNull null) to (p.toIntOrNull(16) ?: return@mapNotNull null)
    }.toSet()

    /** First frame from a Tiny2C under an id not in the list: remember it, so the next
     *  plug-in connects on its own. */
    private fun learnTiny2c() {
        val d = currentDevice ?: return
        if (sdk !is com.magnity.viewer.usb.Tiny2cSource || ThermalSources.isKnown(d)) return
        val learned = ThermalSources.learned + (d.vendorId to d.productId)
        ThermalSources.learned = learned
        tiny2cIds = learned.joinToString(",") { "%04x:%04x".format(it.first, it.second) }
        Log.i(TAG, "learned Tiny2C id ${ThermalSources.idText(d)}")
    }

    /**
     * Another kind of camera than last time: each lens sits elsewhere on the phone and has
     * its own field of view, so park this camera's registration under `<key>@<kind>` and
     * bring back the new camera's (the defaults on its first connect).
     */
    private fun switchCameraProfile(kind: String) {
        val old = cameraKind
        if (kind == old) return
        prefs.edit()
            .putFloat("fusion_zoom@$old", fusionZoom)
            .putFloat("fusion_dx@$old", fusionDx)
            .putFloat("fusion_dy@$old", fusionDy)
            .putString("fusion_calib@$old", fusionCalibJson)
            .apply()
        fusionZoom = prefs.getFloat("fusion_zoom@$kind", 1.6f)
        fusionDx = prefs.getFloat("fusion_dx@$kind", 0f)
        fusionDy = prefs.getFloat("fusion_dy@$kind", 0f)
        fusionCalibJson = prefs.getString("fusion_calib@$kind", "") ?: ""
        calibSamples = FusionCalibration.decodeSamples(fusionCalibJson)
        cameraKind = kind
        Log.i(TAG, "camera profile $old → $kind (${calibSamples.size} calibration samples)")
    }

    // ---- calibration file -------------------------------------------------------

    fun exportCalibration(uri: android.net.Uri) {
        val text = FusionCalibration.encodeFile(FusionCalibration.CalibFile(
            fusionRotation, fusionZoom, fusionDx, fusionDy, calibSamples))
        viewModelScope.launch(Dispatchers.IO) {
            notice = runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!
                    .use { it.write(text.toByteArray()) }
                plural(R.plurals.n_calib_exported, calibSamples.size, calibSamples.size)
            }.getOrElse { str(R.string.n_export_failed, it.message ?: "") }
        }
    }

    fun importCalibration(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val f = runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)!!
                    .use { it.readBytes().decodeToString() }
            }.getOrNull()?.let { FusionCalibration.decodeFile(it) }
            withContext(Dispatchers.Main) {
                if (f == null) { notice = str(R.string.n_not_calib); return@withContext }
                fusionRotation = f.rotation
                fusionZoom = f.zoom; fusionDx = f.dx; fusionDy = f.dy
                calibSamples = f.samples.sortedByDescending { it.invZ }
                fusionCalibJson = FusionCalibration.encodeSamples(calibSamples)
                notice = plural(R.plurals.n_calib_imported, f.samples.size, f.samples.size)
            }
        }
    }

    fun deleteCalibSample(index: Int) {
        calibSamples = calibSamples.filterIndexed { i, _ -> i != index }
        fusionCalibJson = FusionCalibration.encodeSamples(calibSamples)
    }

    fun clearCalibSamples() {
        calibSamples = emptyList()
        fusionCalibJson = FusionCalibration.encodeSamples(calibSamples)
    }

    /** The lens reading to store with a sample at [invZ], or null (with a notice) when it
     *  contradicts the readings already in [others] — see [FusionCalibration.focusConsistent]. */
    private fun checkedFocus(invZ: Float, others: List<FusionCalibration.CalibSample>): Float? {
        val d = focusDiopters ?: return null
        if (FusionCalibration.focusConsistent(d, invZ, others)) return d
        notice = str(R.string.n_focus_dropped, distanceText(invZ))
        return null
    }

    // ---- calibration wizard (PLAN_CALIBRATION.md, B) ------------------------------

    /** Wizard page: null = not running, 0 = intro, 1..[WIZARD_STEPS_M].size = one distance
     *  each, past that = the summary. */
    var wizardStep by mutableStateOf<Int?>(null); private set
    /** Samples taken in this run, by step; the saved calibration changes only on finish. */
    var wizardTaken by mutableStateOf(mapOf<Int, FusionCalibration.CalibSample>()); private set
    private var wizardBackup: Triple<Float, Float, Float>? = null

    /** Starting zoom from the two lenses' fields of view (the phone reports its own; see
     *  [ThermalSource.focalPx]); null = the phone camera doesn't report its optics. */
    fun estimatedZoom(): Float? {
        val (tx, ty) = rgbCamera.fovTan ?: return null
        val ow = lastFrame?.w ?: return null           // oriented thermal width
        // the upright frame's width runs along the sensor's y when it is mounted 90°/270°
        val vis = if (fusionRotation == 90 || fusionRotation == 270) ty else tx
        return (vis / (ow / 2f / sdk.focalPx)).takeIf { it in 1f..3f }
    }

    fun startWizard() {
        wizardBackup = Triple(fusionZoom, fusionDx, fusionDy)
        wizardTaken = emptyMap()
        fusionAligning = false
        wizardStep = 0
    }

    /** Intro → first distance: zoom from the optics, offsets from the old calibration if any. */
    fun wizardBegin() {
        estimatedZoom()?.let { fusionZoom = it }
        calibFit.takeIf { it.usable }?.let { f -> fusionDx = f.dxAt(0f); fusionDy = f.dyAt(0f) }
        fusionAligning = true
        wizardStep = 1
    }

    /** The distance for this step was entered: pre-set X/Y from what is known so far, so
     *  only a nudge is left — the line once two distances exist, else the far sample. */
    fun wizardSeed(invZ: Float) {
        val ss = wizardTaken.values.toList()
        when {
            ss.map { it.invZ }.distinct().size >= 2 ->
                FusionCalibration.fit(ss).let { fusionDx = it.dxAt(invZ); fusionDy = it.dyAt(invZ) }
            ss.isNotEmpty() -> { fusionDx = ss[0].dx; fusionDy = ss[0].dy }
        }
    }

    /** Save the current alignment for this step at the measured [invZ]; ZOOM stays from the
     *  first step on, so every sample shares it. */
    fun wizardSave(invZ: Float) {
        val step = wizardStep ?: return
        val others = wizardTaken.filterKeys { it != step }
        val s = FusionCalibration.CalibSample(invZ, fusionZoom, fusionDx, fusionDy,
                                              checkedFocus(invZ, others.values.toList()))
        wizardTaken = others + (step to s)
        if (s.focusD != null || focusDiopters == null) notice = str(R.string.n_wizard_saved, distanceText(invZ))
        wizardAdvance()
    }

    fun wizardSkip() = wizardAdvance()

    /** Go to the next step that has no sample yet, else the summary. */
    private fun wizardAdvance() {
        val n = WIZARD_STEPS_M.size
        val next = (1..n).firstOrNull { it !in wizardTaken && it > (wizardStep ?: 0) }
        wizardStep = next ?: (n + 1)
        fusionAligning = next != null
    }

    /** Summary → take one step again. */
    fun wizardRedo(step: Int) {
        wizardTaken = wizardTaken - step
        wizardStep = step
        fusionAligning = true
    }

    /** Replace the calibration with this run's samples and let the lens drive the distance. */
    fun wizardFinish() {
        val ss = wizardTaken.values.sortedByDescending { it.invZ }
        if (ss.map { it.invZ }.distinct().size < 2) return
        calibSamples = ss
        fusionCalibJson = FusionCalibration.encodeSamples(calibSamples)
        fusionDistanceSource = FusionCalibration.DistanceSource.AUTO
        calibPromptActive = false
        wizardEnd()
        notice = str(R.string.n_wizard_done)
    }

    fun wizardCancel() {
        wizardBackup?.let { (z, x, y) -> fusionZoom = z; fusionDx = x; fusionDy = y }
        wizardEnd()
    }

    private fun wizardEnd() {
        wizardStep = null
        wizardTaken = emptyMap()
        wizardBackup = null
        fusionAligning = false
    }

    // exponential smoothing of the AUTO 1/Z so AF hunting doesn't jitter the overlay
    private var autoInvZ = 0f
    private var lastDistSrc: FusionCalibration.DistanceSource? = null

    /** Inverse distance in effect this frame (processing thread). */
    private fun currentInvZ(): Float {
        val src = effectiveDistanceSource()
        if (src != lastDistSrc) {           // seed the filter on entry — no jump
            lastDistSrc = src
            if (src == FusionCalibration.DistanceSource.AUTO)
                autoInvZ = rgbCamera.focusDiopters?.let { focusToInvZ(it) } ?: 0f
        }
        return when (src) {
            FusionCalibration.DistanceSource.INFINITY -> 0f
            FusionCalibration.DistanceSource.MANUAL -> fusionManualInvZ
            FusionCalibration.DistanceSource.AUTO -> {
                // trusted diopters are already 1/Z in m⁻¹ (0 = ∞); else learned map
                val d = rgbCamera.focusDiopters
                if (d != null && d >= 0f) autoInvZ += AUTO_INVZ_ALPHA * (focusToInvZ(d) - autoInvZ)
                autoInvZ
            }
        }
    }

    /** The registration in effect this frame: the fit evaluated at [invZ] once
     *  samples exist, else the manual sliders (also while aligning, so the
     *  sliders move the overlay live). */
    private data class Registration(val zoom: Float, val dx: Float, val dy: Float)

    private fun registration(invZ: Float): Registration {
        if (fusionAligning) return Registration(fusionZoom, fusionDx, fusionDy)
        val f = calibFit
        return if (f.usable) f.at(invZ).let { (z, x, y) -> Registration(z, x, y) }
               else Registration(fusionZoom, fusionDx, fusionDy)
    }

    /** Visible frame, registration and 1/Z the last [fuse] used (processing thread). */
    private var lastFuse: Triple<RgbCamera.LumaFrame, Registration, Float>? = null

    // ---- fusion debug dump (PLAN_TRIANGULATION.md, B1) ----------------------------

    /** Frames of the current burst still to write; set from the UI, counted down by the processing loop. */
    @Volatile private var fusionDumpLeft = 0
    private var fusionDumpStamp = ""
    var fusionDumpCount by mutableStateOf(0); private set

    fun requestFusionDump() { if (fusionDumpLeft == 0) fusionDumpLeft = FUSION_DUMP_FRAMES }

    /**
     * Processing thread: the raw oriented thermal field (°C), the same field after the
     * temporal/spatial denoisers, the visible frame of the same fuse() turned upright by
     * the mounting rotation, and the registration in effect, as
     * `<files>/fusion_dump/fd_<burst time>_<k>.{f32,dn.f32,u8,json}` for offline matching.
     */
    private fun dumpFusion(raw: FloatArray, dn: FloatArray, ow: Int, oh: Int) {
        val (fr, reg, invZ) = lastFuse ?: return
        val k = FUSION_DUMP_FRAMES - fusionDumpLeft
        if (k == 0) fusionDumpStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date())
        val last = --fusionDumpLeft == 0
        val rot = fusionRotation
        val (vis, uw, uh) = uprightLuma(fr, rot)
        val therm = raw.copyOf()
        val thermDn = dn.copyOf()
        val meta = "{\"thermal_w\":$ow,\"thermal_h\":$oh,\"visible_w\":$uw,\"visible_h\":$uh," +
            "\"fusion_rotation\":$rot,\"thermal_rotation\":$rotation,\"thermal_mirror\":$mirror," +
            "\"zoom\":${reg.zoom},\"dx\":${reg.dx},\"dy\":${reg.dy},\"invZ\":$invZ," +
            "\"distance_source\":\"${effectiveDistanceSource()}\",\"focus_diopters\":${rgbCamera.focusDiopters}," +
            "\"af_state\":${rgbCamera.afState}," +
            "\"calib\":${FusionCalibration.encodeSamples(calibSamples)}}\n"
        val dir = java.io.File(getApplication<Application>().getExternalFilesDir(null), "fusion_dump")
        val stamp = "${fusionDumpStamp}_$k"
        fun f32(a: FloatArray) = java.nio.ByteBuffer.allocate(a.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .also { bb -> a.forEach { bb.putFloat(it) } }.array()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                java.io.File(dir, "fd_$stamp.f32").writeBytes(f32(therm))
                java.io.File(dir, "fd_$stamp.dn.f32").writeBytes(f32(thermDn))
                java.io.File(dir, "fd_$stamp.u8").writeBytes(vis)
                java.io.File(dir, "fd_$stamp.json").writeText(meta)
            }.onFailure { Log.e(TAG, "fusion dump failed", it) }
            if (last) {
                fusionDumpCount++
                notice = str(R.string.n_fusion_dumped, fusionDumpCount)
            }
        }
    }

    /** Visible luma turned upright by the mounting rotation [rot]: (pixels, width, height). */
    private fun uprightLuma(fr: RgbCamera.LumaFrame, rot: Int): Triple<ByteArray, Int, Int> {
        val lw = fr.width; val lh = fr.height
        val uw = if (rot == 90 || rot == 270) lh else lw
        val uh = if (rot == 90 || rot == 270) lw else lh
        val out = ByteArray(uw * uh)
        for (uy in 0 until uh) for (ux in 0 until uw) {
            // same sensor index as Fusion's rowPart + colPart
            val i = when (rot) {
                90 -> uy + (lh - 1 - ux) * lw
                180 -> (lh - 1 - uy) * lw + (lw - 1 - ux)
                270 -> (lw - 1 - uy) + ux * lw
                else -> uy * lw + ux
            }
            out[uy * uw + ux] = fr.data[i]
        }
        return Triple(out, uw, uh)
    }

    /** [fuse] publishes the AF window for the frame it just built (processing thread). */
    private var afBoxOut: android.graphics.RectF? = null
    private var afHalBoxesOut: List<android.graphics.RectF>? = null

    /**
     * The visible camera's AF window in normalized coordinates of the composed frame.
     * Overlay modes map display → visible with n_vis = (n_disp − 0.5)/zoom + 0.5 + d, so
     * the lens centre comes back at 0.5 − d·zoom and the window's side scales with zoom;
     * wide search IS the visible frame, so the window sits dead centre at its true size.
     */
    private fun afBox(reg: Registration, wide: Boolean): android.graphics.RectF? {
        if (!fusionCrosshair) return null
        val frac = RgbCamera.AF_CENTER_FRAC
        val cx = if (wide) 0.5f else 0.5f - reg.dx * reg.zoom
        val cy = if (wide) 0.5f else 0.5f - reg.dy * reg.zoom
        val half = if (wide) frac / 2f else frac * reg.zoom / 2f
        return android.graphics.RectF(cx - half, cy - half, cx + half, cy + half)
    }

    /**
     * The HAL-reported AF regions ([RgbCamera.halAfRegions], sensor-normalized) in the
     * composed frame's coordinates, for comparing with [afBox]. Sensor → upright by the
     * mounting rotation (the same mapping as Fusion's row/col parts), then the overlay
     * projection n_disp = (n_vis − 0.5 − d)·zoom + 0.5; wide search is the upright frame.
     */
    private fun afHalBoxes(reg: Registration, wide: Boolean): List<android.graphics.RectF>? {
        if (!fusionCrosshair) return null
        val regions = rgbCamera.halAfRegions ?: return null
        fun up(sx: Float, sy: Float): Pair<Float, Float> = when (fusionRotation) {
            90 -> (1f - sy) to sx
            180 -> (1f - sx) to (1f - sy)
            270 -> sy to (1f - sx)
            else -> sx to sy
        }
        fun disp(n: Float, d: Float) = if (wide) n else (n - 0.5f - d) * reg.zoom + 0.5f
        return regions.map { r ->
            val (ax, ay) = up(r.left, r.top)
            val (bx, by) = up(r.right, r.bottom)
            android.graphics.RectF(disp(minOf(ax, bx), reg.dx), disp(minOf(ay, by), reg.dy),
                                   disp(maxOf(ax, bx), reg.dx), disp(maxOf(ay, by), reg.dy))
        }
    }

    /** Temporally smoothed EDGES map in sensor pixels — what compose() samples. */
    private var edgeSmooth: FloatArray? = null
    /** Visible frame and sensor rect [edgeSmooth] was last brought up to date for. */
    private var edgeCacheId = -1L
    private var edgeRoi: IntArray? = null
    /** Wide-search output, reused per frame (the Bitmap copies it). */
    private var wideBuf = IntArray(0)

    /**
     * Blend the visible camera into the display pixels (processing thread). Overlay modes
     * draw into [img] in place; wide search returns the whole visible frame with the
     * thermal image inset, plus that inset's rect. Null = no visible frame yet, draw
     * thermal only. [gate] is the thermal-gradient gating field for EDGES
     * (see [thermalGate]).
     */
    private fun fuse(img: ArgbImage, gate: FloatArray?, gw: Int, gh: Int)
            : Pair<ArgbImage, android.graphics.RectF?>? {
        afBoxOut = null
        afHalBoxesOut = null
        val fr = rgbCamera.latest() ?: return null
        val invZ = currentInvZ()
        val reg = registration(invZ)
        lastFuse = Triple(fr, reg, invZ)
        afBoxOut = afBox(reg, fusionMode == Fusion.Mode.SEARCH)
        afHalBoxesOut = afHalBoxes(reg, fusionMode == Fusion.Mode.SEARCH)
        fusionDistanceLabel = if (invZ <= 1e-4f) "∞" else "≈" + distanceText(invZ)
        val w = img.w; val h = img.h
        if (fusionMode == Fusion.Mode.SEARCH) {
            val n = fr.width * fr.height
            if (wideBuf.size != n) wideBuf = IntArray(n)
            val r = Fusion.composeWide(img.px, w, h, fr.data, fr.width, fr.height,
                                       fusionStrength, reg.zoom, reg.dx, reg.dy,
                                       fusionRotation, wideBuf)
            return ArgbImage(r.pixels, r.width, r.height) to
                android.graphics.RectF(r.rectL, r.rectT, r.rectL + r.rectW, r.rectT + r.rectH)
        }
        val edge = if (fusionMode == Fusion.Mode.EDGES) edgesFor(fr, reg, w, h) else null
        Fusion.compose(img.px, w, h, fr.data, fr.width, fr.height, edge, gate, gw, gh,
                       fusionMode, fusionStrength,
                       reg.zoom, reg.dx, reg.dy, fusionRotation)
        return img to null
    }

    /**
     * The smoothed EDGES map, brought up to date for visible frame [fr] over the patch
     * this registration samples (plus a margin). Sobel runs once per visible frame, not
     * per thermal frame, and only again for the same frame if the patch moved outside
     * what was computed. Temporal IIR on the magnitude: Sobel flickers frame-to-frame on
     * noisy luma, the smoothed map is what compose() samples.
     */
    private fun edgesFor(fr: RgbCamera.LumaFrame, reg: Registration, dispW: Int, dispH: Int)
            : FloatArray {
        val sm = edgeSmooth?.takeIf { it.size == fr.data.size }
            ?: FloatArray(fr.data.size).also { edgeSmooth = it; edgeRoi = null }
        val want = Fusion.sampledRect(dispW, dispH, fr.width, fr.height,
                                      reg.zoom, reg.dx, reg.dy, fusionRotation) ?: return sm
        val have = edgeRoi
        val covered = have != null && have[0] <= want[0] && have[1] <= want[1] &&
                      have[2] >= want[2] && have[3] >= want[3]
        if (edgeCacheId != fr.id || !covered) {
            val m = EDGE_ROI_MARGIN
            val roi = intArrayOf((want[0] - m).coerceAtLeast(0), (want[1] - m).coerceAtLeast(0),
                                 (want[2] + m).coerceAtMost(fr.width),
                                 (want[3] + m).coerceAtMost(fr.height))
            Fusion.smoothEdges(fr.data, fr.width, fr.height, roi, have, sm, EDGE_IIR_KEEP)
            edgeRoi = roi
            edgeCacheId = fr.id
        }
        return sm
    }

    override fun onCleared() {
        if (rgbCameraLazy.isInitialized()) rgbCamera.stop()
        if (locationActive) {
            getApplication<Application>().getSystemService(LocationManager::class.java)
                ?.let { removeLocationUpdates(it) }
        }
        loop?.cancel()
        synchronized(recLock) {
            recorder?.also { recorder = null }?.stop()
            thermalWriter?.also { thermalWriter = null }?.close()
        }
        playback?.close(); playback = null
        anime4k?.close()
        ncnn?.close()
        runCatching { sdk.close() }
    }

    // ---- processing loop --------------------------------------------------------

    /**
     * The SDK pushes frames from its own thread into the [ThermalSource]; this picks each
     * new one up as soon as it lands. No FFC bookkeeping — the SDK does its own.
     */
    private fun startSdkLoop() {
        loop?.cancel()
        loop = viewModelScope.launch(Dispatchers.Default) {
            val startedAt = System.currentTimeMillis()
            var lastSeen = 0L
            var lastSeenAt = startedAt
            var lastProcessed = -1L
            var learnChecked = false
            rangeSnap = true
            while (isActive && connected) {
                if (paused) { delay(50); continue }

                val fc = sdk.frameCount
                val now = System.currentTimeMillis()
                if (sdk.stallRecovery && fc == 0L && now - startedAt > 8000) {
                    Log.w(TAG, "SDK delivered no frames — recovering")
                    recover(); break
                }
                if (sdk.stallRecovery && fc == lastSeen && fc > 0L && now - lastSeenAt > 6000) {
                    Log.w(TAG, "SDK stream stalled — recovering")
                    recover(); break
                }
                if (fc != lastSeen) { lastSeen = fc; lastSeenAt = now }
                if (fc > 0L && !learnChecked) { learnChecked = true; learnTiny2c() }

                // process each SDK frame once, as soon as it lands (no fixed period)
                if (fc == lastProcessed) { delay(4); continue }
                val temp = sdk.getTemperatureData()
                if (temp == null) { delay(20); continue }
                lastProcessed = fc
                try {
                    val t0 = System.nanoTime()
                    val res = process(temp)
                    lastFrame = res
                    val tRec = System.nanoTime()
                    checkSrSpeed((tRec - t0) / 1e6f)
                    feedRecorder(res)
                    lap(ST_REC, tRec)
                    tickFps()
                    status = frameStatus(fc, t0)
                    if (debugOsd) debugInfo = debugText()
                    (sdk as? Hik4117Source)?.let { sdkLog = it.report() }
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
        var t = System.nanoTime()
        val w = sdk.width; val h = sdk.height
        val degC = FloatArray(tempMilliC.size) { MagDeviceWrapper.degC(tempMilliC[it]) }

        val raw = orient(degC, w, h)          // absolute °C, what SPOT and .mgt read
        var oriented = raw
        val ow: Int; val oh: Int
        if (rotation % 180 == 0) { ow = w; oh = h } else { ow = h; oh = w }

        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        var mnI = 0; var mxI = 0
        for (i in oriented.indices) {
            val v = oriented[i]
            if (v < mn) { mn = v; mnI = i }
            if (v > mx) { mx = v; mxI = i }
        }
        if (debugOsd) {
            // frame-to-frame change: ~0 means the camera keeps sending the same picture
            prevRaw?.takeIf { it.size == raw.size }?.let { p ->
                var s = 0f
                for (i in raw.indices) s += abs(raw[i] - p[i])
                frameDiffC = s / raw.size
            }
            prevRaw = raw
        }
        t = lap(ST_IN, t)

        oriented = temporal(oriented)
        if (spatialDenoise && !thermalSrActive) {
            // sigmaColor is in °C — 80 counts of range tolerance would be 80 °C here
            // and would flatten the whole scene.
            oriented = ImageOps.bilateral(oriented, ow, oh, 5, 0.5f, 5f)
        }
        t = lap(ST_DN, t)

        val slo: Float; val shi: Float
        if (autoScale) {
            // one histogram pass for both percentiles — the sorted version's two
            // copies + two sorts per frame were the dominant cost of this stage;
            // no narrower than [minSpan], or a flat scene shows the sensor's noise
            val (p1, tHi) = ImageOps.autoRange(oriented, minSpan)
            // glide toward it: a per-frame jump makes the whole picture pulse when
            // something warm enters or leaves the frame
            val now = System.nanoTime()
            if (rangeSnap) {
                scaleLo = p1; scaleHi = tHi; rangeSnap = false
            } else {
                val dt = ((now - rangeAt) / 1e9f).coerceIn(0f, 1f)
                val k = 1f - exp(-dt / RANGE_TAU_S)
                scaleLo += k * (p1 - scaleLo); scaleHi += k * (tHi - scaleHi)
            }
            rangeAt = now
            slo = scaleLo
            shi = maxOf(scaleHi, slo + 0.1f)
        } else {
            slo = scaleLo; shi = scaleHi
        }
        // the readouts and the EDGES gate keep using [oriented]; only the picture changes
        val shown = if (detailEnhance) detailFilter.apply(oriented, ow, oh, slo, shi)
                    else oriented

        t = lap(ST_RNG, t)

        var img = render(shown, ow, oh, slo, shi)
        t = lap(ST_UP, t)
        var inset: android.graphics.RectF? = null
        var afWindow: android.graphics.RectF? = null
        var afHal: List<android.graphics.RectF>? = null
        if (fusionOn) {
            // EDGES: gate the overlay by the thermal gradient so visible structure
            // only fills flat thermal areas (fusion is display-only; the readouts
            // came from the raw field above)
            val gate = if (fusionMode == Fusion.Mode.EDGES)
                thermalGate(oriented, ow, oh, shi - slo) else null
            fuse(img, gate, ow, oh)?.let { (i, r) -> img = i; inset = r }
            afWindow = afBoxOut
            afHal = afHalBoxesOut
            if (fusionDumpLeft > 0) dumpFusion(raw, oriented, ow, oh)
        }
        t = lap(ST_FUSE, t)
        // the one copy of the frame: every stage above worked on reused pixel buffers
        val bitmap = android.graphics.Bitmap.createBitmap(img.px, img.w, img.h,
                                                          android.graphics.Bitmap.Config.ARGB_8888)
        lap(ST_BMP, t)

        return FrameResult(
            bitmap = bitmap, w = ow, h = oh,
            tempMin = mn, tempMax = mx,
            minPos = mnI, maxPos = mxI,
            fpa = sdk.sensorTemp()?.toLong(),
            scaleLoC = slo, scaleHiC = shi,
            emissivity = emissivity,
            temps = raw,
            inset = inset,
            afBox = afWindow,
            afHalBoxes = afHal,
        )
    }

    /**
     * Upscale the (still scalar) field, then palette-map into display pixels — all on
     * the processing thread, so the UI only draws. Interpolating before colouring keeps
     * edges clean instead of blending palette colours. The pixels live in a reused
     * buffer (the upscaler's, or [pixBuf]); [process] turns them into the frame's Bitmap.
     */
    private fun render(field: FloatArray, w: Int, h: Int, lo: Float, hi: Float): ArgbImage {
        if (ncnnReload) {                  // a model was imported/removed: reopen it
            ncnnReload = false
            runCatching { ncnn?.close() }
            ncnn = null; ncnnSize = 0 to 0
        }
        val k = upscale.coerceIn(1, 4)
        srRendered = false
        if (k > 1 && upscaler == Upscaler.NCNN) {
            try {
                // one model per input size: the exported graph has a static shape
                val u = ncnn?.takeIf { ncnnSize == w to h }
                    ?: openSr(w, h)?.also {
                        ncnn?.close(); ncnn = it; ncnnSize = w to h
                        notice = str(R.string.n_ncnn_loaded,
                                     (if (it.vulkan) "Vulkan" else "CPU") + " · " + str(srTierLabel(it.tier)))
                    }
                if (u != null) {
                    val img = u.render(field, w, h, lo, hi, Palettes.lut(paletteName))
                    noteSrTimings(u)
                    srRendered = true
                    renderPath = "Thermal SR ${img.w}×${img.h}"
                    return img
                }
                upscaler = Upscaler.ANIME4K
                notice = str(R.string.n_no_ncnn, NcnnUpscaler.modelDir(getApplication()).name)
            } catch (e: Throwable) {
                Log.e(TAG, "ncnn failed — falling back to Anime4K", e)
                runCatching { ncnn?.close() }
                ncnn = null
                upscaler = Upscaler.ANIME4K
                notice = str(R.string.n_ncnn_failed, e.message ?: "")
            }
        }
        if (k > 1 && upscaler == Upscaler.ANIME4K) {
            try {
                val gpu = anime4k ?: Anime4kGpu(getApplication()).also { anime4k = it }
                return gpu.render(field, w, h, lo, hi, if (k >= 4) 2 else 1,
                                  Palettes.lut(paletteName))
                    .also { renderPath = "Anime4K ${it.w}×${it.h}" }
            } catch (e: Throwable) {
                Log.e(TAG, "Anime4K GPU failed — falling back to bicubic", e)
                runCatching { anime4k?.close() }
                anime4k = null
                upscaler = Upscaler.BICUBIC
                notice = str(R.string.n_anime4k_failed, e.message ?: "")
            }
        }
        val src = if (k > 1) ImageOps.resizeBicubic(field, w, h, w * k, h * k) else field
        val pal = Palettes.lut(paletteName)
        if (pixBuf.size != src.size) pixBuf = IntArray(src.size)
        val img = pixBuf
        val inv = 255f / (hi - lo)
        for (i in src.indices) {
            var t = (src[i] - lo) * inv
            if (t < 0f) t = 0f else if (t > 255f) t = 255f
            img[i] = pal[(t + 0.5f).toInt()]
        }
        renderPath = "bicubic ${w * k}×${h * k}"
        return ArgbImage(img, w * k, h * k)
    }

    /** Bicubic-path display pixels, reused per frame. */
    private var pixBuf = IntArray(0)

    // debug overlay: which path drew the frame, and the mean per-pixel change since the
    // previous one (processing thread)
    private var renderPath = ""
    private var prevRaw: FloatArray? = null
    private var frameDiffC = 0f

    // Thermal SR diagnostics for the status line: which backend/model ran this frame and
    // where its time went (smoothed like the stage split; processing thread)
    private var srFrame = false
    private var srLabel = ""
    private val srMs = FloatArray(3)

    // Thermal SR speed guard (processing thread)
    private var srRendered = false             // the last frame came out of Thermal SR
    private var srCheckFrames = 0
    private var srFrameMs = 0f
    @Volatile private var srSwitchedForSpeed = false
    /** The guard picked a lighter model size (see [chooseSrTier]). */
    @Volatile private var srSteppedForSpeed = false

    /**
     * Thermal SR costs per input pixel, so a bigger sensor (a Tiny2C's 256×192 is 2.56×
     * the MAG160's) can take longer than the camera's frame period — and the serial loop
     * then runs at that rate (12–15 fps reported on a Tiny2C). When SR keeps the loop under
     * [SR_MIN_RATE_SHARE] of the camera's rate, step to the next lighter model size that is
     * installed (measured afresh), and past the lightest switch to Anime4K once; each time
     * say why. Picking Thermal SR or a size again sets [srKeepSlow] and the guard stays off
     * from then on.
     */
    private fun checkSrSpeed(frameMs: Float) {
        if (!srRendered || srKeepSlow || srSwitchedForSpeed) { srCheckFrames = 0; return }
        val camFps = sdk.fps.takeIf { it > 0 } ?: return
        srCheckFrames++
        if (srCheckFrames <= SR_WARMUP_FRAMES) { srFrameMs = frameMs; return }
        srFrameMs += STAGE_EMA * (frameMs - srFrameMs)
        if (srCheckFrames < SR_WARMUP_FRAMES + SR_CHECK_FRAMES) return
        if (srFrameMs > 1000f / (camFps * SR_MIN_RATE_SHARE)) {
            val cur = ncnn?.tier ?: srTier
            val lighter = SrTier.entries.drop(cur.ordinal + 1).firstOrNull { it in srTiers }
            if (lighter != null) {
                Log.w(TAG, "Thermal SR %.0f ms/frame vs %d fps camera — %s model"
                    .format(srFrameMs, camFps, lighter.name))
                srSteppedForSpeed = true
                srTier = lighter
                srCheckFrames = 0
                notice = str(R.string.n_sr_tier_down, srFrameMs, 1000f / srFrameMs,
                             str(srTierLabel(lighter)))
                return
            }
            Log.w(TAG, "Thermal SR %.0f ms/frame vs %d fps camera — switching to Anime4K"
                .format(srFrameMs, camFps))
            srSwitchedForSpeed = true
            upscaler = Upscaler.ANIME4K
            notice = str(R.string.n_sr_too_slow, srFrameMs, 1000f / srFrameMs)
        }
    }

    private fun noteSrTimings(u: NcnnUpscaler) {
        val t = u.lastTimings()
        for (i in 0..2) srMs[i] += STAGE_EMA * (t[i] - srMs[i])
        srLabel = "SR ${if (u.vulkan) "Vulkan" else "CPU"} · ${u.tier.name.lowercase()} · ${u.inChannels}-ch " +
            (if (u.fromFiles) "imported" else "built-in")
        srFrame = true
    }

    private var anime4k: Anime4kGpu? = null
    private var ncnn: NcnnUpscaler? = null
    private var ncnnSize = 0 to 0

    /** The picked size's model, else the full one. */
    private fun openSr(w: Int, h: Int): NcnnUpscaler? {
        val ctx = getApplication<Application>()
        val tier = srTier
        return NcnnUpscaler.open(ctx, w, h, tier)
            ?: if (tier != SrTier.FULL) NcnnUpscaler.open(ctx, w, h, SrTier.FULL) else null
    }

    /** True when a converted model for the current frame size is present on the device. */
    fun ncnnModelInstalled(): Boolean {
        val fr = lastFrame ?: return false
        val ctx = getApplication<Application>()
        return NcnnUpscaler.isInstalled(ctx, fr.w, fr.h, srTier) ||
            NcnnUpscaler.isInstalled(ctx, fr.w, fr.h, SrTier.FULL)
    }

    /** Where to adb-push the .param/.bin — shown in the drawer. */
    fun ncnnModelDir(): String = NcnnUpscaler.modelDir(getApplication()).absolutePath

    /** Set from any thread; the processing loop drops its model before the next frame, so
     *  an import takes effect without closing a net that is mid-inference. */
    @Volatile private var ncnnReload = false

    fun refreshSrModelInfo() {
        viewModelScope.launch(Dispatchers.IO) { loadSrModelInfo() }
    }

    /** Blocking (file listing): [srImported] and [srTiers] from what is on disk. */
    private fun loadSrModelInfo() {
        val ctx = getApplication<Application>()
        srImported = SrTier.entries.associateWith { NcnnUpscaler.importedSizes(ctx, it) }
            .filterValues { it.isNotEmpty() }
        srTiers = NcnnUpscaler.availableTiers(ctx)
    }

    /** "Lite · 256x192, 192x256" for a notice. */
    private fun srModelText(tier: SrTier, sizes: List<String>) =
        str(srTierLabel(tier)) + " · " + sizes.joinToString()

    /** Install a model package (zip of thermal_<w>x<h>_fp16.param/.bin) picked in the drawer;
     *  its size ([SrTier]) becomes the one in use — it is the model you meant to try. */
    fun importSrModel(uri: android.net.Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            notice = try {
                val got = ctx.contentResolver.openInputStream(uri)
                    ?.use { NcnnUpscaler.importPackage(ctx, it) }
                    ?: error("cannot open the file")
                loadSrModelInfo()
                srTier = got.tier
                ncnnReload = true
                str(R.string.n_sr_imported, srModelText(got.tier, got.sizes))
            } catch (e: Throwable) {
                Log.e(TAG, "SR model import failed", e)
                str(R.string.n_sr_import_failed, e.message ?: "")
            }
        }
    }

    /** Back to the model built into the APK (every imported size is removed). */
    fun removeSrModel() {
        viewModelScope.launch(Dispatchers.IO) {
            NcnnUpscaler.removeImported(getApplication())
            loadSrModelInfo()
            srTier = SrTier.FULL
            ncnnReload = true
            notice = str(R.string.n_using_builtin)
        }
    }

    /** The `sr-models` package to offer for the current frame size (newer than the one
     *  downloaded, or than the built-in weights); null when there is nothing to offer. */
    var srRemote by mutableStateOf<SrModelDownloader.Package?>(null); private set
    /** Bytes done / total of the running model download; null when none runs. */
    var srDownload by mutableStateOf<Pair<Long, Long>?>(null); private set
    @Volatile private var srManifest: List<SrModelDownloader.Package>? = null
    private var srDownloadJob: Job? = null

    /** Look up the current frame size in `models.json` (fetched once per run; a failure,
     *  e.g. offline, is retried on the next call). Network only — nothing is downloaded. */
    fun checkSrRemote() {
        val fr = lastFrame ?: return
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val list = srManifest ?: runCatching { SrModelDownloader.fetchManifest() }
                .onFailure { Log.i(TAG, "sr-models manifest: ${it.message}") }
                .getOrNull()?.also { srManifest = it } ?: return@launch
            srRemote = list.filter { "${fr.w}x${fr.h}" in it.sizes }.firstOrNull { pkg ->
                val have = SrTier.entries.maxOf {
                    SrModelDownloader.installedVersion(NcnnUpscaler.modelDir(ctx, it), pkg.file)
                }
                pkg.version > maxOf(have, SrModelDownloader.BUILTIN_VERSION)
            }
        }
    }

    fun downloadSrModel() {
        val pkg = srRemote ?: return
        if (srDownloadJob?.isActive == true) return
        val ctx = getApplication<Application>()
        srDownload = 0L to pkg.bytes
        srDownloadJob = viewModelScope.launch(Dispatchers.IO) {
            notice = try {
                val got = SrModelDownloader.download(
                    ctx.cacheDir, pkg,
                    onProgress = { done, total -> srDownload = done to total },
                    isCancelled = { !isActive },
                    dirFor = { NcnnUpscaler.modelDir(ctx, it) })
                loadSrModelInfo()
                srTier = got.tier
                ncnnReload = true
                srRemote = null
                str(R.string.n_sr_imported, srModelText(got.tier, got.sizes))
            } catch (e: java.util.concurrent.CancellationException) {
                str(R.string.n_sr_download_cancelled)
            } catch (e: Throwable) {
                Log.e(TAG, "SR model download failed", e)
                str(R.string.n_sr_download_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                srDownload = null
            }
        }
    }

    fun cancelSrDownload() {
        srDownloadJob?.cancel()
    }
    private val temporalFilter = TemporalDenoise()
    private val detailFilter = DetailEnhance()

    // auto range glide state (processing thread); snapped on each new stream
    @Volatile private var rangeSnap = true
    private var rangeAt = 0L

    /** Motion-adaptive temporal IIR on the display field (history resets on orientation
     *  change). The returned array is the filter's buffer — read-only. */
    private fun temporal(field: FloatArray): FloatArray {
        if (!temporalDenoise) { temporalFilter.reset(); return field }
        temporalFilter.strength = temporalStrength
        return temporalFilter.apply(field, rotation * 2 + (if (mirror) 1 else 0))
    }

    // ---- per-stage timing (processing thread) -----------------------------------

    private val stageMs = FloatArray(STAGES.size)
    private var totalMs = 0f

    /** Fold the time since [since] into [stage]'s smoothed ms; returns now for the next lap. */
    private fun lap(stage: Int, since: Long): Long {
        val now = System.nanoTime()
        stageMs[stage] += STAGE_EMA * ((now - since) / 1e6f - stageMs[stage])
        return now
    }

    /** Status line: size, frame count and the smoothed per-frame total. */
    private fun frameStatus(fc: Long, t0: Long): String {
        totalMs += STAGE_EMA * ((System.nanoTime() - t0) / 1e6f - totalMs)
        return "${sdk.width}×${sdk.height} · frames=$fc · " + "%.1f ms".format(totalMs)
    }

    /** Debug overlay: the per-stage split in ms (in: convert+orient+stats · dn: denoise ·
     *  rng: display range + DDE · up: upscale+palette · fuse: visible fusion · bmp: the frame's
     *  Bitmap · rec: video/.mgt while recording), plus the Thermal SR breakdown when it
     *  produced this frame. */
    private fun debugText(): String {
        val sb = StringBuilder()
        sb.append("%.1f fps · %.1f ms/frame\n".format(fps, totalMs))
        STAGES.indices.forEach { i ->
            sb.append("%s %.1f".format(STAGES[i], stageMs[i]))
            sb.append(if (i == 3) "\n" else if (i < STAGES.size - 1) " · " else "")
        }
        if (srFrame) {
            sb.append("\n").append(srLabel)
                .append("\nnet %.1f · pre %.1f · post %.1f ms".format(srMs[1], srMs[0], srMs[2]))
        }
        srFrame = false
        // what the frame is made of: enough to tell a frozen or flat stream from a
        // display problem in a user's screenshot
        sb.append("\n$paletteName · ${upscaler.name} ×$upscale → $renderPath")
        lastFrame?.let {
            sb.append("\nT %.2f..%.2f °C · scale %.2f..%.2f · Δ %.3f °C"
                .format(it.tempMin, it.tempMax, it.scaleLoC, it.scaleHiC, frameDiffC))
        }
        sb.append("\nsrc frames=${sdk.frameCount}")
        sdk.diag()?.let { sb.append("\n").append(it) }
        return sb.toString()
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
    private var thermalWriter: ThermalStream.Writer? = null

    private fun composite(fr: FrameResult) = FrameComposer.compose(
        fr, Palettes.lut(paletteName), showMaxRoi, showMinRoi, spot, spotCelsius(),
        if (showCenter) centerCelsius(fr) else null)

    fun takeScreenshot() {
        val fr = lastFrame ?: return
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val loc = captureLocation()
            notice = try {
                MediaSaver.savePng(ctx, composite(fr), loc)
                    ?.let { (_, tagged) ->
                        str(R.string.n_snapshot_saved) + geoSuffix(loc, tagged) +
                            thermalSuffix(fr)
                    }
                    ?: str(R.string.n_snapshot_failed)
            } catch (e: Throwable) {
                Log.e(TAG, "screenshot failed", e); str(R.string.n_snapshot_failed_msg, e.message ?: "")
            }
        }
    }

    /** Companion .mgt for a snapshot, when the option is on: the field behind the PNG. */
    private fun thermalSuffix(fr: FrameResult): String {
        if (!saveThermalData) return ""
        val name = ThermalStream.writeSingle(getApplication(), fr.temps, fr.w, fr.h,
                                             fr.emissivity)
        return if (name != null) " + $name" else " " + str(R.string.n_temp_file_failed)
    }

    fun toggleRecording() {
        if (recording) { stopRecording(); return }
        val fr = lastFrame ?: return
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val (w, h) = FrameComposer.outSize(fr)
            try {
                val loc = captureLocation()
                synchronized(recLock) {
                    recorder = VideoRecorder(ctx, w, h, loc)
                    // a failed temperature file must not cost the video
                    thermalWriter = if (saveThermalData)
                        runCatching { ThermalStream.Writer(ctx, fr.w, fr.h, emissivity) }
                            .onFailure { Log.e(TAG, "thermal stream start failed", it) }
                            .getOrNull()
                    else null
                }
                if (geotag && loc == null) notice = str(R.string.n_rec_no_geotag)
                recordStartMs = System.currentTimeMillis()
                recording = true
            } catch (e: Throwable) {
                Log.e(TAG, "recorder start failed", e)
                notice = str(R.string.n_rec_start_failed, e.message ?: "")
            }
        }
    }

    private fun geoSuffix(loc: Location?, tagged: Boolean) = when {
        !geotag -> ""
        loc == null -> " " + str(R.string.n_geo_no_fix)
        tagged -> " " + str(R.string.n_geo_tagged)
        else -> " " + str(R.string.n_geo_failed)
    }

    fun stopRecording() {
        val (r, t) = synchronized(recLock) {
            val pair = recorder to thermalWriter
            recorder = null; thermalWriter = null
            pair
        }
        if (r == null) { t?.close(); return }
        recording = false
        viewModelScope.launch(Dispatchers.IO) {
            val uri = r.stop()
            val temps = t?.let { w -> if (w.close() != null) w.displayName else null }
            notice = if (uri != null)
                         plural(if (r.geoTagged) R.plurals.n_video_saved_geo
                                else R.plurals.n_video_saved, r.frames, r.codecName, r.frames) +
                             (temps?.let { " + $it" } ?: "")
                     else str(R.string.n_rec_failed)
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
                thermalWriter?.takeIf { it.width == fr.w && it.height == fr.h }
                    ?.addFrame(fr.temps)
                return
            } catch (e: Throwable) {
                Log.e(TAG, "video frame failed", e)
                notice = str(R.string.n_rec_stopped, e.message ?: "")
            }
        }
        stopRecording()          // encoder broke — finalise what we have
    }

    // ---- saved temperature captures --------------------------------------------

    /** Open a .mgt capture for review; the live stream keeps running underneath. */
    fun openPlayback(uri: android.net.Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val name = runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
                                          null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "capture"
            val opened = runCatching { ThermalStream.Reader(ctx, uri) }
            withContext(Dispatchers.Main) {
                opened.onSuccess { r ->
                    playback?.close()
                    playback = ThermalPlayback(r, name)
                }.onFailure {
                    Log.e(TAG, "playback open failed", it)
                    notice = str(R.string.n_cannot_read_capture, it.message ?: "")
                }
            }
        }
    }

    fun closePlayback() {
        playback?.close()
        playback = null
    }

    // ---- measurement ----------------------------------------------------------

    fun spotCelsius(): Float? {
        val fr = lastFrame ?: return null
        val (x, y) = spot ?: return null
        if (x < 0 || y < 0 || x >= fr.w || y >= fr.h) return null
        return fr.temps.getOrNull(y * fr.w + x)
    }

    /** CTR: mean of the pixels at the grid's geometric centre — the 2×2 block on an even
     *  size, the middle pixel on an odd one. Raw °C, like SPOT. */
    fun centerCelsius(fr: FrameResult? = lastFrame): Float? {
        fr ?: return null
        val t = fr.temps
        if (t.size < fr.w * fr.h || fr.w == 0 || fr.h == 0) return null
        val x0 = (fr.w - 1) / 2; val x1 = fr.w / 2
        val y0 = (fr.h - 1) / 2; val y1 = fr.h / 2
        return (t[y0 * fr.w + x0] + t[y0 * fr.w + x1] + t[y1 * fr.w + x0] + t[y1 * fr.w + x1]) / 4f
    }

    private var gateBuf: FloatArray? = null

    /**
     * Per-frame gating field for the EDGES overlay (w×h, 1 = thermally flat, the
     * visible edge shows fully; →0 where the thermal gradient already carries
     * structure, suppressing double edges). Gradients are measured against the
     * display range so the gate is independent of the palette scale.
     */
    private fun thermalGate(field: FloatArray, w: Int, h: Int, range: Float): FloatArray {
        val g = gateBuf?.takeIf { it.size == field.size }
            ?: FloatArray(field.size).also { gateBuf = it }
        val inv = 1f / (range.coerceAtLeast(0.1f) * GATE_GRAD_REL)
        for (y in 0 until h) {
            val ym = (if (y > 0) y - 1 else 0) * w
            val yp = (if (y < h - 1) y + 1 else h - 1) * w
            val row = y * w
            for (x in 0 until w) {
                val xm = if (x > 0) x - 1 else 0
                val xp = if (x < w - 1) x + 1 else w - 1
                val i = row + x
                // horizontal taps use the ROW base + neighbour column — i already
                // contains x, so field[i + xp] would run past the row end
                val mag = abs(field[row + xp] - field[row + xm]) +
                          abs(field[yp + x] - field[ym + x])
                val t = mag * inv
                g[i] = if (t >= 1f) 0f else 1f - t
            }
        }
        return g
    }

    /** rotate then mirror, applied at input so markers/stats stay consistent. */
    private fun orient(src: FloatArray, w: Int, h: Int): FloatArray {
        var dst = src
        var cw = w; var ch = h
        if (rotation != 0) {
            dst = ImageOps.rotate(dst, w, h, rotation)
            if (rotation % 180 != 0) { cw = h; ch = w }
        }
        if (mirror) dst = ImageOps.flipHorizontal(dst, cw, ch)
        return dst
    }

    // ---- HIK 4117 diagnostic ------------------------------------------------------

    /**
     * The 4117 diagnostic build's stream experiment ([Hik4117Source.runStreamTest], ~1 min).
     * The camera stays connected; its temperature snapshots pause meanwhile, and the
     * report in [sdkLog] fills in as the test goes.
     */
    fun runHik4117Test() {
        val src = sdk as? Hik4117Source ?: return
        if (sdkBusy || !connected) return
        sdkBusy = true
        viewModelScope.launch(Dispatchers.IO) {
            val ticker = launch { while (isActive) { sdkLog = src.report(); delay(500) } }
            try {
                src.runStreamTest()
            } catch (e: Throwable) {
                Log.e(TAG, "4117 stream test failed", e)
            } finally {
                ticker.cancel()
                sdkLog = src.report()
                sdkBusy = false
            }
        }
    }

    /** [sdkLog] plus the camera's live diagnostics — what the Copy report button copies. */
    fun reportText(): String =
        listOfNotNull(sdkLog, sdk.diag()?.takeIf { connected }).joinToString("\n")

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
                status = str(R.string.st_sdk_test)
                delay(750)

                wrapper.open(ctx) { p -> status = str(R.string.st_cali_download, p) }
                say("OK  link + prepare" +
                    if (wrapper.caliDownloaded) " (calibration downloaded from the camera)" else "")
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
                status = str(R.string.st_sdk_test_done)
                sdkBusy = false
            }
        }
    }
}

/** The drawer's name for a Thermal SR model size. */
@StringRes
fun srTierLabel(t: SrTier): Int = when (t) {
    SrTier.FULL -> R.string.sr_tier_full
    SrTier.MEDIUM -> R.string.sr_tier_medium
    SrTier.LITE -> R.string.sr_tier_lite
}
