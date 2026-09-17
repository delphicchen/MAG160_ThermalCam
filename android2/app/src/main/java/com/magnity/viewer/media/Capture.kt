package com.magnity.viewer.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.location.Location
import android.media.ExifInterface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.magnity.viewer.ui.FrameResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Screenshot / video output. Everything here runs off the main thread: screenshots on
 * Dispatchers.IO, video frames on the processing loop right after a frame is built.
 */
object FrameComposer {
    /** Output image is always the 4× grid (640×480 or 480×640) so files are consistent
     *  whatever display upscale is selected. */
    const val SCALE = 4
    private const val BAR_MIN = 72

    /**
     * Composed size, padded so both edges are multiples of 16: hardware H.264/HEVC
     * encoders require that alignment, and an unaligned height made every encoder
     * refuse the format (480×712 / 640×552) — recording failed at configure().
     * The extra rows land in the colour-bar strip, which is drawn to fill whatever
     * height is left.
     */
    fun outSize(fr: FrameResult): Pair<Int, Int> {
        val (iw, ih) = imageSize(fr)
        return align16(iw) to align16(ih + BAR_MIN)
    }

    /** Image area: the 4× thermal grid, or in wide-search fusion the whole visible frame
     *  (already about that size) with the thermal grid inset inside it. */
    private fun imageSize(fr: FrameResult): Pair<Int, Int> =
        if (fr.inset == null) fr.w * SCALE to fr.h * SCALE
        else fr.bitmap.width to fr.bitmap.height

    private fun align16(v: Int) = (v + 15) / 16 * 16

    /** Thermal image + ROI markers + SPOT + colour bar with range and readouts. */
    fun compose(
        fr: FrameResult, lut: IntArray,
        showMax: Boolean, showMin: Boolean,
        spot: Pair<Int, Int>?, spotC: Float?,
    ): Bitmap {
        // paints are per call: screenshot (IO) and video (processing loop) can overlap
        val filter = Paint(Paint.FILTER_BITMAP_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 22f; color = Color.WHITE
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f
        }
        val (ow, oh) = outSize(fr)
        val (iw, ih) = imageSize(fr)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(0x0D, 0x11, 0x17))
        c.drawBitmap(fr.bitmap, null, Rect(0, 0, iw, ih), filter)

        // thermal grid cell centre → output pixel, through the inset rect when present
        val r = fr.inset
        fun px(gx: Int) = ((r?.left ?: 0f) + (gx + 0.5f) / fr.w * (r?.width() ?: 1f)) * iw
        fun py(gy: Int) = ((r?.top ?: 0f) + (gy + 0.5f) / fr.h * (r?.height() ?: 1f)) * ih
        fun marker(pos: Int, color: Int, v: Float) {
            val x = px(pos % fr.w); val y = py(pos / fr.w)
            ring.color = color
            c.drawCircle(x, y, 10f, ring)
            val label = "%.1f°C".format(v)
            val tw = text.measureText(label)
            val tx = (x + 14f).coerceAtMost(iw - tw - 2f)
            val ty = (y - 12f).coerceAtLeast(24f)
            text.color = color
            c.drawText(label, tx, ty, text)
        }
        if (showMax) marker(fr.maxPos, Color.rgb(0xF8, 0x51, 0x49), fr.tempMax)
        if (showMin) marker(fr.minPos, Color.rgb(0x3F, 0xB9, 0x50), fr.tempMin)
        spot?.let { (sx, sy) ->
            val x = px(sx); val y = py(sy)
            ring.color = Color.WHITE
            c.drawLine(x - 14f, y, x + 14f, y, ring)
            c.drawLine(x, y - 14f, x, y + 14f, ring)
        }

        // colour bar, centred in whatever strip is left after alignment padding
        val barTop = ih + (oh - ih - 56f) / 2f
        val lutBmp = Bitmap.createBitmap(lut, 256, 1, Bitmap.Config.ARGB_8888)
        c.drawBitmap(lutBmp, null, RectF(8f, barTop, ow - 8f, barTop + 16f), filter)
        text.color = Color.WHITE
        val y2 = barTop + 42f
        c.drawText("%.1f".format(fr.scaleLoC), 8f, y2, text)
        val hi = "%.1f°C".format(fr.scaleHiC)
        c.drawText(hi, ow - 8f - text.measureText(hi), y2, text)
        val mid = "MAX %.1f  MIN %.1f".format(fr.tempMax, fr.tempMin) +
            (spotC?.let { "  SPOT %.1f".format(it) } ?: "") +
            "  ε%.2f".format(fr.emissivity)
        c.drawText(mid, (ow - text.measureText(mid)) / 2f, y2, text)
        return out
    }
}

object MediaSaver {
    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /**
     * Save [bmp] as PNG. With a [location] the PNG gets EXIF GPS tags; the image is still
     * saved untagged if tagging fails. Returns the Uri and whether the tag made it in.
     */
    fun savePng(ctx: Context, bmp: Bitmap, location: Location? = null): Pair<Uri, Boolean>? {
        // Tag a temp file first: EXIF rewriting needs a seekable file, which a MediaStore
        // output stream is not.
        val tmp = File.createTempFile("snap", ".png", ctx.cacheDir)
        try {
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val tagged = location != null && runCatching {
                ExifInterface(tmp.path).apply { setGps(location) }.saveAttributes()
            }.onFailure { Log.e("MagViewer", "EXIF GPS failed", it) }.isSuccess

            val cr = ctx.contentResolver
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "MagViewer_${stamp()}.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MagViewer")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return null
            return try {
                cr.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                          null, null)
                uri to tagged
            } catch (e: Throwable) {
                cr.delete(uri, null, null); throw e
            }
        } finally {
            tmp.delete()
        }
    }

    /** EXIF GPS block: degrees/minutes/seconds rationals, refs, altitude and UTC fix time. */
    private fun ExifInterface.setGps(loc: Location) {
        fun dms(v: Double): String {
            val a = abs(v)
            val d = a.toInt()
            val m = ((a - d) * 60).toInt()
            val s = ((a - d - m / 60.0) * 3600 * 10_000).roundToLong()
            return "$d/1,$m/1,$s/10000"
        }
        setAttribute(ExifInterface.TAG_GPS_LATITUDE, dms(loc.latitude))
        setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (loc.latitude >= 0) "N" else "S")
        setAttribute(ExifInterface.TAG_GPS_LONGITUDE, dms(loc.longitude))
        setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (loc.longitude >= 0) "E" else "W")
        if (loc.hasAltitude()) {
            setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "${(abs(loc.altitude) * 100).roundToLong()}/100")
            setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, if (loc.altitude >= 0) "0" else "1")
        }
        val utc = TimeZone.getTimeZone("UTC")
        setAttribute(ExifInterface.TAG_GPS_DATESTAMP,
            SimpleDateFormat("yyyy:MM:dd", Locale.US).apply { timeZone = utc }.format(Date(loc.time)))
        setAttribute(ExifInterface.TAG_GPS_TIMESTAMP,
            SimpleDateFormat("H/1,m/1,s/1", Locale.US).apply { timeZone = utc }.format(Date(loc.time)))
    }

    fun newVideoUri(ctx: Context): Uri? {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "MagViewer_${stamp()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MagViewer")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
    }
}

/**
 * HEVC (fallback H.264) MP4 writer fed with already-composed Bitmaps. Frames go through the encoder's
 * input Surface, so their timestamps are the post time — variable frame rate follows the
 * camera exactly, no pacing needed.
 */
class VideoRecorder(
    private val ctx: Context, val width: Int, val height: Int, location: Location? = null,
) {
    private val uri: Uri = MediaSaver.newVideoUri(ctx) ?: error("MediaStore insert failed")
    private val pfd: ParcelFileDescriptor
    private val muxer: MediaMuxer
    private val codec: MediaCodec
    private val surface: Surface
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxing = false
    val startedAt = System.currentTimeMillis()
    var frames = 0; private set

    /** "HEVC" or "H.264" — whichever encoder was picked. */
    val codecName: String
    /** True when the MP4 carries the capture location (set before the muxer starts). */
    val geoTagged: Boolean

    init {
        pfd = ctx.contentResolver.openFileDescriptor(uri, "rw") ?: error("open mp4 failed")
        muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        geoTagged = location != null && runCatching {
            muxer.setLocation(location.latitude.toFloat(), location.longitude.toFloat())
        }.onFailure { Log.e("MagViewer", "MP4 location failed", it) }.isSuccess
        fun format(mime: String, bitrate: Int) =
            MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                           MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
        // HEVC first (≈ half the size at the same quality), then H.264.
        // Try each candidate in turn and keep the first that actually configures —
        // a codec can pass findEncoderForFormat and still reject the format, and an
        // encoder left half-configured leaks, so each failure is released before the
        // next attempt.
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val candidates = listOf(
            Triple("HEVC", MediaFormat.MIMETYPE_VIDEO_HEVC, 2_500_000),
            Triple("H.264", MediaFormat.MIMETYPE_VIDEO_AVC, 4_000_000),
        )
        var chosen: MediaCodec? = null
        var chosenName = ""
        val failures = StringBuilder()
        for ((label, mime, bitrate) in candidates) {
            val fmt = format(mime, bitrate)
            val name = list.findEncoderForFormat(fmt)
            if (name == null) { failures.append("$label: no encoder for ${width}x$height; "); continue }
            var c: MediaCodec? = null
            try {
                c = MediaCodec.createByCodecName(name)
                c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                chosen = c; chosenName = label
                break
            } catch (e: Throwable) {
                failures.append("$label ($name): ${e.message}; ")
                c?.let { runCatching { it.release() } }
            }
        }
        codec = chosen ?: run {
            runCatching { muxer.release() }; runCatching { pfd.close() }
            runCatching { ctx.contentResolver.delete(uri, null, null) }
            error("no usable video encoder — $failures")
        }
        codecName = chosenName
        Log.i("MagViewer", "video encoder: $codecName ${codec.name} ${width}x$height")
        surface = codec.createInputSurface()
        codec.start()
    }

    @Synchronized
    fun addFrame(bmp: Bitmap) {
        val c = surface.lockHardwareCanvas()
        try { c.drawBitmap(bmp, 0f, 0f, null) } finally { surface.unlockCanvasAndPost(c) }
        frames++
        drain(false)
    }

    private fun drain(eos: Boolean) {
        if (eos) codec.signalEndOfInputStream()
        var idle = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!eos || ++idle > 100) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start(); muxing = true
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null && info.size > 0 && muxing) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Finalise the file; returns its Uri, or null if nothing usable was written. */
    @Synchronized
    fun stop(): Uri? {
        var ok = false
        try {
            drain(true)
            if (muxing) { muxer.stop(); ok = true }
        } catch (e: Throwable) {
            Log.e("MagViewer", "video finalise failed", e)
        }
        runCatching { codec.stop() }; runCatching { codec.release() }
        runCatching { surface.release() }; runCatching { muxer.release() }
        runCatching { pfd.close() }
        val cr = ctx.contentResolver
        return if (ok && frames > 0) {
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                      null, null)
            uri
        } else {
            runCatching { cr.delete(uri, null, null) }
            null
        }
    }
}
