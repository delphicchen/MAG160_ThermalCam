package com.magnity.thermalcam.record

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.FileDescriptor

/**
 * MP4 recorder for the viewer's display stream — HEVC (H.265) with AVC fallback.
 *
 * Records whatever the display pipeline produces — any palette, factory NUC, neural
 * super-res, RGB fusion, paused review — by encoding the final ARGB display bitmap of
 * each frame. Frames are drawn onto the encoder's input Surface with a Canvas
 * (nearest-neighbour scale to a fixed output size, so the recording survives display
 * resolution changes mid-take, e.g. toggling SR 2x/4x); the Surface assigns real-time
 * presentation timestamps, giving a correctly-paced variable-frame-rate MP4.
 *
 * Codec: tries the hardware HEVC encoder first (present on every Dimensity 9200-class
 * SoC; ~half the bitrate of AVC at equal quality) and falls back to AVC if HEVC
 * refuses to configure. The active codec is reported via [codecName].
 *
 * Call sequence: start(fd) → encode(bitmap) per display frame (single producer
 * thread) → finish(). All methods are synchronized; finish() may be called from
 * another thread and will wait out an in-flight encode().
 */
class VideoRecorder(
    private val width: Int = 640,
    private val height: Int = 480,
    private val fps: Int = 15,
    private val bitRate: Int = 3_500_000,
) {
    companion object {
        private const val TAG = "VideoRecorder"
        private const val DRAIN_TIMEOUT_US = 10_000L
        private val MIME_PREFERENCE = arrayOf(
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            MediaFormat.MIMETYPE_VIDEO_AVC,
        )
    }

    /** "HEVC" or "AVC" once started. */
    var codecName = ""; private set

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private val paint = Paint().apply { isFilterBitmap = false }   // crisp pixels
    var frameCount = 0L; private set

    private fun makeFormat(mime: String) = MediaFormat.createVideoFormat(mime, width, height).apply {
        setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
        )
        // HEVC needs roughly half the bitrate of AVC for the same quality
        setInteger(
            MediaFormat.KEY_BIT_RATE,
            if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) bitRate else bitRate * 2,
        )
        setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceAtLeast(1))
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }

    /** Configure the encoder and muxer onto an already-open, writable fd (MP4). */
    @Synchronized
    fun start(fd: FileDescriptor) {
        check(codec == null) { "already started" }
        var lastErr: Exception? = null
        for (mime in MIME_PREFERENCE) {
            var c: MediaCodec? = null
            try {
                c = MediaCodec.createEncoderByType(mime)
                c.configure(makeFormat(mime), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = c.createInputSurface()
                c.start()
                codec = c
                codecName = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) "HEVC" else "AVC"
                break
            } catch (e: Exception) {
                Log.w(TAG, "$mime encoder unavailable, trying next", e)
                lastErr = e
                runCatching { c?.release() }
                runCatching { surface?.release() }
                surface = null
            }
        }
        codec ?: throw RuntimeException("no video encoder available", lastErr)
        muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        trackIndex = -1
        muxerStarted = false
        frameCount = 0
    }

    /** Encode one display frame (any size — scaled to the output geometry). */
    @Synchronized
    fun encode(bitmap: Bitmap) {
        val surf = surface ?: return
        try {
            val canvas = surf.lockCanvas(null)
            try {
                canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height), paint)
            } finally {
                surf.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            Log.w(TAG, "frame submit failed", e)
            return
        }
        drain(endOfStream = false)
        frameCount++
    }

    /** Flush the tail, finalize the MP4 and release everything. Safe to call once. */
    @Synchronized
    fun finish() {
        val c = codec ?: return
        try {
            c.signalEndOfInputStream()
            drain(endOfStream = true)
        } catch (e: Exception) {
            Log.w(TAG, "finish drain failed", e)
        }
        runCatching { c.stop() }
        runCatching { c.release() }
        codec = null
        runCatching { surface?.release() }
        surface = null
        if (muxerStarted) {
            runCatching { muxer?.stop() }
        }
        runCatching { muxer?.release() }
        muxer = null
        muxerStarted = false
    }

    private fun drain(endOfStream: Boolean) {
        val c = codec ?: return
        val m = muxer ?: return
        while (true) {
            val idx = c.dequeueOutputBuffer(bufferInfo, if (endOfStream) DRAIN_TIMEOUT_US else 0L)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    // when draining to EOS, keep polling until the EOS flag arrives
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "format changed twice" }
                    trackIndex = m.addTrack(c.outputFormat)
                    m.start()
                    muxerStarted = true
                }
                idx >= 0 -> {
                    val buf = c.getOutputBuffer(idx) ?: continue
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0     // config data travels via outputFormat
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        buf.position(bufferInfo.offset)
                        buf.limit(bufferInfo.offset + bufferInfo.size)
                        m.writeSampleData(trackIndex, buf, bufferInfo)
                    }
                    c.releaseOutputBuffer(idx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
