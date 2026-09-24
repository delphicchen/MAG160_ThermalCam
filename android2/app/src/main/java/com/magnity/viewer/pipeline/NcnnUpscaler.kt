package com.magnity.viewer.pipeline

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Thermal super-resolution through ncnn + Vulkan (fp16).
 *
 * The model is trained by `sr_train/`. It is loaded from the APK's assets when one was
 * built in (`assets/model/`), otherwise from the app's external files dir — which is how
 * you try a new model without rebuilding:
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

        private fun assetNames(w: Int, h: Int) =
            "model/thermal_${w}x${h}_fp16.param" to "model/thermal_${w}x${h}_fp16.bin"

        private fun inAssets(ctx: Context, w: Int, h: Int): Boolean {
            val (p, _) = assetNames(w, h)
            return runCatching { ctx.assets.open(p).close() }.isSuccess
        }

        fun isInstalled(ctx: Context, w: Int, h: Int): Boolean {
            val (p, b) = modelFiles(ctx, w, h)
            return (p.isFile && b.isFile) || inAssets(ctx, w, h)
        }

        /**
         * Null when no model is installed for this frame size. A file pushed into the
         * external dir wins over a bundled asset, so a bundled model can be overridden
         * for testing without rebuilding.
         */
        fun open(ctx: Context, w: Int, h: Int): NcnnUpscaler? {
            val (p, b) = modelFiles(ctx, w, h)
            val fromFiles = p.isFile && b.isFile
            if (!fromFiles && !inAssets(ctx, w, h)) {
                Log.i(TAG, "no ncnn model for ${w}x$h in assets or ${modelDir(ctx)}")
                return null
            }
            val u = NcnnUpscaler()
            u.vulkan = u.nativeGpuAvailable()
            u.handle = if (fromFiles) {
                u.nativeInit(p.absolutePath, b.absolutePath, u.vulkan)
            } else {
                val (ap, ab) = assetNames(w, h)
                u.nativeInitAsset(ctx.assets, ap, ab, u.vulkan)
            }
            if (u.handle == 0L) {
                Log.e(TAG, "ncnn model failed to load")
                return null
            }
            return u
        }
    }

    private external fun nativeInit(param: String, bin: String, useVulkan: Boolean): Long
    private external fun nativeInitAsset(
        assets: android.content.res.AssetManager, param: String, bin: String,
        useVulkan: Boolean,
    ): Long
    private external fun nativeRelease(handle: Long)
    private external fun nativeGpuAvailable(): Boolean
    private external fun nativeRun(
        handle: Long, field: FloatArray, w: Int, h: Int, lo: Float, hi: Float,
        lut: IntArray, out: IntArray,
    ): Boolean

    /** Scalar field in, palette-mapped 4× pixels out — this object's own buffer, valid
     *  until the next call (see [ArgbImage]). Throws if inference fails. */
    fun render(field: FloatArray, w: Int, h: Int, lo: Float, hi: Float, lut: IntArray): ArgbImage {
        val ow = w * 4
        val oh = h * 4
        if (out.size != ow * oh) out = IntArray(ow * oh)
        check(nativeRun(handle, field, w, h, lo, hi, lut, out)) { "ncnn inference failed" }
        return ArgbImage(out, ow, oh)
    }

    override fun close() {
        if (handle != 0L) nativeRelease(handle)
    }
}
