package com.magnity.viewer.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.energy.ac020library.IrcamEngine
import com.energy.ac020library.IrcmdEngine
import com.energy.ac020library.bean.CommonParams
import com.energy.ac020library.bean.ErrorCode
import com.energy.ac020library.bean.HandleInitCallback
import com.energy.ac020library.bean.IIrFrameCallback
import com.energy.ac020library.bean.IrcmdError
import com.energy.ac020library.bean.UvcHandleParam
import com.energy.iruvccamera.usb.USBMonitor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * InfiRay Tiny2C (AC020 ASIC, 256×192 VOx) over UVC, through the vendor's AC020 SDK
 * (the AARs in `app/libs/ac020/`, from `Tiny-2C/SDK/Android/`; reference: the SDK demo's
 * `CameraPreviewManager`).
 *
 * The stream runs in `YUYV_AND_TEMP_OUTPUT`: each 256×386 UVC frame is the module's own
 * YUYV picture (192 rows, unused here), 2 info rows, then 192 rows of per-pixel temperature
 * as little-endian u16 in 1/64 K — `LibIRTemp`'s default scale, °C = raw / 64 − 273.15.
 *
 * Not done (yet): the SDK demo's TC2C warm-up compensation (`TempCompensation`, a 30 s
 * shutter/VTEMP correction after start), high/low gain switching (default high gain,
 * −15…150 °C), and a sensor temperature for [sensorTemp].
 *
 * USB permission is the caller's: [open] expects it granted, like the MAG path's
 * auto-connect. The SDK's [USBMonitor] is used only to open the device.
 */
class Tiny2cSource(val device: UsbDevice) : ThermalSource {

    companion object {
        private const val TAG = "Tiny2cSource"
        const val W = 256
        const val H = 192
        private const val INFO_ROWS = 2
        private const val STREAM_H = H * 2 + INFO_ROWS
        private const val FPS = 25
        private const val INIT_TIMEOUT_S = 10L
        /** Temperature rows start after the YUYV picture and the info rows (bytes). */
        private const val TEMP_OFFSET = W * H * 2 + W * INFO_ROWS * 2
        /** advEnvCorrectEMSSet takes emissivity × 16384 (range 1..16384). */
        private const val EMS_ONE = 16384

        /**
         * USB ids an AC020 module enumerates under: 0x3474:0x4321 ("UVC Camera") reported
         * from a Tiny2C (2026-10-21); 0x0A0A:0x2602 is the one the vendor demo's device
         * filter singles out, kept for other AC020 modules (unconfirmed).
         */
        private val KNOWN = setOf(0x3474 to 0x4321, 0x0A0A to 0x2602)

        fun isKnown(device: UsbDevice): Boolean = (device.vendorId to device.productId) in KNOWN

        fun isUvc(device: UsbDevice): Boolean = (0 until device.interfaceCount).any {
            device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO
        }
    }

    override val kind = "tiny2c"
    /** From the spec sheet's 3.2 mm lens, 51.3° across 256 px: 128 / tan(25.65°). */
    override val focalPx = 266.6f
    override val width = W
    override val height = H
    override val fps = FPS
    @Volatile override var frameCount = 0L; private set

    private var monitor: USBMonitor? = null
    private var ctrl: USBMonitor.UsbControlBlock? = null
    @Volatile private var engine: IrcamEngine? = null
    @Volatile private var cmd: IrcmdEngine? = null
    private val running = AtomicBoolean(false)

    private val lock = ReentrantLock()
    private var latestTemp: IntArray? = null

    override fun open(context: Context, onCaliProgress: (Int) -> Unit) {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        check(usb.hasPermission(device)) { "no USB permission for %04x:%04x"
            .format(device.vendorId, device.productId) }

        val mon = USBMonitor(context.applicationContext, NoopListener)
        monitor = mon
        val block = mon.openDevice(device)
        ctrl = block
        val param = UvcHandleParam().apply {
            setCtrlBlock(block)
            fps = FPS
            bandwidth = 1.0f
        }
        val eng = IrcamEngine.Builder()
            .setLogLevel(CommonParams.LogLevel.SDK_LOG_ERROR)
            .setStreamWidth(W)
            .setStreamHeight(STREAM_H)
            .setDriverType(CommonParams.DriverType.USB)
            .setFrameOutputFormat(CommonParams.FrameOutputFormat.YUYV_AND_TEMP_OUTPUT)
            .setUvcHandleParam(param)
            .build()
        engine = eng

        val done = CountDownLatch(1)
        var fail: ErrorCode? = null
        eng.initHandle(object : HandleInitCallback {
            override fun onSuccess(ircmd: IrcmdEngine) {
                cmd = ircmd
                done.countDown()
            }
            override fun onFail(code: ErrorCode) {
                fail = code
                done.countDown()
            }
        })
        if (!done.await(INIT_TIMEOUT_S, TimeUnit.SECONDS)) {
            close()
            throw RuntimeException("AC020 initHandle timed out")
        }
        if (cmd == null) {
            close()
            throw RuntimeException("AC020 initHandle failed: $fail")
        }
        Log.i(TAG, "open: %04x:%04x ircam=%s".format(device.vendorId, device.productId,
            eng.ircamVersion()))
    }

    override fun start() {
        val eng = engine ?: throw IllegalStateException("camera not open")
        running.set(true)
        eng.setIrFrameCallback(frameCallback)
        val r = eng.startVideoStream()
        if (r != 0) {
            running.set(false)
            eng.setIrFrameCallback(null)
            throw RuntimeException("AC020 startVideoStream failed: $r")
        }
        Log.i(TAG, "stream started")
    }

    private val frameCallback = IIrFrameCallback { frame, length ->
        if (!running.get() || length < TEMP_OFFSET + W * H * 2) return@IIrFrameCallback
        val buf = IntArray(W * H)
        var o = TEMP_OFFSET
        for (i in buf.indices) {
            val raw = (frame[o].toInt() and 0xff) or ((frame[o + 1].toInt() and 0xff) shl 8)
            buf[i] = ((raw * 125) shr 3) - 273150   // raw/64 K → milli-°C
            o += 2
        }
        lock.withLock { latestTemp = buf }
        frameCount++
    }

    override fun getTemperatureData(): IntArray? = lock.withLock { latestTemp }

    override fun sensorTemp(): Int? = null

    /**
     * Through the module's environment correction: off at 1.0 (the factory reading),
     * otherwise on with EMS = [e] × 16384. Atmosphere / reflected temperature stay at the
     * module's defaults.
     */
    override fun setEmissivity(e: Float): Boolean {
        val c = cmd ?: return false
        return try {
            if (e >= 0.995f) {
                c.advEnvCorrectSwitchSet(CommonParams.BasicEnableStatus.BASIC_DISABLE) ==
                    IrcmdError.IRCMD_SUCCESS
            } else {
                val ems = (e.coerceIn(0.01f, 1f) * EMS_ONE).toInt().coerceIn(1, EMS_ONE)
                c.advEnvCorrectEMSSet(ems) == IrcmdError.IRCMD_SUCCESS &&
                    c.advEnvCorrectSwitchSet(CommonParams.BasicEnableStatus.BASIC_ENABLE) ==
                    IrcmdError.IRCMD_SUCCESS
            }
        } catch (t: Throwable) {
            Log.e(TAG, "setEmissivity failed", t); false
        }
    }

    override fun triggerFfc(): Boolean = try {
        cmd?.basicFFCUpdate() == IrcmdError.IRCMD_SUCCESS
    } catch (e: Throwable) {
        Log.e(TAG, "basicFFCUpdate failed", e); false
    }

    override fun stop() {
        running.set(false)
        engine?.let { eng ->
            runCatching {
                eng.setIrFrameCallback(null)
                eng.stopVideoStream()
            }.onFailure { Log.e(TAG, "stopVideoStream failed", it) }
        }
    }

    override fun close() {
        stop()
        val eng = engine.also { engine = null }
        cmd = null
        runCatching {
            eng?.closeVideoStream()
            eng?.releaseVideoStream()
            eng?.destroyHandle()
        }.onFailure { Log.e(TAG, "AC020 release failed", it) }
        runCatching { ctrl?.close() }
        ctrl = null
        runCatching { monitor?.destroy() }
        monitor = null
        lock.withLock { latestTemp = null }
    }

    /** The viewer handles attach/permission itself; the monitor only opens the device. */
    private object NoopListener : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice?) {}
        override fun onGranted(device: UsbDevice?, granted: Boolean) {}
        override fun onConnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?,
                               createNew: Boolean) {}
        override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {}
        override fun onDetach(device: UsbDevice?) {}
        override fun onCancel(device: UsbDevice?) {}
        override fun onException(e: Exception?) {}
    }
}
