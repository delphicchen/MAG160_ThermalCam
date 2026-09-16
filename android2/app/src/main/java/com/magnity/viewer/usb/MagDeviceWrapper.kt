package com.magnity.viewer.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import cn.com.magnity.coresdk.MagDevice
import cn.com.magnity.coresdk.types.CameraInfo
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
class MagDeviceWrapper {

    companion object {
        const val TAG = "MagDeviceWrapper"
        const val VID = 0x833C
        val PIDS = intArrayOf(0x0001, 0x0002)

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

    /** FPA geometry as reported by the SDK (valid after [open]). */
    var width = 0; private set
    var height = 0; private set
    /** Palette-mapped output bitmap size as reported by the SDK. */
    var bmpWidth = 0; private set
    var bmpHeight = 0; private set
    var fps = 0; private set
    var cameraName = ""; private set
    var cameraType = ""; private set

    @Volatile var frameCount = 0L; private set

    private var device: MagDevice? = null
    private val running = AtomicBoolean(false)

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
     */
    fun open(context: Context) {
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
        Log.i(TAG, "prepareProcessImage…")
        val prep = magDev.prepareProcessImage { status, progress ->
            Log.i(TAG, "prepare: status=$status progress=$progress")
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

        device = magDev
        Log.i(TAG, "camera ready: ${width}x$height")
    }

    /** Start streaming. The SDK invokes the callback on its own thread. */
    fun start() {
        val magDev = device ?: throw IllegalStateException("camera not open")
        require(width > 0 && height > 0) { "no FPA geometry from getCameraInfo" }
        running.set(true)
        // 4-arg overload only — the 5-arg one maps to StartProcessImage_v2, absent here.
        val ok = magDev.startProcessImage({ _, _ ->
            frameCount++
            pullFrameData()
        }, 0, 0)
        if (!ok) {
            running.set(false)
            throw RuntimeException("startProcessImage failed")
        }
        Log.i(TAG, "stream started")
    }

    /** Per-pixel temperature, milli-Celsius as the SDK reports it (see [degC]). */
    private fun pullFrameData() {
        val magDev = device ?: return
        val buf = IntArray(width * height)
        if (magDev.getTemperatureData(buf, true, true)) {
            lock.withLock { latestTemp = buf }
        }
    }

    /** Latest per-pixel temperature frame in milli-Celsius, or null if none yet. */
    fun getTemperatureData(): IntArray? = lock.withLock { latestTemp }

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
        device?.getTemperatureProbe(x, y, size)?.takeIf { it != Int.MIN_VALUE }

    fun getFrameStats(): StatisticInfo? {
        val magDev = device ?: return null
        val info = StatisticInfo()
        return if (magDev.getFrameStatisticInfo(info)) info else null
    }

    /** Shutter/housing temperature, milli-Celsius. GetSenorTemperature is absent here. */
    fun sensorTemp(): Int? =
        device?.getCurrentCameraInnerTemperature()?.takeIf { it != Int.MIN_VALUE }

    fun triggerFfc(): Boolean = try {
        device?.triggrtFFC() ?: false
    } catch (e: Exception) {
        Log.e(TAG, "triggrtFFC failed", e); false
    }

    // ---- shutdown -----------------------------------------------------------

    fun stop() {
        running.set(false)
        runCatching { device?.stopProcessImage() }
            .onFailure { Log.e(TAG, "stopProcessImage failed", it) }
    }

    fun close() {
        stop()
        runCatching { device?.dislinkCamera() }
            .onFailure { Log.e(TAG, "dislinkCamera failed", it) }
        device = null
        lock.withLock { latestTemp = null }
    }
}
