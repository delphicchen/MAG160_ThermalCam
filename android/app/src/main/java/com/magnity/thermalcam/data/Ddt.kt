package com.magnity.thermalcam.data

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DDT radiometric snapshot save/load — Kotlin port of `ddt.py` (same file format, so
 * snapshots interchange with the Linux viewer).
 *
 * Layout (little-endian): see ddt.py — 256-byte header ("MDDT", version, W, H, dtype,
 * fpa_raw, timestamp f64, calibrated, lut_idx, cal a/b f64) + W*H float32 frame.
 */
object Ddt {
    private const val HEADER = 256
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'D'.code.toByte(), 'D'.code.toByte(), 'T'.code.toByte())
    private const val VERSION = 1

    data class Snapshot(
        val frame: FloatArray,
        val width: Int,
        val height: Int,
        val fpaRaw: Int?,
        val timestamp: Double,
        val calibrated: Boolean,
        val a: Double,
        val b: Double,
        val lutIdx: Int,
        val name: String = "",
    )

    fun save(
        out: OutputStream, frame: FloatArray, width: Int, height: Int,
        fpaRaw: Int?, a: Double, b: Double, lutIdx: Int, calibrated: Boolean,
        timestamp: Double = System.currentTimeMillis() / 1000.0,
    ) {
        require(frame.size == width * height)
        val hdr = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put(MAGIC)
        hdr.putShort(VERSION.toShort())
        hdr.putShort(width.toShort())
        hdr.putShort(height.toShort())
        hdr.putShort(0)                                     // dtype 0 = float32 counts
        hdr.putInt(fpaRaw ?: -1)                            // 0xFFFFFFFF if unknown
        hdr.putDouble(timestamp)
        hdr.put(if (calibrated) 1 else 0)
        hdr.put((lutIdx and 0xFF).toByte())
        hdr.position(32)
        hdr.putDouble(a)
        hdr.putDouble(b)
        out.write(hdr.array())
        val px = ByteBuffer.allocate(frame.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        px.asFloatBuffer().put(frame)
        out.write(px.array())
        out.flush()
    }

    fun load(input: InputStream, name: String = ""): Snapshot {
        val hdrBytes = ByteArray(HEADER)
        var off = 0
        while (off < HEADER) {
            val r = input.read(hdrBytes, off, HEADER - off)
            if (r < 0) throw IllegalArgumentException("truncated DDT header")
            off += r
        }
        if (!hdrBytes.copyOf(4).contentEquals(MAGIC)) throw IllegalArgumentException("not a DDT file (bad magic)")
        val hdr = ByteBuffer.wrap(hdrBytes).order(ByteOrder.LITTLE_ENDIAN)
        hdr.position(4)
        hdr.short // version
        val w = hdr.short.toInt() and 0xFFFF
        val h = hdr.short.toInt() and 0xFFFF
        hdr.short // dtype
        val fpa = hdr.int
        val ts = hdr.double
        val calibrated = hdr.get().toInt() != 0
        val lutIdx = hdr.get().toInt() and 0xFF
        hdr.position(32)
        val a = hdr.double
        val b = hdr.double

        val data = ByteArray(w * h * 4)
        off = 0
        while (off < data.size) {
            val r = input.read(data, off, data.size - off)
            if (r < 0) throw IllegalArgumentException("truncated DDT frame")
            off += r
        }
        val frame = FloatArray(w * h)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(frame)
        return Snapshot(frame, w, h, if (fpa == -1) null else fpa, ts, calibrated, a, b, lutIdx, name)
    }
}
