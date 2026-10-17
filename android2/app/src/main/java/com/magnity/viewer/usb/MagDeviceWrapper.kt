package com.magnity.viewer.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import cn.com.magnity.coresdk.MagDevice
import cn.com.magnity.coresdk.types.CameraInfo
import cn.com.magnity.coresdk.types.CorrectionPara
import cn.com.magnity.coresdk.types.StatisticInfo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thin wrapper around the MAG SDK's MagDevice.
 *
 * The SDK does USB, NUC, cali-table download and radiometric conversion internally,
 * so no reconstruction of ours is involved on this path (the own pipeline was
 * removed on 2026-09-16 — see docs/OWN_PIPELINE_REMOVED.md).
 *
 * ABI note: the only 64-bit `libcoresdk.so` we have (from the Elo thermal SDK,
 * 2020-05-04) is an older build than the one in the MAG-Mx/Cx APKs and exports 61 of
 * that build's 73 JNI entry points. JNI symbols resolve lazily, so the 14 missing ones
 * only blow up as UnsatisfiedLinkError *when called*. The two this wrapper would
 * otherwise have used:
 *
 *   - `GetOutputRawData`   → absent. No NUC-corrected raw counts from the SDK; use
 *                            [getTemperatureData].
 *   - `GetSenorTemperature`→ absent. [sensorTemp] uses GetCurrentCameraInnerTemperature.
 *
 * Also absent, do not call: GetCameraTemperature, GetEstimatedEnvTemp,
 * Set/GetEstimateUnderArmTempMode, SetEnvTempEstimateMode, SetFFCMode, SetFilter,
 * SetIoAlarmState, ConvertVisCorr2IrCorr, SaveDDT2Buffer, StartProcessImage_v2
 * (= the 5-arg startProcessImage overload), GetOutputImage2.
 *
 * USB is exclusive: `linkCamera` requests permission itself and opens its own
 * UsbDeviceConnection; nothing else may hold the device when [open] is called.
 */
class MagDeviceWrapper : ThermalSource {

    companion object {
        const val TAG = "MagDeviceWrapper"
        const val VID = 0x833C
        val PIDS = intArrayOf(0x0001, 0x0002)
        /** First-link calibration download (~1.8 MB over USB; seconds in practice). */
        private const val CALI_DOWNLOAD_TIMEOUT_S = 120L
        private const val START_RETRIES = 5
        private const val START_RETRY_MS = 400L

        fun isMagnity(device: UsbDevice): Boolean =
            device.vendorId == VID && PIDS.contains(device.productId)

        /**
         * SDK temperature unit -> degrees C.
         *
         * `getTemperatureData` / `getTemperatureProbe` /
         * `getCurrentCameraInnerTemperature` all report **milli-Celsius**, not the
         * centi-Kelvin the firmware's own piecewise curve works in — the native side
         * converts before the value reaches Java. Confirmed against the factory Elo
         * wrapper, whose `createLegalResult` and `getInnerIRCameraTemp` both do
         * `rawInt * 0.001d`, with no 273.15 term anywhere in that class.
         */
        fun degC(raw: Int): Float = raw * 0.001f

        /** Loads libcoresdk.so. Returns the throwable if the .so cannot be linked. */
        fun probeNativeLib(): Throwable? = try {
            System.loadLibrary("coresdk"); null
        } catch (e: Throwable) { e }

        private var initialized = false
    }

    override val kind = "mag160"
    /** Measured once (2026-10-12) as zoom 2.38 against a phone camera whose half-FOV
     *  tangent is 0.560 across the upright frame (PLAN_TRIANGULATION.md). */
    override val focalPx = 255f

    /** FPA geometry as reported by the SDK (valid after [open]). */
    override var width = 0; private set
    override var height = 0; private set
    /** Palette-mapped output bitmap size as reported by the SDK. */
    var bmpWidth = 0; private set
    var bmpHeight = 0; private set
    override var fps = 0; private set
    var cameraName = ""; private set
    var cameraType = ""; private set
    /** The last [open] had to download the calibration file (no cached copy). */
    var caliDownloaded = false; private set

    @Volatile override var frameCount = 0L; private set

    @Volatile private var device: MagDevice? = null
    private val running = AtomicBoolean(false)

    /**
     * Guards every native call against [close]: the SDK frame callback runs on its own
     * thread, and a call landing on a handle mid-`dislinkCamera` is a native crash
     * (SIGSEGV in libcoresdk) — the crash-on-launch loop that only a force-stop cleared.
     */
    private val devLock = ReentrantLock()

    private inline fun <T> withDev(block: (MagDevice) -> T): T? =
        devLock.withLock { device?.let(block) }

    private val lock = ReentrantLock()
    private var latestTemp: IntArray? = null

    /**
     * Wait for the SDK to enumerate the camera.
     *
     * Releasing the device makes the firmware re-enumerate, so for a
     * second or two after `close()` the camera is absent from `UsbManager.getDeviceList`
     * and `MagDevice.getDevices` legitimately returns nothing. Polling rather than
     * failing on the first miss is the difference between "works on the 4th button
     * press" and "works".
     *
     * Note the `deviceId` changes across a re-enumeration, so the id must be taken from
     * this scan and not cached from an earlier one.
     */
    private fun awaitEnumeration(
        context: Context,
        timeoutMs: Long = 10_000,
    ): cn.com.magnity.coresdk.types.EnumInfo {
        val ids = ArrayList<cn.com.magnity.coresdk.types.EnumInfo>()
        val t0 = System.currentTimeMillis()
        var attempts = 0
        while (true) {
            attempts++
            for (pid in PIDS) {
                MagDevice.getDevices(context, VID, pid, ids)
                if (ids.isNotEmpty()) {
                    Log.i(TAG, "enumerated after $attempts attempt(s), " +
                        "${System.currentTimeMillis() - t0} ms")
                    return ids.first()
                }
            }
            if (System.currentTimeMillis() - t0 >= timeoutMs) break
            Thread.sleep(250)
        }
        // Dump what IS attached, so a VID/PID surprise is visible in the log.
        val usb = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        val present = usb.deviceList.values.joinToString(", ") {
            "%04x:%04x".format(it.vendorId, it.productId)
        }.ifEmpty { "none" }
        throw RuntimeException(
            "SDK enumerated no VID=0x833C device after $attempts attempts / " +
                "${timeoutMs}ms (attached: $present)"
        )
    }

    /**
     * Initialise the SDK and link to [usbDevice].
     *
     * [MagDevice.init] needs external storage mounted — it hands the native side
     * `getExternalFilesDir("")`, under which `prepareProcessImage` caches the
     * `mag_cali.bin` it pulls off the camera (`<dir>/cali/<name>.<n>.<serial>`).
     *
     * The first link of a camera on a phone has no cached file: prepare then returns
     * PENDING and downloads it (~1.8 MB) on a detached SDK thread, and streaming cannot
     * start until that finishes — so wait for it here. [onCaliProgress] gets 0..100.
     */
    override fun open(context: Context, onCaliProgress: (Int) -> Unit) {
        probeNativeLib()?.let { throw it }
        if (!initialized) {
            if (!MagDevice.init(context)) {
                throw RuntimeException(
                    "MagDevice.init failed — external storage not mounted? " +
                        "state=${android.os.Environment.getExternalStorageState()}"
                )
            }
            initialized = true
            Log.i(TAG, "MagDevice.init ok, cali dir=${context.getExternalFilesDir("")}")
        }

        val magDev = MagDevice()
        val enum = awaitEnumeration(context)
        Log.i(TAG, "linkCamera: name=${enum.name} id=${enum.id}")

        // Synchronous when USB permission is already granted (returns CONN_SUCC);
        // CONN_PENDING means the SDK raised its own permission dialog — a re-enumeration
        // can drop a permission we checked a moment earlier, so wait for the callback
        // instead of failing.
        val linked = java.util.concurrent.CountDownLatch(1)
        val callbackResult = java.util.concurrent.atomic.AtomicInteger(MagDevice.CONN_FAIL)
        val linkResult = magDev.linkCamera(context, enum.id) { r ->
            Log.i(TAG, "linkCallback: result=$r")
            callbackResult.set(r)
            linked.countDown()
        }
        val conn = when {
            linkResult == MagDevice.CONN_PENDING -> {
                Log.i(TAG, "linkCamera pending — waiting for USB permission…")
                if (!linked.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    magDev.dislinkCamera()
                    throw RuntimeException("linkCamera: USB permission dialog timed out")
                }
                callbackResult.get()
            }
            else -> linkResult
        }
        if (conn != MagDevice.CONN_SUCC) {
            magDev.dislinkCamera()
            throw RuntimeException("linkCamera failed: $conn")
        }

        val info = CameraInfo()
        if (magDev.getCameraInfo(info)) {
            width = info.fpaWidth; height = info.fpaHeight
            bmpWidth = info.bmpWidth; bmpHeight = info.bmpHeight
            fps = if (info.curFps > 0) info.curFps else info.maxFps
            cameraName = info.name ?: ""; cameraType = info.type ?: ""
            Log.i(TAG, "cameraInfo: fpa=${width}x$height bmp=${bmpWidth}x$bmpHeight " +
                "fps=$fps/${info.maxFps} name='$cameraName' type='$cameraType'")
        } else {
            magDev.dislinkCamera()
            throw RuntimeException("getCameraInfo failed after link")
        }

        // Pulls mag_cali.bin off the camera and builds the NUC / radiometric tables.
        // Callback (from libcoresdk's CNetComm::GetCaliFile): (0, 0..100) while the file
        // transfers, (0, 100) once it is written and closed, (<0, _) on a failed transfer.
        Log.i(TAG, "prepareProcessImage…")
        val caliDone = java.util.concurrent.CountDownLatch(1)
        val caliStatus = java.util.concurrent.atomic.AtomicInteger(0)
        val prep = magDev.prepareProcessImage { status, progress ->
            Log.i(TAG, "prepare: status=$status progress=$progress")
            if (status < 0) {
                caliStatus.set(status)
                caliDone.countDown()
            } else {
                onCaliProgress(progress)
                if (progress >= 100) caliDone.countDown()
            }
        }
        if (prep < 0) {
            magDev.dislinkCamera()
            throw RuntimeException("prepareProcessImage failed: $prep")
        }
        Log.i(TAG, "prepareProcessImage → $prep (${
            when (prep) {
                MagDevice.PREPARE_SUCC -> "SUCC"
                MagDevice.PREPARE_PENDING -> "PENDING"
                else -> "?"
            }
        })")
        caliDownloaded = prep == MagDevice.PREPARE_PENDING
        if (caliDownloaded) {
            if (!caliDone.await(CALI_DOWNLOAD_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)) {
                magDev.dislinkCamera()
                throw RuntimeException("calibration download from the camera timed out")
            }
            if (caliStatus.get() < 0) {
                magDev.dislinkCamera()
                throw RuntimeException("calibration download failed: ${caliStatus.get()}")
            }
            Log.i(TAG, "calibration file downloaded")
        }

        devLock.withLock { device = magDev }
        Log.i(TAG, "camera ready: ${width}x$height")
    }

    /** Start streaming. The SDK invokes the callback on its own thread. */
    override fun start() {
        val magDev = device ?: throw IllegalStateException("camera not open")
        require(width > 0 && height > 0) { "no FPA geometry from getCameraInfo" }
        running.set(true)
        // 4-arg overload only — the 5-arg one maps to StartProcessImage_v2, absent here.
        // Right after a download the SDK's prepare thread may still be unwinding (the
        // 100 % callback fires just before it returns), so allow a few short retries.
        var ok = false
        for (attempt in 0 until if (caliDownloaded) START_RETRIES else 1) {
            if (attempt > 0) Thread.sleep(START_RETRY_MS)
            ok = magDev.startProcessImage({ _, _ ->
                frameCount++
                pullFrameData()
            }, 0, 0)
            if (ok) break
        }
        if (!ok) {
            running.set(false)
            throw RuntimeException("startProcessImage failed")
        }
        Log.i(TAG, "stream started")
    }

    /** Per-pixel temperature, milli-Celsius as the SDK reports it (see [degC]). */
    private fun pullFrameData() {
        // tryLock, not lock: if close() holds devLock and dislinkCamera waits for this
        // callback thread to return, blocking here would deadlock — drop the frame instead.
        if (!running.get() || !devLock.tryLock()) return
        try {
            val magDev = device ?: return
            val buf = IntArray(width * height)
            if (magDev.getTemperatureData(buf, true, true)) {
                lock.withLock { latestTemp = buf }
            }
        } finally {
            devLock.unlock()
        }
    }

    /** Latest per-pixel temperature frame in milli-Celsius, or null if none yet. */
    override fun getTemperatureData(): IntArray? = lock.withLock { latestTemp }

    /** Blocking variant for the smoke test. */
    fun awaitTemperatureData(timeoutMs: Long = 5000): IntArray? {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            getTemperatureData()?.let { return it }
            Thread.sleep(20)
        }
        return null
    }

    /**
     * Single-point probe in milli-Celsius, SDK-side. Returns null when unlinked.
     *
     * NB the 2-arg overload is `(pos, size)`, not `(x, y)` — always pass [size]
     * explicitly so the 3-arg `(x, y, size)` form is selected.
     */
    fun probe(x: Int, y: Int, size: Int = 0): Int? =
        withDev { it.getTemperatureProbe(x, y, size) }?.takeIf { it != Int.MIN_VALUE }

    fun getFrameStats(): StatisticInfo? = withDev { magDev ->
        val info = StatisticInfo()
        if (magDev.getFrameStatisticInfo(info)) info else null
    }

    /** Shutter/housing temperature, milli-Celsius. GetSenorTemperature is absent here. */
    override fun sensorTemp(): Int? =
        withDev { it.getCurrentCameraInnerTemperature() }?.takeIf { it != Int.MIN_VALUE }

    /**
     * Target emissivity, (0, 1]. Goes through the SDK's fix-para block
     * (`CorrectionPara.fEmissivity` → `SetFixPara`), the same path the Elo wrapper's
     * `setEmissivity` takes. The block is read back first so distance / ambient / RH keep
     * the values the SDK holds. Takes effect because [pullFrameData] reads temperature
     * with external correction enabled.
     */
    override fun setEmissivity(e: Float): Boolean = withDev { magDev ->
        try {
            val p = CorrectionPara()
            if (!magDev.getFixPara(p)) return@withDev false
            p.fEmissivity = e.coerceIn(0.01f, 1f)
            val r = magDev.setFixPara(p)
            Log.i(TAG, "setFixPara emissivity=${p.fEmissivity} → $r")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "setFixPara failed", e); false
        }
    } ?: false

    override fun triggerFfc(): Boolean = try {
        withDev { it.triggrtFFC() } ?: false
    } catch (e: Exception) {
        Log.e(TAG, "triggrtFFC failed", e); false
    }

    // ---- shutdown -----------------------------------------------------------

    override fun stop() {
        running.set(false)
        runCatching { withDev { it.stopProcessImage() } }
            .onFailure { Log.e(TAG, "stopProcessImage failed", it) }
    }

    override fun close() {
        stop()
        // detach the handle under devLock so no native call can start on it after this
        val magDev = devLock.withLock { device.also { device = null } }
        runCatching { magDev?.dislinkCamera() }
            .onFailure { Log.e(TAG, "dislinkCamera failed", it) }
        lock.withLock { latestTemp = null }
    }
}
