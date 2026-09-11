package com.magnity.viewer.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Driver for the Magnity 833c USB thermal camera — Android USB-host port of the
 * Linux `magcam.py` (itself a reimplementation of the Android coresdk protocol,
 * reverse-engineered in PROTOCOL.md).
 *
 * Usage:
 *   val cam = MagCamera()
 *   cam.open(usbManager, device)   // device permission must already be granted
 *   cam.start()
 *   cam.triggerFfc()
 *   val frame = cam.getFrame()     // FloatArray H*W, FFC-corrected if reference set
 *   cam.stop(); cam.close()
 *
 * Protocol gotcha (see PROTOCOL.md): EVERY command written to EP 0x03 must be
 * followed by a read of its EP-0x82 ack, or the firmware wedges and the device
 * re-enumerates. This includes the 4-byte StartTransferImg/StopTransferImg.
 */
class MagCamera {

    companion object {
        const val TAG = "MagCamera"
        const val VID = 0x833C
        val PIDS = intArrayOf(0x0001, 0x0002)

        // endpoint addresses (interface 0, all bulk)
        private const val EP_CMD_OUT = 0x03
        private const val EP_CMD_IN = 0x82
        private const val EP_IMG_IN = 0x81
        private const val EP_CALI_IN = 0x84

        // command words (PROTOCOL.md)
        private const val GET_PARAM1 = 0x6BB6B66B
        private const val GET_PARAM2 = 0x6BB6B66C
        private const val GET_CALIINFO = 0x6BB6B66F
        private const val GET_CALIFILE = 0x6BB6B670
        private const val SET_SHUTTER = 0x6BB6B672
        private const val START_XFER = 0x6BB6B673
        private const val STOP_XFER = 0x6BB6B674

        private const val FRAME_MAGIC = 0x1BB1B11B
        private const val HDR_LEN = 28
        private const val TAIL_LEN = 28

        fun isMagnity(device: UsbDevice): Boolean =
            device.vendorId == VID && PIDS.contains(device.productId)
    }

    var width = 0; private set
    var height = 0; private set
    var fps = 15; private set
    @Volatile var frameCount = 0L; private set
    /** diagnostics: exceptions swallowed by the reader loop */
    @Volatile var readErrors = 0L; private set

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var epCmdOut: UsbEndpoint? = null
    private var epCmdIn: UsbEndpoint? = null
    private var epImgIn: UsbEndpoint? = null

    private var frameBytes = 0
    private var period = 0

    private val running = AtomicBoolean(false)
    private var readerThread: Thread? = null

    private val lock = ReentrantLock()
    private val cmdLock = ReentrantLock()          // protocol is mutex-serialised
    /**
     * Android's UsbDeviceConnection.bulkTransfer is NOT reliable when called
     * concurrently from multiple threads (Linux libusb is fine — which is why the
     * Python driver never hit this). Serialise EVERY transfer; the reader's 300 ms
     * timeout bounds any cmd() wait.
     */
    private val usbLock = ReentrantLock()
    private var latest: ShortArray? = null          // raw uint16 pixels (as signed shorts)
    private var latestTail: ByteArray? = null       // 28-byte frame tail (telemetry)
    /** Shutter-closed per-pixel reference; null until triggerFfc() succeeds. */
    var ffcRef: FloatArray? = null; private set

    // ---- low level ----------------------------------------------------------

    private fun cmd(cmdWord: Int, param: Int = 0, packetLen: Int = 8, ackTimeoutMs: Int = 2000): ByteArray {
        val conn = connection ?: throw IllegalStateException("camera not open")
        cmdLock.withLock {
            val pkt = ByteBuffer.allocate(packetLen).order(ByteOrder.LITTLE_ENDIAN)
            pkt.putInt(cmdWord)
            if (packetLen == 8) pkt.putInt(param)
            val (wrote, got, ack) = usbLock.withLock {
                val wrote = conn.bulkTransfer(epCmdOut, pkt.array(), packetLen, 500)
                if (wrote != packetLen) Log.w(TAG, "cmd 0x${cmdWord.toUInt().toString(16)} short write: $wrote")
                val ack = ByteArray(4096)
                val got = conn.bulkTransfer(epCmdIn, ack, ack.size, ackTimeoutMs)
                Triple(wrote, got, ack)
            }
            return if (got > 0) ack.copyOf(got) else ByteArray(0)
        }
    }

    private fun drainCmd() {
        val conn = connection ?: return
        val buf = ByteArray(4096)
        for (i in 0 until 8) {
            val got = conn.bulkTransfer(epCmdIn, buf, buf.size, 100)
            if (got <= 0) break
        }
    }

    // ---- lifecycle ----------------------------------------------------------

    /** Open the device. USB permission for [device] must already be granted. */
    fun open(usbManager: UsbManager, device: UsbDevice) {
        require(isMagnity(device)) { "not a Magnity camera: $device" }
        val intf = device.getInterface(0)
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            when (ep.address and 0xFF) {
                EP_CMD_OUT -> epCmdOut = ep
                EP_CMD_IN -> epCmdIn = ep
                EP_IMG_IN -> epImgIn = ep
            }
        }
        checkNotNull(epCmdOut) { "EP 0x03 (cmd out) not found" }
        checkNotNull(epCmdIn) { "EP 0x82 (cmd in) not found" }
        checkNotNull(epImgIn) { "EP 0x81 (img in) not found" }

        val conn = usbManager.openDevice(device)
            ?: throw RuntimeException("openDevice failed (permission?)")
        if (!conn.claimInterface(intf, true)) {
            conn.close()
            throw RuntimeException("claimInterface failed")
        }
        connection = conn
        usbInterface = intf

        // clear any stale HALT on the bulk endpoints — after a previous app crash the
        // firmware can leave them stalled and every subsequent transfer fails silently.
        // Same insurance as magcam.py's clear_halt(0x81).
        for (ep in listOfNotNull(epCmdOut, epCmdIn, epImgIn)) {
            val rc = conn.controlTransfer(
                0x02,        // bmRequestType: endpoint recipient
                0x01,        // bRequest: CLEAR_FEATURE
                0x0000,      // wValue: ENDPOINT_HALT
                ep.address,
                null, 0, 200,
            )
            Log.i(TAG, "clearHalt(${ep.address}) rc=$rc")
        }

        // make sure no stale stream is running, then read geometry
        runCatching { cmd(STOP_XFER, packetLen = 4) }
        drainCmd()
        readParams()
    }

    private fun readParams() {
        var r = ByteArray(0)
        for (attempt in 0 until 6) {
            r = cmd(GET_PARAM1)
            if (r.size >= 32) break
            Thread.sleep(150)
        }
        if (r.size < 32) throw RuntimeException("GetParameter1 failed (${r.size} bytes)")
        val bb = ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN)
        width = bb.getInt(20)
        height = bb.getInt(24)
        fps = bb.getInt(28).coerceAtLeast(1)
        frameBytes = width * height * 2
        period = HDR_LEN + frameBytes + TAIL_LEN
        // the official app also issues these during init; read their acks
        cmd(GET_PARAM2)
        cmd(GET_CALIINFO)
        Log.i(TAG, "camera ${width}x$height @ ${fps}fps")
    }

    fun start() {
        running.set(true)
        readerThread = thread(name = "mag-usb-reader", isDaemon = true) { readerLoop() }
        Thread.sleep(50)
        cmd(START_XFER, packetLen = 4)      // 4-byte start + mandatory ack
    }

    private fun readerLoop() {
        val conn = connection ?: return
        val chunk = ByteArray(0x8000)
        var buf = ByteArray(0)
        var bufLen = 0

        fun ensureCapacity(extra: Int) {
            if (bufLen + extra > buf.size) buf = buf.copyOf((bufLen + extra).coerceAtLeast(buf.size * 2 + 1))
        }

        while (running.get()) {
            try {
                val got = usbLock.withLock { conn.bulkTransfer(epImgIn, chunk, chunk.size, 300) }
                if (got <= 0) continue          // timeout — keep draining
                ensureCapacity(got)
                System.arraycopy(chunk, 0, buf, bufLen, got)
                bufLen += got

                // parse complete magic-delimited frames out of buf
                while (true) {
                    val i = findMagic(buf, bufLen)
                    if (i < 0) {
                        // keep a tail that may hold a partial magic
                        if (bufLen > 4) {
                            System.arraycopy(buf, bufLen - 4, buf, 0, 4)
                            bufLen = 4
                        }
                        break
                    }
                    if (bufLen - i < period) {
                        if (i > 0) {
                            System.arraycopy(buf, i, buf, 0, bufLen - i)
                            bufLen -= i
                        }
                        break
                    }
                    val px = ShortArray(width * height)
                    ByteBuffer.wrap(buf, i + HDR_LEN, frameBytes)
                        .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(px)
                    val tail = buf.copyOfRange(i + HDR_LEN + frameBytes, i + period)
                    val consumed = i + period
                    System.arraycopy(buf, consumed, buf, 0, bufLen - consumed)
                    bufLen -= consumed
                    lock.withLock {
                        latest = px
                        latestTail = tail
                        frameCount++
                    }
                }
            } catch (e: Throwable) {
                // never let the reader thread kill the process — log and resync
                Log.e(TAG, "readerLoop error", e)
                readErrors++
                bufLen = 0
                buf = ByteArray(0)
                lock.withLock {
                    latest = null
                    latestTail = null
                }
            }
        }
    }

    private fun findMagic(b: ByteArray, len: Int): Int {
        // little-endian 0x1BB1B11B => bytes 1B B1 B1 1B
        var i = 0
        val last = len - 4
        while (i <= last) {
            if (b[i] == 0x1B.toByte() && b[i + 1] == 0xB1.toByte() &&
                b[i + 2] == 0xB1.toByte() && b[i + 3] == 0x1B.toByte()
            ) return i
            i++
        }
        return -1
    }

    // ---- telemetry ----------------------------------------------------------

    /**
     * Live FPA / sensor temperature in raw counts (frame tail offset 8, u32).
     * Same units as the factory NUC grid's FPA axis. Null if no frame yet.
     */
    fun sensorTempRaw(): Int? {
        val t = lock.withLock { latestTail } ?: return null
        if (t.size < 12) return null
        return ByteBuffer.wrap(t).order(ByteOrder.LITTLE_ENDIAN).getInt(8)
    }

    // ---- frames -------------------------------------------------------------

    /** Latest raw frame as unsigned 16-bit counts in a FloatArray (H*W), or null. */
    fun getRaw(timeoutMs: Long = 2000): FloatArray? {
        val t0 = System.currentTimeMillis()
        while (true) {
            val px = lock.withLock { latest }
            if (px != null) {
                val out = FloatArray(px.size)
                for (k in px.indices) out[k] = (px[k].toInt() and 0xFFFF).toFloat()
                return out
            }
            if (System.currentTimeMillis() - t0 >= timeoutMs) return null
            Thread.sleep(10)
        }
    }

    /** Latest frame, FFC-corrected if a shutter reference is set, else raw. */
    fun getFrame(timeoutMs: Long = 2000): FloatArray? {
        val raw = getRaw(timeoutMs) ?: return null
        val ref = ffcRef ?: return raw
        var mean = 0.0
        for (v in ref) mean += v
        mean /= ref.size
        val m = mean.toFloat()
        for (k in raw.indices) raw[k] = raw[k] - ref[k] + m
        return raw
    }

    private fun clearLatest() = lock.withLock { latest = null }

    // ---- flat-field (shutter) correction -------------------------------------

    /**
     * Close the shutter, average a few frames as the flat-field reference, reopen.
     * Blocking (~1-2 s) - call from a background thread.
     * Drains EP 0x82 after each shutter command: the firmware pushes async FFC events
     * there; left unread they wedge the cmd buffer and the device re-enumerates.
     */
    fun triggerFfc(settleMs: Long = 500, navg: Int = 4): Boolean {
        cmd(SET_SHUTTER, 0)         // close shutter
        Thread.sleep(settleMs)
        clearLatest()
        val acc = FloatArray(width * height)
        var n = 0
        val t0 = System.currentTimeMillis()
        while (n < navg && System.currentTimeMillis() - t0 < 2000) {
            val f = getRaw(timeoutMs = 500)
            if (f != null) {
                for (k in acc.indices) acc[k] += f[k]
                n++
                clearLatest()
                Thread.sleep((1000L / fps).coerceAtLeast(1))
            }
        }
        if (n > 0) {
            for (k in acc.indices) acc[k] /= n
            ffcRef = acc
        }
        cmd(SET_SHUTTER, 1)         // open shutter
        Thread.sleep(settleMs)
        return n > 0
    }

    /** Swallow async EP-0x82 notifications (FFC-done etc.). */
    private fun drainCmdAcks() {
        val conn = connection ?: return
        val buf = ByteArray(4096)
        for (i in 0 until 8) {
            val got = usbLock.withLock { conn.bulkTransfer(epCmdIn, buf, buf.size, 60) }
            if (got <= 0) break
        }
    }

    fun clearFfc() { ffcRef = null }

    // ---- shutdown -------------------------------------------------------------

    fun stop() {
        running.set(false)
        readerThread?.join(1000)
        readerThread = null
        runCatching { cmd(STOP_XFER, packetLen = 4) }
    }

    fun close() {
        val conn = connection
        connection = null
        if (conn != null) {
            usbInterface?.let { runCatching { conn.releaseInterface(it) } }
            runCatching { conn.close() }
        }
        usbInterface = null
    }
}
