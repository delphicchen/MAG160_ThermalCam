package com.magnity.viewer.usb

import android.content.Context
import android.content.SharedPreferences
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Hikvision TB-4117-3/S (160×120 VOx) on its USB "HCUSB" UVC firmware — **diagnostic
 * build** (PLAN_HIK4117.md, step 1).
 *
 * No vendor SDK: plain [UsbDeviceConnection], after the protocol notes of
 * github.com/vilicvane/hik-tb4117 (`docs/hcusb-uvc-protocol.md`, reverse-engineered from
 * the vendor's `libHCUSBSDK.so` and checked on a unit):
 *  - video: one bulk endpoint, 240×320 MJPEG with the module's own palette and OSD,
 *    whatever format the probe asks for;
 *  - commands: UVC extension unit 10 on the control interface. Selector 5 picks
 *    (group, sub), the group's selector (1 system · 2 image · 3 thermal) carries the data.
 *    The channel stays locked until the version probe (selector 4 = "2.0") has run after
 *    each attach, and only answers while video streams;
 *  - temperature: command 2046 (group 3 sub 9, a two-phase "DoubleGet") returns a JPEG and
 *    a 120×160 f32 °C matrix, read back in ≤512-byte control transfers — ~1–2 s a frame.
 *
 * So this source streams the video only to count and classify what arrives, and loops
 * 2046 for the temperature frames the viewer shows. [runStreamTest] is the experiment the
 * build exists for: switch the thermal stream parameter (2038/2039, byVideoCodingType)
 * and each advertised UVC format, and record what the bulk stream turns into — a
 * per-frame temperature stream there would make the 4117 a live camera like the others.
 *
 * Writes to the module: body-temperature compensation (2044/2045, which lifts skin by
 * ~3 °C) is off while connected and restored on [stop]; the stream test restores the
 * stream parameter, and keeps the original in prefs until it is back, so a crash mid-test
 * is undone on the next connect. Emissivity and FFC are not wired up in this build.
 */
class Hik4117Source(val device: UsbDevice) : ThermalSource {

    companion object {
        private const val TAG = "Hik4117Source"
        const val VID = 0x2BDF
        const val PID = 0x0101
        /** The 2046 matrix: portrait, like the module's 240×320 video. */
        const val W = 120
        const val H = 160

        private const val XU_UNIT = 10
        private const val TIMEOUT_MS = 1000
        private const val SET_CUR = 0x01
        private const val GET_CUR = 0x81
        private const val GET_LEN = 0x85
        private const val REQ_OUT = 0x21        // class request to an interface
        private const val REQ_IN = 0xA1
        private const val VS_PROBE = 0x0100
        private const val VS_COMMIT = 0x0200
        /** Control reads above 512 bytes stall the pipe on this firmware. */
        private const val CHUNK = 512
        private const val MAX_DOUBLE_GET = 2 shl 20
        private const val DEFAULT_INTERVAL = 333_333   // 30 fps in 100 ns units

        /** Stream test: byVideoCodingType values tried, and the time each format gets. */
        private val CODING_TYPES = 0..8
        private const val SETTLE_MS = 400L
        private const val MEASURE_MS = 1500L

        private const val PREFS = "hik4117"
        private const val KEY_RESTORE_2038 = "restore_2038"

        fun isKnown(device: UsbDevice): Boolean =
            device.vendorId == VID && device.productId == PID
    }

    override val kind = "hik4117"
    /** Unverified guess: a 3 mm lens on the 12 µm core is 250 px (fusion's start zoom). */
    override val focalPx = 250f
    override val width = W
    override val height = H
    /** Temperature frames are 2046 snapshots (~1/s): 0 keeps the SR speed guard off. */
    override val fps = 0
    /** Snapshots are slow and pause for the stream test — not a dead camera. */
    override val stallRecovery = false
    @Volatile override var frameCount = 0L; private set

    private class UvcFrame(val index: Int, val w: Int, val h: Int, val interval: Int)
    private class UvcFormat(val index: Int, val kind: String) {
        val frames = mutableListOf<UvcFrame>()
    }
    /** One format/frame the stream test commits. */
    private class Pick(val fmt: Int, val frame: Int, val interval: Int, val label: String)

    private var conn: UsbDeviceConnection? = null
    private var vcIf: UsbInterface? = null
    private var vsIf: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var prefs: SharedPreferences? = null
    private val formats = mutableListOf<UvcFormat>()
    private val xuUnits = mutableListOf<Int>()
    private var xuUnit = XU_UNIT

    private val running = AtomicBoolean(false)
    private val snapping = AtomicBoolean(false)
    @Volatile private var testing = false
    private var reader: Thread? = null
    private var snapper: Thread? = null
    /** Everything on endpoint 0: XU transactions must not interleave. */
    private val ep0 = ReentrantLock()
    private val tempLock = ReentrantLock()
    private var latestTemp: IntArray? = null
    private var compRestore: ByteArray? = null

    private val report = StringBuilder()

    // bulk stream, written by the reader thread
    @Volatile private var bufSize = 1 shl 16
    @Volatile private var maxFrame = 0
    @Volatile private var payloads = 0L
    @Volatile private var bytesIn = 0L
    @Volatile private var badHeaders = 0L
    @Volatile private var fullReads = 0L
    @Volatile private var bulkErrors = 0L
    @Volatile private var videoFrames = 0L
    @Volatile private var lastFrameLen = 0
    @Volatile private var lastFrameKind = "—"
    @Volatile private var lastFrameHead = ByteArray(0)
    @Volatile private var videoFps = 0f
    private var frame = ByteArray(1 shl 16)
    private var frameLen = 0
    private var frameFid = -1
    private var fpsFrames = 0
    private var fpsStart = 0L

    // 2046 snapshots
    @Volatile private var snapMs = 0f
    @Volatile private var snapErrors = 0L
    @Volatile private var snapError: String? = null
    @Volatile private var devEmissivity = "?"
    private var speed = "?"

    /** What [open] / [start] / [runStreamTest] found, for the drawer and Copy report. */
    fun report(): String = synchronized(report) { report.toString() }

    private fun say(line: String) {
        Log.i(TAG, line)
        synchronized(report) { report.append(line).append('\n') }
    }

    override fun open(context: Context, onCaliProgress: (Int) -> Unit) {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        check(usb.hasPermission(device)) { "no USB permission for ${ThermalSources.idText(device)}" }
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        attach(usb.openDevice(device) ?: error("openDevice failed"))
    }

    /** [open] past the system services: take over [c] and find the UVC interfaces. */
    internal fun attach(c: UsbDeviceConnection) {
        synchronized(report) { report.setLength(0) }
        formats.clear(); xuUnits.clear(); vcIf = null; vsIf = null; epIn = null
        payloads = 0; bytesIn = 0; badHeaders = 0; fullReads = 0; bulkErrors = 0
        videoFrames = 0; lastFrameLen = 0; lastFrameKind = "—"; videoFps = 0f
        frameCount = 0; snapErrors = 0; snapError = null; snapMs = 0f

        say("HIK 4117 diagnostic · ${ThermalSources.idText(device)} · " +
            listOfNotNull(runCatching { device.manufacturerName }.getOrNull(),
                          runCatching { device.productName }.getOrNull()).joinToString(" "))
        conn = c
        parseDescriptors(c.rawDescriptors)

        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (itf.interfaceClass != UsbConstants.USB_CLASS_VIDEO) continue
            when (itf.interfaceSubclass) {
                1 -> if (vcIf == null) vcIf = itf
                2 -> for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (epIn == null && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_IN) {
                        vsIf = itf; epIn = ep
                    }
                }
            }
        }
        val vc = vcIf ?: error("no UVC control interface")
        val vs = vsIf ?: error("no UVC bulk stream (isochronous only?)")
        val ep = epIn!!
        speed = when (ep.maxPacketSize) {
            512 -> "USB2 high speed"
            64 -> "USB full speed"
            1024 -> "USB3"
            else -> "maxPacket ${ep.maxPacketSize}"
        }
        say("stream if${vs.id} ep%02x bulk maxPacket ${ep.maxPacketSize} → $speed · control if${vc.id}"
            .format(ep.address))
        check(c.claimInterface(vc, true)) { "claim control interface failed" }
        check(c.claimInterface(vs, true)) { "claim stream interface failed" }
        xuUnit = if (XU_UNIT in xuUnits || xuUnits.isEmpty()) XU_UNIT else xuUnits.first()
    }

    override fun start() {
        val def = defaultPick()
        val pr = commit(def) ?: error("UVC probe/commit failed")
        say("commit ${def.label}: ${probeText(pr)}")
        running.set(true)
        reader = thread(name = "hik4117-bulk", isDaemon = true) { readLoop() }
        val until = System.currentTimeMillis() + 3000
        while (videoFrames == 0L && System.currentTimeMillis() < until) Thread.sleep(20)
        say(if (videoFrames > 0) "video: $lastFrameLen B · $lastFrameKind · ${hex(lastFrameHead, 12)}"
            else "video: no frame in 3 s (payloads=$payloads bad=$badHeaders err=$bulkErrors)")

        if (versionProbe()) {
            restorePending2038()
            readInfo()
            compensationOff()
        }
        snapping.set(true)
        snapper = thread(name = "hik4117-2046", isDaemon = true) { snapLoop() }
    }

    override fun stop() {
        snapping.set(false)
        snapper?.join(4000); snapper = null
        // needs the stream still running: the command channel only answers while it does
        compRestore?.let { c ->
            val err = runCatching { simpleSet(3, 8, c) }.getOrDefault(-1)
            Log.i(TAG, "body-temperature compensation restored (err=$err)")
        }
        compRestore = null
        running.set(false)
        reader?.join(1500); reader = null
    }

    override fun close() {
        stop()
        conn?.let { c ->
            vsIf?.let { runCatching { c.releaseInterface(it) } }
            vcIf?.let { runCatching { c.releaseInterface(it) } }
            runCatching { c.close() }
        }
        conn = null; vcIf = null; vsIf = null; epIn = null
        tempLock.withLock { latestTemp = null }
    }

    override fun getTemperatureData(): IntArray? = tempLock.withLock { latestTemp }

    override fun diag(): String = buildString {
        append("HIK $speed · video %.1f fps · %s · %d B".format(videoFps, lastFrameKind, lastFrameLen))
        append("\n2046 %.0f ms/frame · ok=%d err=%d".format(snapMs, frameCount, snapErrors))
        snapError?.let { append(" · ").append(it.take(60)) }
        append("\nbulk pl=$payloads bad=$badHeaders full=$fullReads err=$bulkErrors · ε(dev) $devEmissivity")
        if (testing) append("\nstream test running…")
    }

    override fun sensorTemp(): Int? = null

    /** Not in the diagnostic build: the module keeps its own ε (shown in [diag]). */
    override fun setEmissivity(e: Float): Boolean = false

    /** No shutter command known on the USB firmware; the module runs FFC on its own. */
    override fun triggerFfc(): Boolean = false

    // ---- the experiment ---------------------------------------------------------------

    /**
     * For each byVideoCodingType in [CODING_TYPES] of the thermal stream parameter
     * (2038/2039), commit each advertised format and record what the bulk stream turns
     * into: rate, frame size, JPEG or raw, the 16-bit and float value ranges of a raw
     * frame. Restores the parameter and the default format afterwards. Blocking, ~1 min;
     * the temperature snapshots pause meanwhile.
     */
    fun runStreamTest() {
        if (!running.get()) { say("FAIL stream test: not streaming"); return }
        testing = true
        val def = defaultPick()
        var orig: ByteArray? = null
        try {
            ep0.withLock { }                      // a 2046 in flight finishes first
            say("— stream test")
            say("baseline: ${measure(2000)}")
            val o = simpleGet(3, 5)
            orig = o
            say("2038 stream param: ${hex(o)}")
            check(o.size >= 2) { "2038 payload too short" }
            prefs?.edit()?.putString(KEY_RESTORE_2038, hex(o, o.size, ""))?.apply()
            val picks = listOf(def) + formats.filter { it.index != def.fmt }.mapNotNull { pickOf(it) }
            var failures = 0                      // a value the firmware chokes on: try the next
            for (v in CODING_TYPES) {
                if (!running.get() || failures >= 3) break
                val p = o.copyOf().also { it[0] = 1; it[1] = v.toByte() }
                val err = try {
                    simpleSet(3, 5, p)
                } catch (t: Throwable) {
                    failures++
                    say("coding=$v: ${t.message}")
                    continue
                }
                failures = 0
                val now = runCatching { hex(simpleGet(3, 5)) }.getOrElse { "read failed" }
                say("coding=$v: set err=$err, now $now")
                for (pk in picks) {
                    if (!running.get()) break
                    val pr = commit(pk)
                    Thread.sleep(SETTLE_MS)
                    say("  ${pk.label}: ${probeText(pr)} → ${measure(MEASURE_MS)}")
                }
            }
        } catch (t: Throwable) {
            say("FAIL ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            orig?.let { restore2038(it, "after the test") }
            commit(def)
            Thread.sleep(SETTLE_MS)
            say("after: ${measure(1500)}")
            testing = false
        }
    }

    private fun restore2038(o: ByteArray, why: String) {
        val err = runCatching { simpleSet(3, 5, o.copyOf().also { it[0] = 1 }) }.getOrDefault(-1)
        val back = runCatching { simpleGet(3, 5) }.getOrNull()
        val same = back != null && back.size == o.size && (1 until o.size).all { back[it] == o[it] }
        if (same) prefs?.edit()?.remove(KEY_RESTORE_2038)?.apply()
        say("2038 restored $why: err=$err, now ${back?.let { hex(it) } ?: "?"}" +
            if (same) "" else " — NOT the original, kept for the next connect")
    }

    /** A test that died mid-way (crash, unplug) left its original in prefs. */
    private fun restorePending2038() {
        val s = prefs?.getString(KEY_RESTORE_2038, null) ?: return
        val o = runCatching {
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
        if (o == null || o.size < 2) { prefs?.edit()?.remove(KEY_RESTORE_2038)?.apply(); return }
        restore2038(o, "from an interrupted test")
    }

    private class Sample(val frames: Long, val fps: Float, val kbps: Float, val len: Int,
                         val kind: String, val head: String, val bad: Long, val full: Long) {
        override fun toString(): String {
            val extra = if (bad > 0 || full > 0) " bad=$bad full=$full" else ""
            return if (frames == 0L) "no frames (%.0f KB/s$extra)".format(kbps)
            else "%.1f fps · %d B · %s · %.0f KB/s%s · %s".format(fps, len, kind, kbps, extra, head)
        }
    }

    private fun measure(ms: Long): Sample {
        val f0 = videoFrames; val b0 = bytesIn; val bad0 = badHeaders; val full0 = fullReads
        val t0 = System.nanoTime()
        Thread.sleep(ms)
        val dt = (System.nanoTime() - t0) / 1e9f
        val n = videoFrames - f0
        return Sample(n, n / dt, (bytesIn - b0) / 1024f / dt, lastFrameLen, lastFrameKind,
                      hex(lastFrameHead, 12), badHeaders - bad0, fullReads - full0)
    }

    // ---- start-up reads ---------------------------------------------------------------

    /** GET_LEN + GET_CUR on selector 4 ("2.0"): unlocks the command channel. */
    private fun versionProbe(): Boolean {
        repeat(3) { attempt ->
            val (len, v) = ep0.withLock { xuLen(4) to xuGet(4, 4) }
            val ok = v != null && v.size >= 3 && v[0] == '2'.code.toByte() && v[1] == '.'.code.toByte()
            say("XU $xuUnit version probe: GET_LEN=$len → ${v?.let { "'${ascii(it)}' ${hex(it)}" } ?: "failed"}")
            if (ok) return true
            if (attempt < 2) Thread.sleep(300)
        }
        say("FAIL command channel locked — no temperature without it")
        return false
    }

    /** Read-only queries for the report: what the firmware is and how it is set. */
    private fun readInfo() {
        val queries = listOf(
            Triple("2011 device info", 1, 1), Triple("2036 alg version", 3, 4),
            Triple("4051 capabilities", 1, 14), Triple("2030 basic param", 3, 1),
            Triple("2032 mode", 3, 2), Triple("2038 stream param", 3, 5),
            Triple("2048 p2p param", 3, 11),
        )
        for ((name, g, s) in queries) {
            runCatching { simpleGet(g, s) }.fold(
                onSuccess = { b ->
                    val text = strings(b)
                    say("$name: ${b.size} B ${hex(b, 24)}" + if (text.isNotEmpty()) " | $text" else "")
                    if (g == 3 && s == 1 && b.size >= 25) {
                        devEmissivity = "%.2f".format(le32(b, 16) / 100f)
                        say("  emissivity ${le32(b, 16)}/100 · distance ${le32(b, 21)}")
                    }
                },
                onFailure = { say("$name: ${it.message}") })
        }
    }

    /** Surface temperature, not the screening curve's estimated core temperature. */
    private fun compensationOff() {
        runCatching {
            val c = simpleGet(3, 8)
            say("2044 body-temp compensation: ${hex(c)}")
            if (c.size >= 2 && c[1].toInt() != 0) {
                val off = c.copyOf().also { it[0] = 1; it[1] = 0 }
                val err = simpleSet(3, 8, off)
                if (err == 0) compRestore = c.copyOf().also { it[0] = 1 }
                say("  turned off for this session (err=$err), restored on disconnect")
            }
        }.onFailure { say("2044: ${it.message}") }
    }

    // ---- 2046 snapshots ---------------------------------------------------------------

    private fun snapLoop() {
        var fails = 0
        while (snapping.get()) {
            if (testing) { Thread.sleep(50); continue }
            val t0 = System.nanoTime()
            try {
                val m = capture2046()
                snapMs = (System.nanoTime() - t0) / 1e6f
                tempLock.withLock { latestTemp = m }
                frameCount++
                fails = 0
                if (frameCount == 1L) {
                    say("2046 first frame: %.0f ms · %.2f..%.2f °C".format(snapMs,
                        m.min() / 1000f, m.max() / 1000f))
                }
            } catch (t: Throwable) {
                snapErrors++
                snapError = t.message
                if (snapErrors <= 3) say("2046 failed: ${t.message}")
                fails++
                Thread.sleep(if (fails > 3) 1000L else 200L)
            }
        }
    }

    /** One radiometric capture → the 120×160 matrix in milli-°C. */
    private fun capture2046(): IntArray {
        val req = ByteArray(13)
        req[0] = 1                                         // byChannelID (2 stalls the pipe)
        val year = Calendar.getInstance().get(Calendar.YEAR)
        req[8] = year.toByte(); req[9] = (year shr 8).toByte()
        val b = doubleGet(3, 9, req)
        check(b.size >= 27) { "2046: ${b.size} B" }
        val jl = le32(b, 1); val w = le32(b, 5); val h = le32(b, 9); val tb = le32(b, 13)
        check(w == W && h == H && tb == w * h * 4) { "2046: ${w}x$h, $tb B of temperature" }
        val o = 27 + jl
        check(jl >= 0 && b.size >= o + tb) { "2046 truncated: ${b.size} < ${o + tb}" }
        val bb = ByteBuffer.wrap(b, o, tb).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(W * H) { (bb.float * 1000f).roundToInt() }
    }

    // ---- XU transactions --------------------------------------------------------------

    private fun ctl(type: Int, req: Int, value: Int, index: Int, buf: ByteArray, len: Int = buf.size): Int =
        conn?.controlTransfer(type, req, value, index, buf, len, TIMEOUT_MS) ?: -1

    private val xuIndex: Int get() = (xuUnit shl 8) or (vcIf?.id ?: 0)

    private fun xuGet(sel: Int, len: Int): ByteArray? {
        val b = ByteArray(len)
        val n = ctl(REQ_IN, GET_CUR, sel shl 8, xuIndex, b)
        return if (n < 0) null else b.copyOf(n)
    }

    private fun xuSet(sel: Int, data: ByteArray): Boolean =
        ctl(REQ_OUT, SET_CUR, sel shl 8, xuIndex, data) >= 0

    /** GET_LEN; this firmware answers in 4 bytes (u32), the UVC spec in 2. */
    private fun xuLen(sel: Int): Int {
        val b = ByteArray(4)
        val n = ctl(REQ_IN, GET_LEN, sel shl 8, xuIndex, b)
        return when {
            n >= 4 -> le32(b, 0)
            n >= 2 -> le16(b, 0)
            else -> -1
        }
    }

    /** Selector 6: the last command's error code (0 = OK), −1 if unreadable. */
    private fun lastError(): Int = xuGet(6, 1)?.takeIf { it.isNotEmpty() }?.let { it[0].toInt() and 0xff } ?: -1

    private fun select(group: Int, sub: Int) =
        check(xuSet(5, byteArrayOf(group.toByte(), sub.toByte()))) { "select $group/$sub failed" }

    private fun simpleGet(group: Int, sub: Int): ByteArray = ep0.withLock {
        select(group, sub)
        val len = xuLen(group)
        check(len in 1..CHUNK) { "GET_LEN $len (err ${lastError()})" }
        xuGet(group, len) ?: error("GET_CUR failed (err ${lastError()})")
    }

    /** SET with a payload whose byte 0 is the channel (1); returns selector 6 (0 = OK),
     *  or −1 when the transfer itself failed. */
    private fun simpleSet(group: Int, sub: Int, payload: ByteArray): Int = ep0.withLock {
        select(group, sub)
        if (!xuSet(group, payload)) -1 else lastError()
    }

    /** Request → 5-byte head with the total length → chunks of ≤512 B, each prefixed
     *  {0x02, u32 sequence}. Never re-SET in phase B: it restarts the transaction. */
    private fun doubleGet(group: Int, sub: Int, req: ByteArray): ByteArray = ep0.withLock {
        select(group, sub)
        check(xuSet(group, req)) { "request SET failed (err ${lastError()})" }
        val hl = xuLen(group)
        check(hl in 5..CHUNK) { "head GET_LEN $hl (err ${lastError()})" }
        val head = xuGet(group, hl) ?: error("head GET failed")
        check(head.size >= 5) { "head ${hex(head)}" }
        val total = le32(head, 1)
        check(total in 1..MAX_DOUBLE_GET) { "length $total" }
        val out = ByteArray(total)
        val chunk = ByteArray(CHUNK)
        var have = 0
        while (have < total) {
            val n = ctl(REQ_IN, GET_CUR, group shl 8, xuIndex, chunk, CHUNK)
            check(n > 0) { "truncated at $have/$total" }
            val off = if (n > 5 && chunk[0].toInt() == 2) 5 else 0
            val m = minOf(n - off, total - have)
            System.arraycopy(chunk, off, out, have, m)
            have += m
        }
        out
    }

    // ---- UVC stream -------------------------------------------------------------------

    /** The module's own 240×320 MJPEG: what hik-tb4117 commits (format 2, frame 2). */
    private fun defaultPick(): Pick {
        val mj = formats.firstOrNull { it.kind == "MJPEG" } ?: return Pick(2, 2, DEFAULT_INTERVAL, "fmt2/2")
        return pickOf(mj) ?: Pick(mj.index, 1, DEFAULT_INTERVAL, "MJPEG")
    }

    private fun pickOf(f: UvcFormat): Pick? {
        val fr = f.frames.firstOrNull { it.w == 240 && it.h == 320 } ?: f.frames.firstOrNull() ?: return null
        return Pick(f.index, fr.index, fr.interval.takeIf { it > 0 } ?: DEFAULT_INTERVAL,
                    "${f.kind} ${fr.w}x${fr.h}")
    }

    /** PROBE → device's answer → COMMIT; sizes the bulk reads from its payload limit. */
    private fun commit(p: Pick): ByteArray? = ep0.withLock {
        val vs = vsIf?.id ?: return@withLock null
        val probe = ByteArray(26)
        probe[2] = p.fmt.toByte(); probe[3] = p.frame.toByte()
        put32(probe, 4, p.interval)
        if (ctl(REQ_OUT, SET_CUR, VS_PROBE, vs, probe) < 0) return@withLock null
        val cur = ByteArray(26)
        if (ctl(REQ_IN, GET_CUR, VS_PROBE, vs, cur) < 0) return@withLock null
        if (ctl(REQ_OUT, SET_CUR, VS_COMMIT, vs, cur) < 0) return@withLock null
        // one bulk read must hold a whole payload, or the next read starts mid-payload
        bufSize = (le32(cur, 22) + 512).coerceIn(1 shl 16, 1 shl 20)
        maxFrame = le32(cur, 18)
        cur
    }

    private fun probeText(p: ByteArray?): String = if (p == null) "commit failed" else
        "fmt=${p[2].toInt() and 0xff} frame=${p[3].toInt() and 0xff} interval=${le32(p, 4)} " +
            "maxFrame=${le32(p, 18)} maxPayload=${le32(p, 22)}"

    private fun readLoop() {
        val c = conn ?: return
        val ep = epIn ?: return
        var buf = ByteArray(bufSize)
        while (running.get()) {
            if (buf.size != bufSize) buf = ByteArray(bufSize)
            val t0 = System.nanoTime()
            val n = c.bulkTransfer(ep, buf, buf.size, 500)
            if (n < 0) {
                bulkErrors++
                // an unplugged device fails at once: don't spin
                if (System.nanoTime() - t0 < 5_000_000L) Thread.sleep(20)
                continue
            }
            if (n == 0) continue
            payloads++; bytesIn += n
            if (n == buf.size) fullReads++
            onPayload(buf, n)
        }
    }

    /**
     * UVC payload header: [0] length, [1] FID (bit 0) / EOF (bit 1). A frame ends on EOF,
     * a FID toggle, a JPEG EOI / next SOI (hik-tb4117 splits the MJPEG that way, so the
     * flags may not be set), or at the probe's maxFrame (a raw frame without flags).
     */
    private fun onPayload(b: ByteArray, n: Int) {
        val hl = b[0].toInt() and 0xff
        if (hl < 2 || hl > n || hl > 64) { badHeaders++; return }
        val flags = b[1].toInt() and 0xff
        val fid = flags and 1
        val m = n - hl
        val soi = m >= 2 && b[hl] == 0xFF.toByte() && b[hl + 1] == 0xD8.toByte()
        if (frameLen > 0 && (fid != frameFid || (soi && frameIsJpeg()))) endFrame()
        frameFid = fid
        if (frameLen + m > frame.size) {
            if (frameLen + m > 8 shl 20) { frameLen = 0; return }   // runaway: drop it
            frame = frame.copyOf(maxOf(frame.size * 2, frameLen + m))
        }
        System.arraycopy(b, hl, frame, frameLen, m)
        frameLen += m
        val eoi = frameIsJpeg() && frame[frameLen - 2] == 0xFF.toByte() &&
            frame[frameLen - 1] == 0xD9.toByte()
        if (flags and 2 != 0 || eoi || (!frameIsJpeg() && maxFrame in 1..frameLen)) endFrame()
    }

    private fun frameIsJpeg(): Boolean =
        frameLen >= 4 && frame[0] == 0xFF.toByte() && frame[1] == 0xD8.toByte()

    private fun endFrame() {
        val n = frameLen
        frameLen = 0
        if (n == 0) return
        lastFrameLen = n
        lastFrameHead = frame.copyOf(minOf(16, n))
        lastFrameKind = classify(frame, n)
        videoFrames++
        val now = System.nanoTime()
        if (fpsStart == 0L) fpsStart = now
        fpsFrames++
        if (now - fpsStart >= 1_000_000_000L) {
            videoFps = fpsFrames * 1e9f / (now - fpsStart)
            fpsStart = now; fpsFrames = 0
        }
    }

    /** JPEG, H.264, or raw with its 16-bit and (if plausible) float value ranges. */
    private fun classify(b: ByteArray, n: Int): String {
        if (n >= 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) {
            return if (b[n - 2] == 0xFF.toByte() && b[n - 1] == 0xD9.toByte()) "JPEG" else "JPEG(no EOI)"
        }
        if (n >= 4 && b[0].toInt() == 0 && b[1].toInt() == 0 &&
            (b[2].toInt() == 1 || (b[2].toInt() == 0 && b[3].toInt() == 1))) return "H264?"
        val sb = StringBuilder("raw")
        var lo = 0xffff; var hi = 0
        var i = 0
        while (i + 1 < n) {
            val v = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
            if (v < lo) lo = v
            if (v > hi) hi = v
            i += 2
        }
        sb.append(" u16 $lo..$hi")
        if (n % 4 == 0) {
            val bb = ByteBuffer.wrap(b, 0, n).order(ByteOrder.LITTLE_ENDIAN)
            val count = n / 4
            val step = maxOf(1, count / 20000)
            var ok = 0; var seen = 0
            var flo = Float.MAX_VALUE; var fhi = -Float.MAX_VALUE
            var k = 0
            while (k < count) {
                val f = bb.getFloat(k * 4)
                seen++
                // °C-like: in range, and not the denormal crumbs 16-bit data reads as
                if (f.isFinite() && f > -60f && f < 1000f && (f == 0f || abs(f) >= 1e-3f)) {
                    ok++
                    if (f < flo) flo = f
                    if (f > fhi) fhi = f
                }
                k += step
            }
            if (ok * 100 >= seen * 99) sb.append(" f32 %.1f..%.1f".format(flo, fhi))
        }
        return sb.toString()
    }

    // ---- descriptors ------------------------------------------------------------------

    /** USB version, endpoints, extension units and the VS formats/frames, for the report. */
    private fun parseDescriptors(raw: ByteArray?) {
        if (raw == null) { say("descriptors: unavailable"); return }
        var i = 0
        var ifNum = -1; var ifAlt = -1; var ifClass = -1; var ifSub = -1
        var fmt: UvcFormat? = null
        val eps = mutableListOf<String>()
        var bcd = 0
        while (i + 2 <= raw.size) {
            val len = raw[i].toInt() and 0xff
            if (len < 2 || i + len > raw.size) break
            val type = raw[i + 1].toInt() and 0xff
            if (type == 1 && len >= 4) {
                bcd = le16(raw, i + 2)
            } else if (type == 4 && len >= 9) {
                ifNum = u8(raw, i + 2); ifAlt = u8(raw, i + 3)
                ifClass = u8(raw, i + 5); ifSub = u8(raw, i + 6)
            } else if (type == 5 && len >= 7) {
                val xfer = when (u8(raw, i + 3) and 3) { 0 -> "ctl"; 1 -> "iso"; 2 -> "bulk"; else -> "int" }
                eps += "if$ifNum.$ifAlt ep%02x $xfer %d".format(u8(raw, i + 2), le16(raw, i + 4) and 0x7ff)
            } else if (type == 0x24 && ifClass == UsbConstants.USB_CLASS_VIDEO && len >= 4) {
                val sub = u8(raw, i + 2)
                if (ifSub == 1 && sub == 0x06 && len >= 21) {
                    xuUnits += u8(raw, i + 3)                    // VC extension unit
                } else if (ifSub == 2) {
                    var f: UvcFormat? = null
                    var fr: UvcFrame? = null
                    when (sub) {
                        0x04, 0x10 -> if (len >= 21) f = UvcFormat(u8(raw, i + 3), fourcc(raw, i + 5))
                        0x06 -> f = UvcFormat(u8(raw, i + 3), "MJPEG")
                        0x05, 0x07 -> if (len >= 25) fr = UvcFrame(u8(raw, i + 3), le16(raw, i + 5),
                                                                   le16(raw, i + 7), le32(raw, i + 21))
                        0x11 -> if (len >= 21) fr = UvcFrame(u8(raw, i + 3), le16(raw, i + 5),
                                                             le16(raw, i + 7), le32(raw, i + 17))
                    }
                    if (f != null) { formats += f; fmt = f }
                    if (fr != null) fmt?.frames?.add(fr)
                }
            }
            i += len
        }
        say("bcdUSB %x.%02x · endpoints: %s".format(bcd shr 8, bcd and 0xff, eps.joinToString(", ")))
        say("extension units: ${xuUnits.joinToString().ifEmpty { "none" }}")
        say("formats: " + formats.joinToString(" · ") { f ->
            "${f.index} ${f.kind} [" + f.frames.joinToString(" ") { fr ->
                "${fr.index}:${fr.w}x${fr.h}@" + (if (fr.interval > 0) "%.0f".format(1e7 / fr.interval) else "?")
            } + "]"
        }.ifEmpty { "none" })
    }
}

private fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xff

private fun le16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)

private fun le32(b: ByteArray, o: Int): Int =
    u8(b, o) or (u8(b, o + 1) shl 8) or (u8(b, o + 2) shl 16) or (u8(b, o + 3) shl 24)

private fun put32(b: ByteArray, o: Int, v: Int) {
    for (k in 0..3) b[o + k] = (v shr (8 * k)).toByte()
}

private fun hex(b: ByteArray, max: Int = 48, sep: String = " "): String {
    val s = b.take(max).joinToString(sep) { "%02x".format(it) }
    return if (b.size > max) "$s…" else s
}

/** A GUID's first 4 bytes as a FourCC ("YUY2", "H264"), or hex when not printable. */
private fun fourcc(b: ByteArray, o: Int): String {
    val s = (0..3).map { b[o + it].toInt() and 0xff }
    return if (s.all { it in 0x20..0x7e }) s.joinToString("") { it.toChar().toString() }.trim()
    else s.joinToString("") { "%02x".format(it) }
}

private fun ascii(b: ByteArray): String =
    b.map { val c = it.toInt() and 0xff; if (c in 0x20..0x7e) c.toChar() else '.' }.joinToString("")

/** Printable runs of 3+ characters — the strings inside a device-info struct. */
private fun strings(b: ByteArray): String {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    for (x in b) {
        val c = x.toInt() and 0xff
        if (c in 0x20..0x7e) cur.append(c.toChar())
        else { if (cur.length >= 3) out += cur.toString(); cur.setLength(0) }
    }
    if (cur.length >= 3) out += cur.toString()
    return out.joinToString(" | ")
}
