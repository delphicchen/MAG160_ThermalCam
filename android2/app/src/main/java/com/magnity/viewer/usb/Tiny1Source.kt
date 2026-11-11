package com.magnity.viewer.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.energy.iruvc.ircmd.ConcreteIRCMDBuilder
import com.energy.iruvc.ircmd.IRCMD
import com.energy.iruvc.ircmd.IRCMDType
import com.energy.iruvc.usb.USBMonitor
import com.energy.iruvc.utils.CommonParams
import com.energy.iruvc.utils.IFrameCallback
import com.energy.iruvc.uvc.ConcreateUVCBuilder
import com.energy.iruvc.uvc.UVCCamera
import com.energy.iruvc.uvc.UVCType
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * InfiRay Tiny1C / Tiny1B (256×192 VOx) over UVC, through the vendor's IRUVC SDK 1.4.2
 * (`app/libs/iruvc/`, from `Tiny1C-SDK.zip` → `SDK/Android/ANDROID_IRUVC_SDK_…_1.4.2`;
 * reference: the SDK sample's `camera/IRUVC.java`). The same SDK drives the older Tiny1B
 * (the sample special-cases its PID 0x3901 as `TC_B`).
 *
 * The module's default output is `IMAGE_AND_TEMP_OUTPUT`: a 256×384 UVC frame = its own
 * YUYV picture (256×192, unused here), then 256×192 temperatures as little-endian u16,
 * plus one trailing flag byte (1 = the SDK asks for a USB restart). Tiny1C: raw / 64 K
 * (the sample's `(T + 273.15) * 16 * 4`); the Tiny1B demo of 2020 divides by 16 instead —
 * the scale is picked per frame from the values ([scaleShift]), since the two ranges don't
 * overlap for any real scene. The Tiny1B demo also lays its frame out 192 wide × 256 tall;
 * [diag] shows which row length gives the smoother picture, to confirm it on hardware.
 *
 * Not done (yet): high/low gain switching (default high gain), the SDK's NUC-table
 * temperature correction, and [sensorTemp].
 */
class Tiny1Source(val device: UsbDevice) : ThermalSource {

    companion object {
        private const val TAG = "Tiny1Source"
        private const val SENSOR_W = 256
        private const val SENSOR_H = 192
        private const val STREAM_H = SENSOR_H * 2
        private const val FPS = 25
        private const val PLANE = SENSOR_W * SENSOR_H * 2
        /** TPD_PROP_EMS: emissivity in 1/128, 1..128. */
        private const val EMS_ONE = 128
        /** Realtek's VID — the USB bridge InfiRay's Tiny1 modules enumerate under. */
        private const val VID_REALTEK = 0x0BDA
        /** Tiny1B, from its 2020 Android demo (and the IRUVC sample's `TC_B`). */
        const val PID_TINY1B = 0x3901

        fun isKnown(device: UsbDevice): Boolean =
            device.vendorId == VID_REALTEK && Tiny2cSource.isUvc(device)

        fun isTiny1b(device: UsbDevice): Boolean =
            device.vendorId == VID_REALTEK && device.productId == PID_TINY1B
    }

    private val tiny1b = isTiny1b(device)
    override val kind = if (tiny1b) "tiny1b" else "tiny1c"
    /** Datasheet lens 3.2 mm ≈ 56° across 256 px: 128 / tan(28°). */
    override val focalPx = 240.7f
    // the Tiny1B demo draws its frame 192 wide × 256 tall; the Tiny1C sample 256 × 192
    override val width = if (tiny1b) SENSOR_H else SENSOR_W
    override val height = if (tiny1b) SENSOR_W else SENSOR_H
    override val fps = FPS
    @Volatile override var frameCount = 0L; private set

    private var monitor: USBMonitor? = null
    private var ctrl: USBMonitor.UsbControlBlock? = null
    @Volatile private var uvc: UVCCamera? = null
    @Volatile private var cmd: IRCMD? = null
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
        val cam = ConcreateUVCBuilder().setUVCType(UVCType.USB_UVC).build()
        cam.setDefaultBandwidth(1.0f)
        val r = cam.openUVCCamera(block)
        if (r != 0) {
            close()
            throw RuntimeException("IRUVC openUVCCamera failed: $r")
        }
        uvc = cam
        sizes = runCatching {
            cam.supportedSizeList.joinToString(" ") { "${it.width}x${it.height}" }
        }.getOrDefault("?")
        cmd = ConcreteIRCMDBuilder()
            .setIrcmdType(IRCMDType.USB_IR_256_384)
            .setIdCamera(cam.nativePtr)
            .build()
        if (cmd == null) Log.w(TAG, "IRCMD not created — no FFC / emissivity")
        val setSize = cam.setUSBPreviewSize(SENSOR_W, STREAM_H)
        Log.i(TAG, "open: %04x:%04x %s sizes=[%s] setSize=%d".format(device.vendorId,
            device.productId, kind, sizes, setSize))
    }

    override fun start() {
        val cam = uvc ?: throw IllegalStateException("camera not open")
        running.set(true)
        cam.setOpenStatus(true)
        cam.setFrameCallback(frameCallback)
        val r = cam.onStartPreview()
        if (r != 0) {
            running.set(false)
            cam.setFrameCallback(null)
            throw RuntimeException("IRUVC onStartPreview failed: $r")
        }
        Log.i(TAG, "stream started")
    }

    // what the SDK actually hands over, for [diag]
    @Volatile private var sizes = "?"
    @Volatile private var callbacks = 0L
    @Volatile private var shortFrames = 0L
    @Volatile private var restartFlags = 0L
    @Volatile private var lastLength = 0
    @Volatile private var rawLo = 0
    @Volatile private var rawHi = 0
    @Volatile private var shift = 6
    /** Mean |Δ| between vertically adjacent pixels for row lengths 256 and 192: the true
     *  row length gives the smaller one. */
    @Volatile private var rowDelta256 = 0
    @Volatile private var rowDelta192 = 0

    /** raw → K shift: 6 (÷64, Tiny1C) or 4 (÷16, Tiny1B demo). ÷64 puts any real scene
     *  (−40…600 °C) at raw ≥ 14 700; ÷16 below 14 000. */
    private fun scaleShift(mean: Int) = if (mean >= 14_000) 6 else 4

    private val frameCallback = IFrameCallback { frame ->
        callbacks++
        lastLength = frame.size
        if (!running.get()) return@IFrameCallback
        if (frame.size < PLANE * 2) { shortFrames++; return@IFrameCallback }
        if (frame.size > PLANE * 2 && frame[frame.size - 1].toInt() == 1) {
            restartFlags++; return@IFrameCallback
        }
        val n = SENSOR_W * SENSOR_H
        val raw = IntArray(n)
        var o = PLANE
        var lo = 0xffff; var hi = 0; var sum = 0L
        for (i in 0 until n) {
            val v = (frame[o].toInt() and 0xff) or ((frame[o + 1].toInt() and 0xff) shl 8)
            raw[i] = v
            sum += v
            if (v < lo) lo = v
            if (v > hi) hi = v
            o += 2
        }
        val s = scaleShift((sum / n).toInt())
        val buf = IntArray(n)
        // raw / 2^s K → milli-°C
        for (i in 0 until n) buf[i] = ((raw[i] * 1000) shr s) - 273150
        if (frameCount % 25 == 0L) {
            rowDelta256 = rowDelta(raw, 256)
            rowDelta192 = rowDelta(raw, 192)
        }
        rawLo = lo; rawHi = hi; shift = s
        lock.withLock { latestTemp = buf }
        frameCount++
    }

    private fun rowDelta(a: IntArray, w: Int): Int {
        var d = 0L
        for (i in 0 until a.size - w) d += kotlin.math.abs(a[i] - a[i + w])
        return (d / (a.size - w)).toInt()
    }

    override fun getTemperatureData(): IntArray? = lock.withLock { latestTemp }

    override fun diag(): String {
        val div = 1 shl shift
        return "IRUVC $kind cb=$callbacks short=$shortFrames restart=$restartFlags len=$lastLength " +
            "(want ${PLANE * 2}+1)\nsizes $sizes · raw $rawLo..$rawHi ÷$div " +
            "(%.1f..%.1f °C) · rowΔ 256:$rowDelta256 192:$rowDelta192"
                .format(rawLo.toFloat() / div - 273.15f, rawHi.toFloat() / div - 273.15f)
    }

    override fun sensorTemp(): Int? = null

    /** TPD_PROP_EMS = [e] × 128 (the module's own temperature correction). */
    override fun setEmissivity(e: Float): Boolean {
        val c = cmd ?: return false
        return try {
            val ems = (e.coerceIn(0.01f, 1f) * EMS_ONE).toInt().coerceIn(1, EMS_ONE)
            c.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_EMS,
                CommonParams.PropTPDParamsValue.NumberType(ems.toString())) == 0
        } catch (t: Throwable) {
            Log.e(TAG, "setEmissivity failed", t); false
        }
    }

    /** Shutter: the Tiny1B has its own command, the others an OOC/B update (as the sample). */
    override fun triggerFfc(): Boolean = try {
        val c = cmd
        when {
            c == null -> false
            tiny1b -> c.tc1bShutterManual() == 0
            else -> c.updateOOCOrB(CommonParams.UpdateOOCOrBType.B_UPDATE) == 0
        }
    } catch (e: Throwable) {
        Log.e(TAG, "FFC failed", e); false
    }

    override fun stop() {
        running.set(false)
        uvc?.let { cam ->
            runCatching {
                cam.setFrameCallback(null)
                if (cam.openStatus) cam.onStopPreview()
                cam.setOpenStatus(false)
            }.onFailure { Log.e(TAG, "onStopPreview failed", it) }
        }
    }

    override fun close() {
        stop()
        runCatching { cmd?.onDestroy() }
        cmd = null
        val cam = uvc.also { uvc = null }
        runCatching {
            cam?.onDestroyPreview()
            cam?.closeUVCCamera()
        }.onFailure { Log.e(TAG, "IRUVC release failed", it) }
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
        override fun onDettach(device: UsbDevice?) {}
        override fun onCancel(device: UsbDevice?) {}
    }
}
