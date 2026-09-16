package com.magnity.viewer.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File

/**
 * Thermal super-resolution through ncnn + Vulkan (fp16).
 *
 * The model is trained by `sr_train/` and is NOT bundled: drop the converted files next
 * to the app and this backend appears in the drawer. Push them with
 *
 *   adb push thermal_120x160_fp16.param /sdcard/Android/data/com.magnity.viewer/files/model/
 *   adb push thermal_120x160_fp16.bin   /sdcard/Android/data/com.magnity.viewer/files/model/
 *
 * One model per orientation, because the exported graph has a static input size — the
 * portrait default is 120×160, the unrotated frame is 160×120.
 *
 * Like [Anime4kGpu], inference runs on whatever thread calls [render]; ncnn has no
 * per-thread context of its own, and the processing loop is the only caller.
 */
class NcnnUpscaler private constructor() : AutoCloseable {

    private var handle = 0L
    var vulkan = false; private set
    private var out = IntArray(0)

    companion object {
        private const val TAG = "MagViewer"

        init {
            System.loadLibrary("magviewer_ncnn")
        }

        fun modelDir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "model")

        /** `<files>/model/thermal_<w>x<h>_fp16.{param,bin}` for this input size. */
        fun modelFiles(ctx: Context, w: Int, h: Int): Pair<File, File> {
            val dir = modelDir(ctx)
            return File(dir, "thermal_${w}x${h}_fp16.param") to
                File(dir, "thermal_${w}x${h}_fp16.bin")
        }

        fun isInstalled(ctx: Context, w: Int, h: Int): Boolean {
            val (p, b) = modelFiles(ctx, w, h)
            return p.isFile && b.isFile
        }

        /** Null when no model is installed for this frame size. */
        fun open(ctx: Context, w: Int, h: Int): NcnnUpscaler? {
            val (p, b) = modelFiles(ctx, w, h)
            if (!p.isFile || !b.isFile) {
                Log.i(TAG, "no ncnn model for ${w}x$h in ${modelDir(ctx)}")
                return null
            }
            val u = NcnnUpscaler()
            u.vulkan = u.nativeGpuAvailable()
            u.handle = u.nativeInit(p.absolutePath, b.absolutePath, u.vulkan)
            if (u.handle == 0L) {
                Log.e(TAG, "ncnn model failed to load")
                return null
            }
            return u
        }
    }

    private external fun nativeInit(param: String, bin: String, useVulkan: Boolean): Long
    private external fun nativeRelease(handle: Long)
    private external fun nativeGpuAvailable(): Boolean
    private external fun nativeRun(
        handle: Long, field: FloatArray, w: Int, h: Int, lo: Float, hi: Float,
        lut: IntArray, out: IntArray,
    ): Boolean

    /** Scalar field in, palette-mapped 4× Bitmap out. Throws if inference fails. */
    fun render(field: FloatArray, w: Int, h: Int, lo: Float, hi: Float, lut: IntArray): Bitmap {
        val ow = w * 4
        val oh = h * 4
        if (out.size != ow * oh) out = IntArray(ow * oh)
        check(nativeRun(handle, field, w, h, lo, hi, lut, out)) { "ncnn inference failed" }
        return Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888)
    }

    override fun close() {
        if (handle != 0L) nativeRelease(handle)
    }
}
