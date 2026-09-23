package com.magnity.viewer.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Radiometric capture file (".mgt") — the per-pixel temperature field behind a snapshot
 * or a recording, so a position can be measured again after the fact. The PNG/MP4 keeps
 * only palette colours; this keeps the numbers.
 *
 * Layout (little-endian, header 32 bytes, then frames back to back):
 *
 *     0  "MAGT"          magic
 *     4  u16 version     1
 *     6  u16 headerSize  32
 *     8  u16 width       oriented grid (rotation/mirror already applied)
 *     10 u16 height
 *     12 u16 flags       0
 *     14 u16 reserved
 *     16 i64 startEpoch  wall clock of frame 0, ms
 *     24 f32 emissivity  ε in effect
 *     28 u32 frameCount  patched on close; 0 = derive from the file length
 *     32 frames: u32 tMs (since startEpoch) + width*height i16 centi-°C
 *
 * Fixed frame stride means frame i is one seek away — the player scrubs without an index.
 * i16 centi-°C spans ±327 °C at 0.01 °C, well past the module's -20…150 °C.
 */
object ThermalStream {

    const val EXT = "mgt"
    const val HEADER = 32
    private const val VERSION = 1
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'A'.code.toByte(),
                                    'G'.code.toByte(), 'T'.code.toByte())

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Bytes per frame: the u32 timestamp plus one i16 per pixel. */
    fun frameStride(w: Int, h: Int) = 4 + w * h * 2

    /**
     * Appends frames to a MediaStore entry in Download/MagViewer. Not thread-safe; the
     * caller (recorder path) already serialises on its own lock.
     */
    class Writer(
        private val ctx: Context, val width: Int, val height: Int, val emissivity: Float,
        val startedAt: Long = System.currentTimeMillis(),
    ) {
        /** The name MediaStore actually gave the file — it may normalise the one we
         *  asked for (an unknown extension can pick up a ".bin" tail). */
        var displayName = "MagViewer_${stamp()}.$EXT"; private set
        private val uri: Uri
        private val pfd: ParcelFileDescriptor
        private val channel: FileChannel
        private val buf = ByteBuffer.allocate(frameStride(width, height))
            .order(ByteOrder.LITTLE_ENDIAN)
        var frames = 0; private set

        init {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/MagViewer")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: error("MediaStore insert failed")
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                                          null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { displayName = it }
                }
            }
            pfd = ctx.contentResolver.openFileDescriptor(uri, "rw")
                ?: run {
                    runCatching { ctx.contentResolver.delete(uri, null, null) }
                    error("open $displayName failed")
                }
            channel = FileOutputStream(pfd.fileDescriptor).channel
            val head = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
            head.put(MAGIC)
            head.putShort(VERSION.toShort())
            head.putShort(HEADER.toShort())
            head.putShort(width.toShort())
            head.putShort(height.toShort())
            head.putShort(0)                 // flags
            head.putShort(0)                 // reserved
            head.putLong(startedAt)
            head.putFloat(emissivity)
            head.putInt(0)                   // frameCount, patched in close()
            head.flip()
            channel.write(head)
        }

        /** One frame of oriented °C (width*height), stamped [atMs] (wall clock). */
        fun addFrame(temps: FloatArray, atMs: Long = System.currentTimeMillis()) {
            if (temps.size != width * height) return
            buf.clear()
            buf.putInt((atMs - startedAt).coerceIn(0L, 0xFFFFFFFFL).toInt())
            for (v in temps) {
                buf.putShort((v * 100f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
            buf.flip()
            channel.write(buf)
            frames++
        }

        /** Patch the frame count and publish; null (and the entry is dropped) if empty. */
        fun close(): Uri? {
            val ok = runCatching {
                if (frames > 0) {
                    val n = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(frames).also { it.flip() }
                    channel.write(n, 28L)
                }
                channel.force(false)
            }.onFailure { Log.e("MagViewer", "thermal stream finalise failed", it) }.isSuccess
            runCatching { channel.close() }
            runCatching { pfd.close() }
            val cr = ctx.contentResolver
            return if (ok && frames > 0) {
                cr.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
                uri
            } else {
                runCatching { cr.delete(uri, null, null) }
                null
            }
        }
    }

    /** One-shot file for a snapshot; returns the saved name, or null on failure. */
    fun writeSingle(ctx: Context, temps: FloatArray, w: Int, h: Int, emissivity: Float): String? {
        val wr = runCatching { Writer(ctx, w, h, emissivity) }
            .onFailure { Log.e("MagViewer", "thermal file failed", it) }.getOrNull() ?: return null
        wr.addFrame(temps)
        return if (wr.close() != null) wr.displayName else null
    }

    /** Random-access reader over a saved capture (SAF Uri). Close it when done. */
    class Reader(ctx: Context, uri: Uri) {
        private val pfd: ParcelFileDescriptor =
            ctx.contentResolver.openFileDescriptor(uri, "r") ?: error("cannot open file")
        private val channel: FileChannel = FileInputStream(pfd.fileDescriptor).channel
        val width: Int
        val height: Int
        val emissivity: Float
        val startEpochMs: Long
        val frameCount: Int
        private val stride: Int
        private val headerSize: Int
        private val buf: ByteBuffer

        init {
            try {
                val head = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
                if (channel.read(head, 0L) < HEADER) error("not a temperature capture")
                head.flip()
                val magic = ByteArray(4).also { head.get(it) }
                if (!magic.contentEquals(MAGIC)) error("not a temperature capture")
                val version = head.short.toInt() and 0xFFFF
                if (version > VERSION) error("capture version $version is newer than this app")
                headerSize = (head.short.toInt() and 0xFFFF).coerceAtLeast(HEADER)
                width = head.short.toInt() and 0xFFFF
                height = head.short.toInt() and 0xFFFF
                head.short                                  // flags
                head.short                                  // reserved
                startEpochMs = head.long
                emissivity = head.float
                val declared = head.int
                if (width <= 0 || height <= 0 || width > 4096 || height > 4096)
                    error("bad frame size ${width}x$height")
                stride = frameStride(width, height)
                // a recording cut short (crash, battery) never got its count patched
                val avail = ((channel.size() - headerSize) / stride).toInt()
                frameCount = if (declared in 1..avail) declared else avail
                if (frameCount <= 0) error("file holds no frames")
                buf = ByteBuffer.allocate(stride).order(ByteOrder.LITTLE_ENDIAN)
            } catch (e: Throwable) {
                close()
                throw e
            }
        }

        /** Frame [i]: temperatures in °C (width*height) and its time from the start. */
        class Frame(val temps: FloatArray, val tMs: Long)

        fun frame(i: Int): Frame {
            val idx = i.coerceIn(0, frameCount - 1)
            buf.clear()
            var pos = headerSize.toLong() + idx.toLong() * stride
            while (buf.hasRemaining()) {
                val n = channel.read(buf, pos)
                if (n <= 0) break
                pos += n
            }
            while (buf.hasRemaining()) buf.put(0)     // truncated tail reads as 0 °C
            buf.flip()
            val tMs = (buf.int.toLong() and 0xFFFFFFFFL)
            val out = FloatArray(width * height)
            for (p in out.indices) out[p] = buf.short / 100f
            return Frame(out, tMs)
        }

        fun close() {
            runCatching { channel.close() }
            runCatching { pfd.close() }
        }
    }
}
